package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for [wrapMarkdown] (GLC-38 / iter3 F14): the inline markers wrap a selection and, when
 * empty, drop the caret between the two markers; the block markers fence, quote by line and build a
 * link with a typed-over `url` placeholder. Each case asserts both the rewritten text and the
 * selection the caller restores.
 */
class MarkdownFormattingTest {

    @Test
    fun `bold wraps the selection and keeps the body selected`() {
        val text = "hello world"
        // select "world" (6..11)
        val r = wrapMarkdown(text, 6, 11, MarkdownMarker.BOLD)

        assertEquals("hello **world**", r.text)
        // the wrapped body ("world") stays selected, after the leading "**".
        assertEquals("world", r.text.substring(r.selectionStart, r.selectionEnd))
        assertEquals(8, r.selectionStart)
        assertEquals(13, r.selectionEnd)
    }

    @Test
    fun `bold on an empty selection inserts the markers and drops the caret between them`() {
        val r = wrapMarkdown("", 0, 0, MarkdownMarker.BOLD)

        assertEquals("****", r.text)
        assertEquals(2, r.selectionStart)
        assertEquals(2, r.selectionEnd) // caret, no selection, right in the middle
    }

    @Test
    fun `italic wraps with a single asterisk`() {
        val r = wrapMarkdown("ab", 0, 2, MarkdownMarker.ITALIC)

        assertEquals("*ab*", r.text)
        assertEquals("ab", r.text.substring(r.selectionStart, r.selectionEnd))
    }

    @Test
    fun `inline code wraps the selection in backticks`() {
        val r = wrapMarkdown("run x", 4, 5, MarkdownMarker.CODE)

        assertEquals("run `x`", r.text)
        assertEquals("x", r.text.substring(r.selectionStart, r.selectionEnd))
    }

    @Test
    fun `code block fences a multiline selection on its own lines`() {
        val text = "line1\nline2"
        val r = wrapMarkdown(text, 0, text.length, MarkdownMarker.CODE_BLOCK)

        assertEquals("```\nline1\nline2\n```", r.text)
        // no leading blank line when the fence already starts the box; the body stays selected.
        assertEquals("line1\nline2", r.text.substring(r.selectionStart, r.selectionEnd))
    }

    @Test
    fun `code block inserts a leading newline when the fence would not start a line`() {
        val text = "note: x"
        val r = wrapMarkdown(text, 6, 7, MarkdownMarker.CODE_BLOCK)

        assertEquals("note: \n```\nx\n```", r.text)
    }

    @Test
    fun `quote prefixes every selected line`() {
        val text = "a\nb\nc"
        val r = wrapMarkdown(text, 0, text.length, MarkdownMarker.QUOTE)

        assertEquals("> a\n> b\n> c", r.text)
        assertEquals("> a\n> b\n> c", r.text.substring(r.selectionStart, r.selectionEnd))
    }

    @Test
    fun `link wraps the selection as text and selects the url placeholder`() {
        val text = "see docs"
        val r = wrapMarkdown(text, 4, 8, MarkdownMarker.LINK)

        assertEquals("see [docs](url)", r.text)
        assertEquals("url", r.text.substring(r.selectionStart, r.selectionEnd))
    }

    @Test
    fun `link with no selection inserts the full template and selects the text placeholder`() {
        val r = wrapMarkdown("", 0, 0, MarkdownMarker.LINK)

        assertEquals("[text](url)", r.text)
        assertEquals("text", r.text.substring(r.selectionStart, r.selectionEnd))
    }

    @Test
    fun `strikethrough wraps with a double tilde`() {
        val r = wrapMarkdown("gone", 0, 4, MarkdownMarker.STRIKE)

        assertEquals("~~gone~~", r.text)
        assertEquals("gone", r.text.substring(r.selectionStart, r.selectionEnd))
    }

    @Test
    fun `a reversed or out-of-range selection is handled gracefully`() {
        // selEnd < selStart, and both beyond the text length: clamped and ordered.
        val r = wrapMarkdown("hi", 9, 0, MarkdownMarker.BOLD)

        assertEquals("**hi**", r.text)
        assertEquals("hi", r.text.substring(r.selectionStart, r.selectionEnd))
    }
}
