package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ProtocolInventoryCacheTest {
    @Test fun automaticReadsRespectFreshnessForceAndServerBoundaries() {
        var now = 0L
        val cache = ProtocolInventoryCache<Int> { now }
        var calls = 0
        fun read(server: String = "one", force: Boolean = false) = cache.read(server, force) { Result.success(++calls) }.getOrThrow()
        assertEquals(1, read()); assertEquals(1, read())
        assertEquals(2, read("two"))
        assertEquals(3, read(force = true)); assertEquals(3, read())
        now = 60_000
        assertEquals(4, read())
        cache.invalidate("one"); assertEquals(5, read())
    }

    @Test fun failuresHaveShortBackoffAndCanBeRetriedExplicitly() {
        var now = 0L
        val cache = ProtocolInventoryCache<Int> { now }
        var calls = 0
        fun read(force: Boolean = false) = cache.read("one", force) { calls++; Result.failure<Int>(Exception("offline")) }
        assertTrue(read().isFailure); read(); assertEquals(1, calls)
        now = 10_000; read(); assertEquals(2, calls)
        read(true); assertEquals(3, calls)
    }

    @Test fun concurrentAutomaticRequestsShareOneServerRead() {
        val cache = ProtocolInventoryCache<Int>()
        val pool = Executors.newFixedThreadPool(4)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        try {
            val futures = (1..4).map { pool.submit<Int> {
                cache.read("one") {
                    calls.incrementAndGet(); entered.countDown()
                    check(release.await(3, TimeUnit.SECONDS)); Result.success(7)
                }.getOrThrow()
            } }
            assertTrue(entered.await(3, TimeUnit.SECONDS)); release.countDown()
            futures.forEach { assertEquals(7, it.get(3, TimeUnit.SECONDS)) }
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }
}
