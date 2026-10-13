package io.github.libreroute.admin

import kotlinx.serialization.json.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Binary frames over the native, pinned TLS bridge. One signature per OPEN. */
internal class CoreUpdateStream(
    private val input: InputStream,
    private val output: OutputStream,
    private val close: () -> Unit,
    private val authorize: (JsonObject) -> String,
    private val acceptReady: (String) -> JsonObject,
    private val checkpoint: (JsonObject) -> Unit,
    private val cancelled: () -> Boolean,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val progress: (String, Long, Long) -> Unit = { _, _, _ -> }
) {
    companion object {
        const val CAPABILITY = "core-update-stream-v1"
        const val CHALLENGE = 1
        const val OPEN = 2
        const val READY = 3
        const val DATA = 4
        const val ACK = 5
        const val FINISH = 6
        const val ERROR = 7
        const val DONE = 8
        const val FRAME_BYTES = 64 * 1024

        internal fun readFrame(input: DataInputStream): Pair<Int, ByteArray> {
            val kind = input.readUnsignedByte()
            val length = input.readInt()
            require(kind in CHALLENGE..DONE && length >= 0 &&
                (if (kind == DATA) length in 25..FRAME_BYTES + 24 else length <= FRAME_BYTES)) {
                "Некорректное сообщение потока обновления"
            }
            return kind to ByteArray(length).also(input::readFully)
        }

        internal fun writeFrame(output: DataOutputStream, kind: Int, bytes: ByteArray) {
            require(kind in CHALLENGE..DONE && bytes.size <= FRAME_BYTES + (if (kind == DATA) 24 else 0))
            // The Unix bridge can forward every write as its own TLS record.
            // Submit a whole bounded frame so the five-byte header cannot
            // trigger a tiny record or a delayed-ACK round trip of its own.
            val frame = java.nio.ByteBuffer.allocate(bytes.size + 5)
                .put(kind.toByte()).putInt(bytes.size).put(bytes).array()
            output.write(frame); output.flush()
        }
    }

    fun upload(artifact: File, plan: JsonObject, deadline: Long): JsonObject {
        val source = DataInputStream(input)
        val sink = DataOutputStream(output)
        var reader: Thread? = null
        var lastState: JsonObject? = null
        try {
            check(!cancelled() && now() < deadline) { "Передача остановлена" }
            val (challengeKind, challengeBytes) = readFrame(source)
            if (challengeKind == ERROR) throw UnknownAdminOperation("Защищённый поток обновления недоступен")
            require(challengeKind == CHALLENGE)
            val challenge = Json.parseToJsonElement(challengeBytes.toString(Charsets.UTF_8)).jsonObject
            require(challenge.string("capability") == CAPABILITY &&
                challenge.string("challenge").matches(Regex("[A-Za-z0-9_-]{43}"))) { "Некорректный запрос авторизации потока" }
            writeFrame(sink, OPEN, authorize(challenge).toByteArray(Charsets.UTF_8))
            val (readyKind, readyBytes) = readFrame(source)
            require(readyKind == READY) { "Сервер не подтвердил открытие потока" }
            var state = acceptReady(readyBytes.toString(Charsets.UTF_8))
            require(state.string("challenge") == challenge.string("challenge")) { "Ответ относится к другому соединению" }
            val epoch = state.getValue("epoch").jsonPrimitive.long
            val frameBytes = state.getValue("frame_bytes").jsonPrimitive.int
            val windowBytes = state.getValue("window_bytes").jsonPrimitive.int
            val chunkBytes = state.getValue("chunk_bytes").jsonPrimitive.int
            require(epoch > 0 && chunkBytes == 8192 && frameBytes in chunkBytes..FRAME_BYTES &&
                frameBytes % chunkBytes == 0 && windowBytes in frameBytes..1024 * 1024) { "Лимиты потока не поддерживаются" }
            val size = plan.getValue("manifest").jsonObject.getValue("size").jsonPrimitive.long
            fun validateStatus(status: JsonObject) {
                for (field in listOf("operation_id", "server_id", "server_public_key", "control_profile_id", "plan_hash")) {
                    require(status.string(field) == plan.string(field) && plan.string(field).isNotEmpty()) { "Ответ потока относится к другой операции: $field" }
                }
                val received = status.getValue("received_bytes").jsonPrimitive.long
                val next = status.getValue("next_offset").jsonPrimitive.long
                require(status.getValue("size").jsonPrimitive.long == size && received in 0..size && next in 0..received &&
                    (next == size || next % chunkBytes == 0L) && status.string("stage") in setOf("planned", "receiving")) { "Некорректный прогресс потока" }
            }
            validateStatus(state)
            val ranges = state.getValue("ranges").jsonArray.map { item ->
                val range = item.jsonArray
                require(range.size == 2)
                val start = range[0].jsonPrimitive.long; val end = range[1].jsonPrimitive.long
                require(start in 0 until size && end in (start + 1)..size && start % chunkBytes == 0L && (end == size || end % chunkBytes == 0L))
                start to end
            }
            require(ranges.size <= 64 && ranges.zipWithNext().all { (a, b) -> a.second < b.first })
            checkpoint(state)
            lastState = state
            val replies = ArrayBlockingQueue<Result<Pair<Int, ByteArray>>>(16)
            reader = Thread({
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val frame = readFrame(source)
                        replies.put(Result.success(frame))
                        if (frame.first == DONE || frame.first == ERROR) break
                    }
                } catch (e: Exception) {
                    try { replies.put(Result.failure(e)) } catch (_: InterruptedException) { }
                }
            }, "core-update-stream-ack").apply { start() }
            data class Flight(val sequence: Long, val bytes: Int)
            val pending = java.util.ArrayDeque<Flight>()
            var sequence = 0L
            var confirmedSequence = 0L
            var outstanding = 0
            var offset = state.getValue("next_offset").jsonPrimitive.long
            var checkpointAt = now()
            fun stopped() { check(!cancelled() && now() < deadline) { "Передача остановлена. Проверьте сохранённый прогресс" } }
            fun reply(wait: Boolean): Boolean {
                val item = if (wait) replies.poll(250, TimeUnit.MILLISECONDS) else replies.poll()
                if (item == null) { stopped(); return false }
                val (kind, bytes) = item.getOrThrow()
                val status = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                if (kind == ERROR) {
                    require(status.getValue("epoch").jsonPrimitive.long == epoch)
                    throw ConfirmedAdminRejection(status.string("error"))
                }
                require(kind == ACK || kind == DONE)
                validateStatus(status)
                val ackSequence = status.getValue("sequence").jsonPrimitive.long
                require(status.getValue("epoch").jsonPrimitive.long == epoch && ackSequence in confirmedSequence..sequence &&
                    status.getValue("received_bytes").jsonPrimitive.long >= state.getValue("received_bytes").jsonPrimitive.long &&
                    status.getValue("next_offset").jsonPrimitive.long >= state.getValue("next_offset").jsonPrimitive.long) { "Устаревшее подтверждение потока" }
                if (kind == ACK) require(ackSequence > confirmedSequence)
                while (pending.peekFirst()?.sequence?.let { it <= ackSequence } == true) outstanding -= pending.removeFirst().bytes
                confirmedSequence = ackSequence
                state = status
                lastState = state
                if (kind == DONE || now() - checkpointAt >= 1000) {
                    checkpoint(state)
                    progress("Передаём сборку…", state.getValue("received_bytes").jsonPrimitive.long, size)
                    checkpointAt = now()
                }
                if (kind == DONE) require(ackSequence == sequence && pending.isEmpty() &&
                    state.getValue("received_bytes").jsonPrimitive.long == size && state.getValue("next_offset").jsonPrimitive.long == size)
                return kind == DONE
            }
            RandomAccessFile(artifact, "r").use { file ->
                while (offset < size) {
                    stopped()
                    while (replies.isNotEmpty()) require(!reply(false)) { "Поток завершился раньше отправки" }
                    ranges.firstOrNull { offset >= it.first && offset < it.second }?.let { offset = it.second }
                    if (offset == size) break
                    val end = ranges.firstOrNull { it.first > offset }?.first ?: size
                    val count = minOf(frameBytes.toLong(), end - offset).toInt()
                    while (outstanding + count > windowBytes) { stopped(); require(!reply(true)) }
                    file.seek(offset)
                    val bytes = ByteArray(count).also(file::readFully)
                    val frame = java.io.ByteArrayOutputStream(count + 24)
                    DataOutputStream(frame).use { data -> data.writeLong(epoch); data.writeLong(++sequence); data.writeLong(offset); data.write(bytes) }
                    pending.addLast(Flight(sequence, count)); outstanding += count
                    writeFrame(sink, DATA, frame.toByteArray())
                    offset += count
                }
            }
            stopped()
            val finish = buildJsonObject { put("epoch", epoch); put("sequence", sequence) }
            writeFrame(sink, FINISH, finish.toString().toByteArray(Charsets.UTF_8))
            while (!reply(true)) stopped()
            return state
        } catch (e: java.io.IOException) {
            lastState?.let(checkpoint)
            throw UnknownAdminOperation("Поток передачи прерван. Проверьте сохранённый прогресс")
        } catch (e: Exception) {
            lastState?.let(checkpoint)
            throw e
        } finally {
            close()
            reader?.interrupt()
            reader?.join(2000)
        }
    }
}
