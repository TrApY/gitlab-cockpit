package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Downloads and caches GitLab Markdown image uploads to a per-instance temp directory, exposing each
 * as a `file://` URL that Swing's HTML editor kit can actually load (a raw `/uploads/…` src is both
 * relative and authenticated, so it renders broken). One instance lives per project service; [fetch]
 * is injected so the class is testable without the platform.
 *
 * [resolve] maps each requested [UploadRef] to its `file://` URL, downloading the ones not yet on
 * disk. Downloads are de-duplicated: concurrent requests for the same key share a single fetch, and a
 * key already downloaded is never re-fetched. A download that fails leaves its key **out** of the
 * returned map, so the caller keeps the original (broken) src rather than a dangling file link.
 *
 * @param fetch downloads one upload's bytes given the MR's project id and the [UploadRef].
 */
class UploadImageCache(
    private val fetch: suspend (projectId: Long, ref: UploadRef) -> GitLabResult<ByteArray>,
) {

    /** Keys already on disk → their `file://` URL. Populated on first successful download. */
    private val done = ConcurrentHashMap<String, String>()

    /** In-flight downloads keyed by [UploadRef.key], so concurrent requests share one fetch. */
    private val mutex = Mutex()
    private val inFlight = HashMap<String, CompletableDeferred<String?>>()

    /** The lazily-created temp directory, one per instance. */
    @Volatile
    private var directory: Path? = null
    private val directoryLock = Any()

    /**
     * Resolves every ref in [refs] (de-duplicated by key) to its `file://` URL, downloading from
     * [projectId] as needed. Refs whose download fails are absent from the result.
     */
    suspend fun resolve(projectId: Long, refs: List<UploadRef>): Map<String, String> = coroutineScope {
        refs.distinctBy { it.key }
            .map { ref -> async { ref.key to resolveOne(projectId, ref) } }
            .awaitAll()
            .mapNotNull { (key, url) -> url?.let { key to it } }
            .toMap()
    }

    /** Resolves a single ref, coordinating with any concurrent download of the same key. */
    private suspend fun resolveOne(projectId: Long, ref: UploadRef): String? {
        done[ref.key]?.let { return it }

        var owned: CompletableDeferred<String?>? = null
        val awaitable = mutex.withLock {
            done[ref.key]?.let { return it }
            inFlight[ref.key] ?: CompletableDeferred<String?>().also {
                inFlight[ref.key] = it
                owned = it
            }
        }
        if (owned == null) return awaitable.await()

        val url: String? = try {
            when (val result = fetch(projectId, ref)) {
                is GitLabResult.Success -> storeToDisk(ref, result.data)
                else -> null
            }
        } catch (t: Throwable) {
            mutex.withLock { inFlight.remove(ref.key) }
            awaitable.completeExceptionally(t)
            throw t
        }
        mutex.withLock {
            inFlight.remove(ref.key)
            if (url != null) done[ref.key] = url
        }
        awaitable.complete(url)
        return url
    }

    /** Writes [bytes] to a stable per-key file (hashed name + original extension) and returns its URL. */
    private suspend fun storeToDisk(ref: UploadRef, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val target = directory().resolve(safeFileName(ref))
        Files.write(target, bytes)
        target.toUri().toString()
    }

    private fun directory(): Path {
        directory?.let { return it }
        synchronized(directoryLock) {
            directory?.let { return it }
            return Files.createTempDirectory("gitlab-cockpit-uploads-").also { directory = it }
        }
    }

    private fun safeFileName(ref: UploadRef): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(ref.key.toByteArray(Charsets.UTF_8))
        val hash = digest.joinToString("") { "%02x".format(it) }
        val extension = ref.filename.substringAfterLast('.', "")
        return if (extension.isNotEmpty() && extension.length <= MAX_EXTENSION_LENGTH) "$hash.$extension" else hash
    }

    companion object {
        /** Guards against a pathological "extension" (e.g. a dot mid-filename with a long tail). */
        private const val MAX_EXTENSION_LENGTH = 16
    }
}
