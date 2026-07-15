package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabDiscussionNote
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [threadNeedsAttention]: a thread needs attention exactly when it carries at least
 * one resolvable-but-unresolved note; an empty thread, an all-resolved thread and a thread with no
 * resolvable notes do not.
 */
class ThreadAttentionTest {

    private val author = GitLabUser(id = 1, username = "jota", name = "JoTa")

    private fun note(
        id: Long,
        resolvable: Boolean = false,
        resolved: Boolean = false,
    ) = GitLabDiscussionNote(
        id = id,
        body = "body $id",
        author = author,
        createdAt = "2026-07-14T10:00:00Z",
        resolvable = resolvable,
        resolved = resolved,
    )

    @Test
    fun `an empty thread does not need attention`() {
        assertFalse(threadNeedsAttention(emptyList()))
    }

    @Test
    fun `a thread with no resolvable notes does not need attention`() {
        assertFalse(threadNeedsAttention(listOf(note(1), note(2))))
    }

    @Test
    fun `a resolvable unresolved note needs attention`() {
        assertTrue(threadNeedsAttention(listOf(note(1, resolvable = true, resolved = false))))
    }

    @Test
    fun `a fully resolved thread does not need attention`() {
        assertFalse(threadNeedsAttention(listOf(note(1, resolvable = true, resolved = true))))
    }

    @Test
    fun `one unresolved resolvable note among resolved ones needs attention`() {
        val notes = listOf(
            note(1, resolvable = true, resolved = true),
            note(2, resolvable = true, resolved = false),
        )
        assertTrue(threadNeedsAttention(notes))
    }
}
