package dev.jota.gitlabcockpit.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure test of [MarkdownRenderer]: a description with bold, a list and a link must convert to HTML
 * containing the corresponding elements. No Swing is involved.
 */
class MarkdownRendererTest {

    @Test
    fun `bold list and link render to their html elements`() {
        val markdown = """
            This is **bold** text.

            - one
            - two

            See [GitLab](https://gitlab.com).
        """.trimIndent()

        val html = MarkdownRenderer.toHtml(markdown)

        assertTrue("expected <strong> in: $html", html.contains("<strong>bold</strong>"))
        assertTrue("expected <ul> in: $html", html.contains("<ul>"))
        assertTrue("expected <li> in: $html", html.contains("<li>"))
        assertTrue("expected link href in: $html", html.contains("href=\"https://gitlab.com\""))
    }

    @Test
    fun `blank input yields an empty string`() {
        assertEquals("", MarkdownRenderer.toHtml("   "))
    }
}
