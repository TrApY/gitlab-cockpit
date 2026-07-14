package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabDiffFile
import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.api.GitLabDiscussionNote
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.NotePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the platform-free changes logic: [changeTypeOf] classification, [buildFileTree]
 * directory nesting / ordering (deleted files placed by old path) and [discussionsByFile] keying
 * (position new/old path, system notes ignored, position-less discussions excluded).
 */
class ChangesModelTest {

    private fun diff(
        newPath: String,
        oldPath: String = newPath,
        newFile: Boolean = false,
        renamedFile: Boolean = false,
        deletedFile: Boolean = false,
    ) = GitLabDiffFile(
        oldPath = oldPath,
        newPath = newPath,
        newFile = newFile,
        renamedFile = renamedFile,
        deletedFile = deletedFile,
    )

    // --- changeTypeOf -------------------------------------------------------------------------

    @Test
    fun `changeTypeOf classifies every flag combination`() {
        assertEquals(ChangeType.ADDED, changeTypeOf(diff("a.kt", newFile = true)))
        assertEquals(ChangeType.DELETED, changeTypeOf(diff("a.kt", deletedFile = true)))
        assertEquals(ChangeType.RENAMED, changeTypeOf(diff("b.kt", oldPath = "a.kt", renamedFile = true)))
        assertEquals(ChangeType.MODIFIED, changeTypeOf(diff("a.kt")))
    }

    @Test
    fun `changeTypeOf applies precedence added over deleted over renamed`() {
        // A new file that is also flagged renamed is still ADDED (new wins).
        assertEquals(ChangeType.ADDED, changeTypeOf(diff("a.kt", newFile = true, renamedFile = true)))
        // A deleted file also flagged renamed is DELETED (deleted wins over renamed).
        assertEquals(ChangeType.DELETED, changeTypeOf(diff("a.kt", deletedFile = true, renamedFile = true)))
    }

    // --- buildFileTree ------------------------------------------------------------------------

    @Test
    fun `buildFileTree nests directories and marks leaves`() {
        val root = buildFileTree(
            listOf(
                diff("src/main/App.kt"),
                diff("src/Util.kt"),
                diff("README.md"),
            ),
        )

        // Directories first (src), then files (README.md), each alphabetical.
        assertEquals(listOf("src", "README.md"), root.children.map { it.name })

        val src = root.children.first { it.name == "src" }
        assertTrue(src.isDir)
        assertNull(src.file)
        assertEquals("src", src.path)
        // Within src: the main directory first, then the Util.kt file.
        assertEquals(listOf("main", "Util.kt"), src.children.map { it.name })

        val main = src.children.first { it.name == "main" }
        assertTrue(main.isDir)
        assertEquals("src/main", main.path)
        val app = main.children.single()
        assertEquals("App.kt", app.name)
        assertEquals("src/main/App.kt", app.path)
        assertFalse(app.isDir)
        assertNotNull(app.file)

        val readme = root.children.first { it.name == "README.md" }
        assertFalse(readme.isDir)
        assertEquals("README.md", readme.path)
        assertNotNull(readme.file)
    }

    @Test
    fun `buildFileTree orders directories before files and alphabetically within each group`() {
        val root = buildFileTree(
            listOf(
                diff("b/2.kt"),
                diff("a/1.kt"),
                diff("z.kt"),
                diff("m.kt"),
                diff("a/0.kt"),
            ),
        )

        assertEquals(listOf("a", "b", "m.kt", "z.kt"), root.children.map { it.name })
        val a = root.children.first { it.name == "a" }
        assertEquals(listOf("0.kt", "1.kt"), a.children.map { it.name })
    }

    @Test
    fun `buildFileTree places a deleted file by its old path`() {
        val root = buildFileTree(
            listOf(diff(newPath = "", oldPath = "pkg/Removed.kt", deletedFile = true)),
        )

        val pkg = root.children.single()
        assertEquals("pkg", pkg.name)
        assertTrue(pkg.isDir)
        val removed = pkg.children.single()
        assertEquals("Removed.kt", removed.name)
        assertEquals("pkg/Removed.kt", removed.path)
        assertNotNull(removed.file)
    }

    @Test
    fun `buildFileTree on empty input yields an empty root`() {
        val root = buildFileTree(emptyList())
        assertTrue(root.isDir)
        assertTrue(root.children.isEmpty())
    }

    // --- discussionsByFile --------------------------------------------------------------------

    private fun user(id: Long) = GitLabUser(id = id, username = "u$id", name = "User $id")

    private fun note(
        id: Long,
        system: Boolean = false,
        position: NotePosition? = null,
        body: String = "body $id",
    ) = GitLabDiscussionNote(
        id = id,
        body = body,
        system = system,
        author = user(id),
        createdAt = "2026-07-14T10:00:00Z",
        position = position,
    )

    @Test
    fun `discussionsByFile keys by the new path of the first positioned note`() {
        val discussion = GitLabDiscussion(
            id = "d1",
            notes = listOf(note(1, position = NotePosition(newPath = "src/App.kt", newLine = 10))),
        )

        val byFile = discussionsByFile(listOf(discussion))

        assertEquals(setOf("src/App.kt"), byFile.keys)
        assertEquals(listOf("d1"), byFile.getValue("src/App.kt").map { it.id })
    }

    @Test
    fun `discussionsByFile falls back to old path when new path is null`() {
        val discussion = GitLabDiscussion(
            id = "d2",
            notes = listOf(note(1, position = NotePosition(newPath = null, oldPath = "old/Gone.kt", oldLine = 3))),
        )

        val byFile = discussionsByFile(listOf(discussion))

        assertEquals(setOf("old/Gone.kt"), byFile.keys)
    }

    @Test
    fun `discussionsByFile ignores system notes when choosing the position`() {
        // First note is a system note carrying a position; it must not be used as the key.
        val discussion = GitLabDiscussion(
            id = "d3",
            notes = listOf(
                note(1, system = true, position = NotePosition(newPath = "system/Path.kt", newLine = 1)),
                note(2, position = NotePosition(newPath = "real/Path.kt", newLine = 5)),
            ),
        )

        val byFile = discussionsByFile(listOf(discussion))

        assertEquals(setOf("real/Path.kt"), byFile.keys)
    }

    @Test
    fun `discussionsByFile excludes discussions with no positioned note`() {
        val general = GitLabDiscussion(id = "d4", notes = listOf(note(1)))
        val systemOnly = GitLabDiscussion(id = "d5", notes = listOf(note(2, system = true)))

        assertTrue(discussionsByFile(listOf(general, systemOnly)).isEmpty())
    }

    @Test
    fun `discussionsByFile groups multiple discussions on the same file`() {
        val position = NotePosition(newPath = "src/App.kt", newLine = 7)
        val first = GitLabDiscussion(id = "a", notes = listOf(note(1, position = position)))
        val second = GitLabDiscussion(id = "b", notes = listOf(note(2, position = position)))

        val byFile = discussionsByFile(listOf(first, second))

        assertEquals(listOf("a", "b"), byFile.getValue("src/App.kt").map { it.id })
    }
}
