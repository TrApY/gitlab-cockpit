package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.api.NotePosition

/** Which diff editor a review thread anchors to: the base (old) side or the head (new) side. */
enum class AnchorSide { OLD, NEW }

/**
 * The editor anchor of a positioned discussion: the [side] whose editor hosts the thread and the
 * **1-based** GitLab [line] on that side. Consumers converting to an editor line must subtract 1
 * (editor lines are 0-based) and clamp to the document's line count at mount time.
 */
data class DiffAnchor(val side: AnchorSide, val line: Int)

/**
 * Resolves a GitLab note [position] to its [DiffAnchor], mirroring GitLab's `position` semantics:
 *
 * - `new_line` present (an added or context line) anchors to the **NEW** side at `new_line`; a
 *   context line carries both numbers and the new side wins, matching where GitLab's own UI shows it.
 * - only `old_line` present (a removed line) anchors to the **OLD** side at `old_line`.
 * - neither present: the position points at no line (e.g. an image note), so there is nothing to
 *   anchor and the result is null.
 */
fun anchorFor(position: NotePosition): DiffAnchor? = when {
    position.newLine != null -> DiffAnchor(AnchorSide.NEW, position.newLine)
    position.oldLine != null -> DiffAnchor(AnchorSide.OLD, position.oldLine)
    else -> null
}

/**
 * Groups diff [discussions] by the (side, line) anchor of each discussion's *first non-system
 * positioned note* (the same note [discussionsByFile] keys on). Discussions without such a note —
 * general comments and system-only threads — are dropped. Insertion order is preserved both across
 * anchors and within each anchor's thread list, so threads render in the order GitLab returned them.
 */
fun threadsByAnchor(discussions: List<GitLabDiscussion>): Map<DiffAnchor, List<GitLabDiscussion>> {
    val byAnchor = LinkedHashMap<DiffAnchor, MutableList<GitLabDiscussion>>()
    for (discussion in discussions) {
        val position = discussion.notes
            .firstOrNull { !it.system && it.position != null }
            ?.position ?: continue
        val anchor = anchorFor(position) ?: continue
        byAnchor.getOrPut(anchor) { mutableListOf() }.add(discussion)
    }
    return byAnchor
}
