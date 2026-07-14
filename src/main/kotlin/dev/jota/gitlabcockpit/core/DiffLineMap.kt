package dev.jota.gitlabcockpit.core

/**
 * A diff line anchor: the old-side and/or new-side line number a comment position resolves to.
 *
 * - An **added** line exists only on the new side → `(oldLine = null, newLine = n)`.
 * - A **removed** line exists only on the old side → `(oldLine = o, newLine = null)`.
 * - A **context** line (unchanged) exists on both sides → `(oldLine = o, newLine = n)`.
 *
 * This mirrors GitLab's `position` semantics: a note on an added line sends only `new_line`, a note
 * on a removed line only `old_line`, and a note on a context line may send either (both are valid).
 */
data class LinePosition(val oldLine: Int?, val newLine: Int?)

/**
 * Look-up table built from one file's unified diff (the hunks-only `diff` field GitLab returns on
 * `/merge_requests/:iid/diffs`). It answers, for a given new- or old-side line number, the
 * [LinePosition] GitLab expects — or `null` when that line is not inside any hunk, since GitLab only
 * allows commenting on lines that appear in the diff.
 *
 * Build one with [buildLineMap]. [commentableNewLines] / [commentableOldLines] list exactly the
 * line numbers a new-side / old-side comment may target, in ascending order.
 */
class DiffLineMap internal constructor(
    private val byNewLine: Map<Int, LinePosition>,
    private val byOldLine: Map<Int, LinePosition>,
    /** New-side line numbers that fall inside a hunk (added or context lines), ascending. */
    val commentableNewLines: List<Int>,
    /** Old-side line numbers that fall inside a hunk (removed or context lines), ascending. */
    val commentableOldLines: List<Int>,
) {
    /** The position for a new-side [newLine], or null when it is not inside any hunk. */
    fun forNewLine(newLine: Int): LinePosition? = byNewLine[newLine]

    /** The position for an old-side [oldLine], or null when it is not inside any hunk. */
    fun forOldLine(oldLine: Int): LinePosition? = byOldLine[oldLine]
}

/** Matches a hunk header `@@ -oldStart[,oldCount] +newStart[,newCount] @@ …`, capturing the starts. */
private val HUNK_HEADER = Regex("""^@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@""")

/**
 * Parses a GitLab unified-diff [diffText] into a [DiffLineMap].
 *
 * The algorithm walks the diff line by line, keeping two counters — `oldLine` (position on the old
 * side) and `newLine` (position on the new side). A `@@ -a,b +c,d @@` header (re)seeds both counters
 * to the hunk's declared starts (`a` and `c`) and opens a hunk. Inside a hunk each body line is
 * classified by its first character and both counters advance accordingly:
 *
 * - `' '` context: records `(oldLine, newLine)` under both sides, then advances **both** counters.
 * - `'+'` added: records `(null, newLine)` under the new side, then advances **newLine** only.
 * - `'-'` removed: records `(oldLine, null)` under the old side, then advances **oldLine** only.
 * - `'\'` the `\ No newline at end of file` marker: carries no line, so it is ignored.
 *
 * Empty lines (e.g. the trailing element of a `\n`-terminated diff) and anything outside a hunk are
 * ignored. An empty [diffText] yields an empty map (nothing is commentable).
 */
fun buildLineMap(diffText: String): DiffLineMap {
    val byNewLine = LinkedHashMap<Int, LinePosition>()
    val byOldLine = LinkedHashMap<Int, LinePosition>()
    val commentableNewLines = mutableListOf<Int>()
    val commentableOldLines = mutableListOf<Int>()

    var oldLine = 0
    var newLine = 0
    var inHunk = false

    for (raw in diffText.split('\n')) {
        val header = HUNK_HEADER.find(raw)
        if (header != null) {
            oldLine = header.groupValues[1].toInt()
            newLine = header.groupValues[2].toInt()
            inHunk = true
            continue
        }
        if (!inHunk || raw.isEmpty()) continue
        when (raw[0]) {
            ' ' -> {
                val pos = LinePosition(oldLine = oldLine, newLine = newLine)
                byNewLine[newLine] = pos
                byOldLine[oldLine] = pos
                commentableNewLines += newLine
                commentableOldLines += oldLine
                oldLine++
                newLine++
            }
            '+' -> {
                byNewLine[newLine] = LinePosition(oldLine = null, newLine = newLine)
                commentableNewLines += newLine
                newLine++
            }
            '-' -> {
                byOldLine[oldLine] = LinePosition(oldLine = oldLine, newLine = null)
                commentableOldLines += oldLine
                oldLine++
            }
            // '\' is the "\ No newline at end of file" marker; anything else is unexpected. Ignore.
            else -> Unit
        }
    }

    return DiffLineMap(byNewLine, byOldLine, commentableNewLines, commentableOldLines)
}
