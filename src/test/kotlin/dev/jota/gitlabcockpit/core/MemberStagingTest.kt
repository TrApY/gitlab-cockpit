package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure tests for the Edit-MR Assignees/Reviewers staging helpers (GLC-50): [stageMember] add-without-
 * duplicate for the multi Reviewers column and replace for the single Assignee column, [unstageMember]
 * removal by id, and the [memberToRemove] "−" target resolution (selected chip, else the last one).
 */
class MemberStagingTest {

    private fun user(id: Long, username: String, name: String = username) = GitLabUser(id, username, name)

    private val alice = user(1, "alice", "Alice")
    private val bob = user(2, "bob", "Bob")
    private val carol = user(3, "carol", "Carol")

    @Test
    fun `stageMember appends a new reviewer preserving order`() {
        assertEquals(
            listOf(alice, bob),
            stageMember(listOf(alice), bob, single = false),
        )
    }

    @Test
    fun `stageMember does not duplicate a reviewer already staged by id`() {
        val current = listOf(alice, bob)
        // Same id, a different instance (as if re-picked from the roster) — still a no-op.
        assertEquals(current, stageMember(current, user(2, "bob", "Bob R."), single = false))
    }

    @Test
    fun `stageMember on an empty reviewer column adds the first pick`() {
        assertEquals(listOf(alice), stageMember(emptyList(), alice, single = false))
    }

    @Test
    fun `stageMember replaces the single assignee`() {
        assertEquals(listOf(bob), stageMember(listOf(alice), bob, single = true))
    }

    @Test
    fun `stageMember sets the single assignee from empty`() {
        assertEquals(listOf(alice), stageMember(emptyList(), alice, single = true))
    }

    @Test
    fun `unstageMember removes the member with the id, preserving order`() {
        assertEquals(listOf(alice, carol), unstageMember(listOf(alice, bob, carol), bob.id))
    }

    @Test
    fun `unstageMember is a no-op when no staged member has the id`() {
        val current = listOf(alice, bob)
        assertEquals(current, unstageMember(current, 99L))
    }

    @Test
    fun `memberToRemove returns the selected id when a chip is selected`() {
        assertEquals(alice.id, memberToRemove(listOf(alice, bob), selectedId = alice.id))
    }

    @Test
    fun `memberToRemove falls back to the last staged member when nothing is selected`() {
        assertEquals(bob.id, memberToRemove(listOf(alice, bob), selectedId = null))
    }

    @Test
    fun `memberToRemove is null on an empty column so the click is a no-op`() {
        assertNull(memberToRemove(emptyList(), selectedId = null))
    }

    @Test
    fun `stageMember returns the same instance when the reviewer is a duplicate`() {
        // The identity short-circuit avoids a needless list copy on a duplicate pick.
        val current = listOf(alice, bob)
        assertSame(current, stageMember(current, alice, single = false))
    }
}
