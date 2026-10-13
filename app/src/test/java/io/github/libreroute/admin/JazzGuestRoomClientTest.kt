package io.github.libreroute.admin

import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class JazzGuestRoomClientTest {
    private fun withServer(code: Int, body: String, block: (JazzGuestRoomClient, AtomicInteger, () -> String) -> Unit) {
        val calls = AtomicInteger()
        var request = ""
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executor.submit {
            try {
                while (!server.isClosed) server.accept().use { socket ->
                    socket.soTimeout = 5000
                    calls.incrementAndGet()
                    val input = socket.getInputStream()
                    fun line(): String {
                        val output = java.io.ByteArrayOutputStream()
                        while (true) {
                            val byte = input.read()
                            check(byte >= 0)
                            if (byte == 10) break
                            if (byte != 13) output.write(byte)
                        }
                        return output.toString("UTF-8")
                    }
                    assertEquals("POST /room/create-meeting HTTP/1.1", line())
                    val headers = mutableMapOf<String, String>()
                    while (true) {
                        val header = line()
                        if (header.isEmpty()) break
                        headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
                    }
                    // Desktop OpenJDK strips Origin as a restricted header; Android permits it.
                    assertEquals("https://salutejazz.ru/", headers["referer"])
                    assertEquals("application/json", headers["content-type"])
                    val size = headers.getValue("content-length").toInt()
                    val received = ByteArray(size)
                    var offset = 0
                    while (offset < size) {
                        val count = input.read(received, offset, size - offset)
                        check(count > 0); offset += count
                    }
                    request = String(received, Charsets.UTF_8)
                    val bytes = body.toByteArray(Charsets.UTF_8)
                    val redirect = if (code == 302) "Location: /redirect-target\r\n" else ""
                    val response = "HTTP/1.1 $code Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$redirect\r\n"
                    socket.getOutputStream().apply {
                        write(response.toByteArray(Charsets.UTF_8)); write(bytes); flush()
                    }
                }
            } catch (e: Throwable) {
                if (!server.isClosed) failure.set(e)
            }
        }
        try {
            val client = JazzGuestRoomClient {
                URL("http://127.0.0.1:${server.localPort}/room/create-meeting").openConnection() as HttpURLConnection
            }
            block(client, calls) { request }
        } finally {
            server.close(); executor.shutdownNow()
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
            failure.get()?.let { throw AssertionError("Test HTTP server failed", it) }
        }
    }

    @Test fun createsPasswordUrlWithGuestAccessAndNoLobby() {
        withServer(200, """{"roomId":"room-123","password":"p_ass-1","extra":true}""") { client, calls, request ->
            assertEquals("https://salutejazz.ru/call/room-123/p_ass-1", client.createRoom())
            assertEquals(1, calls.get())
            val sent = Json.parseToJsonElement(request()).jsonObject
            assertEquals("true", sent.getValue("guestEnabled").toString())
            assertEquals("false", sent.getValue("lobbyEnabled").toString())
            assertEquals("false", sent.getValue("serverVideoRecordAutoStartEnabled").toString())
        }
    }

    @Test fun rejectsMalformedMissingOrUnsafeSecretsWithoutLeakingResponse() {
        for (body in listOf("private-secret not json", "[]", "{}",
            """{"roomId":123,"password":"private-secret"}""",
            """{"roomId":"ok","password":"private-secret/extra"}""",
            """{"roomId":"ok","password":"${"private-secret".repeat(12)}"}""")) {
            withServer(200, body) { client, _, _ ->
                val error = runCatching { client.createRoom() }.exceptionOrNull()!!
                assertTrue(error is JazzRoomCreationException)
                assertFalse(error.stackTraceToString().contains("private-secret"))
                assertNull(error.cause)
            }
        }
    }

    @Test fun boundsResponseAndDoesNotExposeItsContents() {
        withServer(200, "private-secret".repeat(6000)) { client, _, _ ->
            val error = runCatching { client.createRoom() }.exceptionOrNull()!!
            assertTrue(error is JazzRoomCreationException)
            assertFalse(error.stackTraceToString().contains("private-secret"))
        }
    }

    @Test fun rejectsRedirectAndProviderErrorsWithoutFollowingOrEchoingBodies() {
        for (code in listOf(302, 403, 429, 500)) {
            withServer(code, "private-secret") { client, calls, _ ->
                val error = runCatching { client.createRoom() }.exceptionOrNull()!!
                assertTrue(error is JazzRoomCreationException)
                assertEquals(1, calls.get())
                assertFalse(error.stackTraceToString().contains("private-secret"))
            }
        }
    }

    @Test fun connectionErrorsAreSafeAndActionable() {
        val client = JazzGuestRoomClient { throw SocketTimeoutException("private-secret") }
        val error = runCatching { client.createRoom() }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("не ответил вовремя"))
        assertFalse(error.stackTraceToString().contains("private-secret"))
        assertNull(error.cause)
    }

    @Test fun suppliesJazzOriginForAndroidHttpConnection() {
        val connection = object : HttpURLConnection(URL("https://bk.salutejazz.ru/room/create-meeting")) {
            override fun connect() = Unit
            override fun disconnect() = Unit
            override fun usingProxy() = false
            override fun getOutputStream() = java.io.ByteArrayOutputStream()
            override fun getResponseCode() = 200
            override fun getInputStream() = java.io.ByteArrayInputStream("""{"roomId":"test","password":"pass"}""".toByteArray())
        }
        JazzGuestRoomClient { connection }.createRoom()
        assertEquals("https://salutejazz.ru", connection.getRequestProperty("Origin"))
        assertFalse(connection.instanceFollowRedirects)
    }
}
