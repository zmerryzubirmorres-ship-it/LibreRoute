package io.github.libreroute.admin

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class CoreUpdatePipelineTest {
    private fun withArtifact(block: (File, ByteArray, JsonObject) -> Unit) {
        val file = File.createTempFile("core-pipeline", ".bin")
        try {
            val bytes = ByteArray(8 * 8192 + 17) { (it % 251).toByte() }
            file.writeBytes(bytes)
            val plan = buildJsonObject { put("manifest", buildJsonObject {
                put("size", bytes.size); put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).hex())
            }) }
            block(file, bytes, plan)
        } finally { file.delete() }
    }

    private class Receiver(val bytes: ByteArray, resumed: Boolean = false) {
        val chunks = sortedMapOf<Long, ByteArray>()
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val sent = java.util.concurrent.ConcurrentLinkedQueue<Long>()
        init { if (resumed) chunks[0L] = bytes.copyOfRange(0, 8192) }
        @Synchronized fun status(): JsonObject {
            var next = 0L
            while (chunks.containsKey(next)) next += chunks.getValue(next).size
            return buildJsonObject {
                put("stage", "receiving"); put("next_offset", next)
                put("received_bytes", chunks.values.sumOf { it.size }); put("size", bytes.size); put("chunk_bytes", 8192)
            }
        }
        @Synchronized fun put(fields: JsonObject): JsonObject {
            val offset = fields.getValue("offset").jsonPrimitive.long
            val chunk = Base64.getUrlDecoder().decode(fields.string("data"))
            assertArrayEquals(bytes.copyOfRange(offset.toInt(), offset.toInt() + chunk.size), chunk)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(chunk).hex(), fields.string("sha256"))
            chunks[offset] = chunk
            return status()
        }
        fun advance(action: String): JsonObject {
            assertEquals("Must drain all chunk replies before $action", 0, active.get())
            assertEquals(bytes.size.toLong(), status().getValue("next_offset").jsonPrimitive.long)
            return buildJsonObject { put("stage", when (action) {
                "verify" -> "verified"; "prepare" -> "prepared"; "apply" -> "committed"; else -> error(action)
            }) }
        }
    }

    @Test fun boundedPipelineResumesAndIgnoresDelayedOlderAcknowledgement() = withArtifact { file, bytes, plan ->
        val server = Receiver(bytes, resumed = true)
        val firstFour = CountDownLatch(4)
        val receipts = mutableListOf<Long>()
        val client = CoreUpdateClient(call = { action, fields ->
            when (action) {
                "begin" -> server.status()
                "put_chunk" -> {
                    val n = server.active.incrementAndGet(); server.peak.updateAndGet { maxOf(it, n) }
                    val offset = fields.getValue("offset").jsonPrimitive.long
                    server.sent.add(offset)
                    try {
                        val captured = server.put(fields)
                        if (offset < 5 * 8192L) {
                            firstFour.countDown(); check(firstFour.await(5, TimeUnit.SECONDS))
                        }
                        if (offset == 8192L) Thread.sleep(80)
                        captured
                    } finally { server.active.decrementAndGet() }
                }
                else -> server.advance(action)
            }
        }, checkpoint = { if (it.string("stage") == "receiving") receipts += it.getValue("received_bytes").jsonPrimitive.long }, uploadWindow = 4)
        assertEquals("committed", client.submit(plan, file).string("stage"))
        assertEquals(4, server.peak.get())
        assertEquals((1..8).map { it * 8192L }.toSet(), server.sent.toSet())
        assertEquals(8, server.sent.size)
        assertTrue(receipts.zipWithNext().all { (a, b) -> a <= b })
        assertEquals(bytes.size.toLong(), receipts.last())
    }

    private fun interrupted(cancel: Boolean) = withArtifact { file, bytes, plan ->
        val server = Receiver(bytes)
        val firstFour = CountDownLatch(4)
        val cancelled = AtomicBoolean()
        val client = CoreUpdateClient(call = { action, fields ->
            if (action == "begin") server.status() else {
                assertEquals("put_chunk", action)
                server.active.incrementAndGet()
                val offset = fields.getValue("offset").jsonPrimitive.long
                server.sent.add(offset)
                try {
                    firstFour.countDown(); check(firstFour.await(5, TimeUnit.SECONDS))
                    if (offset == 0L) {
                        if (cancel) cancelled.set(true) else error("lost ACK")
                    } else Thread.sleep(80)
                    server.put(fields)
                } finally { server.active.decrementAndGet() }
            }
        }, checkpoint = {}, cancelled = { cancelled.get() }, uploadWindow = 4)
        assertTrue(runCatching { client.submit(plan, file) }.isFailure)
        assertEquals(4, server.sent.size)
        assertEquals(0, server.active.get())
        // A fresh client uses the server receipt rather than an old local offset.
        val resumed = CoreUpdateClient(call = { action, fields -> when (action) {
            "begin" -> server.status(); "put_chunk" -> server.put(fields); else -> server.advance(action)
        } }, checkpoint = {}, uploadWindow = 4)
        assertEquals("committed", resumed.submit(plan, file).string("stage"))
    }

    @Test fun lostAckDrainsWindowAndDoesNotVerifyUntilExplicitResume() = interrupted(false)
    @Test fun cancellationStopsSchedulingAndDrainsAlreadySentChunks() = interrupted(true)

    @Test fun roundTripLatencyIsOverlappedWithFourRequests() = withArtifact { file, bytes, plan ->
        fun measure(window: Int): Pair<Long, Int> {
            val server = Receiver(bytes)
            val start = System.nanoTime()
            val client = CoreUpdateClient(call = { action, fields -> when (action) {
                "begin" -> server.status()
                "put_chunk" -> {
                    val n = server.active.incrementAndGet(); server.peak.updateAndGet { maxOf(it, n) }
                    try { Thread.sleep(50); server.put(fields) } finally { server.active.decrementAndGet() }
                }
                else -> server.advance(action)
            } }, checkpoint = {}, uploadWindow = window)
            assertEquals("committed", client.submit(plan, file).string("stage"))
            return (System.nanoTime() - start) / 1_000_000 to server.peak.get()
        }
        val serial = measure(1); val pipelined = measure(4)
        assertEquals(1, serial.second); assertEquals(4, pipelined.second)
        println("Simulated 50ms RTT, 9 chunks: serial=${serial.first}ms, window4=${pipelined.first}ms")
    }
}
