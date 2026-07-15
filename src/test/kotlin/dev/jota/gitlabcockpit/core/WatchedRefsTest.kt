package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for the watched-MRs codec: [encodeWatchedRefs] / [decodeWatchedRefs] roundtrips, the
 * empty case, the stable ordering, and the tolerance to malformed input.
 */
class WatchedRefsTest {

    @Test
    fun `roundtrip preserves the set`() {
        val refs = setOf(MrRef(10, 3), MrRef(10, 1), MrRef(4, 99))

        assertEquals(refs, decodeWatchedRefs(encodeWatchedRefs(refs)))
    }

    @Test
    fun `encode is ordered by projectId then iid for a stable string`() {
        val refs = setOf(MrRef(10, 3), MrRef(4, 99), MrRef(10, 1))

        assertEquals("4:99,10:1,10:3", encodeWatchedRefs(refs))
    }

    @Test
    fun `empty set encodes to the empty string`() {
        assertEquals("", encodeWatchedRefs(emptySet()))
    }

    @Test
    fun `null and blank decode to the empty set`() {
        assertEquals(emptySet<MrRef>(), decodeWatchedRefs(null))
        assertEquals(emptySet<MrRef>(), decodeWatchedRefs(""))
        assertEquals(emptySet<MrRef>(), decodeWatchedRefs("   "))
    }

    @Test
    fun `malformed entries are skipped, valid ones kept`() {
        // "abc" (no colon), "x:y" (non-numeric), "3:" and ":4" (missing side), "1:2:3" (three parts).
        val decoded = decodeWatchedRefs("abc,1:2,x:y,3:,:4,1:2:3, 5:6 ")

        assertEquals(setOf(MrRef(1, 2), MrRef(5, 6)), decoded)
    }

    @Test
    fun `an all-garbage value decodes to the empty set`() {
        assertEquals(emptySet<MrRef>(), decodeWatchedRefs("nope,,::,7"))
    }
}
