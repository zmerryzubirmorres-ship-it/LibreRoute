package io.github.libreroute.admin

import android.content.Context
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Uses bundled signed artifacts. Credentials are sent only through process stdin. */
class ServerInstaller(private val context: Context) {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun plan(node: ServerNode, inventory: ServerInventory, operationId: String): JsonObject {
        val request = request(node, inventory, operationId, "plan")
        return run(request)
    }

    fun install(node: ServerNode, inventory: ServerInventory, operationId: String,
                documentUrl: String, join: AdminEnvelope, pairingCode: String, expectedPlan: JsonObject,
                ownerRecovery: Boolean = false): JsonObject =
        submit(node, inventory, operationId, documentUrl, join, pairingCode, expectedPlan, ownerRecovery, false)

    /** Explicit OS-authorized takeover; the server's installed executable is preserved. */
    fun recoverOwner(node: ServerNode, inventory: ServerInventory, operationId: String,
                     documentUrl: String, join: AdminEnvelope, pairingCode: String,
                     expectedPlan: JsonObject): JsonObject =
        submit(node, inventory, operationId, documentUrl, join, pairingCode, expectedPlan, true, true)

    private fun submit(node: ServerNode, inventory: ServerInventory, operationId: String,
                       documentUrl: String, join: AdminEnvelope, pairingCode: String,
                       expectedPlan: JsonObject, ownerRecovery: Boolean, takeover: Boolean): JsonObject {
        if (takeover) {
            require(expectedPlan["mode"]?.jsonPrimitive?.content == "update" &&
                !expectedPlan["server_public_key"]?.jsonPrimitive?.content.isNullOrBlank() &&
                !expectedPlan["owner_device_ids"]?.jsonArray.isNullOrEmpty()) { "Подтверждённая серверная идентичность и текущие владельцы обязательны для восстановления" }
        }
        val request = request(node, inventory, operationId, if (takeover) "recover-owner" else "install")
        val setup = buildJsonObject {
            put("namespace", namespace(node)); put("server_id", node.id)
            put("document_url", documentUrl); put("control_profile_id", join.profileId)
            put("join", Json.parseToJsonElement(join.toJson())); put("pairing_code", pairingCode)
            put("owner_recovery", ownerRecovery)
            if (takeover) {
                put("recovery_server_public_key", expectedPlan.getValue("server_public_key"))
                put("recovery_owner_device_ids", expectedPlan.getValue("owner_device_ids"))
            }
            val cookies = File(context.filesDir, "yandex_webview_cookies.json")
            if (documentUrl.isNotBlank() && cookies.isFile && cookies.length() <= 96 * 1024) put("browser_cookies", cookies.readText())
        }
        require(expectedPlan["operation_id"]?.jsonPrimitive?.content == operationId &&
            expectedPlan["namespace"]?.jsonPrimitive?.content == namespace(node)) { "План относится к другой операции или серверу" }
        return run(JsonObject(request + mapOf("setup" to setup, "expected_plan" to expectedPlan)))
    }

    /** Recovery uses system SSH, never the replaceable server executable. */
    fun status(node: ServerNode, operationId: String): JsonObject = run(connectionRequest(node, operationId, "status"))

    fun rollback(node: ServerNode, operationId: String): JsonObject = run(connectionRequest(node, operationId, "rollback"))

    /** Untrusted observation only. UI must verify and confirm fingerprint before saving the pin. */
    fun discoverHostKey(host: String, port: Int): JsonObject {
        require(host.isNotBlank() && port in 1..65535) { "Укажите SSH-адрес и порт" }
        return run(buildJsonObject { put("host", host.trim()); put("port", port) }, "ssh-host-key")
    }

    fun namespace(node: ServerNode): String = (node.managementNamespace ?: ("node-" + node.id.replace("-", "").take(32))).also {
        require(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}").matches(it)) { "Некорректное пространство установки сервера" }
    }
    fun controlProfile(node: ServerNode): String = "control-" + namespace(node)
    fun newOperationId(): String = UUID.randomUUID().toString()

