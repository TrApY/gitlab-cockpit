package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabMergeRequest

/**
 * The `group/project` label shown in front of an MR's title in the "All projects" list, so a row can
 * be told apart from an identically-titled MR in another project. Two sources are tried, in order:
 *
 * 1. the MR's `references.full` (`group/project!iid`) — the part before the `!`;
 * 2. a fallback derived from [GitLabMergeRequest.webUrl]: the path between the host and the
 *    `/-/merge_requests/` marker (which keeps any nested subgroup, e.g. `group/sub/project`).
 *
 * Returns `null` when neither source yields a usable label (so the caller can simply omit the prefix).
 * Pure and platform-free for direct unit testing.
 */
fun projectLabelOf(mr: GitLabMergeRequest): String? {
    mr.references?.full?.let { full ->
        if ('!' in full) {
            val label = full.substringBefore('!')
            if (label.isNotBlank()) return label
        }
    }
    return labelFromWebUrl(mr.webUrl)
}

/** Extracts `group/project` (or `group/sub/project`) from a merge-request web URL, or null. */
private fun labelFromWebUrl(webUrl: String): String? {
    val marker = "/-/merge_requests/"
    val markerIndex = webUrl.indexOf(marker)
    if (markerIndex < 0) return null
    val beforeMarker = webUrl.substring(0, markerIndex)
    val afterScheme = beforeMarker.substringAfter("://", beforeMarker)
    val firstSlash = afterScheme.indexOf('/')
    if (firstSlash < 0) return null
    return afterScheme.substring(firstSlash + 1).ifBlank { null }
}
