package io.github.libreroute.service

import org.junit.Assert.*
import org.junit.Test

class NativeTransportReadinessTest {
    @Test fun yandexStartupWaitsForPeerAndRecoveryCannotUseSessionAlone() {
        val readiness = NativeTransportReadiness(listOf("--session-negotiate", "--peer-health"))
        for (line in listOf(
            "[VOLGA] transport started", "Tunnel active", "[VOLGA] WS connected",
            "[SESSION] ready: codec=batched", "[HEALTH] probe sent role=client"
        )) assertFalse(line, readiness.isReady(line))
        assertTrue(readiness.isReady("[HEALTH] peer ready role=client round trip 388ms"))
        assertFalse(readiness.isReady("[HEALTH] peer round trip timed out"))
        assertFalse(readiness.isReady("[SESSION] ready: codec=batched"))
        assertTrue(readiness.isReady("[HEALTH] peer ready role=client (ping received)"))
    }

    @Test fun healthAlsoGatesProfilesWithoutSessionNegotiation() {
        val readiness = NativeTransportReadiness(listOf("--peer-health=true"))
        assertFalse(readiness.isReady("WS connected"))
        assertFalse(readiness.isReady("Running as CLIENT"))
        assertTrue(readiness.isReady("[HEALTH] peer ready role=client"))
    }

    @Test fun sessionAndLegacyProfilesKeepTheirReadinessSignals() {
        val session = NativeTransportReadiness(listOf("--session-negotiate", "--peer-health=false"))
        assertFalse(session.isReady("Tunnel active"))
        assertFalse(session.isReady("[HEALTH] peer ready"))
        assertTrue(session.isReady("[SESSION] ready"))
        val legacy = NativeTransportReadiness(emptyList())
        assertTrue(legacy.isReady("WS connected"))
        assertTrue(legacy.isReady("Running as CLIENT (Android TUN, direct DPI)"))
        assertFalse(legacy.isReady("[HEALTH] peer round trip timed out"))
    }

    @Test fun booleanFlagsMatchGoSpellingsAndLastValue() {
        assertTrue(NativeTransportReadiness(listOf("-peer-health=1")).requiresPeerHealth)
        assertTrue(NativeTransportReadiness(listOf("--peer-health=false", "--peer-health")).requiresPeerHealth)
        assertFalse(NativeTransportReadiness(listOf("--peer-health", "--peer-health=false")).requiresPeerHealth)
        assertFalse(NativeTransportReadiness(listOf("--peer-health=0")).requiresPeerHealth)
    }
}
