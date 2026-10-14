package io.github.libreroute.service

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Real TCP sockets, production bridge, isolated loopback SOCKS backend; no provider/server credentials. */
class HotspotProxyBridgeIntegrationTest {
    private val backend = ServerSocket(0, 10, InetAddress.getLoopbackAddress())
    private val bridgePort = ServerSocket(0).use { it.localPort }
    private val peers = java.util.concurrent.CopyOnWriteArrayList<Socket>()

    @After fun close() {
        HotspotProxyBridge.stop()
        backend.close()
        peers.forEach { runCatching { it.close() } }
    }

    private fun start(auth: Boolean = false) {
        HotspotProxyBridge.start(bridgePort, backend.localPort, auth, if (auth) "user" else "", if (auth) "secret" else "")
        assertTrue(HotspotProxyBridge.running)
    }

    private fun client() = Socket("127.0.0.1", bridgePort).apply { soTimeout = 3000; peers.add(this) }

    private fun upstream(auth: Boolean = false, action: (Socket) -> Unit): CompletableFuture<Unit> {
        val result = CompletableFuture<Unit>()
        thread(isDaemon = true, name = "ProxyTestBackend") {
            try {
                backend.accept().use { socket ->
                    peers.add(socket); socket.soTimeout = 3000
                    val input = socket.getInputStream(); val output = socket.getOutputStream()
                    assertEquals(5, input.read())
                    val methods = ByteArray(input.read()); input.readFully(methods)
                    assertTrue(methods.contains(if (auth) 2.toByte() else 0.toByte()))
                    output.write(byteArrayOf(5, if (auth) 2 else 0)); output.flush()
                    if (auth) {
                        assertEquals(1, input.read())
                        val user = ByteArray(input.read()); input.readFully(user)
                        val pass = ByteArray(input.read()); input.readFully(pass)
                        assertEquals("user", user.toString(Charsets.UTF_8)); assertEquals("secret", pass.toString(Charsets.UTF_8))
                        output.write(byteArrayOf(1, 0)); output.flush()
                    }
                    val request = ByteArray(4); input.readFully(request)
                    assertArrayEquals(byteArrayOf(5, 1, 0, 3), request)
                    val host = ByteArray(input.read()); input.readFully(host)
                    assertEquals("example.org", host.toString(Charsets.UTF_8))
                    val port = ByteArray(2); input.readFully(port)
                    output.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); output.flush()
                    action(socket)
                }
                result.complete(Unit)
            } catch (error: Throwable) { result.completeExceptionally(error) }
        }
        return result
    }

    @Test fun plainHttpUsesOriginFormAndNeverForwardsProxyCredentials() {
        start()
        val done = upstream { socket ->
            val request = headers(socket.getInputStream())
            assertTrue(request, request.startsWith("GET /path?q=1 HTTP/1.1\r\n"))
            assertFalse(request, request.contains("Proxy-Authorization", true))
            assertFalse(request, request.contains("Proxy-Connection", true))
            assertTrue(request, request.contains("Connection: close", true))
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK".toByteArray())
        }
        client().use { socket ->
            socket.getOutputStream().write(("GET http://example.org/path?q=1 HTTP/1.1\r\nHost: example.org\r\n" +
                "Proxy-Authorization: Basic dXNlcjpzZWNyZXQ=\r\nProxy-Connection: keep-alive\r\n\r\n").toByteArray())
            val response = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            done.get(4, TimeUnit.SECONDS)
            assertTrue(response, response.endsWith("OK"))
        }
    }

    @Test fun authenticatedHttpConnectRelaysBytesThroughAuthenticatedUpstream() {
        start(auth = true)
        val done = upstream(auth = true) { socket ->
            val data = ByteArray(4); socket.getInputStream().readFully(data)
            assertEquals("ping", data.toString(Charsets.UTF_8))
            socket.getOutputStream().write("pong".toByteArray())
        }
        client().use { socket ->
            val token = Base64.getEncoder().encodeToString("user:secret".toByteArray())
            socket.getOutputStream().write("CONNECT example.org:443 HTTP/1.1\r\nHost: example.org:443\r\nProxy-Authorization: basic $token\r\n\r\n".toByteArray())
            assertTrue(headers(socket.getInputStream()).startsWith("HTTP/1.1 200 "))
            socket.getOutputStream().write("ping".toByteArray())
            val data = ByteArray(4); socket.getInputStream().readFully(data)
            assertEquals("pong", data.toString(Charsets.UTF_8))
            done.get(4, TimeUnit.SECONDS)
        }
    }

    @Test fun httpWithoutCredentialsIsRejectedBeforeUpstreamConnect() {
        start(auth = true)
        client().use { socket ->
            socket.getOutputStream().write("CONNECT example.org:443 HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(headers(socket.getInputStream()).startsWith("HTTP/1.1 407 "))
        }
    }

    @Test fun socksRejectsMethodsTheClientDidNotOffer() {
        start()
        client().use { socket ->
            socket.getOutputStream().write(byteArrayOf(5, 1, 2))
            assertEquals(5, socket.getInputStream().read())
            assertEquals(255, socket.getInputStream().read())
        }
    }

    @Test fun invalidAuthenticationCannotStartAnUnprotectedLanListener() {
        assertFalse(HotspotProxyBridge.start(bridgePort, backend.localPort, true, "user", ""))
        assertFalse(HotspotProxyBridge.running)
    }

    @Test fun incorrectSocksPasswordIsRejected() {
        start(auth = true)
        client().use { socket ->
            socket.getOutputStream().write(byteArrayOf(5, 1, 2))
            assertEquals(5, socket.getInputStream().read()); assertEquals(2, socket.getInputStream().read())
            socket.getOutputStream().write(byteArrayOf(1, 4, 117, 115, 101, 114, 3, 98, 97, 100))
            assertEquals(1, socket.getInputStream().read()); assertEquals(1, socket.getInputStream().read())
        }
    }

    @Test fun healthProbeAuthenticatesWithoutUsingTheGlobalAuthenticator() {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val previous = java.net.Authenticator::class.java.getMethod("getDefault").invoke(null) as? java.net.Authenticator
        java.net.Authenticator.setDefault(object : java.net.Authenticator() {
            override fun getPasswordAuthentication(): java.net.PasswordAuthentication? { calls.incrementAndGet(); return null }
        })
        try {
            val done = upstream(auth = true) { socket ->
                val data = ByteArray(4); socket.getInputStream().readFully(data)
                socket.getOutputStream().write(data)
            }
            val session = io.github.libreroute.util.LocalSocksSession.SessionInfo(backend.localPort, "user", "secret", true)
            ProxyConnectivityProbe.connect(session, "example.org", 443, 3000).use { socket ->
                socket.getOutputStream().write("ping".toByteArray())
                val data = ByteArray(4); socket.getInputStream().readFully(data)
                assertEquals("ping", data.toString(Charsets.UTF_8))
                done.get(4, TimeUnit.SECONDS)
            }
            assertEquals(0, calls.get())
        } finally { java.net.Authenticator.setDefault(previous) }
    }

    @Test fun socksAuthenticationAndBidirectionalTransferWork() {
        start(auth = true)
        val done = upstream(auth = true) { socket ->
            val data = ByteArray(4); socket.getInputStream().readFully(data)
            socket.getOutputStream().write(data)
        }
        client().use { socket ->
            socksConnect(socket, auth = true)
            socket.getOutputStream().write("echo".toByteArray())
            val data = ByteArray(4); socket.getInputStream().readFully(data)
            assertEquals("echo", data.toString(Charsets.UTF_8))
            done.get(4, TimeUnit.SECONDS)
        }
    }

    @Test fun uploadHalfCloseDoesNotTruncateTheResponse() {
        start()
        val expected = ByteArray(128 * 1024) { (it % 251).toByte() }
        val done = upstream { socket ->
            assertEquals("upload", socket.getInputStream().readBytes().toString(Charsets.UTF_8))
            socket.getOutputStream().write(expected)
        }
        client().use { socket ->
            socksConnect(socket)
            socket.getOutputStream().write("upload".toByteArray())
            socket.shutdownOutput()
            assertArrayEquals(expected, socket.getInputStream().readBytes())
            done.get(4, TimeUnit.SECONDS)
        }
    }

    @Test fun stopClosesListenersAndActiveRelays() {
        start()
        val done = upstream { socket -> assertEquals(-1, socket.getInputStream().read()) }
        client().use { socket ->
            socksConnect(socket)
            HotspotProxyBridge.stop()
            assertFalse(HotspotProxyBridge.running)
            assertEquals(-1, socket.getInputStream().read())
            done.get(4, TimeUnit.SECONDS)
        }
        ServerSocket(bridgePort).close()
    }

    private fun socksConnect(socket: Socket, auth: Boolean = false) {
        val output = socket.getOutputStream(); val input = socket.getInputStream()
        output.write(byteArrayOf(5, 1, if (auth) 2 else 0)); output.flush()
        assertEquals(5, input.read()); assertEquals(if (auth) 2 else 0, input.read())
        if (auth) {
            output.write(byteArrayOf(1, 4)); output.write("user".toByteArray()); output.write(6); output.write("secret".toByteArray()); output.flush()
            assertEquals(1, input.read()); assertEquals(0, input.read())
        }
        output.write(byteArrayOf(5, 1, 0, 3, 11)); output.write("example.org".toByteArray()); output.write(byteArrayOf(1, -69)); output.flush()
        val reply = ByteArray(10); input.readFully(reply)
        assertEquals(0, reply[1].toInt())
    }

    private fun headers(input: InputStream): String {
        val text = StringBuilder()
        while (!text.endsWith("\r\n\r\n")) {
            val value = input.read()
            check(value >= 0) { "EOF before HTTP headers: $text" }
            text.append(value.toChar())
        }
        return text.toString()
    }

    private fun InputStream.readFully(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val size = read(bytes, offset, bytes.size - offset)
            check(size > 0) { "Unexpected EOF" }
            offset += size
        }
    }
}