    private fun request(node: ServerNode, inventory: ServerInventory, operationId: String, action: String): JsonObject {
        val connection = connectionRequest(node, operationId, action)
        val arch = when (inventory.arch) { "x86_64", "amd64" -> "amd64"; "aarch64", "arm64" -> "arm64"; else -> error("Нет подписанной сборки для ${inventory.arch}") }
        val dir = File(context.noBackupFilesDir, "install-artifacts").apply { mkdirs() }
        val artifact = File(dir, "libreroute-linux-$arch")
        val staged = File.createTempFile("artifact-$arch-", ".tmp", dir)
        try {
            context.assets.open("install/libreroute-linux-$arch").use { input -> staged.outputStream().use { input.copyTo(it) } }
            Files.move(staged.toPath(), artifact.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { staged.delete() }
        val manifest = context.assets.open("install/manifest-$arch.json").bufferedReader().use { it.readText() }
        return JsonObject(connection + mapOf("manifest" to Json.parseToJsonElement(manifest), "artifact_path" to JsonPrimitive(artifact.absolutePath)))
    }

    private fun connectionRequest(node: ServerNode, operationId: String, action: String): JsonObject {
        require(!node.hostPublicKey.isNullOrBlank()) { "Сначала сверьте SSH host key" }
        require(node.host.isNotBlank() && node.port in 1..65535 && node.sshUser.isNotBlank()) { "Проверьте SSH-адрес, порт и пользователя" }
        require(node.authType != SshAuthType.PASSWORD || node.allowPasswordAuth) { "Пароль не разрешён политикой узла" }
        require(node.authType != SshAuthType.PASSWORD || !node.password.isNullOrBlank()) { "Укажите SSH-пароль" }
        require(node.authType != SshAuthType.KEY || !node.privateKeyPath.isNullOrBlank()) { "Выберите SSH-ключ" }
        require(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}").matches(operationId)) { "Некорректный идентификатор операции" }
        return buildJsonObject {
            put("host", node.host); put("port", node.port); put("user", node.sshUser)
            put("host_public_key", node.hostPublicKey!!); put("auth", if (node.authType == SshAuthType.KEY) "key" else "password")
            node.privateKeyPath?.let { put("private_key_path", it) }; node.passphrase?.let { put("passphrase", it) }; node.password?.let { put("password", it) }
            put("remote_namespace", node.managementNamespace.orEmpty())
            put("action", action); put("namespace", namespace(node)); put("operation_id", operationId)
        }
    }

    private fun run(request: JsonObject, command: String = "ssh-install"): JsonObject {
        val action = request["action"]?.jsonPrimitive?.content ?: command
        val host = request["host"]?.jsonPrimitive?.content.orEmpty()
        val operation = request["operation_id"]?.jsonPrimitive?.content.orEmpty()
        val namespace = request["namespace"]?.jsonPrimitive?.content.orEmpty()
        InstallationLog.append(context, "request", "command=$command action=$action host=$host namespace=$namespace operation_id=$operation")
        val binary = File(context.applicationInfo.nativeLibraryDir, "liblibreroute_client.so")
        val proc = ProcessBuilder(binary.absolutePath, "admin", command).start()
        val out = StringBuilder(); val errors = StringBuilder()
        var truncated = false
        val readOut = Thread { proc.inputStream.bufferedReader().use { reader ->
            val chunk = CharArray(4096)
            while (true) {
                val size = reader.read(chunk)
                if (size < 0) break
                if (out.length + size <= 512 * 1024) out.append(chunk, 0, size) else truncated = true
            }
        } }.apply { isDaemon = true; start() }
        val readError = Thread { proc.errorStream.bufferedReader().use { reader -> reader.forEachLine { if (errors.length < 1024) errors.append(it.take(200)) } } }.apply { isDaemon = true; start() }
        try {
            proc.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request.toString()) }
            require(proc.waitFor(240, TimeUnit.SECONDS)) { "Установка не завершилась; проверьте сохранённый backup на узле" }
            readOut.join(1000); readError.join(1000)
            require(!readOut.isAlive && !readError.isAlive && !truncated) { "Ответ сервера не завершён; проверьте результат операции" }
            require(proc.exitValue() == 0) { errors.toString().ifBlank { "Установка не подтверждена" } }
            val response = json.parseToJsonElement(out.toString()).jsonObject
            InstallationLog.append(context, "response", "action=$action host=$host namespace=$namespace operation_id=$operation status=${response["status"]?.jsonPrimitive?.content ?: "unknown"} stage=${response["stage"]?.jsonPrimitive?.content ?: ""}")
            return response
        } catch (error: Exception) {
            InstallationLog.append(context, "error", "action=$action host=$host namespace=$namespace operation_id=$operation message=${error.message.orEmpty()} stderr=${errors.toString()}")
            throw error
        } finally { if (proc.isAlive) proc.destroyForcibly() }
    }
}
