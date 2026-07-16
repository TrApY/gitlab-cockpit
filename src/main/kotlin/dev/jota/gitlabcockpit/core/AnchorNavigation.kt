package dev.jota.gitlabcockpit.core

/**
 * A total order over review-thread [DiffAnchor]s for keyboard thread-to-thread navigation: by
 * [DiffAnchor.line] ascending and, at the same line, the **OLD** side before the **NEW** side. Stable
 * and side-agnostic so Next / Previous walk every thread of the file in a predictable sequence
 * regardless of which editor hosts each one.
 */
private val ANCHOR_ORDER: Comparator<DiffAnchor> =
    compareBy({ it.line }, { it.side == AnchorSide.NEW })

/** [anchors] sorted by [ANCHOR_ORDER] (line ascending, OLD before NEW at an equal line). */
fun sortAnchors(anchors: List<DiffAnchor>): List<DiffAnchor> = anchors.sortedWith(ANCHOR_ORDER)

/**
 * The index — into the already-[sortAnchors]-ordered [anchors] — of the thread to jump to from
 * [current] when moving [forward] (or backward), cyclically:
 *
 * - empty [anchors] → `null` (nothing to navigate to).
 * - `null` [current] → the first anchor when [forward], else the last.
 * - [current] is one of [anchors] → its neighbour, wrapping around the ends.
 * - [current] is a caret position sitting on no anchor → the nearest anchor *after* it ([forward]) or
 *   *before* it (backward) in the order, wrapping around when there is none on that side.
 *
 * [current] is the caret's own `(side, 1-based line)`, so it need not be a member of [anchors]; that
 * "not present" case is the last two bullets. Pure and platform-free.
 */
fun nextAnchorIndex(anchors: List<DiffAnchor>, current: DiffAnchor?, forward: Boolean): Int? {
    if (anchors.isEmpty()) return null
    if (current == null) return if (forward) 0 else anchors.lastIndex
    val exact = anchors.indexOf(current)
    if (exact >= 0) {
        return if (forward) (exact + 1) % anchors.size else (exact - 1 + anchors.size) % anchors.size
    }
    return if (forward) {
        anchors.indexOfFirst { ANCHOR_ORDER.compare(it, current) > 0 }.let { if (it >= 0) it else 0 }
    } else {
        anchors.indexOfLast { ANCHOR_ORDER.compare(it, current) < 0 }.let { if (it >= 0) it else anchors.lastIndex }
    }
}
