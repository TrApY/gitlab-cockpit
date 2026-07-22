package dev.jota.gitlabcockpit.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import dev.jota.gitlabcockpit.core.ReviewedFiles
import dev.jota.gitlabcockpit.core.ThreadSide

/**
 * Entry point of the diff-review keyboard/inline features (F4c + GLC-29). Registered as a
 * `diff.DiffExtension` in `plugin.xml`, so it sees *every* diff viewer the IDE creates; it only acts
 * when
 *
 * - the request carries a [CockpitDiffContext] (put there by
 *   [dev.jota.gitlabcockpit.ui.ChangesPanel] — i.e. the diff was opened from the Changes tab), and
 * - the viewer is a [TwosideTextDiffViewer] (the side-by-side text viewer, whose editor lines map
 *   1:1 to GitLab's old/new line numbers because each side shows the whole base/head file).
 *
 * For such a diff it always stamps a [CockpitCommentHandle] on both editors, so "New comment at caret"
 * works even on a file with no threads. Only when the file *has* threads does it also install the
 * inline [DiffThreadsRenderer] and stamp a [CockpitThreadNavigator] (nothing to navigate otherwise).
 * Any other viewer (unified, binary…) is left untouched.
 *
 * Showing a cockpit diff also *auto-marks* its file reviewed (GLC-35): opening a file's diff is the
 * user reviewing it, so [ReviewedFiles] records it (keyed at the MR's head SHA) as soon as the viewer
 * exists, and the owning Changes tab is asked to refresh its tree/counter via
 * [CockpitDiffContext.onFileReviewed].
 */
class CockpitDiffExtension : DiffExtension() {

    override fun onViewerCreated(
        viewer: FrameDiffTool.DiffViewer,
        context: DiffContext,
        request: DiffRequest,
    ) {
        val cockpitContext = request.getUserData(CockpitDiffContext.KEY) ?: return
        if (viewer !is TwosideTextDiffViewer) return
        val project = context.project ?: return

        autoMarkReviewed(project, cockpitContext)
        // GLC-43 C14: keep the Changes tree selection following the file whose diff is now shown (the
        // same per-file hook as auto-mark-reviewed, so chain next/previous navigation drives it too).
        cockpitContext.onFileShown?.invoke()

        cockpitContext.openNewThread?.let { openThread -> stampCommentHandles(viewer, openThread) }

        if (cockpitContext.discussions.isNotEmpty()) {
            val renderer = DiffThreadsRenderer(project, viewer, cockpitContext)
            renderer.install()
            stampNavigator(viewer, renderer)
        }
    }

    /**
     * Records the shown file as reviewed for its MR at the diff's head SHA, then lets the owning
     * Changes tab refresh its tree/counter. The path is the file's display path (`new_path`, or
     * `old_path` for a deleted file), matching the tree's file keys.
     */
    private fun autoMarkReviewed(
        project: Project,
        cockpitContext: CockpitDiffContext,
    ) {
        val file = cockpitContext.file
        val path = if (file.deletedFile) file.oldPath else file.newPath
        ReviewedFiles.getInstance(project).mark(cockpitContext.mrRef, cockpitContext.refs.headSha, path)
        cockpitContext.onFileReviewed?.invoke()
    }

    /**
     * Puts a [CockpitCommentHandle] on the left (OLD) and right (NEW) editors and clears both when the
     * viewer is disposed. Disposal runs while the editors are still alive (child disposables fire
     * before the viewer's own), so no validity guard is needed.
     */
    private fun stampCommentHandles(
        viewer: TwosideTextDiffViewer,
        openThread: (ThreadSide, Int) -> Unit,
    ) {
        val left = viewer.getEditor(Side.LEFT)
        val right = viewer.getEditor(Side.RIGHT)
        left.putUserData(CockpitCommentHandle.KEY, CockpitCommentHandle(ThreadSide.OLD, openThread))
        right.putUserData(CockpitCommentHandle.KEY, CockpitCommentHandle(ThreadSide.NEW, openThread))
        clearOnDispose(viewer, left, right, CockpitCommentHandle.KEY)
    }

    /** Points both editors at the file's renderer for thread navigation, cleared on viewer disposal. */
    private fun stampNavigator(viewer: TwosideTextDiffViewer, renderer: DiffThreadsRenderer) {
        val navigator = CockpitThreadNavigator(renderer::navigate)
        val left = viewer.getEditor(Side.LEFT)
        val right = viewer.getEditor(Side.RIGHT)
        left.putUserData(CockpitThreadNavigator.KEY, navigator)
        right.putUserData(CockpitThreadNavigator.KEY, navigator)
        clearOnDispose(viewer, left, right, CockpitThreadNavigator.KEY)
    }

    private fun <T> clearOnDispose(
        viewer: TwosideTextDiffViewer,
        left: Editor,
        right: Editor,
        key: com.intellij.openapi.util.Key<T>,
    ) {
        Disposer.register(
            viewer,
            Disposable {
                left.putUserData(key, null)
                right.putUserData(key, null)
            },
        )
    }
}
