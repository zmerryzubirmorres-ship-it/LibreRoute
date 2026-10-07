package io.github.libreroute.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectDpiProfileTest {
    @Test
    fun builtInProfileContainsRunnableZapretDefaults() {
        val profile = DirectDpiProfile.create()
        assertEquals(TransportType.directDpi.name, profile.transportType)
        assertTrue(profile.transportConnPayload.contains("--role=client"))
        assertTrue(profile.transportConnPayload.containsAll(listOf("--transport", "direct-dpi")))
        assertTrue(profile.transportConnPayload.contains("--dpi-block-quic=true"))
    }

    @Test
    fun incompleteLegacyProfileIsRepairedWithoutTouchingOtherArguments() {
        val legacy = DirectDpiProfile.create().copy(
            transportConnPayload = listOf("--transport", "direct-dpi", "--legacy-option=kept")
        )
        val repaired = DirectDpiProfile.ensureConfigured(legacy)
        assertTrue(repaired.transportConnPayload.contains("--role=client"))
        assertTrue(repaired.transportConnPayload.contains("--dpi-block-quic=true"))
        assertTrue(repaired.transportConnPayload.contains("--legacy-option=kept"))
    }
}
