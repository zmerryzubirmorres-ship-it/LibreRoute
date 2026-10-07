package io.github.libreroute.util

import io.github.libreroute.data.Tunnel
import org.junit.Assert.*
import org.junit.Test

class BackupMergeTest {
    @Test fun collidingNamesAndIdsPreserveOriginalAndRepeatDoesNotDuplicate() {
        val original = Tunnel(1, "Route", "mqtt", listOf("--url", "wss://host/mqtt#first"), "first-key")
        val incoming = original.copy(transportConnPayload = listOf("--url", "wss://host/mqtt#second"), encryptionKey = "second-key")
        val merged = BackupMerge.merge(listOf(original), listOf(incoming)) { 2L }
        assertEquals(original, merged.first()); assertEquals(2L, merged.last().id); assertEquals("Route (импорт 2)", merged.last().name)
        assertEquals(merged, BackupMerge.merge(merged, listOf(incoming)) { 3L })
    }
}
