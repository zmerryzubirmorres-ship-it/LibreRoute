package io.github.libreroute.service

import org.junit.Assert.*
import org.junit.Test

class PhysicalNetworkSelectionTest {
    private fun path(name: String, priority: Int, validated: Boolean = true, notVpn: Boolean = true,
                     internet: Boolean = true) = PhysicalNetworkCandidate(name, internet, notVpn, validated, priority)

    @Test fun ownVpnIsExcludedEvenWhenItHasInternetAndValidation() {
        assertEquals("wifi", selectPhysicalNetwork(listOf(path("vpn", 100, notVpn = false), path("wifi", 2)), "vpn"))
        assertNull(selectPhysicalNetwork(listOf(path("vpn", 100, notVpn = false)), "vpn"))
    }

    @Test fun validatedMobileBeatsCaptiveWifiAndWifiReturnsAfterValidation() {
        val mobile = path("mobile", 1)
        assertEquals("mobile", selectPhysicalNetwork(listOf(mobile, path("wifi", 2, validated = false)), "wifi"))
        assertEquals("wifi", selectPhysicalNetwork(listOf(mobile, path("wifi", 2)), "mobile"))
    }

    @Test fun lossOfPreferredNetworkFallsBackAndIrrelevantLossDoesNotSwitchIt() {
        val wifi = path("wifi", 2)
        val mobile = path("mobile", 1)
        assertEquals("mobile", selectPhysicalNetwork(listOf(mobile), "wifi"))
        assertEquals("wifi", selectPhysicalNetwork(listOf(wifi), "wifi"))
        assertNull(selectPhysicalNetwork(emptyList<PhysicalNetworkCandidate<String>>(), "wifi"))
    }

    @Test fun equalCandidatesDoNotOscillateAndInternetIsRequired() {
        val a = path("wifi-a", 2)
        val b = path("wifi-b", 2)
        assertEquals("wifi-a", selectPhysicalNetwork(listOf(a, b), "wifi-a"))
        assertEquals("wifi-a", selectPhysicalNetwork(listOf(b, a), "wifi-a"))
        assertNull(selectPhysicalNetwork(listOf(path("lan", 3, internet = false)), "lan"))
    }
}
