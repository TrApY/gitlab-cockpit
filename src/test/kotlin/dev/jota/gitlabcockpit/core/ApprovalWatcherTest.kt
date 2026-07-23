package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.ApprovedBy
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [newApprovers], the platform-free delta behind the GLC-55 approval-notification
 * watcher: return the users whose approval is *new* since the last pass, never fire on the first
 * observation (`prev == null`), never fire on an unchanged set, and never fire on an unapprove.
 */
class ApprovalWatcherTest {

    private fun user(id: Long) = GitLabUser(id = id, username = "u$id", name = "U$id")

    private fun approvedBy(vararg ids: Long): List<ApprovedBy> = ids.map { ApprovedBy(user(it)) }

    @Test
    fun `first observation only memorizes and yields no new approvers`() {
        assertTrue(newApprovers(null, approvedBy(1, 2)).isEmpty())
    }

    @Test
    fun `a brand-new approver is returned`() {
        val gained = newApprovers(setOf(1L), approvedBy(1, 2))
        assertEquals(listOf(2L), gained.map { it.id })
    }

    @Test
    fun `two approvers appearing at once are both returned in order`() {
        val gained = newApprovers(setOf(1L), approvedBy(1, 2, 3))
        assertEquals(listOf(2L, 3L), gained.map { it.id })
    }

    @Test
    fun `an approval on a previously-known but unapproved MR notifies`() {
        // A known MR (prev is a real, empty set — not null) that gains its first approver still fires.
        val gained = newApprovers(emptySet(), approvedBy(5))
        assertEquals(listOf(5L), gained.map { it.id })
    }

    @Test
    fun `an unchanged approver set yields nothing`() {
        assertTrue(newApprovers(setOf(1L, 2L), approvedBy(1, 2)).isEmpty())
    }

    @Test
    fun `an unapprove (an approver dropping off) yields nothing`() {
        assertTrue(newApprovers(setOf(1L, 2L), approvedBy(1)).isEmpty())
    }
}
