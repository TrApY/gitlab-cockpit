package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [shouldNotify], the platform-free decision behind the pipeline-notification
 * watcher: notify only on a *changed* transition into a terminal status we care about
 * (`success` / `failed`), never on the first observation (`prev == null`), never on an unchanged
 * status, and never on a non-terminal one.
 */
class PipelineWatcherTest {

    @Test
    fun `first observation only memorizes and never notifies`() {
        assertFalse(shouldNotify(null, "success"))
        assertFalse(shouldNotify(null, "failed"))
        assertFalse(shouldNotify(null, "running"))
    }

    @Test
    fun `transition into a terminal status from a different status notifies`() {
        assertTrue(shouldNotify("running", "success"))
        assertTrue(shouldNotify("running", "failed"))
        assertTrue(shouldNotify("pending", "success"))
        // Terminal-to-terminal still counts as a change.
        assertTrue(shouldNotify("success", "failed"))
        assertTrue(shouldNotify("failed", "success"))
    }

    @Test
    fun `an unchanged status never notifies`() {
        assertFalse(shouldNotify("success", "success"))
        assertFalse(shouldNotify("failed", "failed"))
        assertFalse(shouldNotify("running", "running"))
    }

    @Test
    fun `a non-terminal new status never notifies`() {
        assertFalse(shouldNotify("running", "pending"))
        assertFalse(shouldNotify("pending", "running"))
        assertFalse(shouldNotify("running", "canceled"))
        assertFalse(shouldNotify("success", "manual"))
    }
}
