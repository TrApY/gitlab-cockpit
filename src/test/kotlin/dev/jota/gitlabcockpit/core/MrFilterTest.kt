package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.ApprovedBy
import dev.jota.gitlabcockpit.api.GitLabApprovals
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure tests for [filterNotApproved]. */
class MrFilterTest {

    private val me = 1L

    private fun mr(iid: Long): GitLabMergeRequest = GitLabMergeRequest(
        iid = iid,
        title = "MR $iid",
        state = "opened",
        sourceBranch = "feature/$iid",
        targetBranch = "main",
        webUrl = "https://gitlab.com/g/r/-/merge_requests/$iid",
        updatedAt = "2026-07-14T10:00:00.000Z",
        author = GitLabUser(id = 99, username = "author", name = "Author"),
    )

    private fun approvals(vararg approverIds: Long): GitLabApprovals =
        GitLabApprovals(approvedBy = approverIds.map { ApprovedBy(GitLabUser(it, "u$it", "U$it")) })

    @Test
    fun `merge request approved by me is excluded`() {
        val mrs = listOf(mr(1))
        val byIid = mapOf(1L to approvals(me))

        assertEquals(emptyList<GitLabMergeRequest>(), filterNotApproved(mrs, byIid, me))
    }

    @Test
    fun `merge request approved only by others is kept`() {
        val mrs = listOf(mr(2))
        val byIid = mapOf(2L to approvals(2L, 3L))

        assertEquals(listOf(mr(2)), filterNotApproved(mrs, byIid, me))
    }

    @Test
    fun `merge request with no approvals is kept`() {
        val mrs = listOf(mr(3))
        val byIid = mapOf(3L to approvals())

        assertEquals(listOf(mr(3)), filterNotApproved(mrs, byIid, me))
    }

    @Test
    fun `merge request missing from approvals map is kept`() {
        val mrs = listOf(mr(4))

        assertEquals(listOf(mr(4)), filterNotApproved(mrs, emptyMap(), me))
    }

    @Test
    fun `mixed set keeps only the ones not approved by me`() {
        val mrs = listOf(mr(1), mr(2), mr(3), mr(4))
        val byIid = mapOf(
            1L to approvals(me),        // approved by me -> drop
            2L to approvals(2L),        // approved by other -> keep
            3L to approvals(),          // nobody -> keep
            // 4 missing -> keep
        )

        assertEquals(listOf(mr(2), mr(3), mr(4)), filterNotApproved(mrs, byIid, me))
    }
}
