package dev.jota.gitlabcockpit.ui

import com.intellij.icons.AllIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure tests of [CockpitTheme] and [CockpitIcons]: every known GitLab status must resolve to a
 * color and an icon without throwing, the success/failed/running colors must be exactly the
 * semantic palette entries, and a failed-but-allowed job must render as the warning icon.
 */
class CockpitThemeTest {

    /** Every status GitLab reports for a job or an aggregated stage. */
    private val knownStatuses = listOf(
        "success", "failed", "running", "pending", "created",
        "skipped", "manual", "canceled", "warning",
    )

    @Test
    fun `statusColor resolves every known status without throwing`() {
        for (status in knownStatuses) {
            assertNotNull("statusColor should resolve '$status'", CockpitTheme.statusColor(status))
        }
    }

    @Test
    fun `status icon resolves every known status without throwing`() {
        for (status in knownStatuses) {
            assertNotNull("status icon should resolve '$status'", CockpitIcons.status(status))
        }
    }

    @Test
    fun `success failed and running map to the semantic palette`() {
        assertEquals(CockpitTheme.success, CockpitTheme.statusColor("success"))
        assertEquals(CockpitTheme.danger, CockpitTheme.statusColor("failed"))
        assertEquals(CockpitTheme.info, CockpitTheme.statusColor("running"))
    }

    @Test
    fun `a failed job allowed to fail shows the warning icon`() {
        assertSame(AllIcons.General.Warning, CockpitIcons.status("failed", allowFailure = true))
    }
}
