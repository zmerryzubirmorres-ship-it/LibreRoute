package io.github.libreroute.service

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.currentTime

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeTransportReadinessTest {
    @Test fun coreReadinessIgnoresAllProviderAndLaneSignals() {
        val readiness = NativeTransportReadiness(listOf("--core-health"))
        for (line in listOf("[JAZZ] data channel open", "[VOLGA] transport started",
            "WebSocket connected", "[CHANNEL] peer ready", "[GROUP] peer ready lanes=2/3",
            "[SESSION] ready", "[HEALTH] peer ready", "[PROXY_LISTENER] ready")) {
            assertFalse(line, readiness.isReady(line))
        }
        assertTrue(readiness.isReady("[CORE_HEALTH] peer ready round trip 106ms"))
        assertTrue(readiness.isReady("[CORE_CONNECTION] ready"))
        assertTrue(readiness.isDisconnected("[CORE_CONNECTION] unavailable"))
        assertFalse(readiness.isDisconnected("[SOCKS5] connection reset by peer"))
    }

    @Test fun coreReadinessWindowDoesNotDependOnProviderName() = runTest {
        val readiness = NativeTransportReadiness(listOf("--core-health"))
        assertFalse(readiness.awaitReady("vyandex", canWait = { true }, connected = { false }))
        assertEquals(60_000L, currentTime)
    }

    @Test fun finalNativeDropTriggersRecoveryInRulesAndStrictModes() {
        for (args in listOf(listOf("--peer-health"), listOf("--proxy-routing-mode=rules", "--peer-health"))) {
            val readiness = NativeTransportReadiness(args)
            assertTrue(readiness.isDisconnected("[CORE_CONNECTION] unavailable"))
            assertFalse(readiness.isDisconnected("[PROXY_LISTENER] ready"))
            assertFalse(readiness.isDisconnected("[CORE_RTT] rtt_us=106000 age_ms=0"))
        }
    }
    @Test fun explicitJazzRefusalDoesNotConfuseFlowErrorsOrNetworkFailures() {
        val readiness = NativeTransportReadiness(emptyList())
        assertTrue(readiness.isJazzRoomRejected("2026/10/15 [JAZZ] session ended: Jazz: room access rejected"))
        assertFalse(readiness.isJazzRoomRejected("[SOCKS5] connect failed: connection refused"))
        assertFalse(readiness.isJazzRoomRejected("[JAZZ] session ended: read tcp: connection reset by peer"))
        assertFalse(readiness.isJazzRoomRejected("[JAZZ] reconnect in 1s"))
    }
    @Test fun dnsResetFromPhoneDoesNotRestartHealthyJazz() {
        val readiness = NativeTransportReadiness(listOf("--peer-health"))
        assertFalse(readiness.isDisconnected("2026/10/14 21:23:47.502415 [PKT] DNS-over-TCP 192.46.237.123:53: read tcp 10.10.10.2:46121: connection reset by peer"))
        assertFalse(readiness.isDisconnected("[TUNNEL] DialTCP: broken pipe"))
        assertFalse(readiness.isDisconnected("Read error: connection reset by peer"))
        assertTrue(readiness.isDisconnected("[HEALTH] peer round trip timed out"))
    }

    @Test fun legacyFlowErrorsStayLocalAndProviderDropsStillReconnect() {
        val readiness = NativeTransportReadiness(emptyList())
        assertFalse(readiness.isDisconnected("[PKT] DNS-over-TCP: connection reset by peer"))
        assertFalse(readiness.isDisconnected("[SOCKS5] Read error: broken pipe"))
        assertTrue(readiness.isDisconnected("websocket: close 1005"))
        assertTrue(readiness.isDisconnected("mqtt: connection lost: EOF"))
    }

    @Test fun groupFailureDetectionDoesNotReactToIndividualLaneErrors() {
        val readiness = NativeTransportReadiness(listOf("--channel-group-config=group", "--peer-health"))
        assertFalse(readiness.isDisconnected("[HEALTH] peer round trip timed out"))
        assertFalse(readiness.isDisconnected("websocket: close 1005"))
        assertFalse(readiness.isDisconnected("[GROUP] disconnected"))
        assertFalse(readiness.isDisconnected("[GROUP] failed: no lanes"))
        assertTrue(readiness.isDisconnected("[CORE_CONNECTION] unavailable"))
        assertTrue(readiness.isDisconnected("[CORE_HEALTH] peer round trip timed out"))
    }
    @org.junit.Test fun flexibleProxyWaitsForFinalTransportEvenAfterHealthPing() {
        val readiness = NativeTransportReadiness(listOf("--proxy-routing-mode=rules", "--peer-health"))
        org.junit.Assert.assertFalse(readiness.isReady("[HEALTH] peer ready role=client (ping received)"))
        org.junit.Assert.assertFalse(readiness.isReady("WebSocket connected"))
        org.junit.Assert.assertTrue(readiness.isReady("[CORE_CONNECTION] ready"))
    }
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
        assertFalse(readiness.isReady("[GROUP] peer ready lanes=2/3"))
        assertTrue(readiness.isReady("[CORE_HEALTH] peer ready round trip 106ms"))
    }

    @Test fun flexibleListenerDoesNotPretendTheServerIsConnected() {
        val readiness = NativeTransportReadiness(listOf("--proxy-routing-mode=rules"))
        assertFalse(readiness.isReady("[PROXY_LISTENER] ready mode=rules"))
        assertFalse(readiness.isReady("Running as CLIENT (Android TUN)"))
        assertFalse(readiness.isReady("Tunnel active"))
        assertFalse(readiness.isReady("[CORE_CONNECTION] unavailable"))
        assertTrue(readiness.isReady("[CORE_CONNECTION] ready"))
    }
    @Test fun pairChannelRequiresAuthenticatedRoundTrip() {
        val readiness = NativeTransportReadiness(listOf("--pair-channel-config", "public-binding"))
        assertFalse(readiness.isReady("[JAZZ] data channel open"))
        assertFalse(readiness.isReady("[SESSION] ready"))
        assertFalse(readiness.isReady("[HEALTH] peer ready"))
        assertFalse(readiness.isReady("[CHANNEL] peer ready"))
        assertTrue(readiness.isReady("[CORE_HEALTH] peer ready round trip 106ms"))
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
