package dev.jota.gitlabcockpit.ui.diff

import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.impl.EditorEmbeddedComponentManager
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabDiscussion
import dev.jota.gitlabcockpit.core.AnchorSide
import dev.jota.gitlabcockpit.core.CockpitProjectService
import dev.jota.gitlabcockpit.core.DiffAnchor
import dev.jota.gitlabcockpit.core.nextAnchorIndex
import dev.jota.gitlabcockpit.core.sortAnchors
import dev.jota.gitlabcockpit.core.threadNeedsAttention
import dev.jota.gitlabcockpit.core.threadsByAnchor
import dev.jota.gitlabcockpit.ui.CockpitTheme
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Mounts one embedded component per (side, line) anchor of the file's review threads, using
 * [EditorEmbeddedComponentManager] — the same block-inlay mechanism the bundled GitHub/GitLab
 * plugins use for their inline comments. Each component is a vertical stack of [DiffThreadPanel]s
 * (one per discussion on that line), shown *below* the anchored line of the corresponding side's
 * editor.
 *
 * On the same pass it also *marks* every commented line: a gutter icon (with the line's comment
 * count as tooltip) plus, for lines whose thread still needs attention (an unresolved resolvable
 * note), a translucent amber line background. Fully resolved threads keep the gutter icon but get no
 * background.
 *
 * Lifecycle: mounting is deferred to the viewer's `onInit` (editors fully initialized), and every
 * inlay and line highlighter is registered against the viewer via [Disposer] — closing the diff
 * cancels the panels' in-flight actions and disposes the inlays/highlighters, so nothing survives
 * the viewer (and a reopened diff rebuilds them from scratch — no orphans after a refresh).
 */
