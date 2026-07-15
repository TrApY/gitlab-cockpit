package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.api.GitLabDiscussionNote
import dev.jota.gitlabcockpit.api.GitLabUser
import dev.jota.gitlabcockpit.api.NotePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the Comments-tab thread model: [commentThreads] (system filtering, empty-discussion
 * dropping, order preservation, resolved computation) and [threadAnchorLabel] (new/old/null).
 */
class CommentThreadsTest {

    private val author = GitLabUser(id = 1, username = "jota", name = "Jo Ta")

    private fun note(
        id: Long,
        system: Boolean = false,
        resolvable: Boolean = false,
        resolved: Boolean = false,
        position: NotePosition? = null,
    ) = GitLabDiscussionNote(
        id = id,
        body = "body $id",
        system = system,
        author = author,
        createdAt = "2026-07-14T09:00:00Z",
        resolvable = resolvable,
        resolved = resolved,
        position = position,
    )

    private fun discussion(id: String, vararg notes: GitLabDiscussionNote) =
        GitLabDiscussion(id = id, notes = notes.toList())

    // --- commentThreads -----------------------------------------------------------------------

    @Test
    fun `system notes are filtered out of a thread`() {
        val threads = commentThreads(
            listOf(discussion("d1", note(1), note(2, system = true), note(3))),
        )
        assertEquals(1, threads.size)
        assertEquals(listOf(1L, 3L), threads.single().notes.map { it.id })
    }

    @Test
    fun `a discussion left empty after filtering is discarded`() {
        val threads = commentThreads(
            listOf(
                discussion("d1", note(1, system = true), note(2, system = true)),
                discussion("d2", note(3)),
            ),
        )
        assertEquals(listOf("d2"), threads.map { it.discussionId })
    }

    @Test
    fun `discussion order is preserved`() {
        val threads = commentThreads(
            listOf(
                discussion("d1", note(1)),
                discussion("d2", note(2)),
                discussion("d3", note(3)),
            ),
        )
        assertEquals(listOf("d1", "d2", "d3"), threads.map { it.discussionId })
    }

    @Test
    fun `resolved is true when the first resolvable note is resolved`() {
        // A leading non-resolvable note must not shadow the resolvable one behind it.
        val threads = commentThreads(
            listOf(discussion("d1", note(1, resolvable = false), note(2, resolvable = true, resolved = true))),
        )
        assertTrue(threads.single().resolved)
    }

    @Test
    fun `resolved is false when the resolvable note is unresolved`() {
        val threads = commentThreads(
            listOf(discussion("d1", note(1, resolvable = true, resolved = false))),
        )
        assertFalse(threads.single().resolved)
    }

    @Test
    fun `resolved is false when no note is resolvable`() {
        val threads = commentThreads(listOf(discussion("d1", note(1), note(2))))
        assertFalse(threads.single().resolved)
    }

    // --- threadAnchorLabel --------------------------------------------------------------------

    @Test
    fun `anchor label uses the new side when present`() {
        val thread = commentThreads(
            listOf(discussion("d1", note(1, position = NotePosition(newPath = "src/App.kt", newLine = 42)))),
        ).single()
        assertEquals("src/App.kt:42", threadAnchorLabel(thread))
    }

    @Test
    fun `anchor label falls back to the old side`() {
        // A removed-line comment: both paths present but only the old line is set.
        val thread = commentThreads(
            listOf(
                discussion(
                    "d1",
                    note(1, position = NotePosition(newPath = "src/App.kt", oldPath = "src/App.kt", oldLine = 13)),
                ),
            ),
        ).single()
        assertEquals("src/App.kt:13", threadAnchorLabel(thread))
    }

    @Test
    fun `anchor label is null for a general thread`() {
        val thread = commentThreads(listOf(discussion("d1", note(1)))).single()
        assertNull(threadAnchorLabel(thread))
    }

    @Test
    fun `anchor label is taken from the first positioned note`() {
        // The first note is a general one; the positioned reply supplies the anchor.
        val thread = commentThreads(
            listOf(
                discussion(
                    "d1",
                    note(1),
                    note(2, position = NotePosition(newPath = "lib/B.kt", newLine = 7)),
                ),
            ),
        ).single()
        assertEquals("lib/B.kt:7", threadAnchorLabel(thread))
    }
}
