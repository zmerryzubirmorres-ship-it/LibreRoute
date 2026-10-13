package io.github.libreroute.admin

import java.util.concurrent.ConcurrentHashMap

/** Coalesces automatic reads per server, including across navigation/reconnection. */
internal class ProtocolInventoryCache<T>(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry<T>(val until: Long, val result: Result<T>)
    private val locks = ConcurrentHashMap<String, Any>()
    private val entries = ConcurrentHashMap<String, Entry<T>>()

    fun read(serverId: String, force: Boolean = false, fetch: () -> Result<T>): Result<T> =
        synchronized(locks.computeIfAbsent(serverId) { Any() }) {
            entries[serverId]?.takeIf { !force && now() < it.until }?.let { return@synchronized it.result }
            fetch().also { entries[serverId] = Entry(now() + if (it.isSuccess) 60_000 else 10_000, it) }
        }

    fun invalidate(serverId: String) {
        synchronized(locks.computeIfAbsent(serverId) { Any() }) { entries.remove(serverId) }
    }
}
