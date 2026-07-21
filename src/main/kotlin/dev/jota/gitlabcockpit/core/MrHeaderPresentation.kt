package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabMergeRequest

/**
 * An MR's lifecycle as far as the Overview header's meta line is concerned: still [OPEN], or already
 * [MERGED] / [CLOSED] (mutually exclusive in practice — GitLab sets at most one of the two closing
 * timestamps).
 */
enum class Closing { OPEN, MERGED, CLOSED }

/**
 * The purely textual composition of the MR detail header, derived from the merge request and the
 * caller-formatted relative dates. The string assembly and the pipeline/merge decisions — the parts
 * worth testing — live here, pure and platform-free; the time formatting (which is time-dependent)
 * and the Swing styling stay in the UI.
 *
 * @property reference the `!iid` reference shown first on the meta line.
 * @property title the MR title.
 * @property draft whether to show the DRAFT badge next to the title.
 * @property sourceBranch the branch line's source (rendered normally).
 * @property targetBranch the branch line's target (rendered muted).
 * @property authorName the display name shown in the meta line's "by <author>".
 * @property createdRelative the pre-formatted creation relative date, or null when the payload omits it.
 * @property closing whether the meta line carries a merged/closed suffix, and which.
 * @property closingRelative the pre-formatted merged/closed relative date, or null when [closing] is
 * [Closing.OPEN].
 * @property pipelineStatus the head pipeline's status, or null when the MR has no head pipeline (the
 * pipeline line is then omitted).
 * @property merge the resolved Overview merge-readiness line.
 */
data class MrHeaderPresentation(
    val reference: String,
    val title: String,
    val draft: Boolean,
    val sourceBranch: String,
    val targetBranch: String,
    val authorName: String,
    val createdRelative: String?,
    val closing: Closing,
    val closingRelative: String?,
    val pipelineStatus: String?,
    val merge: MergeLinePresentation,
)

/**
 * Builds an [MrHeaderPresentation] from [mr], the display [authorName] and the already-formatted
 * relative dates the UI resolves ([createdRelative], [mergedRelative], [closedRelative]). A present
 * [mergedRelative] wins over [closedRelative] (an MR is merged xor closed); with neither the MR is
 * still open. The pipeline status comes from the MR's head pipeline and the merge-readiness line from
 * [mergeLinePresentation]. Pure and platform-free.
 */
fun mrHeaderPresentation(
    mr: GitLabMergeRequest,
    authorName: String,
    createdRelative: String?,
    mergedRelative: String?,
    closedRelative: String?,
): MrHeaderPresentation {
    val (closing, closingRelative) = when {
        mergedRelative != null -> Closing.MERGED to mergedRelative
        closedRelative != null -> Closing.CLOSED to closedRelative
        else -> Closing.OPEN to null
    }
    return MrHeaderPresentation(
        reference = "!${mr.iid}",
        title = mr.title,
        draft = mr.draft,
        sourceBranch = mr.sourceBranch,
        targetBranch = mr.targetBranch,
        authorName = authorName,
        createdRelative = createdRelative,
        closing = closing,
        closingRelative = closingRelative,
        pipelineStatus = mr.headPipeline?.status,
        merge = mergeLinePresentation(mr.state, mr.detailedMergeStatus),
    )
}
