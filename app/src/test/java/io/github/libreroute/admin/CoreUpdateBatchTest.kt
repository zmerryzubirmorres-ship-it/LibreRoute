package io.github.libreroute.admin

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class CoreUpdateBatchTest {
    private fun artifact(block: (File, ByteArray, JsonObject) -> Unit) {
        val file = File.createTempFile("core-batch", ".bin")
        try {
            val bytes = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
            file.writeBytes(bytes)
            val plan = buildJsonObject { put("manifest", buildJsonObject {
                put("size", bytes.size); put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).hex())
            }) }
            block(file, bytes, plan)
        } finally { file.delete() }
    }

    private class Receiver(val bytes: ByteArray, val limit: Int = 256 * 1024) {
        val chunks = sortedMapOf<Long, ByteArray>()
        val actions = mutableListOf<String>()
        val lengths = mutableListOf<Int>()
        var lost = false
        @Synchronized fun status(): JsonObject {
            var next = 0L
            while (chunks.containsKey(next)) next += chunks.getValue(next).size
            val ranges = mutableListOf<Pair<Long, Long>>()
            for ((start, data) in chunks) {
                if (ranges.lastOrNull()?.second == start) ranges[ranges.lastIndex] = ranges.last().first to start + data.size
                else ranges += start to start + data.size
            }
            return buildJsonObject {
                put("stage", "receiving"); put("next_offset", next); put("received_bytes", chunks.values.sumOf { it.size })
                put("size", bytes.size); put("chunk_bytes", 8192); if (limit > 0) put("batch_bytes", limit)
                put("ranges", buildJsonArray { ranges.take(64).forEach { (start, end) -> add(buildJsonArray { add(start); add(end) }) } })
            }
        }
        @Synchronized fun call(action: String, fields: JsonObject): JsonObject {
            actions += action
            when (action) {
                "begin", "upload_status" -> return status()
                "put_batch", "put_chunk" -> {
                    val offset = fields.getValue("offset").jsonPrimitive.long
                    val data = Base64.getUrlDecoder().decode(fields.string("data"))
                    assertArrayEquals(bytes.copyOfRange(offset.toInt(), offset.toInt() + data.size), data)
                    assertEquals(MessageDigest.getInstance("SHA-256").digest(data).hex(), fields.string("sha256"))
                    assertTrue(data.size <= if (action == "put_batch") limit else 8192)
                    lengths += data.size
                    for (pos in data.indices step 8192) chunks[offset + pos] = data.copyOfRange(pos, minOf(pos + 8192, data.size))
                    if (lost) { lost = false; throw java.io.IOException("lost durable ACK") }
                    return status()
                }
                "verify" -> { assertEquals(bytes.size.toLong(), status().getValue("next_offset").jsonPrimitive.long); return buildJsonObject { put("stage", "verified") } }
                "prepare" -> return buildJsonObject { put("stage", "prepared") }
                "apply" -> return buildJsonObject { put("stage", "committed") }
                else -> error(action)
            }
        }
    }

    @Test fun adaptiveBatchesReduceRequestsAndCoalescePhoneCheckpoints() = artifact { file, bytes, plan ->
        val server = Receiver(bytes)
        var checkpoints = 0
        val client = CoreUpdateClient(server::call, { checkpoints++ }, useReliableBatches = true, uploadWindow = 4)
        assertEquals("committed", client.submit(plan, file).string("stage"))
        assertTrue(server.lengths.size < 20) // Old RPC needs 129 round trips.
        assertTrue(server.lengths.any { it > 64 * 1024 })
        assertFalse(server.actions.contains("put_chunk"))
        assertTrue(checkpoints < server.lengths.size)
    }

    @Test fun lostAckResumesSameOperationFromServerDurableOffset() = artifact { file, bytes, plan ->
        val server = Receiver(bytes); server.lost = true
        val waits = mutableListOf<Long>()
        val client = CoreUpdateClient(server::call, {}, useReliableBatches = true, uploadWindow = 4,
            recoverableUploadFailure = { it is java.io.IOException }, pause = { waits += it })
        assertEquals("committed", client.submit(plan, file).string("stage"))
        assertEquals(listOf(1000L), waits)
        assertEquals(1, server.actions.count { it == "begin" })
        assertEquals(1, server.actions.count { it == "upload_status" })
        assertEquals(1, server.actions.count { it == "apply" })
    }

    @Test fun sparseResumeSendsOnlyMissingRanges() = artifact { file, bytes, plan ->
        val server = Receiver(bytes)
        for (pos in 65536 until bytes.size step 8192) {
            server.chunks[pos.toLong()] = bytes.copyOfRange(pos, minOf(pos + 8192, bytes.size))
        }
        assertEquals("committed", CoreUpdateClient(server::call, {}, useReliableBatches = true, uploadWindow = 4).submit(plan, file).string("stage"))
        assertEquals(listOf(65536), server.lengths)
    }

    @Test fun repeatedInterruptionsHaveBoundedExponentialBackoff() = artifact { file, bytes, plan ->
        val server = Receiver(bytes)
        val waits = mutableListOf<Long>()
        var attempts = 0
        try {
            CoreUpdateClient({ action, fields ->
                if (action == "put_batch") { attempts++; throw java.io.IOException("offline") }
                server.call(action, fields)
            }, {}, useReliableBatches = true, recoverableUploadFailure = { it is java.io.IOException }, pause = { waits += it }).submit(plan, file)
            fail("retry limit ignored")
        } catch (_: java.io.IOException) { }
        assertEquals(listOf(1000L, 2000L, 4000L), waits)
        assertEquals(4, attempts); assertFalse(server.actions.contains("apply"))
    }

    @Test fun highLatencyKeepsUsefulBatchSize() = artifact { file, bytes, plan ->
        val server = Receiver(bytes)
        var clock = 0L
        CoreUpdateClient({ action, fields -> server.call(action, fields).also { if (action == "put_batch") clock += 5000 } },
            {}, useReliableBatches = true, now = { clock }).submit(plan, file)
        assertTrue(server.lengths.dropLast(1).all { it >= 32 * 1024 })
    }

    @Test fun simulatedLatencyComparisonPreservesBytesWithFewerRequests() = artifact { file, bytes, plan ->
        fun measure(batching: Boolean): Pair<Long, Int> {
            val server = Receiver(bytes)
            val start = System.nanoTime()
            assertEquals("committed", CoreUpdateClient({ action, fields ->
                if (action in setOf("put_chunk", "put_batch")) Thread.sleep(20)
                server.call(action, fields)
            }, {}, useReliableBatches = batching, uploadWindow = 4).submit(plan, file).string("stage"))
            return (System.nanoTime() - start) / 1_000_000 to server.lengths.size
        }
        val old = measure(false); val fast = measure(true)
        assertEquals(129, old.second); assertTrue(fast.second < 20)
        println("Simulated 20ms/request, 1048593 bytes, window4: old=${old.first}ms/${old.second} requests, batch=${fast.first}ms/${fast.second} requests")
    }

    @Test fun oldServerAndIsolatedDocumentKeepSmallMessages() = artifact { file, bytes, plan ->
        for ((limit, enabled) in listOf(0 to true, 256 * 1024 to false)) {
            val server = Receiver(bytes, limit)
            assertEquals("committed", CoreUpdateClient(server::call, {}, useReliableBatches = enabled).submit(plan, file).string("stage"))
            assertFalse(server.actions.contains("put_batch")); assertTrue(server.lengths.all { it <= 8192 })
        }
    }

    @Test fun cancelDuringBackoffDoesNotOpenAnotherRequest() = artifact { file, bytes, plan ->
        val server = Receiver(bytes); server.lost = true; var stopped = false
        try {
            CoreUpdateClient(server::call, {}, cancelled = { stopped }, useReliableBatches = true,
                recoverableUploadFailure = { it is java.io.IOException }, pause = { stopped = true }).submit(plan, file)
            fail("cancelled transfer continued")
        } catch (_: IllegalStateException) { }
        assertFalse(server.actions.contains("upload_status")); assertFalse(server.actions.contains("apply"))
    }

    @Test fun rejectedAuthorizationAndApplyAreNeverRetried() = artifact { file, _, plan ->
        for (failureAction in listOf("put_batch", "apply")) {
            val server = Receiver(file.readBytes())
            var attempts = 0
            try {
                CoreUpdateClient({ action, fields ->
                    if (action == failureAction) { attempts++; throw SecurityException("rejected") }
                    server.call(action, fields)
                }, {}, useReliableBatches = true, recoverableUploadFailure = { it is java.io.IOException }, pause = { fail("rejection retried") }).submit(plan, file)
                fail("rejection ignored")
            } catch (_: SecurityException) { }
            assertEquals(1, attempts); assertFalse(server.actions.contains("upload_status"))
        }
    }
}
