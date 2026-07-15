package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Pure tests for [chooseRemote] and [orderCandidates]. */
class RemoteSelectionTest {

    private fun candidate(path: String, rootPath: String, host: String = "gitlab.com"): CandidateRemote =
        CandidateRemote(RemoteCoords(host, path), rootPath)

    // --- chooseRemote -------------------------------------------------------------------------

    @Test
    fun `chooseRemote returns null for empty candidates`() {
        assertNull(chooseRemote(emptyList(), persistedPath = "group/repo", projectBasePath = "/home/p"))
    }

    @Test
    fun `chooseRemote prefers the persisted path over the base path`() {
        val main = candidate("group/main", "/home/p")
        val sub = candidate("group/sub", "/home/p/sub")
        assertEquals(sub, chooseRemote(listOf(main, sub), persistedPath = "group/sub", projectBasePath = "/home/p"))
    }

    @Test
    fun `chooseRemote falls back to the base path root when nothing is persisted`() {
        val main = candidate("group/main", "/home/p")
        val sub = candidate("group/sub", "/home/p/sub")
        assertEquals(main, chooseRemote(listOf(sub, main), persistedPath = null, projectBasePath = "/home/p"))
    }

    @Test
    fun `chooseRemote base path comparison normalizes trailing slash and separators`() {
        val main = candidate("group/main", "C:\\repo\\main")
        assertEquals(main, chooseRemote(listOf(main), persistedPath = null, projectBasePath = "C:/repo/main/"))
    }

    @Test
    fun `chooseRemote ignores a persisted path that matches nothing and uses the base path`() {
        val main = candidate("group/main", "/home/p")
        val sub = candidate("group/sub", "/home/p/sub")
        assertEquals(main, chooseRemote(listOf(sub, main), persistedPath = "group/ghost", projectBasePath = "/home/p"))
    }

    @Test
    fun `chooseRemote falls back to the first candidate when no persisted and no base match`() {
        val a = candidate("group/a", "/x/a")
        val b = candidate("group/b", "/x/b")
        assertEquals(a, chooseRemote(listOf(a, b), persistedPath = null, projectBasePath = "/home/other"))
    }

    @Test
    fun `chooseRemote falls back to the first candidate when base path is null`() {
        val a = candidate("group/a", "/x/a")
        val b = candidate("group/b", "/x/b")
        assertEquals(a, chooseRemote(listOf(a, b), persistedPath = null, projectBasePath = null))
    }

    // --- orderCandidates ----------------------------------------------------------------------

    @Test
    fun `orderCandidates puts the base path root first then the rest alphabetically`() {
        val main = candidate("group/main", "/home/p")
        val zebra = candidate("group/zebra", "/home/p/z")
        val alpha = candidate("group/alpha", "/home/p/a")
        assertEquals(
            listOf(main, alpha, zebra),
            orderCandidates(listOf(zebra, alpha, main), projectBasePath = "/home/p"),
        )
    }

    @Test
    fun `orderCandidates without a base match sorts alphabetically`() {
        val b = candidate("group/b", "/x/b")
        val a = candidate("group/a", "/x/a")
        assertEquals(listOf(a, b), orderCandidates(listOf(b, a), projectBasePath = null))
    }

    @Test
    fun `orderCandidates dedupes by path keeping the base path root`() {
        val nested = candidate("group/repo", "/home/p/nested")
        val base = candidate("group/repo", "/home/p")
        assertEquals(listOf(base), orderCandidates(listOf(nested, base), projectBasePath = "/home/p"))
    }

    @Test
    fun `orderCandidates dedupes by path keeping the first when none is the base`() {
        val first = candidate("group/repo", "/x/first")
        val second = candidate("group/repo", "/x/second")
        assertEquals(listOf(first), orderCandidates(listOf(first, second), projectBasePath = "/home/other"))
    }

    @Test
    fun `orderCandidates on empty list returns empty`() {
        assertEquals(emptyList<CandidateRemote>(), orderCandidates(emptyList(), projectBasePath = "/home/p"))
    }
}
