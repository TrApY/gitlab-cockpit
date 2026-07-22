package dev.jota.gitlabcockpit.ui

import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color

/**
 * Single source of visual style for the plugin: the semantic status palette, the one status→color
 * mapping every status-aware surface shares, and the standard insets and dialog sizes. Centralizing
 * these (instead of scattering literals across panels) keeps the look consistent and each [JBColor]
 * carries its own light/dark pair, so colors track the active IDE theme.
 */
object CockpitTheme {

    /** Green (light/dark) for success, satisfied approvals and resolved threads. */
    val success = JBColor(0x2E7D32, 0x499C54)

    /** Amber (light/dark) for warnings, pending approvals and threads needing attention. */
    val warning = JBColor(0xB07800, 0xD6A243)

    /** Red (light/dark) for failures, conflicts and other danger states — softer than the raw platform red. */
    val danger = JBColor(0xC7222D, 0xDB5C5C)

    /** JetBrains blue (light/dark) for running/in-progress states, links and chips. */
    val info = JBColor(0x3574F0, 0x548AF7)

    /**
     * Muted foreground for neutral/secondary text and idle statuses. A function rather than a val
     * because it resolves against the active theme's context-help color at call time.
     */
    fun muted(): Color = UIUtil.getContextHelpForeground()

    /** Translucent amber background for a diff line whose thread still needs attention. */
    val attentionBackground = JBColor(Color(0xFF, 0xC1, 0x07, 0x18), Color(0xD6, 0xA2, 0x43, 0x20))

    /**
     * Maps a GitLab job/stage status to its dot/label color — the single mapping shared by the
     * pipelines tree and any other status-aware surface. `running` is [info]; success/failed/warning
     * use the semantic palette; `canceled` is dark gray; every idle or unknown status falls back to
     * [muted].
     */
    fun statusColor(status: String): Color = when (status) {
        "success" -> success
        "failed" -> danger
        "running" -> info
        "warning" -> warning
        "canceled" -> JBColor.DARK_GRAY
        else -> muted() // manual / skipped / pending / created / unknown
    }

    /** Standard header/section insets: 6px vertical, 8px horizontal. */
    fun panelBorder() = JBUI.Borders.empty(6, 8)

    /** Compact row insets: 2px vertical, 8px horizontal. */
    fun compactBorder() = JBUI.Borders.empty(2, 8)

    /** Preferred size of the member-picker dialogs (reviewers, assignee). */
    val REVIEWERS_DIALOG_SIZE = JBUI.size(360, 320)

    /** Preferred size of the edit-title/description dialog's description area. */
    val EDIT_MR_DIALOG_SIZE = JBUI.size(560, 300)

    /** Preferred size of the new-diff-thread dialog. */
    val NEW_THREAD_DIALOG_SIZE = JBUI.size(520, 360)

    /**
     * Background for a timeline "card" — a subtle one-step shift off the panel background so each
     * event or discussion reads as its own block without needing a divider. A function rather than a
     * val (like [muted]) because it resolves against the active theme at call time: on a bright theme
     * the card is a touch darker than the panel, on a dark theme a touch brighter, both via the
     * platform's [ColorUtil] tone steps (roughly one 5% step off the base).
     */
    fun cardBackground(): Color {
        val base = UIUtil.getPanelBackground()
        return if (JBColor.isBright()) ColorUtil.darker(base, 1) else ColorUtil.brighter(base, 1)
    }

    /**
     * Background for a subtle rounded "chip" — the branch pills on an MR list row (GLC-37). Derived
     * from [UIUtil.getPanelBackground] with [ColorUtil] tone steps like [cardBackground], but a touch
     * stronger (two steps) so the pill reads as its own shape against the row: darker on a bright
     * theme, brighter on a dark one. A function (not a val) so it resolves against the active theme at
     * call time.
     */
    fun chipBackground(): Color {
        val base = UIUtil.getPanelBackground()
        return if (JBColor.isBright()) ColorUtil.darker(base, 2) else ColorUtil.brighter(base, 2)
    }
}
