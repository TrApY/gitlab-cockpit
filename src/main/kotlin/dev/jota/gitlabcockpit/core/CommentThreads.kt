package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.api.GitLabDiscussionNote

/**
 * One discussion thread reduced to what the Comments tab renders: its [discussionId], the human
 * [notes] (GitLab system notes filtered out, first-to-last order kept) and whether the thread is
 * [resolved]. Pure and platform-free so it can be unit tested directly.
 */
data class CommentThread(
    val discussionId: String,
    val notes: List<GitLabDiscussionNote>,
    val resolved: Boolean,
)

/**
 * Turns raw [discussions] into renderable [CommentThread]s: system notes are dropped from each
 * discussion, discussions left empty by that filtering (pure system threads) are discarded, and the
 * original discussion order is preserved. A thread is [CommentThread.resolved] when its first
 * resolvable note is marked resolved. Pure and platform-free.
 */
fun commentThreads(discussions: List<GitLabDiscussion>): List<CommentThread> =
    discussions.mapNotNull { discussion ->
        val notes = discussion.notes.filterNot { it.system }
        if (notes.isEmpty()) return@mapNotNull null
        val resolved = notes.firstOrNull { it.resolvable }?.resolved == true
        CommentThread(discussion.id, notes, resolved)
    }

/**
 * A short `path:line` anchor for a diff-positioned [thread] — taken from its first note that carries
 * a [dev.jota.gitlabcockpit.api.NotePosition]. The new-side path/line are used when both are present,
 * otherwise the old-side ones; a lone path (no line) falls back to just the path. Null for general
 * (non-positioned) threads. Pure and platform-free.
 */
fun threadAnchorLabel(thread: CommentThread): String? {
    val position = thread.notes.firstOrNull { it.position != null }?.position ?: return null
    return when {
        position.newPath != null && position.newLine != null -> "${position.newPath}:${position.newLine}"
        position.oldPath != null && position.oldLine != null -> "${position.oldPath}:${position.oldLine}"
        position.newPath != null -> position.newPath
        position.oldPath != null -> position.oldPath
        else -> null
    }
}
