package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.util.ui.ImageUtil
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBImageIcon
import com.intellij.util.ui.JBUI
import dev.jota.gitlabcockpit.api.GitLabUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * Fetches the raw bytes of an avatar image for a URL. The seam that lets [AvatarLoader] be unit
 * tested without touching the network: production uses [HttpAvatarDownloader], tests a fake.
 */
fun interface AvatarDownloader {
    /** Returns the image bytes, or `null` on any failure (non-2xx, transport error, timeout). */
    fun download(url: String): ByteArray?
}

/** The production [AvatarDownloader]: a bare JDK [HttpClient] GET, no auth (avatar URLs are public). */
class HttpAvatarDownloader : AvatarDownloader {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    override fun download(url: String): ByteArray? = try {
        val request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() in 200..299) response.body() else null
    } catch (e: Exception) {
        null
    }
}

/**
 * The avatar cache engine behind [AvatarCache], with every dependency injected ([scope],
 * [downloader], [diskDir], [clock]) so it is unit-testable without the platform or the network.
 *
 * [icon] is synchronous and never blocks: it returns the circular icon already in memory, or the
 * [AllIcons.General.User] placeholder while it downloads in the background (then invokes `onLoaded`
 * on the EDT so the caller can repaint). Downloads run on [Dispatchers.IO] capped at
 * [MAX_CONCURRENT_DOWNLOADS] via a [Semaphore]; bytes are cached on disk at `<diskDir>/<sha1(url)>.png`
 * (immutable — GitLab changes the URL when the image changes, so there is no TTL) and the rendered
 * circular [Icon] is cached in memory per `url + size`. A failed download is remembered for
 * [FAILURE_TTL_MS] so a broken avatar is not re-fetched on every repaint; a user with no `avatar_url`
 * gets the placeholder with no network at all.
 */
