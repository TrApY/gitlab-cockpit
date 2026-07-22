package dev.jota.gitlabcockpit.core

/**
 * Turns GitLab's raw job trace into clean, printable text while **preserving SGR color escapes** so a
 * downstream ANSI decoder can still colorize the output. It is *stateful* and must be instantiated
 * **once per console/stream** (never shared): the trace arrives in byte-sliced chunks that can split a
 * line, an ANSI escape or a timestamped prefix across two [feed] calls, so state is carried between
 * calls.
 *
 * Two buffers hold that state:
 *  1. a **partial-line** buffer — [feed] only processes lines terminated by `\n`; a trailing fragment
 *     without a newline is kept until the next [feed] (or [flush]) completes it. This makes a chunk
 *     boundary anywhere inside a physical line (mid-escape, mid-prefix) harmless.
 *  2. the **held logical line** — GitLab 17+ timestamped traces mark continuations of a logical line
 *     with a `+` flag, so a logical line is only known to be complete once the *next* one starts.
 *     The processor therefore emits logical line N when it sees line N+1 begin, and [flush] emits the
 *     last one still held.
 *
 * ### Per physical line
 * A physical line is matched against the GitLab 17+ timestamped format
 * `<RFC3339>Z <2 stream digits><O|E><+?> <content>`:
 *  - no `+` -> the content **starts a new** logical line (the previously held one is emitted first);
 *  - `+`    -> the content is a **continuation**, appended to the held logical line with no separator;
 *  - no match (old flat traces) -> the whole line passes through as a new logical line.
 * Timestamps themselves are dropped (v1).
 *
 * ### Per completed logical line (order matters)
 *  1. `\r` semantics: split on `\r`, clean each segment and keep the **last non-empty** one — GitLab
 *     precedes section headers with `\r<CSI>0K...` and redraws progress bars with `\r`, so the last
 *     segment is the final visible text (empty if every segment cleans away).
 *  2. Strip non-SGR ANSI: CSI sequences whose final byte is not `m` (cursor moves, `0K` clears...),
 *     OSC sequences and stray escapes are removed; **SGR** (`...m`) is preserved for color.
 *  3. Drop the line entirely if what remains (compared with SGR also stripped) is a bare section
 *     marker `section_start:<ts>:<name>` / `section_end:...`.
 *
 * Pure and platform-free for direct unit testing.
 */
class JobTraceProcessor {

    /** Physical-line fragment received without a trailing `\n`; completed by a later [feed]/[flush]. */
    private var partial: String = ""

    /** The current logical line, held until the next one starts (or [flush]); null = none held. */
    private var currentLogical: String? = null

    /**
     * Feeds a raw chunk and returns the text ready to print (logical lines separated by `\n`, SGR
     * escapes intact). Only lines completed by a `\n` are emitted; the last logical line stays held
     * until the next one starts or [flush] is called.
     */
    fun feed(chunk: String): String {
        val out = StringBuilder()
        val combined = if (partial.isEmpty()) chunk else partial + chunk
        var start = 0
        while (true) {
            val newline = combined.indexOf('\n', start)
            if (newline < 0) break
            processPhysicalLine(combined.substring(start, newline), out)
            start = newline + 1
        }
        partial = combined.substring(start)
        return out.toString()
    }

    /**
     * Processes any buffered partial line and emits the last held logical line. Call once when the
     * stream ends (or after loading a finished job's whole trace in one shot). The processor is reset
     * afterwards, so a later [feed] resumes cleanly.
     */
    fun flush(): String {
        val out = StringBuilder()
        if (partial.isNotEmpty()) {
            processPhysicalLine(partial, out)
            partial = ""
        }
        emitLogical(currentLogical, out)
        currentLogical = null
        return out.toString()
    }

    private fun processPhysicalLine(line: String, out: StringBuilder) {
        val match = TIMESTAMPED.matchEntire(line)
        if (match != null) {
            val continuation = match.groupValues[3] == "+"
            val content = match.groupValues[4]
            if (continuation) {
                currentLogical = (currentLogical ?: "") + content
            } else {
                emitLogical(currentLogical, out)
                currentLogical = content
            }
        } else {
            emitLogical(currentLogical, out)
            currentLogical = line
        }
    }

