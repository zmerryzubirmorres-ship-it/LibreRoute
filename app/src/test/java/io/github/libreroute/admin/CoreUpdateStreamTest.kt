package io.github.libreroute.admin

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CoreUpdateStreamTest {
    private val challenge = "a".repeat(43)
    private fun plan(size: Int) = buildJsonObject {
        put("operation_id", "op"); put("server_id", "server"); put("server_public_key", "pin")
        put("control_profile_id", "control"); put("plan_hash", "hash")
        put("manifest", buildJsonObject { put("size", size); put("sha256", "target") })
    }

    private fun status(plan: JsonObject, received: Long, sequence: Long = 0, foreign: Boolean = false): JsonObject = buildJsonObject {
        for (field in listOf("operation_id", "server_id", "server_public_key", "control_profile_id", "plan_hash")) put(field, plan.getValue(field))
        put("stage", "receiving"); put("size", plan.getValue("manifest").jsonObject.getValue("size"))
        put("next_offset", received); put("received_bytes", received); put("chunk_bytes", 8192)
        put("challenge", challenge); put("epoch", if (foreign) 2 else 1); put("sequence", sequence)
        put("frame_bytes", 65536); put("window_bytes", 1048576)
        put("ranges", buildJsonArray { if (received > 0) add(buildJsonArray { add(0); add(received) }) })
    }

    @Test fun binaryWindowResumeSingleSignatureAndDurableACKs() {
        val bytes = ByteArray(2 * 1048576 + 17) { (it % 251).toByte() }
        val file = File.createTempFile("binary-stream", ".bin").apply { writeBytes(bytes) }
        val plan = plan(bytes.size)
        val serverInput = PipedInputStream(2 * 1048576)
        val clientOutput = PipedOutputStream(serverInput)
        val clientInput = PipedInputStream(2 * 1048576)
        val serverOutput = PipedOutputStream(clientInput)
        val failure = AtomicReference<Throwable?>()
        val signatures = AtomicInteger()
        val receipts = mutableListOf<Long>()
        val server = Thread {
            try {
                val input = DataInputStream(serverInput); val output = DataOutputStream(serverOutput)
                fun send(kind: Int, json: JsonObject) = CoreUpdateStream.writeFrame(output, kind, json.toString().toByteArray())
                send(CoreUpdateStream.CHALLENGE, buildJsonObject { put("challenge", challenge); put("capability", CoreUpdateStream.CAPABILITY) })
                assertEquals(CoreUpdateStream.OPEN, CoreUpdateStream.readFrame(input).first)
                send(CoreUpdateStream.READY, status(plan, 8192))
                var offset = 8192L; var sequence = 0L; var durable = 8192L
                while (true) {
                    val (kind, raw) = CoreUpdateStream.readFrame(input)
                    if (kind == CoreUpdateStream.FINISH) {
                        assertEquals(bytes.size.toLong(), offset)
                        if (durable < offset) send(CoreUpdateStream.ACK, status(plan, offset, sequence))
                        send(CoreUpdateStream.DONE, status(plan, offset, sequence)); break
                    }
                    assertEquals(CoreUpdateStream.DATA, kind)
                    val data = DataInputStream(ByteArrayInputStream(raw))
                    assertEquals(1L, data.readLong()); assertEquals(++sequence, data.readLong()); assertEquals(offset, data.readLong())
                    val chunk = ByteArray(raw.size - 24).also(data::readFully)
                    assertArrayEquals(bytes.copyOfRange(offset.toInt(), offset.toInt() + chunk.size), chunk)
                    offset += chunk.size
                    // Delay ACK until a full 1-MiB window: detect per-frame RTT waits.
                    if (offset - durable >= 1048576) { durable = offset; send(CoreUpdateStream.ACK, status(plan, durable, sequence)) }
                }
            } catch (e: Throwable) { failure.set(e) }
            finally { serverInput.close(); serverOutput.close() }
        }.apply { start() }
        try {
            val client = CoreUpdateStream(clientInput, clientOutput, { clientInput.close(); clientOutput.close() },
                { signatures.incrementAndGet(); "signed" }, { Json.parseToJsonElement(it).jsonObject },
                { receipts += it.getValue("received_bytes").jsonPrimitive.long }, { false })
            val result = client.upload(file, plan, System.currentTimeMillis() + 10000)
            server.join(3000)
            failure.get()?.let { throw AssertionError(it) }
            assertEquals(1, signatures.get())
            assertEquals(bytes.size.toLong(), result.getValue("received_bytes").jsonPrimitive.long)
            assertEquals(8192L, receipts.first()); assertEquals(bytes.size.toLong(), receipts.last())
        } finally { clientInput.close(); clientOutput.close(); server.join(3000); file.delete() }
    }

    @Test fun rejectsForeignEpochBeforeAcceptingProgress() {
        val file = File.createTempFile("foreign-stream", ".bin").apply { writeBytes(ByteArray(17)) }
        val plan = plan(17)
        val bytes = ByteArrayOutputStream()
        val output = DataOutputStream(bytes)
        CoreUpdateStream.writeFrame(output, CoreUpdateStream.CHALLENGE, buildJsonObject {
            put("challenge", challenge); put("capability", CoreUpdateStream.CAPABILITY)
        }.toString().toByteArray())
        CoreUpdateStream.writeFrame(output, CoreUpdateStream.READY, status(plan, 0).toString().toByteArray())
        CoreUpdateStream.writeFrame(output, CoreUpdateStream.ACK, status(plan, 17, 1, foreign = true).toString().toByteArray())
        try {
            val client = CoreUpdateStream(ByteArrayInputStream(bytes.toByteArray()), ByteArrayOutputStream(), {},
                { "signed" }, { Json.parseToJsonElement(it).jsonObject }, {}, { false })
            assertTrue(runCatching { client.upload(file, plan, System.currentTimeMillis() + 1000) }.isFailure)
        } finally { file.delete() }
    }

    @Test fun rejectsOversizedFrameBeforeAllocation() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { it.writeByte(CoreUpdateStream.ACK); it.writeInt(Int.MAX_VALUE) }
        assertTrue(runCatching { CoreUpdateStream.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }.isFailure)
    }
}
