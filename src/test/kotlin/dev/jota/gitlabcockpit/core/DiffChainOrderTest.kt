package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabDiffFile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for [chainIndex]: the position a diff chain should open at for a requested file, matched
 * by old+new path, with a 0 fallback when the file is absent (or the list empty).
 */
class DiffChainOrderTest {

    private fun file(old: String, new: String = old) = GitLabDiffFile(oldPath = old, newPath = new)

    private val files = listOf(file("a.kt"), file("dir/b.kt"), file("c.kt"))

    @Test
    fun `chainIndex returns the position of the requested file`() {
        assertEquals(0, chainIndex(files, file("a.kt")))
        assertEquals(1, chainIndex(files, file("dir/b.kt")))
        assertEquals(2, chainIndex(files, file("c.kt")))
    }

    @Test
    fun `chainIndex matches by paths, not identity`() {
        // A freshly built file with the same paths (different diff text/instance) still resolves.
        val rebuilt = GitLabDiffFile(oldPath = "dir/b.kt", newPath = "dir/b.kt", diff = "@@ -1 +1 @@")
        assertEquals(1, chainIndex(files, rebuilt))
    }

    @Test
    fun `chainIndex distinguishes a rename by its old and new paths`() {
        val renamed = file(old = "old/name.kt", new = "new/name.kt")
        val withRename = files + renamed
        assertEquals(3, chainIndex(withRename, renamed))
        // Same new path but a different old path is a different file → not found → fallback 0.
        assertEquals(0, chainIndex(withRename, file(old = "other.kt", new = "new/name.kt")))
    }

    @Test
    fun `chainIndex falls back to 0 when the file is absent`() {
        assertEquals(0, chainIndex(files, file("missing.kt")))
    }

    @Test
    fun `chainIndex falls back to 0 for an empty list`() {
        assertEquals(0, chainIndex(emptyList(), file("a.kt")))
    }
}
