package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for [JobTraceProcessor]: GitLab 17+ timestamped prefixes, continuation joining, section
 * marker dropping, ANSI cleanup (SGR preserved, everything else stripped), `\r` semantics and correct
 * behavior across chunk boundaries.
 */
class JobTraceProcessorTest {

    /** ASCII ESC (0x1B); built from a code point so the source file stays free of raw control bytes. */
    private val esc = Char(0x1B).toString()

    /** Timestamped prefix of a brand-new logical line (`...Z 01O ` — stream 01, stdout, no `+`). */
    private val tsNew = "2026-07-13T16:25:02.840617Z 01O "

    /** Timestamped prefix of a continuation line (`...Z 00O+ ` — the `+` marks a continuation). */
    private val tsCont = "2026-07-13T16:25:02.840618Z 00O+ "

    /** Runs the chunks through one processor and returns feed output concatenated with the flush. */
    private fun process(vararg chunks: String): String {
        val processor = JobTraceProcessor()
        val out = StringBuilder()
        for (chunk in chunks) out.append(processor.feed(chunk))
        out.append(processor.flush())
        return out.toString()
    }

    @Test
    fun `timestamped line drops the prefix and keeps the content`() {
        assertEquals(
            "Building the project\n",
            process(tsNew + "Building the project\n"),
        )
    }

    @Test
    fun `continuation is joined to the previous logical line without a break`() {
        assertEquals(
            "abcdef\n",
            process(tsNew + "abc\n" + tsCont + "def\n"),
        )
    }

    @Test
    fun `section markers are dropped while their header text survives`() {
        val input =
            tsNew + "section_start:1783959924:archive_cache\r" + esc + "[0KChecking cache\n" +
                tsNew + "regular build output\n" +
                tsNew + "section_end:1783959924:archive_cache\r" + esc + "[0K\n" +
                // Variant: the marker preceded by a `[0K` clear (no header after it) is dropped too.
                tsNew + esc + "[0Ksection_start:1783959925:compile\r" + esc + "[0K\n"
        assertEquals(
            "Checking cache\nregular build output\n",
            process(input),
        )
    }

    @Test
    fun `SGR color escapes are preserved`() {
        val colored = esc + "[32;1mBuilding" + esc + "[0;m"
        assertEquals(
            colored + "\n",
            process(tsNew + colored + "\n"),
        )
    }

    @Test
    fun `non-SGR CSI sequences are stripped`() {
        assertEquals(
            "Hello World\n",
            process(tsNew + esc + "[0KHello " + esc + "[2KWorld\n"),
        )
    }

    @Test
    fun `carriage return keeps the last non-empty segment`() {
        assertEquals("def\n", process(tsNew + "abc\rdef\n"))
        assertEquals("header\n", process(tsNew + "\r" + esc + "[0Kheader\n"))
    }

    @Test
    fun `splitting a chunk mid-line and mid-prefix yields the same result`() {
        val whole =
            tsNew + "Building the project\n" +
                "2026-07-13T16:25:03.000000Z 01O Done\n"
        val single = process(whole)
        val split = process(
            "2026-07-13T16:25:02.840617Z 01O Building the pro",
            "ject\n2026-07-13T16:25:03.0",
            "00000Z 01O Done\n",
        )
        assertEquals(single, split)
        assertEquals("Building the project\nDone\n", split)
    }

    @Test
    fun `splitting a chunk mid-ANSI-escape does not corrupt it`() {
        val colored = esc + "[32;1mHello" + esc + "[0;m"
        val single = process(tsNew + colored + "\n")
        val split = process(
            tsNew + esc + "[3",
            "2;1mHello" + esc + "[0;m\n",
        )
        assertEquals(single, split)
        assertEquals(colored + "\n", split)
    }

    @Test
    fun `old traces without timestamps pass through with ANSI and section cleanup applied`() {
        val input =
            "plain line one\n" +
                esc + "[0Kplain line two\n" +
                "section_start:12:build\r" + esc + "[0K\n"
        assertEquals(
            "plain line one\nplain line two\n",
            process(input),
        )
    }

    @Test
    fun `flush emits the last retained line and feed still works afterwards`() {
        val processor = JobTraceProcessor()
        val first = processor.feed(tsNew + "first\n" + tsNew + "second\n")
        assertEquals("first\n", first)
        assertEquals("second\n", processor.flush())
        val afterFlush = processor.feed(tsNew + "third\n")
        assertEquals("", afterFlush)
        assertEquals("third\n", processor.flush())
    }
}
