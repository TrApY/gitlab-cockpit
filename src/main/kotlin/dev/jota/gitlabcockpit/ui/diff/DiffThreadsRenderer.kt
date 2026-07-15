package dev.jota.gitlabcockpit.ui.diff

import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.jota.gitlabcockpit.core.AnchorSide
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.threadsByAnchor
import javax.swing.JPanel

/**
 * Mounts one embedded component per (side, line) anchor of the file's review threads, using
 * [EditorEmbeddedComponentManager] — the same block-inlay mechanism the bundled GitHub/GitLab
 * plugins use for their inline comments. Each component is a vertical stack of [DiffThreadPanel]s
 * (one per discussion on that line), shown *below* the anchored line of the corresponding side's
 * editor.
 *
 * Lifecycle: mounting is deferred to the viewer's `onInit` (editors fully initialized), and every
 * inlay is registered against the viewer via [Disposer] — closing the diff cancels the panels'
 * in-flight actions and disposes the inlays, so nothing survives the viewer.
 */
internal class DiffThreadsRenderer(
    private val project: Project,
    private val viewer: TwosideTextDiffViewer,
    private val diffContext: CockpitDiffContext,
) {

    /** Defers mounting to the viewer's init; [DiffViewerListener.onInit] fires on the EDT. */
    fun install() {
        viewer.addListener(object : DiffViewerListener() {
            override fun onInit() = mount()
        })
    }

    /** EDT. Creates one embedded inlay per anchor and ties its lifetime to the viewer. */
    private fun mount() {
        val service = CockpitProjectService.getInstance(project)
        for ((anchor, discussions) in threadsByAnchor(diffContext.discussions)) {
            val editor = viewer.getEditor(if (anchor.side == AnchorSide.OLD) Side.LEFT else Side.RIGHT)
            val document = editor.document
            // GitLab lines are 1-based, editor lines 0-based: line N lives at document line N-1.
            // The index is clamped to the document so a stale position (side shorter than the note
            // expects) still mounts on the last line instead of throwing; an empty side anchors at 0.
            val offset = if (document.lineCount == 0) {
                0
            } else {
                document.getLineStartOffset((anchor.line - 1).coerceIn(0, document.lineCount - 1))
            }

            val panels = discussions.map { DiffThreadPanel(project, service, diffContext.mrRef, it) }
            val group = JPanel(VerticalLayout(JBUI.scale(4))).apply {
                isOpaque = false
                border = JBUI.Borders.empty(2, 10)
                panels.forEach { add(it) }
            }

            val inlay = EditorEmbeddedComponentManager.getInstance().addComponent(
                editor,
                group,
                EditorEmbeddedComponentManager.Properties(
                    EditorEmbeddedComponentManager.ResizePolicy.none(),
                    null, // no gutter renderer for the inlay itself
                    true, // relatesToPrecedingText: the thread belongs to the line above it
                    false, // showAbove = false: render below the anchored line
                    false, // showWhenFolded
                    true, // fullWidth: stretch to the editor's width
                    0, // priority
                    offset,
                ),
            ) ?: continue

            panels.forEach { panel ->
                panel.onContentChanged = {
                    group.revalidate()
                    group.repaint()
                    inlay.update()
                }
            }

            // Child disposables run before the viewer's own dispose, i.e. while the editor is still
            // alive; the isValid guard skips inlays the editor release already killed.
            Disposer.register(
                viewer,
                Disposable {
                    panels.forEach { it.cancelPendingAction() }
                    if (inlay.isValid) Disposer.dispose(inlay)
                },
            )
        }
    }
}
