package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabApprovals

/**
 * What the Overview Merge button can do for the current mergeability state:
 * [MERGE] merges immediately, [MERGE_WHEN_PIPELINE_SUCCEEDS] queues the merge for a green pipeline,
 * and [DISABLED] leaves the button greyed out (with a reason tooltip, unless the MR is simply not open).
 */
enum class MergeAction { MERGE, MERGE_WHEN_PIPELINE_SUCCEEDS, DISABLED }

/**
 * The resolved Merge button state: the [action] to offer and, for a [MergeAction.DISABLED] button, the
 * bundle key of the reason to show as a tooltip ([reasonKey]) — or `null` when there is nothing to
 * explain (e.g. an already merged/closed MR).
 */
data class MergeButtonState(val action: MergeAction, val reasonKey: String?)

/**
 * Maps an MR's [state] (`opened`/`merged`/`closed`…) and GitLab's fine-grained
 * [detailedMergeStatus] to a [MergeButtonState]. A non-`opened` MR is always [MergeAction.DISABLED]
 * with no reason. For an open MR: `mergeable` enables the merge, `ci_still_running` offers
 * merge-when-pipeline-succeeds, and every other known blocker disables the button with a localized
 * reason; any unknown / null status falls back to a generic reason. Pure and platform-free.
 */
fun mergeButtonState(state: String?, detailedMergeStatus: String?): MergeButtonState {
    if (state != "opened") return MergeButtonState(MergeAction.DISABLED, null)
    return when (detailedMergeStatus) {
        "mergeable" -> MergeButtonState(MergeAction.MERGE, null)
        "ci_still_running" -> MergeButtonState(MergeAction.MERGE_WHEN_PIPELINE_SUCCEEDS, null)
        "conflict" -> MergeButtonState(MergeAction.DISABLED, "merge.status.conflict")
        "not_approved" -> MergeButtonState(MergeAction.DISABLED, "merge.status.notApproved")
        "draft_status" -> MergeButtonState(MergeAction.DISABLED, "merge.status.draft")
        "discussions_not_resolved" -> MergeButtonState(MergeAction.DISABLED, "merge.status.discussions")
        "need_rebase" -> MergeButtonState(MergeAction.DISABLED, "merge.status.needRebase")
        "unchecked", "checking", "cannot_be_merged_recheck" ->
            MergeButtonState(MergeAction.DISABLED, "merge.status.checking")
        else -> MergeButtonState(MergeAction.DISABLED, "merge.status.generic")
    }
}

/**
 * How healthy an MR's approvals are, for coloring the Overview approvals line: [SATISFIED] (green),
 * [PENDING] (amber) or [UNKNOWN] (no color — the project reports no approval rules).
 */
enum class ApprovalsHealth { SATISFIED, PENDING, UNKNOWN }

/**
 * Derives an [ApprovalsHealth] from [approvals]: `approvals_left == 0` with a positive
 * `approvals_required` is [SATISFIED]; a positive `approvals_left` is [PENDING]; when the counts are
 * absent it falls back to the approver list — a non-empty one is [SATISFIED], otherwise [UNKNOWN].
 * Pure and platform-free.
 */
fun approvalsHealth(approvals: GitLabApprovals): ApprovalsHealth {
    val left = approvals.approvalsLeft
    val required = approvals.approvalsRequired
    return when {
        left == 0 && required != null && required > 0 -> ApprovalsHealth.SATISFIED
        left != null && left > 0 -> ApprovalsHealth.PENDING
        approvals.approvedBy.isNotEmpty() -> ApprovalsHealth.SATISFIED
        else -> ApprovalsHealth.UNKNOWN
    }
}
