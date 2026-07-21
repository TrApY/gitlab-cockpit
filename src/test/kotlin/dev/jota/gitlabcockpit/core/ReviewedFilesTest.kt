package dev.jota.gitlabcockpit.core

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [ReviewedFiles]: mark/toggle idempotency, the "new commits (head SHA change) discard the
 * old reviewed set" rule, the counter that only counts paths still present in the change, and an XML
 * serialization round-trip proving the workspace state persists.
 */
class ReviewedFilesTest {

    private val ref = MrRef(projectId = 10, iid = 3)
    private val other = MrRef(projectId = 7, iid = 1)
    private val sha = "headsha1"

    @Test
    fun `mark is idempotent`() {
        val store = ReviewedFiles()

        store.mark(ref, sha, "src/A.kt")
        store.mark(ref, sha, "src/A.kt")

        assertTrue(store.isReviewed(ref, sha, "src/A.kt"))
        assertEquals(1, store.reviewedCount(ref, sha, listOf("src/A.kt")))
    }

    @Test
    fun `toggle flips the reviewed state and reports the new value`() {
        val store = ReviewedFiles()

        assertTrue(store.toggle(ref, sha, "src/A.kt"))
        assertTrue(store.isReviewed(ref, sha, "src/A.kt"))

        assertFalse(store.toggle(ref, sha, "src/A.kt"))
        assertFalse(store.isReviewed(ref, sha, "src/A.kt"))
    }

    @Test
    fun `a new head SHA discards the previous reviewed set on first access`() {
        val store = ReviewedFiles()
        store.mark(ref, sha, "src/A.kt")
        store.mark(ref, sha, "src/B.kt")

        // First access at the new SHA must see an empty set (the old reviews are stale).
        assertFalse(store.isReviewed(ref, "headsha2", "src/A.kt"))
        assertEquals(0, store.reviewedCount(ref, "headsha2", listOf("src/A.kt", "src/B.kt")))

        // Re-marking at the new SHA works, and the old SHA no longer holds anything.
        store.mark(ref, "headsha2", "src/A.kt")
        assertTrue(store.isReviewed(ref, "headsha2", "src/A.kt"))
        assertFalse(store.isReviewed(ref, "headsha2", "src/B.kt"))
    }

    @Test
    fun `reviewedCount only counts paths present in the change`() {
        val store = ReviewedFiles()
        store.mark(ref, sha, "src/A.kt")
        store.mark(ref, sha, "src/B.kt")

        // B is reviewed but absent from the change; C is in the change but not reviewed → only A counts.
        assertEquals(1, store.reviewedCount(ref, sha, listOf("src/A.kt", "src/C.kt")))
        assertEquals(2, store.reviewedCount(ref, sha, listOf("src/A.kt", "src/B.kt", "src/C.kt")))
        assertEquals(0, store.reviewedCount(ref, sha, emptyList()))
    }

    @Test
    fun `entries are isolated per merge request`() {
        val store = ReviewedFiles()
        store.mark(ref, sha, "src/A.kt")

        assertFalse(store.isReviewed(other, sha, "src/A.kt"))
        assertEquals(0, store.reviewedCount(other, sha, listOf("src/A.kt")))
    }

    @Test
    fun `reading a never-seen merge request does not fabricate an entry`() {
        val store = ReviewedFiles()

        assertFalse(store.isReviewed(ref, sha, "src/A.kt"))

        assertTrue(store.getState().entries.isEmpty())
    }

    @Test
    fun `state survives an xml serialization round-trip`() {
        val store = ReviewedFiles()
        store.mark(ref, sha, "src/A.kt")
        store.mark(ref, sha, "src/B.kt")
        store.mark(other, "shaX", "README.md")

        val element = XmlSerializer.serialize(store.getState())
        val restored = ReviewedFiles()
        restored.loadState(XmlSerializer.deserialize(element, ReviewedFiles.State::class.java))

        assertTrue(restored.isReviewed(ref, sha, "src/A.kt"))
        assertTrue(restored.isReviewed(ref, sha, "src/B.kt"))
        assertTrue(restored.isReviewed(other, "shaX", "README.md"))
        assertEquals(2, restored.reviewedCount(ref, sha, listOf("src/A.kt", "src/B.kt", "src/C.kt")))
    }
}
