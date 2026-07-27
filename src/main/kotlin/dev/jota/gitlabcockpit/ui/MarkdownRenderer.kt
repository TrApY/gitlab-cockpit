package dev.jota.gitlabcockpit.ui

import org.intellij.markdown.ExperimentalApi
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.CancellationToken
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
        // The 3-arg constructor is the non-deprecated primary in the 0.7.7 library bundled by
        // 2026.2+ and exists (behind @ExperimentalApi, stable since 0.7.7) in the 0.7.2 bundled
        // by 2025.2–2026.1. buildMarkdownTreeFromString(String) is deprecated in 0.7.7 too, but
        // its CharSequence replacement does not exist in 0.7.2 — migrate it when the minimum
        // platform moves past 2026.1.
        @OptIn(ExperimentalApi::class)
        val parser = MarkdownParser(flavour, assertionsEnabled = true, cancellationToken = CancellationToken.NonCancellable)
        val tree = parser.buildMarkdownTreeFromString(markdown)
        return HtmlGenerator(markdown, tree, flavour).generateHtml()
    }
}
