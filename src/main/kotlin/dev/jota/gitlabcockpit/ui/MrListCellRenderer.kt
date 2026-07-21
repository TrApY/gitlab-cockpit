package dev.jota.gitlabcockpit.ui

import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import dev.jota.gitlabcockpit.CockpitBundle
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.core.MrSegmentStyle
import dev.jota.gitlabcockpit.core.mrRowPresentation
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer

/**
 * The MR list row renderer: a two-line cell (title on top, muted `iid · author · when · branches`
 * below) with a right-aligned column carrying the head-pipeline status icon, a comments badge and the
 * author + reviewer avatars. Replaces the old single-line [com.intellij.ui.ColoredListCellRenderer]
 * because a 2-line layout with a right column needs a real [JPanel] renderer.
 *
 * All text composition is delegated to [mrRowPresentation] (pure, tested); this class only paints.
 * Avatars come from [AvatarCache] (placeholder first, [repaintList] on load) and the pipeline status
 * from [MrListEnrichment] (no icon until known). Selection colors are honored on every segment.
 *
 * @param showProject mirrors the "All projects" mode; when set, line 2 is prefixed with `group/project`.
 */
class MrListCellRenderer(
    project: Project,
    private val avatarCache: AvatarCache,
    private val enrichment: MrListEnrichment,
    private val repaintList: () -> Unit,
) : ListCellRenderer<GitLabMergeRequest> {

    var showProject: Boolean = false

    private val draftPrefix: String = CockpitBundle.message("toolwindow.mr.draftPrefix") + " "
    private val conflictsSuffix: String = " · " + CockpitBundle.message("toolwindow.mr.conflicts")

    private val line1 = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.insets(0)
    }
    private val line2 = SimpleColoredComponent().apply {
        isOpaque = false
        ipad = JBUI.insets(0)
    }

    /**
     * The `sourceBranch` chip: a muted rounded pill painted on [line2Row] (GLC-37), prefixed with the
     * collab branch glyph (GLC-38 / iter3 A3) so it reads as a branch at a glance.
     */
    private val sourceChip = BranchChipLabel().apply {
        icon = CockpitIcons.branchChip
        iconTextGap = JBUI.scale(2)
    }

    /** The `targetBranch` chip. */
    private val targetChip = BranchChipLabel()

    /** The muted `→` between the two branch chips. */
    private val branchArrow = JLabel(BRANCH_ARROW)

    /** Line 2: the muted metadata text followed by the two branch chips, left-aligned. */
    private val line2Row = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        add(line2)
        add(Box.createHorizontalStrut(JBUI.scale(RIGHT_GAP)))
        add(sourceChip)
        add(Box.createHorizontalStrut(JBUI.scale(CHIP_GAP)))
        add(branchArrow)
        add(Box.createHorizontalStrut(JBUI.scale(CHIP_GAP)))
        add(targetChip)
        add(Box.createHorizontalGlue())
    }

    private val textColumn = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        line1.alignmentX = Component.LEFT_ALIGNMENT
        add(Box.createVerticalGlue())
        add(line1)
        add(line2Row)
        add(Box.createVerticalGlue())
    }

    private val statusLabel = JLabel()

    /** Conflicts indicator (GLC-38 / iter3 A3): the collab non-mergeable glyph, shown only on conflict. */
    private val conflictsLabel = JLabel(CockpitIcons.nonMergeable).apply {
        toolTipText = CockpitBundle.message("toolwindow.mr.conflicts")
    }

    private val commentsLabel = JLabel().apply { iconTextGap = JBUI.scale(2) }

    /** The author's circular avatar: its own fixed element, one [RIGHT_GAP] gap from the comments badge. */
    private val authorLabel = JLabel()

    /**
     * Up to [MAX_REVIEWER_AVATARS] reviewer avatars, laid out left-to-right with a small *positive*
     * gap ([AVATAR_GAP]) — no negative overlap, so no avatar paints on top of another.
     */
    private val reviewersPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(AVATAR_GAP), 0)).apply {
        isOpaque = false
    }

    private val overflowLabel = JLabel()

    /**
     * The right column: one horizontal row of fixed-size elements, each separated by a [RIGHT_GAP]
     * gap — `[pipeline icon] [comments badge] [author avatar] [reviewer avatars] [+N]`. [FlowLayout]
     * honors every child's `preferredSize` (nothing is compressed) and centers them vertically in the
     * [ROW_HEIGHT] row; the column reports its own preferred width, so the center text column is the
     * one that gives way and truncates (ellipsis) when the row is narrow.
     */
    private val rightColumn = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(RIGHT_GAP), 0)).apply {
        isOpaque = false
        add(conflictsLabel)
        add(statusLabel)
        add(commentsLabel)
        add(authorLabel)
        add(reviewersPanel)
        add(overflowLabel)
    }

    private val root = object : JPanel(BorderLayout()) {
        override fun getPreferredSize(): Dimension {
            val size = super.getPreferredSize()
            size.height = JBUI.scale(ROW_HEIGHT)
            return size
        }
    }.apply {
        border = CockpitTheme.compactBorder()
        add(textColumn, BorderLayout.CENTER)
        add(rightColumn, BorderLayout.EAST)
    }

    override fun getListCellRendererComponent(
        list: JList<out GitLabMergeRequest>,
        value: GitLabMergeRequest,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val background: Color = if (isSelected) list.selectionBackground else list.background
        val foreground: Color = if (isSelected) list.selectionForeground else list.foreground
        // On a selected row every run uses the selection foreground for legibility over the highlight.
        val muted: Color = if (isSelected) foreground else CockpitTheme.muted()

        root.isOpaque = true
        root.background = background

        val presentation = mrRowPresentation(
            mr = value,
            showProject = showProject,
            relativeUpdatedAt = formatRelative(value.updatedAt),
            maxReviewerAvatars = MAX_REVIEWER_AVATARS,
            draftPrefix = draftPrefix,
            conflictsSuffix = conflictsSuffix,
        )

        line1.font = list.font
        line1.clear()
        for (segment in presentation.line1) {
            val color = if (isSelected) foreground else segmentColor(segment.style, foreground)
            line1.append(segment.text, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, color))
        }

        val metaFont = list.font.deriveFont(list.font.size2D - 1f)
        line2.font = metaFont
        line2.clear()
        line2.append(presentation.line2, SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, muted))

        // Branch chips: muted text on a subtle rounded pill, `source → target` (GLC-37).
        branchArrow.font = metaFont
        branchArrow.foreground = muted
        sourceChip.font = metaFont
        sourceChip.foreground = muted
        sourceChip.text = presentation.sourceBranch
        targetChip.font = metaFont
        targetChip.foreground = muted
        targetChip.text = presentation.targetBranch

        conflictsLabel.isVisible = value.hasConflicts

        val status = enrichment.statusOf(value)
        statusLabel.icon = status?.let { CockpitIcons.status(it) }
        statusLabel.isVisible = statusLabel.icon != null

        val notes = value.userNotesCount ?: 0
        if (notes > 0) {
            // The collab speech-balloon badge with the count (GLC-38 / iter3 A3, G18).
            commentsLabel.icon = CockpitIcons.commentBadge
            commentsLabel.text = notes.toString()
            commentsLabel.foreground = muted
            commentsLabel.isVisible = true
        } else {
            commentsLabel.isVisible = false
        }

        authorLabel.icon = avatarCache.icon(value.author, AVATAR_SIZE) { repaintList() }

        reviewersPanel.removeAll()
        for (reviewer in value.reviewers.take(MAX_REVIEWER_AVATARS)) {
            reviewersPanel.add(JLabel(avatarCache.icon(reviewer, AVATAR_SIZE) { repaintList() }))
        }
        reviewersPanel.isVisible = value.reviewers.isNotEmpty()

        if (presentation.reviewerOverflow > 0) {
            overflowLabel.text = "+${presentation.reviewerOverflow}"
            overflowLabel.foreground = muted
            overflowLabel.isVisible = true
        } else {
            overflowLabel.isVisible = false
        }

        return root
    }

    private fun segmentColor(style: MrSegmentStyle, normal: Color): Color = when (style) {
        MrSegmentStyle.NORMAL -> normal
        MrSegmentStyle.WARNING -> CockpitTheme.warning
        MrSegmentStyle.DANGER -> CockpitTheme.danger
    }

    /**
     * A branch "chip": a [JLabel] that paints a subtle [CockpitTheme.chipBackground] rounded pill
     * behind its text (GLC-37). The label owns its text/foreground (the renderer sets both per row);
     * the [CHIP_RADIUS] rounding and the h4/v1 padding come from here. One instance per branch is held
     * as a renderer field, so nothing is allocated per row; the reused [pill] shape avoids a per-paint
     * allocation too.
     */
    private class BranchChipLabel : JLabel() {
        private val pill = RoundRectangle2D.Float()

        init {
            isOpaque = false
            border = JBUI.Borders.empty(1, 4)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUI.scale(CHIP_RADIUS) * 2f
                pill.setRoundRect(0f, 0f, width.toFloat(), height.toFloat(), arc, arc)
                g2.color = CockpitTheme.chipBackground()
                g2.fill(pill)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    companion object {
        /** Row height in unscaled px; JBUI-scaled at paint time. */
        private const val ROW_HEIGHT = 48

        /** Avatar diameter in unscaled px (author + reviewers). */
        private const val AVATAR_SIZE = 20

        /** Gap (unscaled px) between the right column's elements. */
        private const val RIGHT_GAP = 8

        /** Gap (unscaled px) between adjacent reviewer avatars — positive, so they never overlap. */
        private const val AVATAR_GAP = 2

        /** Gap (unscaled px) around the `→` between the two branch chips. */
        private const val CHIP_GAP = 4

        /** Corner radius (unscaled px) of a branch chip's rounded pill. */
        private const val CHIP_RADIUS = 6

        /** Arrow rendered between the source and target branch chips. */
        private const val BRANCH_ARROW = "→"

        /** How many reviewers are shown as avatars before the `+N` badge takes over. */
        private const val MAX_REVIEWER_AVATARS = 2
    }
}
