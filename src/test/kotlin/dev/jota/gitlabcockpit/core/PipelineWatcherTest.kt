package dev.jota.gitlabcockpit.core

import dev.jota.gitlabcockpit.api.GitLabBridge
import dev.jota.gitlabcockpit.api.GitLabDownstreamPipeline
import dev.jota.gitlabcockpit.api.GitLabMergeRequest
import dev.jota.gitlabcockpit.api.GitLabUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for [shouldNotify], the platform-free decision behind the pipeline-notification
 * watcher: notify only on a *changed* transition into a terminal status we care about
 * (`success` / `failed`), never on the first observation (`prev == null`), never on an unchanged
 * status, and never on a non-terminal one. The GLC-61 downstream delta ([downstreamChange]) is tested
 * here too, since it reuses the very same rules.
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

    // --- GLC-61: downstream (bridge) delta, reusing the same shouldNotify rules -------------------

    @Test
    fun `a downstream first observation only memorizes and never notifies`() {
        assertNull(downstreamChange(null, bridge("success"), mr()))
        assertNull(downstreamChange(null, bridge("failed"), mr()))
    }

    @Test
    fun `a downstream transition into a terminal status notifies with the bridge name and status`() {
        assertEquals(
            DownstreamStatusChange(mr(), "release-management", "failed"),
            downstreamChange("running", bridge("failed"), mr()),
        )
        assertEquals(
            DownstreamStatusChange(mr(), "release-management", "success"),
            downstreamChange("running", bridge("success"), mr()),
        )
    }

    @Test
    fun `an unchanged downstream status never notifies`() {
        assertNull(downstreamChange("failed", bridge("failed"), mr()))
        assertNull(downstreamChange("success", bridge("success"), mr()))
    }

    @Test
    fun `a non-terminal downstream status never notifies`() {
        assertNull(downstreamChange("running", bridge("running"), mr()))
        assertNull(downstreamChange("success", bridge("manual"), mr()))
    }

    @Test
    fun `a bridge that has not triggered a downstream yet never notifies`() {
        assertNull(downstreamChange(null, bridge(null), mr()))
        assertNull(downstreamChange("running", bridge(null), mr()))
    }

    private fun mr(): GitLabMergeRequest = GitLabMergeRequest(
        iid = 1,
        projectId = 500L,
        title = "Some MR",
        state = "opened",
        sourceBranch = "feature/1",
        targetBranch = "main",
        webUrl = "https://gitlab.com/g/r/-/merge_requests/1",
        updatedAt = "2026-07-22T10:00:00.000Z",
        author = GitLabUser(id = 2, username = "u2", name = "U2"),
    )

    /** A bridge whose downstream carries [downstreamStatus]; a null [downstreamStatus] means it has not fired yet. */
    private fun bridge(downstreamStatus: String?, name: String = "release-management"): GitLabBridge =
        GitLabBridge(
            name = name,
            status = "success",
            downstream = downstreamStatus?.let {
                GitLabDownstreamPipeline(id = 99L, projectId = 700L, status = it, webUrl = "https://gitlab.com/x")
            },
        )
}
