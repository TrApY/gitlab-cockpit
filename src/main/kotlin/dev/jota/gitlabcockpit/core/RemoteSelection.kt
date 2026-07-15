package dev.jota.gitlabcockpit.core

/**
 * A git root of the current project whose remote matches the configured GitLab instance host.
 * [rootPath] is the absolute filesystem path of the repository root, used to prefer the project's
 * own root over nested ones (e.g. submodules).
 */
data class CandidateRemote(
    val coords: RemoteCoords,
    val rootPath: String,
)

/** Normalizes a filesystem path for comparison: unifies separators and drops trailing slashes. */
private fun normalizePath(path: String): String =
    path.replace('\\', '/').trimEnd('/')

/**
 * Picks the candidate to drive the tool window, in priority order:
 *  1. the one whose `pathWithNamespace` equals [persistedPath] (a repo the user explicitly chose),
 *  2. the one whose [CandidateRemote.rootPath] is the project's own [projectBasePath] (so the
 *     project's main repo wins over nested submodules by default), compared after normalizing
 *     trailing slashes and path separators,
 *  3. the first candidate.
 *
 * Returns `null` only when [candidates] is empty.
 */
fun chooseRemote(
    candidates: List<CandidateRemote>,
    persistedPath: String?,
    projectBasePath: String?,
): CandidateRemote? {
    if (candidates.isEmpty()) return null
    if (persistedPath != null) {
        candidates.firstOrNull { it.coords.pathWithNamespace == persistedPath }?.let { return it }
    }
    if (projectBasePath != null) {
        val base = normalizePath(projectBasePath)
        candidates.firstOrNull { normalizePath(it.rootPath) == base }?.let { return it }
    }
    return candidates.first()
}

/**
 * Orders candidates for the repo selector: the project's own root (its [CandidateRemote.rootPath]
 * matching [projectBasePath]) first, the rest alphabetically by `pathWithNamespace`. The list is
 * de-duplicated by `pathWithNamespace` — two roots resolving to the same GitLab project collapse to
 * one, keeping the base-path root if present, otherwise the first seen.
 */
fun orderCandidates(
    candidates: List<CandidateRemote>,
    projectBasePath: String?,
): List<CandidateRemote> {
    val base = projectBasePath?.let { normalizePath(it) }
    fun isBase(candidate: CandidateRemote): Boolean =
        base != null && normalizePath(candidate.rootPath) == base

    // Dedupe by pathWithNamespace, preferring the base-path root, else the first seen.
    val deduped = LinkedHashMap<String, CandidateRemote>()
    for (candidate in candidates) {
        val key = candidate.coords.pathWithNamespace
        val existing = deduped[key]
        if (existing == null || (!isBase(existing) && isBase(candidate))) {
            deduped[key] = candidate
        }
    }

    return deduped.values.sortedWith(
        compareByDescending<CandidateRemote> { isBase(it) }
            .thenBy { it.coords.pathWithNamespace },
    )
}
