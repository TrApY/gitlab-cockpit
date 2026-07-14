package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabNote
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Test

/** Verifies the pure [userNotes] filter drops GitLab system notes while preserving order. */
class UserNotesTest {

    private fun note(id: Long, system: Boolean) = GitLabNote(
        id = id,
        body = "body $id",
        system = system,
        author = GitLabUser(id = 1, username = "jota", name = "Jo Ta"),
        createdAt = "2026-07-14T09:00:00Z",
    )

    @Test
    fun `drops system notes and keeps human comments in order`() {
        val notes = listOf(note(1, false), note(2, true), note(3, false), note(4, true))

        assertEquals(listOf(1L, 3L), userNotes(notes).map { it.id })
    }

    @Test
    fun `empty stays empty`() {
        assertEquals(emptyList<GitLabNote>(), userNotes(emptyList()))
    }

    @Test
    fun `all system notes yield empty`() {
        assertEquals(emptyList<GitLabNote>(), userNotes(listOf(note(1, true), note(2, true))))
    }
}
