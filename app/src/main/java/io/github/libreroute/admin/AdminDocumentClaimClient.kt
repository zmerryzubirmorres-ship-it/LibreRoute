package io.github.libreroute.admin

import android.content.Context
import android.net.Uri
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import io.github.libreroute.util.Logx

@Serializable
data class DocumentSessionKeys(val clientKey: String, val serverKey: String, val documentUrl: String)

data class DocumentClaimResult(
    val grant: AdminEnvelope,
    val serverPublicKey: String,
    val clientKey: String,
    val serverKey: String
)

/** Runs the bundled Go document client; secrets are passed over stdin only. */
class AdminDocumentClaimClient(private val context: Context) {
    companion object {
        fun resolveDocumentUrl(value: String): String {
            val uri = java.net.URI(value.trim())
            if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1)
                throw IllegalArgumentException("Нужна ссылка на документ Яндекса по HTTPS")
            if (uri.host?.lowercase() == "docs.yandex.ru" && uri.path?.startsWith("/edit/d/") == true) {
                return value.trim()
            }
            if (uri.host?.lowercase() != "disk.yandex.ru" || uri.path?.startsWith("/i/") != true) {
                throw IllegalArgumentException("Нужна ссылка на документ docs.yandex.ru или disk.yandex.ru/i/")
            }
            val connection = (URL(value.trim()).openConnection() as HttpURLConnection).apply {
                requestMethod = "HEAD"
                instanceFollowRedirects = true
                connectTimeout = 7000
                readTimeout = 7000
            }
            return try {
                if (connection.responseCode !in 200..399) throw IllegalStateException("Не удалось открыть ссылку на документ")
                val resolved = connection.url.toString()
                val target = java.net.URI(resolved)
                if (target.scheme != "https" || target.host?.lowercase() != "docs.yandex.ru" ||
                    target.userInfo != null || target.port != -1 ||
                    target.path?.startsWith("/edit/d/") != true) {
                    throw IllegalArgumentException("Короткая ссылка не ведёт к редактируемому документу")
                }
                resolved
            } finally {
                connection.disconnect()
            }
        }

        fun stateFileFor(context: Context, documentUrl: String, profileId: String): File {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$documentUrl|$profileId".toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
            return File(context.noBackupFilesDir, "admin-claim-$digest.json")
        }
    }

    fun claim(documentUrl: String, profileId: String, setupToken: String,
              join: AdminEnvelope, ephemeralPrivate: String): DocumentClaimResult {
        val input = JSONObject()
            .put("setup_token", setupToken)
            .put("join", JSONObject(join.toJson()))
            .put("ephemeral_private", ephemeralPrivate)
        val result = JSONObject(runCore("document-claim", documentUrl, profileId, input))
        val grant = AdminEnvelope.fromJson(result.getJSONObject("grant").toString())
            ?: throw IllegalStateException("Некорректный допуск сервера")
        return DocumentClaimResult(grant, result.getString("server_public_key"),
            result.getString("client_key"), result.getString("server_key"))
    }

    fun execute(documentUrl: String, profileId: String, command: AdminEnvelope,
                session: DocumentSessionKeys, serverPublicKey: String): AdminEnvelope {
        val input = JSONObject()
            .put("command", JSONObject(command.toJson()))
            .put("client_key", session.clientKey)
            .put("server_key", session.serverKey)
            .put("server_public_key", serverPublicKey)
        return AdminEnvelope.fromJson(runCore("document-command", documentUrl, profileId, input))
            ?: throw IllegalStateException("Некорректный ответ сервера")
    }

    fun redeem(documentUrl: String, profileId: String, secretKey: String,
               redeemEnvelope: AdminEnvelope, serverPublicKey: String): AdminEnvelope {
        val input = JSONObject()
            .put("redeem", JSONObject(redeemEnvelope.toJson()))
            .put("secret_key", secretKey)
            .put("server_public_key", serverPublicKey)
        return AdminEnvelope.fromJson(runCore("document-redeem", documentUrl, profileId, input))
            ?: throw IllegalStateException("Некорректный ответ сервера на гашение приглашения")
    }

    fun sync(documentUrl: String, profileId: String, secretKey: String,
             syncEnvelope: AdminEnvelope, serverPublicKey: String): AdminEnvelope {
        val input = JSONObject()
            .put("redeem", JSONObject(syncEnvelope.toJson()))
            .put("secret_key", secretKey)
            .put("server_public_key", serverPublicKey)
        return AdminEnvelope.fromJson(runCore("document-redeem", documentUrl, profileId, input))
            ?: throw IllegalStateException("Некорректный ответ сервера на синхронизацию маршрутов")
    }

    private fun runCore(subcommand: String, documentUrl: String, profileId: String, input: JSONObject): String {
        val uri = Uri.parse(documentUrl)
        val host = uri.host?.lowercase() ?: ""
        require(uri.scheme == "https" && host == "docs.yandex.ru" &&
            uri.path?.startsWith("/edit/d/") == true) {
            "Откройте короткую ссылку и вставьте полный адрес docs.yandex.ru/edit/d/…"
        }
        val binary = File(context.applicationInfo.nativeLibraryDir, "liblibreroute_client.so")
        require(binary.isFile) { "Ядро LibreRoute не найдено" }
        val command = mutableListOf(binary.absolutePath, "admin", subcommand,
            "--document-url=$documentUrl", "--profile-id=$profileId")
        if (subcommand == "document-claim") {
            command.add("--state-file=${stateFileFor(context, documentUrl, profileId).absolutePath}")
        }
        val cookies = File(context.filesDir, "yandex_webview_cookies.json")
        if (cookies.isFile) command.add("--cookie-file=${cookies.absolutePath}")
        Logx.i("AdminDocClaim", "Executing admin $subcommand")
        val process = ProcessBuilder(command).directory(context.filesDir).start()
        val errorText = StringBuilder()
        val errorReader = Thread {
            process.errorStream.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    Logx.i("AdminDocClaim", "core: $line")
                    if (errorText.length < 2048) errorText.append(line.take(256)).append('\n')
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            process.outputStream.bufferedWriter().use { it.write(input.toString()) }
            if (!process.waitFor(105, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                throw IllegalStateException("Время ожидания ответа через документ истекло")
            }
            errorReader.join(1000)
            if (process.exitValue() != 0) {
                throw IllegalStateException(errorText.toString().trim().takeLast(500).ifBlank {
                    "Сервер не подтвердил привязку через документ"
                })
            }
            return process.inputStream.bufferedReader().use { it.readText() }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
