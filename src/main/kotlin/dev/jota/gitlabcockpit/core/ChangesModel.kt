package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabDiffFile
import dev.jota.gitlabcockpit.api.GitLabDiscussion

/**
 * How a file changed in a merge request. Derived from a [GitLabDiffFile]'s boolean flags by
 * [changeTypeOf]; drives the file-tree icon.
 */
enum class ChangeType { ADDED, DELETED, RENAMED, MODIFIED }

/**
 * Classifies a changed file. The flags are mutually exclusive in practice, but a strict precedence
 * is applied so the result is deterministic: a new file wins over deleted, which wins over renamed;
 * anything else is a plain modification.
 */
fun changeTypeOf(file: GitLabDiffFile): ChangeType = when {
    file.newFile -> ChangeType.ADDED
    file.deletedFile -> ChangeType.DELETED
    file.renamedFile -> ChangeType.RENAMED
    else -> ChangeType.MODIFIED
}

/**
 * One node of the changed-files tree. A directory node ([isDir] true) has [children] and no [file];
 * a file leaf carries its [GitLabDiffFile] and no children. [path] is the node's full path from the
 * repository root (used both as a stable identity and as the discussion-map key for leaves).
 */
data class FileNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val children: List<FileNode>,
    val file: GitLabDiffFile?,
)

/**
 * Builds a directory tree from the flat [files] list. Each file is placed by its `new_path`
 * (or `old_path` when the file was deleted, since it no longer has a new path). Every node's
 * [FileNode.children] are ordered directories-first, then files, alphabetically within each group.
 * The returned root is a synthetic directory node whose [FileNode.children] are the top-level
 * entries.
 */
fun buildFileTree(files: List<GitLabDiffFile>): FileNode {
    val root = MutableNode(name = "", path = "", isDir = true)
    for (file in files) {
        val fullPath = if (file.deletedFile) file.oldPath else file.newPath
        val segments = fullPath.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) continue
        var current = root
        for ((index, segment) in segments.withIndex()) {
            val isLeaf = index == segments.lastIndex
            val childPath = if (current.path.isEmpty()) segment else "${current.path}/$segment"
            val child = current.children.getOrPut(segment) {
                MutableNode(name = segment, path = childPath, isDir = !isLeaf)
            }
            if (isLeaf) child.file = file
            current = child
        }
    }
    return root.toFileNode()
}

/**
 * Groups diff [discussions] by the file they are anchored to. The key is the `new_path` (falling
 * back to `old_path`) of the *first non-system note that carries a position*; discussions with no
 * positioned note — general MR comments — are excluded. System notes never contribute a position.
 * Insertion order of files is preserved.
 */
fun discussionsByFile(discussions: List<GitLabDiscussion>): Map<String, List<GitLabDiscussion>> {
    val byFile = LinkedHashMap<String, MutableList<GitLabDiscussion>>()
    for (discussion in discussions) {
        val position = discussion.notes
            .firstOrNull { !it.system && it.position != null }
            ?.position ?: continue
        val key = position.newPath ?: position.oldPath ?: continue
        byFile.getOrPut(key) { mutableListOf() }.add(discussion)
    }
    return byFile
}

/** Mutable scaffold used only while assembling the tree; converted to an immutable [FileNode]. */
private class MutableNode(val name: String, val path: String, val isDir: Boolean) {
    val children = LinkedHashMap<String, MutableNode>()
    var file: GitLabDiffFile? = null

    fun toFileNode(): FileNode {
        val sortedChildren = children.values
            .sortedWith(compareBy({ !it.isDir }, { it.name }))
            .map { it.toFileNode() }
        return FileNode(name = name, path = path, isDir = isDir, children = sortedChildren, file = file)
    }
}
