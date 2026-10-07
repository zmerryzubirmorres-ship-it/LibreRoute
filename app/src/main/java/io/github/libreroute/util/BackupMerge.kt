package io.github.libreroute.util

import io.github.libreroute.data.Tunnel

/** Merge preserves existing routes even when a different backup reuses names or IDs. */
object BackupMerge {
    fun merge(current: List<Tunnel>, imported: List<Tunnel>, newId: () -> Long): List<Tunnel> {
        val result = current.toMutableList()
        for (raw in imported) {
            if (result.any { it.transportType == raw.transportType && it.encryptionKey == raw.encryptionKey && payload(it) == payload(raw) }) continue
            var id = raw.id
            while (result.any { it.id == id }) id = newId()
            var name = raw.name; var suffix = 2
            while (result.any { it.name == name }) name = "${raw.name} (импорт ${suffix++})"
            result.add(raw.copy(id = id, name = name))
        }
        return result
    }

    private fun payload(tunnel: Tunnel): List<String> {
        val result = mutableListOf<String>(); var index = 0
        while (index < tunnel.transportConnPayload.size) {
            val value = tunnel.transportConnPayload[index++]
            if (value == "--encryption-key-file" || value == "-encryption-key-file") { index++; continue }
            if (value.startsWith("--encryption-key-file=") || value.startsWith("-encryption-key-file=")) continue
            result.add(value)
        }
        return result
    }
}
