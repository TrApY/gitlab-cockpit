package dev.jota.gitlabcockpit.ui.diff

import com.intellij.openapi.util.Key
import dev.jota.gitlabcockpit.api.DiffRefs
import dev.jota.gitlabcockpit.api.GitLabDiffFile
import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.core.MrRef
import dev.jota.gitlabcockpit.core.ThreadSide

/**
 * Everything [CockpitDiffExtension] needs to render one file's review threads inside its diff:
 * the MR's [mrRef], the changed [file], the MR's diff [refs], the [discussions] anchored to the
 * file (the slice of `discussionsByFile` for it) and the MR's [projectWebUrl] (used to absolutize
 * relative `/uploads/…` attachment links in the embedded threads).
 * [dev.jota.gitlabcockpit.ui.ChangesPanel] attaches an instance to each `SimpleDiffRequest` it opens
 * via [KEY] — plain request user-data, no global state, so every opened diff carries exactly the
 * threads that were loaded when it was opened.
 *
 * [revealDiscussionId] is a one-shot scroll target: when a diff is opened by the "jump to thread"
 * action of the Comments tab, it names the discussion the diff should scroll to once its inline
 * threads are mounted. [DiffThreadsRenderer] consumes it (clears it to null) after scrolling, so a
 * later re-init of the same viewer does not scroll again. Null for a diff opened any other way.
 *
 * [openNewThread] lets the "New comment at caret" action ([CockpitCommentHandle]) start a review
 * thread on the caret's `(side, 1-based line)` reusing ChangesPanel's new-thread flow;
 * [CockpitDiffExtension] moves it onto each editor as user-data. Null (the default) leaves the action
 * disabled — e.g. for tests that build a context without the panel.
 *
 * [onFileReviewed] is invoked (on the EDT) right after [CockpitDiffExtension] auto-marks this file
 * reviewed when its viewer is created, so the owning [dev.jota.gitlabcockpit.ui.ChangesPanel] can
 * refresh its tree and reviewed counter while they are visible. Null (the default) means "nothing to
 * refresh" — the file is still marked in the persistent store regardless.
 */
data class CockpitDiffContext(
    val mrRef: MrRef,
    val file: GitLabDiffFile,
    val refs: DiffRefs,
    val discussions: List<GitLabDiscussion>,
    val projectWebUrl: String?,
    var revealDiscussionId: String? = null,
    val openNewThread: ((side: ThreadSide, line1Based: Int) -> Unit)? = null,
    val onFileReviewed: (() -> Unit)? = null,
) {
    companion object {
        /** The request user-data slot [CockpitDiffExtension] looks for. */
        val KEY: Key<CockpitDiffContext> = Key.create("dev.jota.gitlabcockpit.diff.context")
    }
}
