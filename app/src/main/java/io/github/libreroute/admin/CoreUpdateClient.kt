package io.github.libreroute.admin

import kotlinx.serialization.json.*
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException

/** One wire protocol for all carriers. Reconnection never invents a new operation. */
internal class CoreUpdateClient(
    private val call: (String, JsonObject) -> JsonObject,
    private val checkpoint: (JsonObject) -> Unit,
    private val cancelled: () -> Boolean = { false },
    private val beforeSubmit: () -> Unit = {},
    private val now: () -> Long = { System.currentTimeMillis() },
    private val progress: (String, Long, Long) -> Unit = { _, _, _ -> },
    private val uploadWindow: Int = 1,
    private val useReliableBatches: Boolean = false,
    private val recoverableUploadFailure: (Exception) -> Boolean = { false },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
    private val binaryUpload: ((File, JsonObject, Long) -> JsonObject)? = null
) {
    companion object {
        const val CAPABILITY = "core-update-transaction-v1"
        const val CONFIGURATION_BASELINE = "core-update-configuration-baseline-v1"
        const val PREPARATION_MS = 30_000L
        const val UPLOAD_MS = 30 * 60_000L
        const val OBSERVE_MS = 60_000L
    }

    fun submit(plan: JsonObject, artifact: File): JsonObject {
        val manifest = plan.getValue("manifest").jsonObject
        val size = manifest.getValue("size").jsonPrimitive.long
        require(artifact.isFile && artifact.length() == size) { "Размер сборки отличается от подписанного плана" }
        val hash = MessageDigest.getInstance("SHA-256")
        artifact.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) { val n = input.read(bytes); if (n < 0) break; hash.update(bytes, 0, n) }
        }
        require(hash.digest().hex() == manifest.getValue("sha256").jsonPrimitive.content) { "Контрольная сумма сборки не совпадает" }
        check(!cancelled()) { "Подготовка отменена до отправки" }
        beforeSubmit()
        var state = call("begin", buildJsonObject { put("plan", plan) }).also(checkpoint)
        val deadline = now() + UPLOAD_MS
        if (state.string("stage") in setOf("planned", "receiving")) {
            var recoveries = 0
            while (true) {
                try {
                    state = if (binaryUpload != null && state["stream_available"]?.jsonPrimitive?.booleanOrNull == true)
                        binaryUpload.invoke(artifact, plan, deadline) else upload(artifact, size, state, deadline)
                    break
                }
                catch (e: Exception) {
                    // Only receiving is replayable. Drain all requests first,
                    // then trust a fresh bound server status, never local bytes.
                    if (!useReliableBatches || !recoverableUploadFailure(e) ||
                        recoveries >= 3 || cancelled() || now() >= deadline) throw e
                    pause(1000L shl recoveries++)
                    check(!cancelled() && now() < deadline) { "Передача остановлена. Проверьте сохранённый прогресс" }
                    state = call("upload_status", buildJsonObject { }).also(checkpoint)
                    if (state.string("stage") !in setOf("planned", "receiving")) break
                }
            }
        }
        fun advance(action: String): JsonObject {
            check(!cancelled() && now() < deadline) { "Продолжение остановлено. Проверьте состояние обновления" }
            return call(action, buildJsonObject { }).also(checkpoint)
        }
        if (state.string("stage") in setOf("planned", "receiving")) state = advance("verify")
        if (state.string("stage") == "verified") state = advance("prepare")
        if (state.string("stage") == "prepared") state = advance("apply")
        return observe(plan, state)
    }

    /** Only a live carrier opts in: its server queue serializes coordinator calls.
     * Drain sent requests on failure before returning to reconciliation. Checkpoints
     * stay on this thread, and older replies cannot move durable progress backwards.
     */
    internal fun upload(artifact: File, size: Long, initial: JsonObject, deadline: Long): JsonObject {
        require(uploadWindow in 1..4)
        var state = initial
        var offset = initial.getValue("next_offset").jsonPrimitive.long
        require(offset in 0..size) { "Сервер вернул неверный прогресс передачи" }
        val chunkSize = initial.getValue("chunk_bytes").jsonPrimitive.int
        require(chunkSize in 1..8 * 1024) { "Лимит сообщения сервера не поддерживается" }
        require(offset == size || offset % chunkSize == 0L) { "Неверное смещение передачи" }
        val batchLimit = if (useReliableBatches) initial["batch_bytes"]?.jsonPrimitive?.intOrNull ?: 0 else 0
        require(batchLimit == 0 || batchLimit in chunkSize..256 * 1024 && batchLimit % chunkSize == 0) {
            "Лимит потока сервера не поддерживается"
        }
        val batching = batchLimit > 0
        var sendSize = if (batching) minOf(64 * 1024, batchLimit) else chunkSize
        val durableRanges = initial["ranges"]?.jsonArray?.map { element ->
            val range = element.jsonArray
            require(range.size == 2) { "Некорректный диапазон передачи" }
            val start = range[0].jsonPrimitive.long
            val end = range[1].jsonPrimitive.long
            require(start in 0 until size && end in (start + 1)..size && start % chunkSize == 0L &&
                (end == size || end % chunkSize == 0L)) { "Некорректный диапазон передачи" }
            start to end
        }.orEmpty()
        require(durableRanges.size <= 64 && durableRanges.zipWithNext().all { (a, b) -> a.second < b.first }) {
            "Некорректные подтверждённые диапазоны"
        }
        var checkpointAt = now()
        val executor = Executors.newFixedThreadPool(uploadWindow)
        val replies = ExecutorCompletionService<Pair<JsonObject, Long>>(executor)
        var pending = 0
        var failure: Exception? = null
        try {
            RandomAccessFile(artifact, "r").use { input ->
                while (offset < size || pending > 0) {
                    if (failure == null && (cancelled() || now() >= deadline)) {
                        failure = IllegalStateException("Передача остановлена. Проверьте сохранённый прогресс")
                    }
                    while (failure == null && offset < size && pending < uploadWindow) {
                        durableRanges.firstOrNull { offset >= it.first && offset < it.second }?.let { offset = it.second }
                        if (offset == size) break
                        val sentOffset = offset
                        input.seek(sentOffset)
                        val rangeEnd = durableRanges.firstOrNull { it.first > sentOffset }?.first ?: size
                        val bytes = ByteArray(minOf(sendSize.toLong(), rangeEnd - sentOffset).toInt())
                        input.readFully(bytes)
                        val fields = buildJsonObject {
                            put("offset", sentOffset)
                            put("data", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
                            put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).hex())
                        }
                        replies.submit(java.util.concurrent.Callable {
                            val started = now()
                            call(if (batching) "put_batch" else "put_chunk", fields) to (now() - started)
                        })
                        pending++
                        offset += bytes.size
                    }
                    if (pending == 0) break
                    try {
                        val (reply, elapsed) = replies.take().get()
                        val received = reply.getValue("received_bytes").jsonPrimitive.long
                        val next = reply.getValue("next_offset").jsonPrimitive.long
                        require(received in 0..size && next in 0..size && next <= received &&
                            reply.string("stage") in setOf("planned", "receiving")) { "Сервер вернул неверный прогресс передачи" }
                        val previous = state.getValue("received_bytes").jsonPrimitive.long
                        if (received > previous || (received == previous && next >= state.getValue("next_offset").jsonPrimitive.long)) {
                            state = reply
                            if (!batching || received == size || now() - checkpointAt >= 1000) {
                                checkpoint(state)
                                progress("Передаём сборку…", received, size)
                                checkpointAt = now()
                            }
                        }
                        if (batching) sendSize = when {
                            elapsed < 2000 -> minOf(batchLimit, sendSize * 2)
                            elapsed > 4000 -> maxOf(minOf(batchLimit, 32 * 1024), sendSize / 2)
                            else -> sendSize
                        }
                    } catch (e: Exception) {
                        if (failure == null) failure = (e as? ExecutionException)?.cause as? Exception ?: e
                    }
                    pending--
                }
            }
            failure?.let { throw it }
            require(state.getValue("next_offset").jsonPrimitive.long == size &&
                state.getValue("received_bytes").jsonPrimitive.long == size) { "Передача не подтверждена полностью. Проверьте сохранённый прогресс" }
            return state
        } finally {
            executor.shutdown()
            // Also covers local I/O/checkpoint failures: never leave a worker
            // changing the operation after its caller reports completion.
            while (!executor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) { }
        }
    }

    fun reconcile(plan: JsonObject): JsonObject = observe(plan, call("status", buildJsonObject { }).also(checkpoint))

    private fun observe(plan: JsonObject, initial: JsonObject): JsonObject {
        var state = initial
        val deadline = now() + OBSERVE_MS
        while (state.string("stage") in setOf("prepared", "backing_up", "switching", "checking", "awaiting_confirmation", "rolling_back")) {
            check(!cancelled() && now() < deadline) { "Результат пока неизвестен. Проверьте состояние обновления" }
            if (state.string("stage") == "prepared" && (state["deadline"]?.jsonPrimitive?.longOrNull ?: 0) == 0L) return state
            if (state.string("stage") == "awaiting_confirmation") {
                val runtime = state.getValue("runtime").jsonObject
                val target = plan.getValue("manifest").jsonObject
                require(runtime.string("build_id") == target.string("sha256") && runtime.string("version") == target.string("version")) {
                    "Работающее ядро отличается от подписанной целевой сборки"
                }
                state = call("confirm", buildJsonObject { put("runtime_sha", target.getValue("sha256")) }).also(checkpoint)
            }
            progress(coreUpdateStageLabel(state.string("stage")), state["received_bytes"]?.jsonPrimitive?.longOrNull ?: 0, state["size"]?.jsonPrimitive?.longOrNull ?: 0)
            if (state.string("stage") == "committed") break
            Thread.sleep(1000)
            state = call("status", buildJsonObject { }).also(checkpoint)
        }
        return state
    }
}

