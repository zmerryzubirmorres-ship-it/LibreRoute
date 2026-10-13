package io.github.libreroute.admin

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import io.github.libreroute.service.NativeProcessSupervisor
import java.util.Base64
import org.json.JSONObject

/** Commands use the live route, but authority comes from the existing signed grant. */
internal class AdminCarrierClient(private val context: Context, private val keys: AdminKeyManager) {
    fun uploadBinary(artifact: java.io.File, plan: kotlinx.serialization.json.JsonObject, serverPublicKey: String,
                     deadline: Long, authorize: (kotlinx.serialization.json.JsonObject) -> String,
                     acceptReady: (String) -> kotlinx.serialization.json.JsonObject,
                     checkpoint: (kotlinx.serialization.json.JsonObject) -> Unit,
                     cancelled: () -> Boolean,
                     carrier: NativeProcessSupervisor.AdminCarrier): kotlinx.serialization.json.JsonObject {
        require(carrier.ready && NativeProcessSupervisor.activeAdminCarrier?.path == carrier.path) { "Канал подключения ещё не готов к управлению" }
        require(java.io.File(carrier.path).parentFile?.canonicalFile == context.filesDir.canonicalFile) { "Некорректный локальный канал управления" }
        try {
            LocalSocket().use { socket ->
                socket.connect(LocalSocketAddress(carrier.path, LocalSocketAddress.Namespace.FILESYSTEM))
                socket.soTimeout = 65_000
                val request = JSONObject().put("binary_upload", true).put("server_public_key", serverPublicKey)
                socket.outputStream.write((request.toString() + "\n").toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                return CoreUpdateStream(socket.inputStream.buffered(), socket.outputStream.buffered(), socket::close,
                    authorize, acceptReady, checkpoint,
                    { cancelled() || NativeProcessSupervisor.activeAdminCarrier?.path != carrier.path })
                    .upload(artifact, plan, deadline)
            }
        } catch (e: java.io.IOException) {
            throw UnknownAdminOperation("Поток передачи прерван. Проверьте сохранённый прогресс")
        }
    }

    fun execute(request: AdminEnvelope, serverPublicKey: String, documentOnly: Boolean = false,
                reliableStream: Boolean = false,
                carrier: NativeProcessSupervisor.AdminCarrier = NativeProcessSupervisor.activeAdminCarrier
                    ?: error("Сначала подключите маршрут этого сервера на главном экране")): AdminEnvelope {
        require(carrier.ready && NativeProcessSupervisor.activeAdminCarrier?.path == carrier.path) { "Канал подключения ещё не готов к управлению" }
        require(!documentOnly || carrier.transport in setOf("vyandex", "yandex")) {
            "Для управления через документ подключите маршрут VYANDEX этого сервера"
        }
        require(java.io.File(carrier.path).parentFile?.canonicalFile == context.filesDir.canonicalFile) {
            "Некорректный локальный канал управления"
        }
        val reply = RecipientInvitationCrypto(InMemoryInvitationStateStore())
        val payload = JSONObject().put("envelope", JSONObject(request.toJson()))
            .put("reply_public_key", reply.getOrCreatePublicKeyBase64())
        val outer = keys.signEnvelope(request.copy(
            subtype = "ADMIN_CARRIER_REQUEST",
            payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray(Charsets.UTF_8)),
            signature = ""
        ))
        val (ephemeral, nonce, ciphertext) = RecipientInvitationCrypto.encryptForDevice(serverPublicKey, outer.toJson().toByteArray(Charsets.UTF_8))
        val box = JSONObject().put("ephemeral_public_key", ephemeral).put("nonce", nonce).put("ciphertext", ciphertext)
            .put("reliable_stream", reliableStream)
        LocalSocket().use { socket ->
            socket.connect(LocalSocketAddress(carrier.path, LocalSocketAddress.Namespace.FILESYSTEM))
            // LocalSocket creates its descriptor in connect(); options require it.
            socket.soTimeout = 65_000
            socket.outputStream.write((box.toString() + "\n").toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
            val bytes = java.io.ByteArrayOutputStream()
            val input = socket.inputStream.buffered()
            while (true) {
                val b = input.read()
                if (b == -1 || b == 10) break
                require(bytes.size() < 1024 * 1024) { "Ответ канала управления слишком велик" }
                bytes.write(b)
            }
            val response = JSONObject(bytes.toString("UTF-8"))
            response.optString("error").takeIf { it.isNotBlank() }?.let { error(it) }
            val raw = reply.decrypt(response.getString("ephemeral_public_key"), response.getString("nonce"), response.getString("ciphertext"))
            return AdminEnvelope.fromJson(String(raw, Charsets.UTF_8)) ?: error("Некорректный ответ сервера")
        }
    }
}
