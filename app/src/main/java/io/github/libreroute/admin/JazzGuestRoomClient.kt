package io.github.libreroute.admin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Guest endpoint used by the Jazz web client. No account or SDK credentials needed. */
internal class JazzGuestRoomClient(
    private val openConnection: () -> HttpURLConnection = {
        URL("https://bk.salutejazz.ru/room/create-meeting").openConnection() as HttpURLConnection
    }
) {
    fun createRoom(): String {
        var connection: HttpURLConnection? = null
        try {
            val http = openConnection().also { connection = it }
            http.instanceFollowRedirects = false
            http.connectTimeout = 15_000
            http.readTimeout = 15_000
            http.requestMethod = "POST"
            http.doOutput = true
            http.setRequestProperty("Content-Type", "application/json")
            http.setRequestProperty("Accept", "application/json")
            http.setRequestProperty("Origin", "https://salutejazz.ru")
            http.setRequestProperty("Referer", "https://salutejazz.ru/")
            http.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36")
            val body = """{"title":"Video meeting","guestEnabled":true,"lobbyEnabled":false,"serverVideoRecordAutoStartEnabled":false,"sipEnabled":false,"moderatorEmails":[],"summarizationEnabled":false,"room3dEnabled":false,"room3dScene":"XRLobby"}""".toByteArray(Charsets.UTF_8)
            http.setFixedLengthStreamingMode(body.size)
            http.outputStream.use { it.write(body) }
            val code = http.responseCode
            if (code !in 200..299) {
                val message = when (code) {
                    401, 403 -> "СберДжаз не разрешил создание гостевой комнаты. Можно вставить готовую гостевую ссылку."
                    429 -> "СберДжаз временно ограничил создание комнат. Повторите позже."
                    else -> "Не удалось создать комнату СберДжаза (HTTP $code). Повторите позже или вставьте готовую ссылку."
                }
                throw JazzRoomCreationException(message)
            }
            val bytes = http.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() + count > 65_536) throw JazzRoomCreationException(INVALID_RESPONSE)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            val response = Json.parseToJsonElement(String(bytes, Charsets.UTF_8)) as? JsonObject
                ?: throw JazzRoomCreationException(INVALID_RESPONSE)
            fun field(name: String): String {
                val value = response[name] as? JsonPrimitive
                if (value?.isString != true || !TOKEN.matches(value.content)) {
                    throw JazzRoomCreationException(INVALID_RESPONSE)
                }
                return value.content
            }
            return "https://salutejazz.ru/call/${field("roomId")}/${field("password")}"
        } catch (e: JazzRoomCreationException) {
            throw e
        } catch (_: SocketTimeoutException) {
            throw JazzRoomCreationException("СберДжаз не ответил вовремя. Повторите создание комнаты позже.")
        } catch (_: IOException) {
            throw JazzRoomCreationException("Не удалось связаться со СберДжазом. Проверьте интернет и повторите создание комнаты.")
        } catch (_: Exception) {
            // Parser/HTTP errors can contain a password or response body. Never retain their causes.
            throw JazzRoomCreationException(INVALID_RESPONSE)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        val TOKEN = Regex("[a-zA-Z0-9_-]{1,128}")
        const val INVALID_RESPONSE = "СберДжаз вернул некорректную ссылку комнаты. Повторите позже или вставьте готовую ссылку."
    }
}

internal class JazzRoomCreationException(message: String) : Exception(message)
