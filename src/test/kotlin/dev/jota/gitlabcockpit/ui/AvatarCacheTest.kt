package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import dev.jota.gitlabcockpit.api.GitLabUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.imageio.ImageIO

/**
 * Tests [AvatarLoader] (the engine behind [AvatarCache]) with a fake downloader and a controllable
 * clock: the disk filename is a stable SHA-1 of the URL, a user without an avatar never touches the
 * network, and a failed download serves the placeholder and is not retried before the 10-minute
 * window elapses.
 */
class AvatarCacheTest {

    private val avatarUrl = "https://gitlab.example/avatar/jota.png"
    private val withAvatar = GitLabUser(id = 1, username = "jota", name = "JoTa", avatarUrl = avatarUrl)
    private val withoutAvatar = GitLabUser(id = 2, username = "nobody", name = "Nobody", avatarUrl = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun tempDir(): Path = Files.createTempDirectory("avatar-cache-test")

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    @Test
    fun `sha1 disk stem is stable and matches the known digest of the url`() {
        assertEquals(AvatarLoader.sha1(avatarUrl), AvatarLoader.sha1(avatarUrl))
        assertEquals("a9dd86f7a553ee7d41945413eebdfa7d2b1d64bd", AvatarLoader.sha1(avatarUrl))
    }

    @Test
    fun `a user without an avatar gets the placeholder without touching the network`() {
        val calls = AtomicInteger(0)
        val loader = AvatarLoader(scope, { calls.incrementAndGet(); null }, tempDir())

        val icon = loader.icon(withoutAvatar, 16) { }

        assertSame(AllIcons.General.User, icon)
        assertEquals(0, calls.get())
    }

    @Test
    fun `a failed download is served as placeholder and not retried before ten minutes`() = runBlocking {
        val calls = AtomicInteger(0)
        val now = AtomicLong(0L)
        val loader = AvatarLoader(scope, { calls.incrementAndGet(); null }, tempDir()) { now.get() }

        // First attempt fails and is remembered.
        assertNull(loader.ensureLoaded(withAvatar, 16))
        assertEquals(1, calls.get())

        // A second attempt within the window does not hit the network again.
        assertNull(loader.ensureLoaded(withAvatar, 16))
        assertEquals(1, calls.get())

        // After the 10-minute window a retry is allowed.
        now.set(11 * 60 * 1000L)
        assertNull(loader.ensureLoaded(withAvatar, 16))
        assertEquals(2, calls.get())
    }

    @Test
    fun `a successful download is cached in memory and not re-fetched`() = runBlocking {
        val calls = AtomicInteger(0)
        val png = pngBytes()
        val loader = AvatarLoader(scope, { calls.incrementAndGet(); png }, tempDir())

        val first = loader.ensureLoaded(withAvatar, 16)
        val second = loader.ensureLoaded(withAvatar, 16)

        assertNotNull(first)
        assertSame(first, second)
        assertEquals(1, calls.get())
    }
}
