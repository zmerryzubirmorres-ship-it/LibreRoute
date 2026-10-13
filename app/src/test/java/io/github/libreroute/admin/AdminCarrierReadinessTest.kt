package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test

class AdminCarrierReadinessTest {
    @Test fun startupRaceRetriesOnlyReadAndRecoversBeforeUsingFallback() {
        var calls = 0
        val pauses = mutableListOf<Long>()
        val result = readReadyAdminCarrier({ true }, {
            calls++
            if (calls == 1) Result.failure(IllegalStateException("not connected")) else Result.success("signed snapshot")
        }, pauses::add)
        assertEquals("signed snapshot", result!!.getOrThrow())
        assertEquals(2, calls); assertEquals(listOf(250L), pauses)
    }
    @Test fun replacedRouteOrRevokedGrantStopsPendingReadWithoutApplyingOldResult() {
        var current = true
        var calls = 0
        val result = readReadyAdminCarrier({ current }, { calls++; Result.failure<String>(IllegalStateException("offline")) },
            { current = false })
        assertNull(result); assertEquals(1, calls)
        current = true
        assertNull(readReadyAdminCarrier({ current }, { current = false; Result.success("old snapshot") }, {}))
    }
    @Test fun unavailableRouteHasBoundedAttempts() {
        var calls = 0
        val pauses = mutableListOf<Long>()
        val result = readReadyAdminCarrier({ true }, { calls++; Result.failure<String>(IllegalStateException("offline")) }, pauses::add)
        assertTrue(result!!.isFailure); assertEquals(3, calls); assertEquals(listOf(250L, 1000L), pauses)
    }
    @Test fun signedAuthorizationRejectionIsNotRetried() {
        var calls = 0
        val result = readReadyAdminCarrier({ true }, { calls++; Result.failure<String>(ConfirmedAdminRejection("revoked")) },
            { fail("Authorization rejection must not wait or retry") })
        assertTrue(result!!.exceptionOrNull() is ConfirmedAdminRejection); assertEquals(1, calls)
    }
}
