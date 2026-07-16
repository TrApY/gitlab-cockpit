package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabDiffFile

/**
 * The index [target] should occupy when [files] (the MR's changed files, in tree order) are opened as
 * a single diff chain — i.e. the position [com.intellij.diff.chains.SimpleDiffRequestChain.fromProducers]
 * should select so the chain opens on the file the user asked for while keeping the others reachable
 * with the platform's next/previous-file navigation.
 *
 * A file is matched by its old+new paths (the same identity the changed-files tree keys on), so a
 * freshly constructed [GitLabDiffFile] with the same paths still resolves. Falls back to `0` when
 * [target] is not among [files] (an empty list included), so the caller always gets a valid producer
 * index. Pure and platform-free.
 */
fun chainIndex(files: List<GitLabDiffFile>, target: GitLabDiffFile): Int {
    val index = files.indexOfFirst { it.oldPath == target.oldPath && it.newPath == target.newPath }
    return if (index >= 0) index else 0
}