class AvatarLoader(
    private val scope: CoroutineScope,
    private val downloader: AvatarDownloader,
    private val diskDir: Path,
    private val placeholderFactory: (Int) -> Icon = { AllIcons.General.User },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** Rendered circular icons, keyed by `url + size`. Populated on a successful load. */
    private val memory = ConcurrentHashMap<String, Icon>()

    /** URL → epoch-ms of the last failed download; guards the [FAILURE_TTL_MS] no-retry window. */
    private val failures = ConcurrentHashMap<String, Long>()

    /** Memory keys whose background download is in flight, so a repaint storm launches one fetch. */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    /** The immediate placeholder shown while (or instead of) downloading, memoized per requested size. */
    private val placeholders = ConcurrentHashMap<Int, Icon>()

    /** The placeholder for [size] px, built once per size from [placeholderFactory]. */
    private fun placeholder(size: Int): Icon = placeholders.getOrPut(size) { placeholderFactory(size) }

    /**
     * The circular avatar for [user] at [size] px, or the placeholder while it loads. A user with no
     * `avatar_url` always gets the placeholder with no network. On a cache miss the download starts in
     * the background and [onLoaded] fires on the EDT once the icon is ready (the caller repaints).
     */
    fun icon(user: GitLabUser, size: Int, onLoaded: () -> Unit): Icon {
        val url = user.avatarUrl?.takeIf { it.isNotBlank() } ?: return placeholder(size)
        val key = memKey(url, size)
        memory[key]?.let { return it }
        if (isRecentFailure(url)) return placeholder(size)
        if (inFlight.add(key)) {
            scope.launch {
                try {
                    val icon = ensureLoaded(user, size)
                    if (icon != null) withContext(Dispatchers.EDT) { onLoaded() }
                } finally {
                    inFlight.remove(key)
                }
            }
        }
        return placeholder(size)
    }

    /**
     * Loads (from memory, then disk, then network) and caches the circular icon for [user] at [size],
     * returning `null` when the user has no avatar, the URL failed recently, the download fails, or the
     * bytes are not a decodable image. Suspending and side-effecting only through the injected deps, so
     * tests drive it directly with a fake downloader and a controllable [clock].
     */
    internal suspend fun ensureLoaded(user: GitLabUser, size: Int): Icon? {
        val url = user.avatarUrl?.takeIf { it.isNotBlank() } ?: return null
        val key = memKey(url, size)
        memory[key]?.let { return it }
        if (isRecentFailure(url)) return null

        val bytes = readDisk(url) ?: run {
            val downloaded = semaphore.withPermit { withContext(Dispatchers.IO) { downloader.download(url) } }
            if (downloaded == null) {
                failures[url] = clock()
                return null
            }
            writeDisk(url, downloaded)
            downloaded
        }

        val icon = withContext(Dispatchers.IO) { renderCircular(bytes, size) }
        if (icon == null) {
            failures[url] = clock()
            return null
        }
        memory[key] = icon
        return icon
    }

    private fun isRecentFailure(url: String): Boolean =
        failures[url]?.let { clock() - it < FAILURE_TTL_MS } ?: false

    private fun readDisk(url: String): ByteArray? {
        val file = diskFile(url)
        return if (Files.isRegularFile(file)) runCatching { Files.readAllBytes(file) }.getOrNull() else null
    }

    private fun writeDisk(url: String, bytes: ByteArray) {
        runCatching {
            Files.createDirectories(diskDir)
            Files.write(diskFile(url), bytes)
        }
    }

    private fun diskFile(url: String): Path = diskDir.resolve("${sha1(url)}.png")

    /**
     * Renders [bytes] into a JBUI-scaled circular icon, or `null` if the bytes are not a decodable
     * image. The image is clipped to a circle on an ARGB [BufferedImage] and wrapped in a
     * [JBImageIcon] so the platform handles HiDPI scaling.
     */
    private fun renderCircular(bytes: ByteArray, size: Int): Icon? {
        val src: BufferedImage = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null
        val px = JBUI.scale(size)
        val image = ImageUtil.createImage(px, px, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.clip(Ellipse2D.Float(0f, 0f, px.toFloat(), px.toFloat()))
            g.drawImage(src, 0, 0, px, px, null)
        } finally {
            g.dispose()
        }
        return JBImageIcon(image)
    }

    companion object {
        /** Max avatar downloads running at once; the rest queue on the [Semaphore]. */
        private const val MAX_CONCURRENT_DOWNLOADS = 4

        /** How long (ms) a failed URL is skipped before a retry is allowed: 10 minutes. */
        private const val FAILURE_TTL_MS = 10 * 60 * 1000L

        private fun memKey(url: String, size: Int): String = "$url@$size"

        /** Lowercase-hex SHA-1 of [text]; the stable disk filename stem for an avatar URL. */
        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Application service exposing circular avatar icons for the MR list, backed by an [AvatarLoader]
 * wired with the production downloader and the IDE's system path
 * (`<system>/gitlab-cockpit/avatars`). The [scope] is injected by the platform.
 */
@Service(Service.Level.APP)
class AvatarCache(scope: CoroutineScope) {

    private val loader = AvatarLoader(
        scope = scope,
        downloader = HttpAvatarDownloader(),
        diskDir = Path.of(PathManager.getSystemPath(), "gitlab-cockpit", "avatars"),
        // Placeholder avatar (GLC-38 / iter3 A3, ADENDA 2): the copied collaboration-tools DefaultAvatar,
        // resized to the requested size with IconUtil.resizeSquared — the same helper JetBrains'
        // GitLabImageLoader uses — instead of the generic AllIcons.General.User silhouette.
        placeholderFactory = { size -> IconUtil.resizeSquared(CockpitIcons.defaultAvatar, size) },
    )

    /**
     * The circular avatar for [user] at [size] px (default 16), or the placeholder while it loads;
     * [onLoaded] fires on the EDT once the real icon is ready so the list can repaint. See
     * [AvatarLoader.icon].
     */
    fun icon(user: GitLabUser, size: Int = 16, onLoaded: () -> Unit): Icon = loader.icon(user, size, onLoaded)

    companion object {
        fun getInstance(): AvatarCache = service()
    }
}
