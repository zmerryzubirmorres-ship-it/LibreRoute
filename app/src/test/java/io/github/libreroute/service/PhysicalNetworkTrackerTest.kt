package io.github.libreroute.service

import org.junit.Assert.*
import org.junit.Test

class PhysicalNetworkTrackerTest {
    private fun mobile(validated: Boolean) = PhysicalNetworkCandidate("mobile", true, true, validated, 1)

    @Test fun validationReturnOnSameMobileNetworkIsRecovery() {
        val tracker = PhysicalNetworkTracker<String>()
        val initial = tracker.update(listOf(mobile(true)))
        assertTrue(initial.changed)
        assertTrue(initial.initial)
        val loss = tracker.update(listOf(mobile(false)))
        assertFalse(loss.changed)
        assertFalse(loss.validationRestored)
        val restored = tracker.update(listOf(mobile(true)))
        assertFalse(restored.initial)
        assertFalse(restored.changed)
        assertTrue(restored.validationRestored)
        assertFalse(tracker.update(listOf(mobile(true))).validationRestored)
    }

    @Test fun vpnCannotHidePhysicalLossAndMobileReturn() {
        val tracker = PhysicalNetworkTracker<String>()
        val vpn = PhysicalNetworkCandidate("vpn", true, false, true, 3)
        tracker.update(listOf(mobile(true), vpn))
        assertNull(tracker.update(listOf(vpn)).network)
        val restored = tracker.update(listOf(mobile(true), vpn))
        assertEquals("mobile", restored.network)
        assertTrue(restored.changed)
        assertFalse(restored.validationRestored) // Availability change already signals recovery.
    }
}
