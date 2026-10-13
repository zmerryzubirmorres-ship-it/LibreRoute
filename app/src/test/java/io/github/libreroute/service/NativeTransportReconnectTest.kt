package io.github.libreroute.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeTransportReconnectTest {
    @Test fun yandexReadyAtElevenSecondsIsNotRestartedAtTen() = runTest {
        val readiness = NativeTransportReadiness(listOf("--peer-health", "--session-negotiate"))
        assertTrue(readiness.awaitReady("vyandex", { true }, { testScheduler.currentTime >= 11_000 }))
        assertEquals(11_000L, testScheduler.currentTime)
    }

    @Test fun unresponsiveProviderHasBoundedWait() = runTest {
        assertFalse(NativeTransportReadiness(listOf("--peer-health")).awaitReady("vyandex", { true }, { false }))
        assertEquals(60_000L, testScheduler.currentTime)
    }

    @Test fun stoppedProcessCannotPublishReadiness() = runTest {
        assertFalse(NativeTransportReadiness(emptyList()).awaitReady("jitsi", { false }, { true }))
        assertEquals(250L, testScheduler.currentTime)
    }

    @Test fun cancellingReconfigurationStopsOldWait() = runTest {
        var checks = 0
        val pending = async {
            NativeTransportReadiness(listOf("--peer-health")).awaitReady("vyandex", { true }, { checks++; false })
        }
        testScheduler.advanceTimeBy(1_000)
        pending.cancel()
        testScheduler.runCurrent()
        val before = checks
        testScheduler.advanceTimeBy(60_000)
        assertEquals(before, checks)
        assertTrue(pending.isCancelled)
    }
}
