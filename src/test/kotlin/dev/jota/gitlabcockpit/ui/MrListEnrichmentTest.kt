package dev.jota.gitlabcockpit.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [shouldRetry] — the MR-list enrichment's retry decision. The backoff schedule is
 * 30s → 2min → 5min for the 1st/2nd/3rd failure; after the 3rd (attempts ≥ 4) the MR is given up on
 * until its `updated_at` changes, so no time makes it retry.
 */
class MrListEnrichmentTest {

    private val base = 1_000L

    @Test
    fun `first failure waits 30 seconds`() {
        assertFalse(shouldRetry(attempts = 1, lastFailureMs = base, nowMs = base + 29_999L))
        assertTrue(shouldRetry(attempts = 1, lastFailureMs = base, nowMs = base + 30_000L))
    }

    @Test
    fun `second failure waits 2 minutes`() {
        assertFalse(shouldRetry(attempts = 2, lastFailureMs = base, nowMs = base + 119_999L))
        assertTrue(shouldRetry(attempts = 2, lastFailureMs = base, nowMs = base + 120_000L))
    }

    @Test
    fun `third failure waits 5 minutes`() {
        assertFalse(shouldRetry(attempts = 3, lastFailureMs = base, nowMs = base + 299_999L))
        assertTrue(shouldRetry(attempts = 3, lastFailureMs = base, nowMs = base + 300_000L))
    }

    @Test
    fun `after the third failure the MR is given up on regardless of elapsed time`() {
        assertFalse(shouldRetry(attempts = 4, lastFailureMs = base, nowMs = base + 60 * 60_000L))
        assertFalse(shouldRetry(attempts = 10, lastFailureMs = base, nowMs = Long.MAX_VALUE / 2))
    }

    @Test
    fun `a non-positive attempt count never retries`() {
        assertFalse(shouldRetry(attempts = 0, lastFailureMs = base, nowMs = base + 10 * 60_000L))
    }
}
