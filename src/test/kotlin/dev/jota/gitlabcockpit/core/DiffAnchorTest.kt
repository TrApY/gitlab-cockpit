package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.api.GitLabDiscussionNote
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.NotePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the F4c anchor logic: the [anchorFor] position table (new/old/both/none) and
 * [threadsByAnchor] grouping (side+line key, first non-system positioned note, insertion order,
 * unanchorable discussions dropped).
 */
class DiffAnchorTest {

    private val author = GitLabUser(id = 1, username = "jota", name = "JoTa")

    private fun note(
        id: Long = 1,
        system: Boolean = false,
        position: NotePosition? = null,
    ) = GitLabDiscussionNote(
        id = id,
        body = "body",
        system = system,
        author = author,
        createdAt = "2026-07-14T10:00:00Z",
        position = position,
    )

    private fun discussion(id: String, vararg notes: GitLabDiscussionNote) =
        GitLabDiscussion(id = id, notes = notes.toList())

    // --- anchorFor ------------------------------------------------------------------------------

    @Test
    fun `anchorFor with only new_line anchors to the new side`() {
        assertEquals(DiffAnchor(AnchorSide.NEW, 5), anchorFor(NotePosition(newLine = 5)))
    }

    @Test
    fun `anchorFor with only old_line anchors to the old side`() {
        assertEquals(DiffAnchor(AnchorSide.OLD, 3), anchorFor(NotePosition(oldLine = 3)))
    }

    @Test
    fun `anchorFor with both lines prefers the new side`() {
        // A context line carries both numbers; GitLab's UI shows it on the new side.
        assertEquals(DiffAnchor(AnchorSide.NEW, 7), anchorFor(NotePosition(oldLine = 4, newLine = 7)))
    }

    @Test
    fun `anchorFor with no lines returns null`() {
        assertNull(anchorFor(NotePosition(newPath = "a.kt", oldPath = "a.kt")))
    }

    // --- threadsByAnchor ------------------------------------------------------------------------

    @Test
    fun `threadsByAnchor groups discussions on the same side and line`() {
        val d1 = discussion("d1", note(id = 1, position = NotePosition(newLine = 5)))
        val d2 = discussion("d2", note(id = 2, position = NotePosition(newLine = 5)))
        val d3 = discussion("d3", note(id = 3, position = NotePosition(oldLine = 5)))

        val grouped = threadsByAnchor(listOf(d1, d2, d3))

        assertEquals(2, grouped.size)
        assertEquals(listOf(d1, d2), grouped[DiffAnchor(AnchorSide.NEW, 5)])
        // Same line number on the OLD side is a different anchor (different editor).
        assertEquals(listOf(d3), grouped[DiffAnchor(AnchorSide.OLD, 5)])
    }

    @Test
    fun `threadsByAnchor drops unpositioned and system-only discussions`() {
        val general = discussion("general", note(id = 1))
        val systemOnly = discussion("system", note(id = 2, system = true, position = NotePosition(newLine = 3)))
        val lineless = discussion("lineless", note(id = 3, position = NotePosition(newPath = "a.kt")))

        assertTrue(threadsByAnchor(listOf(general, systemOnly, lineless)).isEmpty())
    }

    @Test
    fun `threadsByAnchor anchors on the first non-system positioned note`() {
        // The system note's position must not decide the anchor; the reply's position must not either.
        val d = discussion(
            "d1",
            note(id = 1, system = true, position = NotePosition(newLine = 9)),
            note(id = 2, position = NotePosition(oldLine = 2)),
            note(id = 3, position = NotePosition(newLine = 4)),
        )

        assertEquals(mapOf(DiffAnchor(AnchorSide.OLD, 2) to listOf(d)), threadsByAnchor(listOf(d)))
    }

    @Test
    fun `threadsByAnchor preserves insertion order of anchors and threads`() {
        val d1 = discussion("d1", note(id = 1, position = NotePosition(newLine = 20)))
        val d2 = discussion("d2", note(id = 2, position = NotePosition(oldLine = 1)))
        val d3 = discussion("d3", note(id = 3, position = NotePosition(newLine = 20)))

        val grouped = threadsByAnchor(listOf(d1, d2, d3))

        // Anchors keep first-seen order (not sorted by line), threads keep list order within one.
        assertEquals(
            listOf(DiffAnchor(AnchorSide.NEW, 20), DiffAnchor(AnchorSide.OLD, 1)),
            grouped.keys.toList(),
        )
        assertEquals(listOf(d1, d3), grouped[DiffAnchor(AnchorSide.NEW, 20)])
    }
}
