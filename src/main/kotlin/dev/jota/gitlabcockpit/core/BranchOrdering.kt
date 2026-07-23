package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabBranch

/**
 * The order the Edit dialog's "Destination branch" autocomplete presents its candidates (GLC-57): the
 * repository's default branch first (the most common merge target, so it leads), then every other branch
 * name in case-insensitive alphabetical order. Returns plain names — the value the field holds and sends.
 *
 * Default branches keep their incoming order among themselves (GitLab returns exactly one in practice, so
 * this only matters for a malformed payload). Pure and platform-free so the ordering is unit-tested
 * without a UI; the completion provider just preserves the order it is fed via `setVariants`.
 */
fun orderBranchNames(branches: List<GitLabBranch>): List<String> {
    val (defaults, rest) = branches.partition { it.default }
    return defaults.map { it.name } + rest.map { it.name }.sortedWith(String.CASE_INSENSITIVE_ORDER)
}