internal fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content.orEmpty()
internal fun requireCoreUpdatePlanIdentity(plan: JsonObject, serverId: String, serverKey: String,
                                          profileId: String, operationId: String, namespace: String) {
    val expected = mapOf("server_id" to serverId, "server_public_key" to serverKey,
        "control_profile_id" to profileId, "operation_id" to operationId, "namespace" to namespace,
        "operation_kind" to ServerInstallOperation.KIND_CORE_UPDATE)
    for ((field, value) in expected) require(value.isNotBlank() && plan.string(field) == value) {
        "План обновления не совпадает с выбранным сервером или операцией: $field"
    }
}
internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
internal fun coreUpdateStageLabel(stage: String): String = when (stage) {
    "planned", "receiving" -> "Передаём сборку…"
    "verified" -> "Проверяем новую сборку…"
    "prepared" -> "Сборка готова к запуску"
    "backing_up" -> "Сохраняем резервную копию…"
    "switching" -> "Перезапускаем ядро…"
    "checking", "awaiting_confirmation" -> "Проверяем доступ к обновлённому серверу…"
    "committed" -> "Обновление подтверждено"
    "rolling_back" -> "Возвращаем предыдущую версию…"
    "rolled_back" -> "Восстановлена предыдущая версия"
    else -> "Проверьте состояние обновления"
}

internal fun coreUpdateProgressLabel(operation: ServerInstallOperation): String {
    val label = coreUpdateStageLabel(operation.serverStage)
    if (operation.totalBytes <= 0 || operation.serverStage !in setOf("planned", "receiving", "verified", "prepared")) return label
    val percent = (operation.receivedBytes * 100 / operation.totalBytes).coerceIn(0, 100)
    val megabytes = String.format(java.util.Locale.getDefault(), "%.1f из %.1f МБ",
        operation.receivedBytes / 1048576.0, operation.totalBytes / 1048576.0)
    return "$label $percent% · $megabytes"
}
