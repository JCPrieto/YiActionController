package es.jcprieto.yiactioncontroller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraNotificationUpdatesTest {
    @Test
    fun repeatedProgressDoesNotRebuildActions() {
        val updates = CameraNotificationUpdates()
        assertTrue(updates.shouldPublish("DOWNLOADING", 10, 0))
        assertFalse(updates.shouldPublish("DOWNLOADING", 10, 200))
        assertFalse(updates.shouldPublish("DOWNLOADING", 11, 400))
        assertFalse(updates.shouldPublish("DOWNLOADING", 12, 999))
        assertTrue(updates.shouldPublish("DOWNLOADING", 13, 1_000))
        assertFalse(updates.shouldPublish("DOWNLOADING", 13, 3_000))
    }

    @Test
    fun cancellationAndOtherActionChangesAreImmediate() {
        val updates = CameraNotificationUpdates()
        assertTrue(updates.shouldPublish("DOWNLOADING", 20, 0))
        assertTrue(updates.shouldPublish("CANCELLED", 20, 1))
        assertTrue(updates.shouldPublish("PREPARING", 20, 2))
        assertTrue(updates.shouldPublish("DOWNLOADING", 20, 3))
        assertTrue(updates.shouldPublish("PUBLISHING", 100, 4))
        assertTrue(updates.shouldPublish("COMPLETED", 100, 5))
    }

    @Test
    fun newConnectionCanPublishSameState() {
        val updates = CameraNotificationUpdates()
        assertTrue(updates.shouldPublish("CONNECTED", 0, 0))
        updates.reset()
        assertTrue(updates.shouldPublish("CONNECTED", 0, 1))
    }
}