internal class DiffThreadsRenderer(
    private val project: Project,
    private val viewer: TwosideTextDiffViewer,
    private val diffContext: CockpitDiffContext,
) {

    /**
     * The file's mounted anchors, sorted for navigation ([sortAnchors]): line ascending, OLD before
     * NEW at an equal line. Populated on [mount] (EDT); read by [navigate] (EDT). Empty until the
     * viewer inits, so an early Next/Previous is a harmless no-op.
     */
    private var mountedAnchors: List<MountedAnchor> = emptyList()

    /** One navigable review-thread anchor: the [editor] hosting it, its 0-based [lineIndex], the [anchor]. */
    private data class MountedAnchor(val editor: Editor, val lineIndex: Int, val anchor: DiffAnchor)

    /** Defers mounting to the viewer's init; [DiffViewerListener.onInit] fires on the EDT. */
    fun install() {
        viewer.addListener(object : DiffViewerListener() {
            override fun onInit() = mount()
        })
    }

    /** EDT. Creates one embedded inlay + line marker per anchor and ties their lifetime to the viewer. */
    private fun mount() {
        val service = CockpitProjectService.getInstance(project)
        // The line (and its editor) hosting the discussion the diff was asked to scroll to, if any.
        var revealTarget: Pair<Editor, Int>? = null
        val mounted = mutableListOf<MountedAnchor>()

        for ((anchor, discussions) in threadsByAnchor(diffContext.discussions)) {
            val editor = viewer.getEditor(if (anchor.side == AnchorSide.OLD) Side.LEFT else Side.RIGHT)
            val document = editor.document
            // GitLab lines are 1-based, editor lines 0-based: line N lives at document line N-1.
            // The index is clamped to the document so a stale position (side shorter than the note
            // expects) still mounts on the last line instead of throwing; an empty side anchors at 0.
            val lineIndex = if (document.lineCount == 0) 0 else (anchor.line - 1).coerceIn(0, document.lineCount - 1)
            val offset = if (document.lineCount == 0) 0 else document.getLineStartOffset(lineIndex)

            val panels = discussions.map {
                DiffThreadPanel(project, service, diffContext.mrRef, it, diffContext.projectWebUrl)
            }
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

            val highlighter = addLineMarker(editor, lineIndex, discussions)
            mounted += MountedAnchor(editor, lineIndex, anchor)

            if (diffContext.revealDiscussionId != null &&
                discussions.any { it.id == diffContext.revealDiscussionId }
            ) {
                revealTarget = editor to lineIndex
            }

            panels.forEach { panel ->
                panel.onContentChanged = {
                    group.revalidate()
                    group.repaint()
                    inlay.update()
                }
            }

            // Child disposables run before the viewer's own dispose, i.e. while the editor is still
            // alive; the isValid guards skip inlays/highlighters the editor release already killed.
            Disposer.register(
                viewer,
                Disposable {
                    panels.forEach { it.cancelPendingAction() }
                    if (inlay.isValid) Disposer.dispose(inlay)
                    if (highlighter.isValid) highlighter.dispose()
                },
            )
        }

        // Order the mounted anchors for keyboard navigation; each unique anchor maps to one mount.
        val byAnchor = mounted.associateBy { it.anchor }
        mountedAnchors = sortAnchors(mounted.map { it.anchor }).mapNotNull { byAnchor[it] }

        revealTarget?.let { (editor, line) ->
            editor.scrollingModel.scrollTo(LogicalPosition(line, 0), ScrollType.CENTER)
        }
        // One-shot: never scroll again if this viewer re-inits.
        diffContext.revealDiscussionId = null
    }

    /**
     * EDT. Moves the caret to the next ([forward] = true) or previous review thread and scrolls it
     * into view, cycling. The "current" position is the focused editor's side + caret line; from it
     * [nextAnchorIndex] picks the thread to jump to (the anchor on the caret line, else the nearest one
     * in the navigation order). A no-op until threads are mounted or when the focused editor is not one
     * of the two sides. Driven by [CockpitThreadNavigator] on both editors.
     */
    fun navigate(forward: Boolean) {
        val anchors = mountedAnchors
        if (anchors.isEmpty()) return
        val index = nextAnchorIndex(anchors.map { it.anchor }, currentReference(), forward) ?: return
        val target = anchors[index]
        target.editor.caretModel.moveToLogicalPosition(LogicalPosition(target.lineIndex, 0))
        target.editor.scrollingModel.scrollTo(LogicalPosition(target.lineIndex, 0), ScrollType.CENTER)
        target.editor.contentComponent.requestFocusInWindow()
    }

    /** The caret's `(side, 1-based line)` in the focused editor, or null when it is neither side. */
    private fun currentReference(): DiffAnchor? {
        val editor = viewer.currentEditor ?: return null
        val side = when (editor) {
            viewer.getEditor(Side.LEFT) -> AnchorSide.OLD
            viewer.getEditor(Side.RIGHT) -> AnchorSide.NEW
            else -> return null
        }
        return DiffAnchor(side, editor.caretModel.logicalPosition.line + 1)
    }

    /**
     * Adds the gutter icon (tooltip = the line's comment count) plus, when any thread on the line
     * still needs attention, the translucent amber line background. The highlighter is returned so
     * the caller can tie its disposal to the viewer.
     */
    private fun addLineMarker(
        editor: Editor,
        lineIndex: Int,
        discussions: List<GitLabDiscussion>,
    ): RangeHighlighter {
        val needsAttention = discussions.any { threadNeedsAttention(it.notes) }
        val commentCount = discussions.sumOf { discussion -> discussion.notes.count { !it.system } }
        val attributes = if (needsAttention) TextAttributes().apply { backgroundColor = CockpitTheme.attentionBackground } else null
        val highlighter = editor.markupModel.addLineHighlighter(lineIndex, HighlighterLayer.SELECTION - 1, attributes)
        highlighter.gutterIconRenderer =
            ThreadGutterIconRenderer(CockpitBundle.message("diff.gutter.tooltip", commentCount))
        return highlighter
    }

    /** Gutter marker for a commented line; its tooltip carries the line's comment count. */
    private class ThreadGutterIconRenderer(private val tooltip: String) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.Toolwindows.ToolWindowMessages
        override fun getTooltipText(): String = tooltip
        override fun getAlignment(): Alignment = Alignment.LEFT
        override fun equals(other: Any?): Boolean = other is ThreadGutterIconRenderer && other.tooltip == tooltip
        override fun hashCode(): Int = tooltip.hashCode()
    }
}
