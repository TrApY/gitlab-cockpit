package dev.jota.gitlabcockpit.ui.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer

/**
 * Entry point of the inline review-thread render (F4c). Registered as a `diff.DiffExtension` in
 * `plugin.xml`, so it sees *every* diff viewer the IDE creates; it only acts when
 *
 * - the request carries a [CockpitDiffContext] (put there by
 *   [dev.jota.gitlabcockpit.ui.ChangesPanel] — i.e. the diff was opened from the Changes tab), and
 * - the viewer is a [TwosideTextDiffViewer] (the side-by-side text viewer, whose editor lines map
 *   1:1 to GitLab's old/new line numbers because each side shows the whole base/head file).
 *
 * Any other viewer (unified, binary…) is left untouched.
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
        if (cockpitContext.discussions.isEmpty()) return
        DiffThreadsRenderer(project, viewer, cockpitContext).install()
    }
}
