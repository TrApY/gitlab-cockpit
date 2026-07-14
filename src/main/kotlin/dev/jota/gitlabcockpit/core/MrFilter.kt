package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabApprovals
import dev.jota.gitlabcockpit.api.GitLabMergeRequest

/**
 * Role-based filter chosen in the tool window toolbar. Whenever a role maps cleanly to a
 * server-side query param it is pushed to GitLab; [REVIEWER_NOT_APPROVED] additionally needs a
 * client-side cross-check against the approvals of each returned MR.
 */
enum class RoleFilter {
    ALL,
    I_AM_AUTHOR,
    I_AM_REVIEWER,
    REVIEWER_NOT_APPROVED,
    BY_USER,
}

/** Merge request `state` filter, with the exact value GitLab's REST API expects. */
enum class MergeRequestState(val apiValue: String) {
    OPENED("opened"),
    MERGED("merged"),
    CLOSED("closed"),
    ALL("all"),
}

/**
 * Full toolbar selection: role + state (+ the arbitrary username used only by [RoleFilter.BY_USER]).
 * This is what the UI hands to the project service.
 */
data class MrFilterSelection(
    val role: RoleFilter,
    val state: MergeRequestState,
    val username: String? = null,
)

/**
 * Keeps the merge requests the current user has NOT approved. The list is expected to already be
 * server-filtered to "I am a reviewer", so the only remaining test is whether [currentUserId]
 * appears among each MR's approvers. When approvals are unavailable for an MR (missing from
 * [approvalsByIid], e.g. the approvals call failed) the MR is kept — a review queue should err
 * towards showing work rather than hiding it.
 */
fun filterNotApproved(
    mrs: List<GitLabMergeRequest>,
    approvalsByIid: Map<Long, GitLabApprovals>,
    currentUserId: Long,
): List<GitLabMergeRequest> =
    mrs.filter { mr ->
        val approvedByMe = approvalsByIid[mr.iid]
            ?.approvedBy
            ?.any { it.user.id == currentUserId }
            ?: false
        !approvedByMe
    }
