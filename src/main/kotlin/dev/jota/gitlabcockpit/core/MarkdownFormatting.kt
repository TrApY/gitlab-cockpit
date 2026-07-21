package dev.jota.gitlabcockpit.core

/**
 * One markdown formatting action a composer's toolbar button applies to the textarea selection
 * (GLC-38 / iter3 F14). [BOLD], [ITALIC], [STRIKE] and [CODE] wrap the selection symmetrically with an
 * inline marker; [CODE_BLOCK] fences it on its own lines; [QUOTE] prefixes every selected line; [LINK]
 * turns it into a `[text](url)` link. Kept here (pure, platform-free) so [wrapMarkdown] can be unit
 * tested without Swing.
 */
enum class MarkdownMarker { BOLD, ITALIC, STRIKE, CODE, CODE_BLOCK, QUOTE, LINK }

/**
 * The result of a [wrapMarkdown] call: the rewritten [text] and the selection the caller should install
 * afterwards ([selectionStart]..[selectionEnd]). An empty range (`start == end`) is a plain caret
 * position; a non-empty one keeps a run selected (e.g. the wrapped body, or a `url` placeholder to type
 * over).
 */
data class WrapResult(val text: String, val selectionStart: Int, val selectionEnd: Int)

/** The `[url]` placeholder [MarkdownMarker.LINK] leaves selected so the caller can type the address. */
private const val LINK_PLACEHOLDER = "url"

/**
 * Wraps the `[selStart, selEnd)` selection of [text] with the markdown syntax for [marker] and returns
 * the rewritten text plus the selection to restore. Pure and platform-free (GLC-38 / iter3 F14):
 *
 * - inline markers ([MarkdownMarker.BOLD] `**`, [MarkdownMarker.ITALIC] `*`, [MarkdownMarker.STRIKE]
 *   `~~`, [MarkdownMarker.CODE] `` ` ``): the marker is inserted before and after the selection. An
 *   empty selection inserts the two markers and places the caret between them; a non-empty selection
 *   keeps the wrapped body selected.
 * - [MarkdownMarker.CODE_BLOCK]: the selection is fenced with ```` ``` ```` on their own lines (a
 *   leading newline is added only when the selection does not already start a line), with the body kept
 *   selected (or the caret placed on the empty fenced line when the selection is empty).
 * - [MarkdownMarker.QUOTE]: every selected line is prefixed with `> ` (an empty selection prefixes the
 *   current line); the quoted body stays selected.
 * - [MarkdownMarker.LINK]: the selection becomes the link text of `[text](url)` and the literal `url`
 *   placeholder is left selected to be typed over.
 *
 * [selStart]/[selEnd] are clamped to `text` and ordered, so a reversed or out-of-range selection is
 * handled gracefully.
 */
fun wrapMarkdown(text: String, selStart: Int, selEnd: Int, marker: MarkdownMarker): WrapResult {
    val start = selStart.coerceIn(0, text.length)
    val end = selEnd.coerceIn(0, text.length)
    val from = minOf(start, end)
    val to = maxOf(start, end)
    val selected = text.substring(from, to)
    val before = text.substring(0, from)
    val after = text.substring(to)

    return when (marker) {
        MarkdownMarker.BOLD -> inlineWrap(before, selected, after, from, "**")
        MarkdownMarker.ITALIC -> inlineWrap(before, selected, after, from, "*")
        MarkdownMarker.STRIKE -> inlineWrap(before, selected, after, from, "~~")
        MarkdownMarker.CODE -> inlineWrap(before, selected, after, from, "`")
        MarkdownMarker.CODE_BLOCK -> codeBlock(before, selected, after, from)
        MarkdownMarker.QUOTE -> quote(before, selected, after, from)
        MarkdownMarker.LINK -> link(before, selected, after, from)
    }
}

/** Symmetric inline wrap; empty selection leaves the caret between the two [symbol]s. */
private fun inlineWrap(before: String, selected: String, after: String, from: Int, symbol: String): WrapResult {
    val text = before + symbol + selected + symbol + after
    val innerStart = from + symbol.length
    return WrapResult(text, innerStart, innerStart + selected.length)
}

/**
 * Fences [selected] with ```` ``` ````. A leading newline is inserted only when the fence would not
 * already begin its own line (i.e. [before] is non-empty and does not end with a newline), so a fence
 * at the start of the box, or after an existing blank line, is not double-spaced.
 */
private fun codeBlock(before: String, selected: String, after: String, from: Int): WrapResult {
    val lead = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
    val prefix = "$lead```\n"
    val suffix = "\n```"
    val text = before + prefix + selected + suffix + after
    val innerStart = from + prefix.length
    return WrapResult(text, innerStart, innerStart + selected.length)
}

/** Prefixes every line of [selected] (or the empty current line) with `> `, keeping it selected. */
private fun quote(before: String, selected: String, after: String, from: Int): WrapResult {
    val quoted = selected.split("\n").joinToString("\n") { "> $it" }
    val text = before + quoted + after
    return WrapResult(text, from, from + quoted.length)
}

/** Builds `[selected](url)` and selects the literal `url` placeholder so it can be typed over. */
private fun link(before: String, selected: String, after: String, from: Int): WrapResult {
    val head = "[$selected]("
    val text = before + head + LINK_PLACEHOLDER + ")" + after
    val urlStart = from + head.length
    return WrapResult(text, urlStart, urlStart + LINK_PLACEHOLDER.length)
}
