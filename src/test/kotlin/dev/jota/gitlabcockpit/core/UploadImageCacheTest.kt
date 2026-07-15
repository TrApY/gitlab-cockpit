package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests [UploadImageCache] with a fake fetcher (no platform, no network): a cached key is not
 * re-downloaded, two concurrent resolves of the same key share a single fetch, and a failed
 * download leaves its key out of the result.
 */
class UploadImageCacheTest {

    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun `a cached key is not fetched again`() = runBlocking {
        val fetches = AtomicInteger(0)
        val cache = UploadImageCache { _, _ ->
            fetches.incrementAndGet()
            GitLabResult.Success("bytes".toByteArray())
        }
        val ref = UploadRef(secret, "a.png")

        val first = cache.resolve(1L, listOf(ref))
        val second = cache.resolve(1L, listOf(ref))

        assertEquals(1, fetches.get())
        assertEquals(first[ref.key], second[ref.key])
        assertTrue("expected a file: URL but was ${first[ref.key]}", first[ref.key]!!.startsWith("file:"))
    }

    @Test
    fun `two concurrent resolves of the same key share one fetch`() = runBlocking {
        val fetches = AtomicInteger(0)
        val gate = CompletableDeferred<Unit>()
        val cache = UploadImageCache { _, _ ->
            fetches.incrementAndGet()
            gate.await() // hold the owning fetch so the other resolve overlaps it
            GitLabResult.Success("bytes".toByteArray())
        }
        val ref = UploadRef(secret, "a.png")

        val a = async(Dispatchers.IO) { cache.resolve(1L, listOf(ref)) }
        val b = async(Dispatchers.IO) { cache.resolve(1L, listOf(ref)) }
        delay(200) // let both reach the in-flight registration
        gate.complete(Unit)
        val ra = a.await()
        val rb = b.await()

        assertEquals(1, fetches.get())
        assertEquals(ra[ref.key], rb[ref.key])
        assertTrue("expected a file: URL but was ${ra[ref.key]}", ra[ref.key]!!.startsWith("file:"))
    }

    @Test
    fun `a failed download leaves the key out of the result`() = runBlocking {
        val cache = UploadImageCache { _, _ -> GitLabResult.HttpError(404, "not found") }
        val ref = UploadRef(secret, "gone.png")

        val result = cache.resolve(1L, listOf(ref))

        assertTrue("expected an empty map but was $result", result.isEmpty())
        assertNull(result[ref.key])
    }
}
