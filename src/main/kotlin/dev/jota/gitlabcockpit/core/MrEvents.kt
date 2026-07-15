package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabMergeRequest

/**
 * The minimal per-MR fingerprint the notifications watcher remembers between passes, keyed by
 * [MrRef]. [state] tracks merged/closed/reopened transitions, [sha] the MR head (a new push), and
 * [notesCount] the user-note count (new comments). [sha] and [notesCount] are nullable because the
 * list endpoint may omit them on a trimmed payload; a null on either side is treated as "unknown"
 * and never fires its event (see [detectMrEvents]).
 */
data class MrSnapshot(val state: String, val sha: String?, val notesCount: Int?)

/**
 * A change on a watched merge request worth an IDE notification. Every variant carries the [mr] it
 * happened on so the notifier can render its `!iid — title`.
 */
sealed interface MrEvent {
    val mr: GitLabMergeRequest

    /** The MR just entered the watched scope (no prior snapshot for it). */
    data class NewMr(override val mr: GitLabMergeRequest) : MrEvent

    /** The MR's `state` changed from [old] to [new] (e.g. `opened` → `merged`/`closed`). */
    data class StateChanged(override val mr: GitLabMergeRequest, val old: String, val new: String) : MrEvent

    /** New commits were pushed: the MR's head [MrSnapshot.sha] changed. */
    data class NewPush(override val mr: GitLabMergeRequest) : MrEvent

    /** [count] new user comments were added since the previous pass (the note-count delta). */
    data class NewComments(override val mr: GitLabMergeRequest, val count: Int) : MrEvent
}

/**
 * The current user's notification scope (v1): the merge requests where the user (id [meId]) is the
 * author, an assignee or a reviewer. Pure and platform-free.
 */
fun mrScope(mrs: List<GitLabMergeRequest>, meId: Long): List<GitLabMergeRequest> =
    mrs.filter { mr ->
        mr.author.id == meId ||
            mr.assignees.any { it.id == meId } ||
            mr.reviewers.any { it.id == meId }
    }

/**
 * Diffs the current scoped [mrs] against the [previous] snapshot and returns the detected [MrEvent]s
 * together with the fresh snapshot to remember for the next pass (keyed by [MrRef]).
 *
 * Rules:
 * - `previous == null` (the very first pass) yields **no events** — it only memorizes the baseline.
 * - An MR with no [previous] entry is a [MrEvent.NewMr].
 * - A changed `state` is a [MrEvent.StateChanged] (carrying the old and new state).
 * - A changed `sha` (both sides non-null) is a [MrEvent.NewPush].
 * - A grown note count (both sides non-null, new > old) is a [MrEvent.NewComments] with the delta.
 *
 * A single MR may emit several events in one pass. MRs that dropped out of [mrs] simply fall out of
 * the returned snapshot with no event. Pure and platform-free.
 */
fun detectMrEvents(
    previous: Map<MrRef, MrSnapshot>?,
    mrs: List<GitLabMergeRequest>,
): Pair<List<MrEvent>, Map<MrRef, MrSnapshot>> {
    val snapshot = mrs.associate { mr ->
        MrRef(mr.projectId, mr.iid) to MrSnapshot(mr.state, mr.sha, mr.userNotesCount)
    }
    if (previous == null) return emptyList<MrEvent>() to snapshot

    val events = mutableListOf<MrEvent>()
    for (mr in mrs) {
        val prev = previous[MrRef(mr.projectId, mr.iid)]
        if (prev == null) {
            events += MrEvent.NewMr(mr)
            continue
        }
        if (prev.state != mr.state) {
            events += MrEvent.StateChanged(mr, prev.state, mr.state)
        }
        if (prev.sha != null && mr.sha != null && prev.sha != mr.sha) {
            events += MrEvent.NewPush(mr)
        }
        if (prev.notesCount != null && mr.userNotesCount != null && mr.userNotesCount > prev.notesCount) {
            events += MrEvent.NewComments(mr, mr.userNotesCount - prev.notesCount)
        }
    }
    return events to snapshot
}
