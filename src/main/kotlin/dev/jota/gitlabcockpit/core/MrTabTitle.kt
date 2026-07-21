package dev.jota.gitlabcockpit.core

/** Max characters of an MR title shown in a tool-window tab before it is ellipsized. */
const val MR_TAB_TITLE_MAX = 30

/**
 * The label of an MR's tool-window tab: `!<iid> <title>`, the title trimmed and ellipsized to
 * [MR_TAB_TITLE_MAX] characters (a trailing `…` replaces the cut-off remainder). Truncation is purely
 * visual — the full title is shown as the tab's tooltip.
 */
fun mrTabLabel(iid: Long, title: String): String {
    val trimmed = title.trim()
    val shown = if (trimmed.length <= MR_TAB_TITLE_MAX) {
        trimmed
    } else {
        trimmed.take(MR_TAB_TITLE_MAX).trimEnd() + "…"
    }
    return "!$iid $shown"
}
