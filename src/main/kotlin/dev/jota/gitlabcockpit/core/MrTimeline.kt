package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabNote

/**
 * One entry of the "Events & Discussions" timeline (GLC-34): either a GitLab [EventItem] (a system
 * note such as "added 3 commits" or "approved this merge request") or a user [DiscussionItem] (a
 * comment thread). Ordered purely by [createdAt] so the two sources interleave chronologically. Pure
 * and platform-free so [buildTimeline] can be unit tested directly.
 */
sealed interface TimelineItem {

    /** The ISO-8601 instant the entry sorts on (a system note's, or a thread's first note's). */
    val createdAt: String

    /** A single GitLab system note rendered as a compact event line. */
    data class EventItem(val note: GitLabNote) : TimelineItem {
        override val createdAt: String get() = note.createdAt
    }

    /** A user discussion thread; it sorts on its first note (the thread's opening comment). */
    data class DiscussionItem(val thread: CommentThread) : TimelineItem {
        override val createdAt: String get() = thread.notes.first().createdAt
    }
}

/** Which timeline entries the toolbar filter keeps: everything, only events, or only discussions. */
enum class TimelineFilter { ALL, EVENTS, DISCUSSIONS }

/**
 * Merges the MR's system [notes] (each becomes a [TimelineItem.EventItem]) and its user [threads]
 * (each becomes a [TimelineItem.DiscussionItem]) into one chronologically ordered timeline. Only the
 * `system` notes are turned into events (human notes already reach the timeline as their thread);
 * [filter] drops one of the two sources when not [TimelineFilter.ALL]. Order is by [createdAt],
 * ascending by default (as the GitLab web timeline reads) or descending when [ascending] is false;
 * ties keep events before discussions. Pure and platform-free.
 */
fun buildTimeline(
    notes: List<GitLabNote>,
    threads: List<CommentThread>,
    filter: TimelineFilter,
    ascending: Boolean,
): List<TimelineItem> {
    val items = buildList {
        if (filter != TimelineFilter.DISCUSSIONS) {
            notes.filter { it.system }.forEach { add(TimelineItem.EventItem(it)) }
        }
        if (filter != TimelineFilter.EVENTS) {
            threads.forEach { add(TimelineItem.DiscussionItem(it)) }
        }
    }
    return if (ascending) items.sortedBy { it.createdAt } else items.sortedByDescending { it.createdAt }
}

/**
 * Filters an already-built [items] timeline by a free-text [query] (GLC-40, the toolbar search),
 * case-insensitively. An [TimelineItem.EventItem] matches when the query is in its note body or its
 * author's name/username; a [TimelineItem.DiscussionItem] matches when the query is in the body or
 * author of *any* of its notes (so a hit on a reply keeps the whole thread). A blank query returns
 * every item unchanged, preserving order. Pure and platform-free.
 */
fun filterTimeline(items: List<TimelineItem>, query: String): List<TimelineItem> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return items
    return items.filter { item ->
        when (item) {
            is TimelineItem.EventItem -> matchesNote(item.note.body, item.note.author.name, item.note.author.username, needle)
            is TimelineItem.DiscussionItem ->
                item.thread.notes.any { note ->
                    matchesNote(note.body, note.author.name, note.author.username, needle)
                }
        }
    }
}

/** True when [needle] (already lowercased) is a substring of the note body or either author field. */
private fun matchesNote(body: String, authorName: String, authorUsername: String, needle: String): Boolean =
    body.lowercase().contains(needle) ||
        authorName.lowercase().contains(needle) ||
        authorUsername.lowercase().contains(needle)

/**
 * Classifies a system note [body] into an icon key by the phrase GitLab uses, so the UI can pick an
 * icon: `commit` ("added N commits"), `assign` ("assigned to …"), `review` ("requested review …"),
 * `approve` ("approved this merge request"), `merge` ("merged"), `state` ("closed" / "reopened" /
 * "marked …"), or `generic` for anything else. Matched case-insensitively; the order resolves the
 * rare overlaps (e.g. an "approved this merge request" note is an `approve`, not a `merge`). Pure.
 */
fun eventIconKey(body: String): String {
    val b = body.lowercase()
    return when {
        b.contains("commit") -> "commit"
        b.contains("assigned") -> "assign"
        b.contains("requested review") -> "review"
        b.contains("approved") -> "approve"
        b.contains("merged") -> "merge"
        b.contains("closed") || b.contains("reopened") || b.contains("marked") -> "state"
        else -> "generic"
    }
}
