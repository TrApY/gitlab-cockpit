package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.ApprovedBy
import dev.jota.gitlabcockpit.api.GitLabApprovals
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for the merge-readiness helpers: [mergeButtonState] (every known
 * `detailed_merge_status`, the non-opened short-circuit and the unknown/null fallback) and
 * [approvalsHealth] (counts-based and approver-list fallback).
 */
class MergeReadinessTest {

    // --- mergeButtonState ---------------------------------------------------------------------

    @Test
    fun `an open mergeable MR enables the merge`() {
        assertEquals(
            MergeButtonState(MergeAction.MERGE, null),
            mergeButtonState("opened", "mergeable"),
        )
    }

    @Test
    fun `an open MR with CI still running offers merge-when-pipeline-succeeds`() {
        assertEquals(
            MergeButtonState(MergeAction.MERGE_WHEN_PIPELINE_SUCCEEDS, null),
            mergeButtonState("opened", "ci_still_running"),
        )
    }

    @Test
    fun `known blockers disable the button with their reason key`() {
        val expected = mapOf(
            "conflict" to "merge.status.conflict",
            "not_approved" to "merge.status.notApproved",
            "draft_status" to "merge.status.draft",
            "discussions_not_resolved" to "merge.status.discussions",
            "need_rebase" to "merge.status.needRebase",
            "unchecked" to "merge.status.checking",
            "checking" to "merge.status.checking",
            "cannot_be_merged_recheck" to "merge.status.checking",
        )
        for ((status, reasonKey) in expected) {
            assertEquals(
                "status $status",
                MergeButtonState(MergeAction.DISABLED, reasonKey),
                mergeButtonState("opened", status),
            )
        }
    }

    @Test
    fun `an unknown or null status falls back to the generic reason`() {
        assertEquals(
            MergeButtonState(MergeAction.DISABLED, "merge.status.generic"),
            mergeButtonState("opened", "some_future_status"),
        )
        assertEquals(
            MergeButtonState(MergeAction.DISABLED, "merge.status.generic"),
            mergeButtonState("opened", null),
        )
    }

    @Test
    fun `a non-opened MR is disabled with no reason`() {
        // Even a "mergeable" status must not enable the button once the MR is merged or closed.
        assertEquals(MergeButtonState(MergeAction.DISABLED, null), mergeButtonState("merged", "mergeable"))
        assertEquals(MergeButtonState(MergeAction.DISABLED, null), mergeButtonState("closed", "conflict"))
        assertEquals(MergeButtonState(MergeAction.DISABLED, null), mergeButtonState(null, "mergeable"))
    }

    // --- approvalsHealth ----------------------------------------------------------------------

    private fun approvals(
        required: Int? = null,
        left: Int? = null,
        approvers: List<Long> = emptyList(),
    ) = GitLabApprovals(
        approvedBy = approvers.map { ApprovedBy(GitLabUser(id = it, username = "u$it", name = "U $it")) },
        approvalsRequired = required,
        approvalsLeft = left,
    )

    @Test
    fun `no approvals left with a positive requirement is satisfied`() {
        assertEquals(ApprovalsHealth.SATISFIED, approvalsHealth(approvals(required = 2, left = 0)))
    }

    @Test
    fun `approvals still left is pending`() {
        assertEquals(ApprovalsHealth.PENDING, approvalsHealth(approvals(required = 2, left = 1)))
    }

    @Test
    fun `without counts a non-empty approver list is satisfied`() {
        assertEquals(ApprovalsHealth.SATISFIED, approvalsHealth(approvals(approvers = listOf(1L, 2L))))
    }

    @Test
    fun `without counts and no approvers it is unknown`() {
        assertEquals(ApprovalsHealth.UNKNOWN, approvalsHealth(approvals()))
    }

    @Test
    fun `zero left with no approval rules falls back to the approver list`() {
        // approvals_left == 0 but approvals_required == 0 (no rules): defer to the approver list.
        assertEquals(ApprovalsHealth.UNKNOWN, approvalsHealth(approvals(required = 0, left = 0)))
        assertEquals(
            ApprovalsHealth.SATISFIED,
            approvalsHealth(approvals(required = 0, left = 0, approvers = listOf(9L))),
        )
    }
}
