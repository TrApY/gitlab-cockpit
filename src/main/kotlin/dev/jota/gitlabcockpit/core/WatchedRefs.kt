package dev.jota.gitlabcockpit.core

/**
 * Serialization of the per-project "watched" merge requests (GLC-28). A watched MR joins the
 * notification scope even when it does not match the user's role criteria. The set is persisted as a
 * single string in [com.intellij.ide.util.PropertiesComponent]; these two helpers are the pure,
 * platform-free codec so they can be unit tested directly.
 *
 * Wire format: each ref as `projectId:iid`, refs separated by commas, ordered by (projectId, iid) for
 * a stable, diff-friendly string.
 */
fun encodeWatchedRefs(refs: Set<MrRef>): String =
    refs.sortedWith(compareBy({ it.projectId }, { it.iid }))
        .joinToString(",") { "${it.projectId}:${it.iid}" }

/**
 * Parses the string produced by [encodeWatchedRefs] back into a set of [MrRef]. Tolerant to garbage:
 * a null/blank input yields the empty set, and any entry that is not exactly two non-negative longs
 * separated by a colon is skipped rather than failing the whole parse — so a corrupted or
 * hand-edited value never crashes the watcher.
 */
fun decodeWatchedRefs(raw: String?): Set<MrRef> {
    if (raw.isNullOrBlank()) return emptySet()
    return raw.split(",")
        .mapNotNull { entry ->
            val parts = entry.trim().split(":")
            if (parts.size != 2) return@mapNotNull null
            val projectId = parts[0].toLongOrNull() ?: return@mapNotNull null
            val iid = parts[1].toLongOrNull() ?: return@mapNotNull null
            MrRef(projectId, iid)
        }
        .toSet()
}
