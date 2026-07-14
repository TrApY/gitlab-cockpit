package dev.jota.gitlabcockpit.ui

import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

/**
 * Converts GitLab-flavoured markdown (GFM) to an HTML fragment using the pure JetBrains
 * `org.intellij.markdown` parser — no Swing, no platform dependency. Kept separate from
 * [MrDetailPanel] so the conversion can be unit tested on its own.
 */
object MarkdownRenderer {

    private val flavour = GFMFlavourDescriptor()

    /**
     * Renders [markdown] to HTML. The generator wraps the result in `<body>…</body>`; callers that
     * need a full document should wrap this in their own `<html><head>…</head>…</html>` shell.
     * Blank input yields an empty string.
     */
    fun toHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
    }
}
