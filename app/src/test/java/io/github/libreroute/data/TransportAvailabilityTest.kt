package io.github.libreroute.data

import org.junit.Assert.*
import org.junit.Test

class TransportAvailabilityTest {
    @Test fun zapretIsAvailableAsAStandaloneConnection() {
        assertEquals(TransportType.directDpi, TransportType.from("direct-dpi"))
        assertTrue(TransportType.directDpi.isConnection)
        assertFalse(TransportType.mqtt.isConnection)
        TransportType.entries.filter { it !in setOf(TransportType.directDpi, TransportType.mqtt) }
            .forEach { assertTrue(it.isConnection) }
    }
    @Test fun legacyMqttRemainsReadableWhileOtherProtocolsRemainAvailable() {
        assertEquals(TransportType.mqtt, TransportType.from("MQTT"))
        assertFalse(TransportType.isAvailable("MQTT"))
        TransportType.entries.filter { it != TransportType.mqtt }.forEach {
            assertTrue(TransportType.isAvailable(it.cliName))
            assertEquals(it, TransportType.from(it.cliName))
        }
    }
}
