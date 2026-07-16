package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure tests for the keyboard thread-navigation logic: [sortAnchors] (line ascending, OLD before NEW
 * at an equal line) and [nextAnchorIndex] (cyclic next/previous, with the caret reference possibly
 * sitting on no anchor).
 */
class AnchorNavigationTest {

    private fun old(line: Int) = DiffAnchor(AnchorSide.OLD, line)
    private fun new(line: Int) = DiffAnchor(AnchorSide.NEW, line)

    // --- sortAnchors ----------------------------------------------------------------------------

    @Test
    fun `sortAnchors orders by line ascending`() {
        assertEquals(
            listOf(new(1), new(4), new(9)),
            sortAnchors(listOf(new(9), new(1), new(4))),
        )
    }

    @Test
    fun `sortAnchors puts OLD before NEW at the same line`() {
        assertEquals(
            listOf(old(5), new(5)),
            sortAnchors(listOf(new(5), old(5))),
        )
    }

    @Test
    fun `sortAnchors interleaves sides by line`() {
        assertEquals(
            listOf(new(2), old(5), new(5), old(8)),
            sortAnchors(listOf(old(8), new(5), old(5), new(2))),
        )
    }

    @Test
    fun `sortAnchors of an empty list is empty`() {
        assertEquals(emptyList<DiffAnchor>(), sortAnchors(emptyList()))
    }

    // --- nextAnchorIndex: empty & null current --------------------------------------------------

    @Test
    fun `nextAnchorIndex on an empty list is null`() {
        assertNull(nextAnchorIndex(emptyList(), null, forward = true))
        assertNull(nextAnchorIndex(emptyList(), new(3), forward = false))
    }

    @Test
    fun `nextAnchorIndex with no current goes to the first forward and the last backward`() {
        val anchors = listOf(new(1), new(4), new(9))
        assertEquals(0, nextAnchorIndex(anchors, null, forward = true))
        assertEquals(2, nextAnchorIndex(anchors, null, forward = false))
    }

    // --- nextAnchorIndex: current is itself an anchor -------------------------------------------

    @Test
    fun `nextAnchorIndex moves to the neighbour when current is an anchor`() {
        val anchors = listOf(new(1), new(4), new(9))
        assertEquals(2, nextAnchorIndex(anchors, new(4), forward = true))
        assertEquals(0, nextAnchorIndex(anchors, new(4), forward = false))
    }

    @Test
    fun `nextAnchorIndex wraps around the ends`() {
        val anchors = listOf(new(1), new(4), new(9))
        assertEquals(0, nextAnchorIndex(anchors, new(9), forward = true))
        assertEquals(2, nextAnchorIndex(anchors, new(1), forward = false))
    }

    // --- nextAnchorIndex: current not present (caret on no anchor) -------------------------------

    @Test
    fun `nextAnchorIndex forward from a reference between anchors picks the next`() {
        val anchors = listOf(new(1), new(4), new(9))
        assertEquals(2, nextAnchorIndex(anchors, new(6), forward = true))
    }

    @Test
    fun `nextAnchorIndex backward from a reference between anchors picks the previous`() {
        val anchors = listOf(new(1), new(4), new(9))
        assertEquals(1, nextAnchorIndex(anchors, new(6), forward = false))
    }

    @Test
    fun `nextAnchorIndex forward from a reference past the last wraps to the first`() {
        val anchors = listOf(new(1), new(4), new(9))
        assertEquals(0, nextAnchorIndex(anchors, new(20), forward = true))
    }

    @Test
    fun `nextAnchorIndex backward from a reference before the first wraps to the last`() {
        val anchors = listOf(new(1), new(4), new(9))
        assertEquals(2, nextAnchorIndex(anchors, new(0), forward = false))
    }

    @Test
    fun `nextAnchorIndex resolves the reference side-aware at an equal line`() {
        // anchors sorted: old(5) then new(5). A caret reference on old(5) between them.
        val anchors = sortAnchors(listOf(new(5), old(5), new(9)))
        assertEquals(listOf(old(5), new(5), new(9)), anchors)
        // Forward from the OLD-5 anchor lands on NEW-5 (its neighbour in order).
        assertEquals(1, nextAnchorIndex(anchors, old(5), forward = true))
    }
}
