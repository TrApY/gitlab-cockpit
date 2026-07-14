package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [buildLineMap]: the unified-diff parser at the heart of F4a. Verifies the exact
 * old/new line numbers a position resolves to for added / removed / context lines, multi-hunk
 * offsets, whole-file add and delete, out-of-hunk lookups (null), the `\ No newline` marker being
 * ignored, and the empty-diff case yielding an empty map.
 */
class DiffLineMapTest {

    @Test
    fun `simple hunk maps added removed and context to exact line numbers`() {
        val map = buildLineMap(
            """
            @@ -1,3 +1,4 @@
             line1
            -line2
            +line2new
            +line3added
             line4
            """.trimIndent(),
        )

        // Context line 1 exists on both sides.
        assertEquals(LinePosition(oldLine = 1, newLine = 1), map.forNewLine(1))
        assertEquals(LinePosition(oldLine = 1, newLine = 1), map.forOldLine(1))
        // Removed line: only old side.
        assertEquals(LinePosition(oldLine = 2, newLine = null), map.forOldLine(2))
        // Added lines: only new side.
        assertEquals(LinePosition(oldLine = null, newLine = 2), map.forNewLine(2))
        assertEquals(LinePosition(oldLine = null, newLine = 3), map.forNewLine(3))
        // Trailing context line: both sides, with the correct offsets.
        assertEquals(LinePosition(oldLine = 3, newLine = 4), map.forNewLine(4))
        assertEquals(LinePosition(oldLine = 3, newLine = 4), map.forOldLine(3))

        assertEquals(listOf(1, 2, 3, 4), map.commentableNewLines)
        assertEquals(listOf(1, 2, 3), map.commentableOldLines)
    }

    @Test
    fun `line outside any hunk resolves to null`() {
        val map = buildLineMap(
            """
            @@ -1,3 +1,4 @@
             line1
            -line2
            +line2new
            +line3added
             line4
            """.trimIndent(),
        )
        assertNull(map.forNewLine(5))
        assertNull(map.forNewLine(100))
        assertNull(map.forOldLine(4))
        assertNull(map.forOldLine(0))
    }

    @Test
    fun `multiple hunks apply the declared offsets`() {
        val map = buildLineMap(
            """
            @@ -1,2 +1,2 @@
             a
            -b
            +B
            @@ -10,2 +12,3 @@
             c
            +d
             e
            """.trimIndent(),
        )

        // First hunk.
        assertEquals(LinePosition(oldLine = 1, newLine = 1), map.forNewLine(1))
        assertEquals(LinePosition(oldLine = 2, newLine = null), map.forOldLine(2))
        assertEquals(LinePosition(oldLine = null, newLine = 2), map.forNewLine(2))
        // Second hunk starts at old 10 / new 12.
        assertEquals(LinePosition(oldLine = 10, newLine = 12), map.forNewLine(12))
        assertEquals(LinePosition(oldLine = 10, newLine = 12), map.forOldLine(10))
        assertEquals(LinePosition(oldLine = null, newLine = 13), map.forNewLine(13))
        assertEquals(LinePosition(oldLine = 11, newLine = 14), map.forNewLine(14))
        assertEquals(LinePosition(oldLine = 11, newLine = 14), map.forOldLine(11))
        // Gaps between the hunks are not commentable.
        assertNull(map.forNewLine(3))
        assertNull(map.forOldLine(3))

        assertEquals(listOf(1, 2, 12, 13, 14), map.commentableNewLines)
        assertEquals(listOf(1, 2, 10, 11), map.commentableOldLines)
    }

    @Test
    fun `a brand new file is all added lines with no commentable old lines`() {
        val map = buildLineMap(
            """
            @@ -0,0 +1,3 @@
            +first
            +second
            +third
            """.trimIndent(),
        )

        assertEquals(LinePosition(oldLine = null, newLine = 1), map.forNewLine(1))
        assertEquals(LinePosition(oldLine = null, newLine = 3), map.forNewLine(3))
        assertEquals(listOf(1, 2, 3), map.commentableNewLines)
        assertTrue(map.commentableOldLines.isEmpty())
        assertNull(map.forOldLine(1))
    }

    @Test
    fun `a fully deleted file is all removed lines with no commentable new lines`() {
        val map = buildLineMap(
            """
            @@ -1,3 +0,0 @@
            -a
            -b
            -c
            """.trimIndent(),
        )

        assertEquals(LinePosition(oldLine = 1, newLine = null), map.forOldLine(1))
        assertEquals(LinePosition(oldLine = 3, newLine = null), map.forOldLine(3))
        assertEquals(listOf(1, 2, 3), map.commentableOldLines)
        assertTrue(map.commentableNewLines.isEmpty())
        assertNull(map.forNewLine(1))
    }

    @Test
    fun `no newline at end of file marker is ignored and does not shift counters`() {
        val map = buildLineMap(
            """
            @@ -1,2 +1,2 @@
             a
            -b
            \ No newline at end of file
            +b
            \ No newline at end of file
            """.trimIndent(),
        )

        assertEquals(LinePosition(oldLine = 1, newLine = 1), map.forNewLine(1))
        assertEquals(LinePosition(oldLine = 2, newLine = null), map.forOldLine(2))
        assertEquals(LinePosition(oldLine = null, newLine = 2), map.forNewLine(2))
        assertEquals(listOf(1, 2), map.commentableNewLines)
        assertEquals(listOf(1, 2), map.commentableOldLines)
    }

    @Test
    fun `hunk header without counts defaults to single lines`() {
        val map = buildLineMap(
            """
            @@ -5 +7 @@
            -x
            +y
            """.trimIndent(),
        )
        assertEquals(LinePosition(oldLine = 5, newLine = null), map.forOldLine(5))
        assertEquals(LinePosition(oldLine = null, newLine = 7), map.forNewLine(7))
        assertEquals(listOf(7), map.commentableNewLines)
        assertEquals(listOf(5), map.commentableOldLines)
    }

    @Test
    fun `empty diff yields an empty map`() {
        val map = buildLineMap("")
        assertTrue(map.commentableNewLines.isEmpty())
        assertTrue(map.commentableOldLines.isEmpty())
        assertNull(map.forNewLine(1))
        assertNull(map.forOldLine(1))
    }
}
