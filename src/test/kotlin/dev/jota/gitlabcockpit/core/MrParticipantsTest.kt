package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for [mrParticipants]: role combination, dedup and ordering for the Info people row. */
class MrParticipantsTest {

    private fun user(id: Long, name: String = "User $id"): GitLabUser =
        GitLabUser(id = id, username = "u$id", name = name)

    @Test
    fun `an author with no assignees or reviewers is the only participant`() {
        val author = user(1)

        val result = mrParticipants(author, emptyList(), emptyList())

        assertEquals(listOf(MrParticipant(author, listOf(MrRole.AUTHOR))), result)
    }

    @Test
    fun `a user who is both author and reviewer is combined with both roles`() {
        val author = user(1)

        val result = mrParticipants(author, emptyList(), listOf(author))

        assertEquals(1, result.size)
        assertEquals(author, result[0].user)
        assertEquals(listOf(MrRole.AUTHOR, MrRole.REVIEWER), result[0].roles)
    }

    @Test
    fun `an assignee-only user carries just the assignee role`() {
        val author = user(1)
        val assignee = user(2)

        val result = mrParticipants(author, listOf(assignee), emptyList())

        assertEquals(listOf(MrRole.ASSIGNEE), result.single { it.user == assignee }.roles)
    }

    @Test
    fun `participants are ordered author first, then assignees, then reviewers`() {
        val author = user(1)
        val assignee = user(2)
        val reviewer = user(3)

        val result = mrParticipants(author, listOf(assignee), listOf(reviewer))

        assertEquals(listOf(author, assignee, reviewer), result.map { it.user })
        assertEquals(listOf(MrRole.AUTHOR), result[0].roles)
        assertEquals(listOf(MrRole.ASSIGNEE), result[1].roles)
        assertEquals(listOf(MrRole.REVIEWER), result[2].roles)
    }

    @Test
    fun `a user who is assignee and reviewer combines both, ordered among the assignees`() {
        val author = user(1)
        val both = user(2)
        val reviewer = user(3)

        val result = mrParticipants(author, listOf(both), listOf(both, reviewer))

        assertEquals(listOf(author, both, reviewer), result.map { it.user })
        assertEquals(listOf(MrRole.ASSIGNEE, MrRole.REVIEWER), result[1].roles)
        assertEquals(listOf(MrRole.REVIEWER), result[2].roles)
    }

    @Test
    fun `a repeated reviewer is deduplicated to a single participant`() {
        val author = user(1)
        val reviewer = user(2)

        val result = mrParticipants(author, emptyList(), listOf(reviewer, reviewer))

        assertEquals(1, result.count { it.user == reviewer })
        assertEquals(listOf(MrRole.REVIEWER), result.single { it.user == reviewer }.roles)
    }
}
