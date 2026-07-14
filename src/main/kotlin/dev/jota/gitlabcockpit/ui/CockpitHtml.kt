package dev.jota.gitlabcockpit.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ui.ColorUtil
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import javax.swing.JEditorPane
import javax.swing.event.HyperlinkEvent

/**
 * Shared helpers for rendering GitLab markdown (descriptions, comments) into themed, read-only
 * Swing HTML. Extracted from [MrDetailPanel] so the same wrapping/stripping is reused by both the
 * MR description and the comment thread instead of being duplicated.
 */
object CockpitHtml {

    /** Removes the single wrapping `<body>…</body>` the markdown generator emits. */
    fun stripBody(html: String): String {
        var s = html.trim()
        if (s.startsWith("<body>")) s = s.removePrefix("<body>")
        if (s.endsWith("</body>")) s = s.removeSuffix("</body>")
        return s
    }

    fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Wraps an HTML fragment in a full themed document (label colors, links, monospaced code). */
    fun wrapHtml(inner: String): String {
        val fg = ColorUtil.toHtmlColor(UIUtil.getLabelForeground())
        val link = ColorUtil.toHtmlColor(JBUI.CurrentTheme.Link.Foreground.ENABLED)
        return buildString {
            append("<html><head><style>")
            append("body { color: ").append(fg).append("; font-family: sans-serif; }")
            append("a { color: ").append(link).append("; }")
            append("code, pre { font-family: monospace; }")
            append("</style></head><body>")
            append(inner)
            append("</body></html>")
        }
    }

    /**
     * Creates a read-only, transparent [JEditorPane] configured with the platform HTML editor kit
     * (word wrap) and a hyperlink listener that opens links in the external browser.
     */
    fun createHtmlPane(): JEditorPane = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty(4, 8)
        addHyperlinkListener { event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                val href = event.url?.toExternalForm() ?: event.description
                if (!href.isNullOrBlank()) BrowserUtil.browse(href)
            }
        }
    }
}
