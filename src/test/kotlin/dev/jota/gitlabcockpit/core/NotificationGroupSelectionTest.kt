package dev.jota.gitlabcockpit.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure tests for [notificationGroupFor], the platform-free decision that routes each event balloon
 * to the auto-hiding group by default and to the STICKY_BALLOON group when the opt-in sticky setting
 * is on (GLC-30). The two ids must match the `notificationGroup` registrations in `plugin.xml`.
 */
class NotificationGroupSelectionTest {

    @Test
    fun `sticky off uses the auto-hiding balloon group`() {
        assertEquals(COCKPIT_NOTIFICATION_GROUP, notificationGroupFor(false))
    }

    @Test
    fun `sticky on uses the sticky balloon group`() {
        assertEquals(COCKPIT_STICKY_NOTIFICATION_GROUP, notificationGroupFor(true))
    }
}
