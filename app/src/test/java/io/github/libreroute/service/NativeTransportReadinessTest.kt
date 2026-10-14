package io.github.libreroute.service

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeTransportReadinessTest {
    @Test fun slowJazzProxyHandshakeKeepsItsFullReadinessWindow() = runTest {
        val readiness = NativeTransportReadiness(emptyList())
        assertTrue(readiness.awaitReady("jazz", canWait = { true }, connected = { currentTime >= 30_000 }))
        assertEquals(30_000L, currentTime)
    }

    @Test fun channelGroupProxyWaitsForPeerForUpToSixtySeconds() = runTest {
        val readiness = NativeTransportReadiness(listOf("--channel-group-config=public-binding"))
        assertFalse(readiness.awaitReady("channel-group", canWait = { true }, connected = { false }))
        assertEquals(60_000L, currentTime)
    }

    @Test fun stoppedNativeProcessEndsTheReadinessWaitImmediately() = runTest {
        val readiness = NativeTransportReadiness(emptyList())
        assertFalse(readiness.awaitReady("jazz", canWait = { false }, connected = { true }))
        assertEquals(250L, currentTime)
    }
    @Test fun groupRequiresItsOwnAuthenticatedRoundTrip() {
        val readiness = NativeTransportReadiness(listOf("--channel-group-config", "signed-group", "--peer-health"))
        assertFalse(readiness.isReady("[VOLGA] transport started"))
        assertFalse(readiness.isReady("[CHANNEL] peer ready"))
        assertFalse(readiness.isReady("[HEALTH] peer ready"))
        assertTrue(readiness.isReady("[GROUP] peer ready lanes=2/3"))
    }
    @Test fun pairChannelRequiresAuthenticatedRoundTrip() {
        val readiness = NativeTransportReadiness(listOf("--pair-channel-config", "public-binding"))
        assertFalse(readiness.isReady("[JAZZ] data channel open"))
        assertFalse(readiness.isReady("[SESSION] ready"))
        assertFalse(readiness.isReady("[HEALTH] peer ready"))
        assertTrue(readiness.isReady("[CHANNEL] peer ready"))
    }
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