    private fun emitLogical(logical: String?, out: StringBuilder) {
        if (logical == null) return
        val cleaned = cleanLogicalLine(logical) ?: return
        out.append(cleaned).append('\n')
    }

    /**
     * Cleans one complete logical line. Returns the printable text (SGR preserved, possibly empty for
     * a blank line) or `null` when the line is a section marker and must be dropped entirely.
     */
    private fun cleanLogicalLine(raw: String): String? {
        // 1. `\r` semantics: keep the last non-empty segment after cleaning each.
        var chosen = ""
        for (segment in raw.split('\r')) {
            val cleaned = stripNonSgr(segment)
            if (cleaned.isNotEmpty()) chosen = cleaned
        }
        // 3. Drop bare section markers (compared with SGR also stripped).
        if (SECTION.matches(stripSgr(chosen))) return null
        return chosen
    }

    /** Removes OSC sequences, non-SGR CSI sequences and stray escapes; keeps SGR (`...m`) for color. */
    private fun stripNonSgr(segment: String): String {
        var result = OSC.replace(segment, "")
        result = CSI_NON_SGR.replace(result, "")
        result = LONE_ESC.replace(result, "")
        return result
    }

    private fun stripSgr(segment: String): String = SGR.replace(segment, "")

    companion object {
        /** The ASCII escape (`ESC`, 0x1B) that introduces every ANSI control sequence. */
        private val ESC: String = Char(0x1B).toString()

        /** The ASCII bell (`BEL`, 0x07), one of the two terminators of an OSC sequence. */
        private val BEL: String = Char(0x07).toString()

        /**
         * GitLab 17+ timestamped line: `<RFC3339 with optional fraction>Z <2 stream digits><O|E><flag>`
         * where the flag is the FOURTH character of the marker — a space for a line start or `+` for a
         * continuation — and the content follows **immediately** (no separator: real traces read
         * `…Z 00O+section_start:…`, GLC-47). The earlier pattern demanded a space after an *optional*
         * `+`, so every continuation line failed to match and leaked through with its raw prefix.
         * [RegexOption.DOT_MATCHES_ALL] lets the content group capture the embedded `\r` that section
         * headers and progress redraws use (a physical line never contains `\n` here).
         */
        private val TIMESTAMPED = Regex(
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z (\\d{2})([OE])([+ ])(.*)$",
            RegexOption.DOT_MATCHES_ALL,
        )

        /** A bare GitLab section marker, matched against the cleaned + SGR-stripped logical line. */
        private val SECTION = Regex("^section_(?:start|end):\\d+:[A-Za-z0-9_.\\-]+$")

        /**
         * OSC sequence (`ESC ] ... BEL` or `ESC ] ... ESC \`). Removed wholesale; GitLab does not use
         * these, but stripping them keeps stray terminal control out of the log.
         */
        private val OSC = Regex("$ESC\\][^$BEL$ESC]*(?:$BEL|$ESC\\\\)?")

        /**
         * Non-SGR CSI sequence: `ESC [` + params + a final byte that is **not** `m` (nor `M`). Covers
         * `0K` line clears, cursor moves, etc. The `ESC` is required — matching bare brackets would
         * corrupt ordinary log text like `[INFO]` (see the deviation noted in the ticket report).
         */
        private val CSI_NON_SGR = Regex("$ESC\\[[0-9;?]*[A-LN-Za-ln-z]")

        /** A stray `ESC` not introducing an SGR sequence; removed so no lone escape reaches the view. */
        private val LONE_ESC = Regex("$ESC(?!\\[[0-9;?]*m)")

        /** SGR sequence (`ESC [` params `m`); stripped only for the section-marker comparison. */
        private val SGR = Regex("$ESC\\[[0-9;?]*m")
    }
}
