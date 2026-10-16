package io.github.libreroute.service

import org.junit.Assert.*
import org.junit.Test

class ChannelConnectionStateTest {
    @Test fun localDpiCanResumeWithoutReplacingItsProcess() {
        val state = ChannelConnectionState { 30_000L }
        val process = Any()
        state.begin(process, 42)
        state.ready(process, true)
        state.networkLost(process)
        state.localNetworkRestored(process)
        assertTrue(state.value.running)
        assertTrue(state.value.ready)
        assertFalse(state.value.waitingForNetwork)
        assertNull(state.value.rttMs)
    }
    @Test fun channelRoundTripSurvivesUiRecreationAndExpiresWithoutNewSamples() {
        var now = 10_000L
        val state = ChannelConnectionState { now }
        val process = Any()
        state.begin(process, 42)
        state.ready(process, true)
        state.sample(process, "2026/10/15 [CORE_RTT] rtt_us=106000 age_ms=900")
        // A new subscriber reads the same native state without a replayed UI event.
        assertEquals(42L, state.snapshots.value.tunnelId)
        assertEquals(106L, state.snapshots.value.freshRtt(now))
        now += 19_100
        assertNull(state.value.freshRtt(now))
        assertTrue(state.value.ready)
    }

    @Test fun previousProcessCannotRestoreReadinessOrLatencyAfterRestart() {
        val state = ChannelConnectionState { 30_000L }
        val old = Any()
        val current = Any()
        state.begin(old, 1)
        state.ready(old, true)
        state.sample(old, "[CORE_RTT] rtt_us=491000 age_ms=0")
        state.begin(current, 2)
        state.ready(old, true)
        state.sample(old, "[CORE_RTT] rtt_us=106000 age_ms=0")
        state.end(old)
        assertEquals(2L, state.value.tunnelId)
        assertTrue(state.value.running)
        assertFalse(state.value.ready)
        assertNull(state.value.rttMs)
        state.ready(current, true)
        state.sample(current, "[CORE_RTT] rtt_us=106000 age_ms=0")
        assertEquals(106L, state.value.freshRtt(30_000L))
        state.ready(current, false)
        assertNull(state.value.rttMs)
    }

    @Test fun physicalOutageFencesQueuedReadyMessagesUntilCarrierRestart() {
        val state = ChannelConnectionState { 30_000L }
        val process = Any()
        state.begin(process, 42)
        state.ready(process, true)
        state.networkLost(process)
        state.ready(process, true)
        state.sample(process, "[CORE_RTT] rtt_us=106000 age_ms=0")
        assertFalse(state.value.ready)
        assertTrue(state.value.waitingForNetwork)
        assertNull(state.value.rttMs)
        val recovered = Any()
        state.begin(recovered, 42)
        state.ready(recovered, true)
        assertTrue(state.value.ready)
        assertFalse(state.value.waitingForNetwork)
    }

    @Test fun invalidAndStaleSamplesAndHttpsTimingsAreNeverChannelRtt() {
        val state = ChannelConnectionState { 30_000L }
        val process = Any()
        state.begin(process, 42)
        state.ready(process, true)
        for (line in listOf("HTTPS probe: total=1021ms socks=100ms", "[CORE_RTT] rtt_us=-1 age_ms=0",
            "[CORE_RTT] rtt_us=0 age_ms=0", "[CORE_RTT] rtt_us=999999999999999999999999 age_ms=0",
            "[CORE_RTT] rtt_us=106000 age_ms=20000", "[CORE_RTT] rtt_us=106000 age_ms=-1")) {
            state.sample(process, line)
            assertNull(line, state.value.rttMs)
        }
        state.ready(process, false, rejected = true)
        assertTrue(state.value.rejected)
        state.end(process)
        assertFalse(state.value.running)
    }
}
