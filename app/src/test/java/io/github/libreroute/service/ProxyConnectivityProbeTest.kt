package io.github.libreroute.service

import io.github.libreroute.util.LocalSocksSession
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.security.KeyStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Real TCP and TLS, test-only localhost certificate, no Internet or provider credentials. */
class ProxyConnectivityProbeTest {
    companion object {
        private val context: SSLContext = run {
            val store = KeyStore.getInstance("PKCS12")
            ProxyConnectivityProbeTest::class.java.getResourceAsStream("/connectivity-test.p12").use {
                store.load(it, "probe-test".toCharArray())
            }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(store, "probe-test".toCharArray())
            }
            val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
            SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        }
    }

    private val listener = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()
    private val session get() = LocalSocksSession.SessionInfo(listener.localPort, "", "", false)
    private fun endpoint(host: String = "localhost", code: Int = 204, body: String? = null) =
        ProxyConnectivityProbe.Endpoint(URI("https://$host/check"), code, body)

    @After fun close() {
        listener.close()
        sockets.forEach { runCatching { it.close() } }
    }

    private fun backend(count: Int = 1, action: (Socket) -> Unit): List<CompletableFuture<Unit>> =
        (1..count).map {
            val done = CompletableFuture<Unit>()
            thread(isDaemon = true, name = "ProbeFixture") {
                try {
                    listener.accept().use { socket ->
                        sockets += socket
                        socket.soTimeout = 5000
                        action(socket)
                    }
                    done.complete(Unit)
                } catch (error: Throwable) { done.completeExceptionally(error) }
            }
            done
        }

    private fun socks(socket: Socket): String {
        val input = socket.getInputStream()
        assertEquals(5, input.read())
        input.bytes(input.read())
        socket.getOutputStream().write(byteArrayOf(5, 0))
        assertArrayEquals(byteArrayOf(5, 1, 0, 3), input.bytes(4))
        val host = input.bytes(input.read()).toString(Charsets.UTF_8)
        input.bytes(2)
        socket.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        return host
    }

    private fun https(socket: Socket, response: String, write: ((SSLSocket) -> Unit)? = null) {
        (context.socketFactory.createSocket(socket, "localhost", 443, false) as SSLSocket).use { tls ->
            tls.useClientMode = false
            tls.soTimeout = 5000
            tls.startHandshake()
            val input = tls.getInputStream()
            var tail = ""
            while (!tail.endsWith("\r\n\r\n")) {
                val ch = input.read()
                check(ch != -1)
                tail = (tail + ch.toChar()).takeLast(4)
            }
            if (write != null) write(tls) else {
                tls.getOutputStream().write(response.toByteArray(Charsets.US_ASCII))
                tls.getOutputStream().flush()
            }
        }
    }

    private fun probe(targets: List<ProxyConnectivityProbe.Endpoint> = listOf(endpoint()), timeout: Int = 3000,
                      factory: SSLSocketFactory = context.socketFactory) =
        ProxyConnectivityProbe.probeEndpoints(session, timeout, targets, factory)

    @Test fun verified204ReportsWholeDurationAndSeparateStages() {
        val done = backend { socket -> socks(socket); https(socket, "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\n\r\n") }
        val result = probe()
        assertTrue(result.toString(), result.available)
        assertEquals(result.totalMs, result.socksMs + result.tlsMs + result.responseMs)
        assertTrue(result.totalMs > 0)
        done.single().get(5, TimeUnit.SECONDS)
    }

    @Test fun endpointFailureDoesNotHideAnotherEndpointsSuccess() {
        val negotiated = CountDownLatch(2)
        val done = backend(2) { socket ->
            val host = socks(socket)
            negotiated.countDown()
            assertTrue(negotiated.await(2, TimeUnit.SECONDS))
            if (host == "localhost") https(socket, "HTTP/1.1 204 OK\r\n\r\n")
            else socket.close()
        }
        assertTrue(probe(listOf(endpoint("failed.invalid"), endpoint())).available)
        done.forEach { it.get(5, TimeUnit.SECONDS) }
    }

    @Test fun proxyRefusalIsBoundedAndClassifiedWithoutTls() {
        backend { socket ->
            val input = socket.getInputStream()
            input.bytes(2).also { assertEquals(5, it[0].toInt()) }
            input.bytes(1)
            socket.getOutputStream().write(byteArrayOf(5, -1))
        }
        assertEquals(ProxyConnectivityProbe.Failure.SOCKS, probe().failure)
    }

    @Test fun oneDeadlineCoversAllStalledSocksEndpointsAndClosesEverySocket() {
        val done = backend(3) { socket ->
            val input = socket.getInputStream()
            input.bytes(3)
            assertEquals(-1, input.read())
        }
        val started = System.nanoTime()
        val result = probe(List(3) { endpoint() }, timeout = 300)
        assertEquals(ProxyConnectivityProbe.Failure.TIMEOUT, result.failure)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
        done.forEach { it.get(2, TimeUnit.SECONDS) }
    }

    @Test fun stalledTlsAlsoHonorsTheOverallDeadline() {
        backend { socket -> socks(socket); while (socket.getInputStream().read() != -1) {} }
        val started = System.nanoTime()
        assertEquals(ProxyConnectivityProbe.Failure.TIMEOUT, probe(timeout = 300).failure)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1500)
    }

    @Test fun cancellingCoroutineClosesTheBlockedSocketImmediately(): Unit = runBlocking {
        val entered = CountDownLatch(1)
        val done = backend { socket ->
            socket.getInputStream().bytes(3)
            entered.countDown()
            assertEquals(-1, socket.getInputStream().read())
        }
        val job = launch(Dispatchers.IO) { runInterruptible { probe(timeout = 10_000) } }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        withTimeout(1500) { job.cancelAndJoin() }
        done.single().get(2, TimeUnit.SECONDS)
    }

    @Test fun winningEndpointClosesTheOtherEndpointsBlockedRead() {
        val negotiated = CountDownLatch(2)
        val done = backend(2) { socket ->
            val host = socks(socket)
            negotiated.countDown()
            assertTrue(negotiated.await(2, TimeUnit.SECONDS))
            if (host == "localhost") https(socket, "HTTP/1.1 204 OK\r\n\r\n")
            else {
                try { while (socket.getInputStream().read() != -1) {} }
                catch (_: java.net.SocketException) { /* Closing with unread TLS may reset TCP. */ }
            }
        }
        assertTrue(probe(listOf(endpoint("stalled.invalid"), endpoint())).available)
        done.forEach { it.get(2, TimeUnit.SECONDS) }
    }

    @Test fun tricklingHttpBytesCannotExtendTheOverallDeadline() {
        backend { socket ->
            socks(socket)
            runCatching {
                https(socket, "") { tls ->
                    for (byte in "HTTP/1.1 204 OK\r\n\r\n".toByteArray()) {
                        tls.getOutputStream().write(byte.toInt())
                        tls.getOutputStream().flush()
                        Thread.sleep(100)
                    }
                }
            }
        }
        val started = System.nanoTime()
        assertEquals(ProxyConnectivityProbe.Failure.TIMEOUT, probe(timeout = 800).failure)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1800)
    }

    @Test fun redirectsAndIncompleteHeadersAreNotConnectivitySuccess() {
        for (response in listOf("HTTP/1.1 302 Found\r\nLocation: https://portal.invalid/\r\n\r\n",
            "HTTP/1.1 204 No Content\r\n")) {
            backend { socket -> socks(socket); https(socket, response) }
            assertEquals(ProxyConnectivityProbe.Failure.HTTP, probe().failure)
        }
    }

    @Test fun portalBodyCannotImpersonateTheExpectedSuccessResponse() {
        for ((body, success) in listOf("success\n" to true, "<html>Login</html>" to false, "success\n<html>Login</html>" to false)) {
            backend { socket -> socks(socket); https(socket, "HTTP/1.1 200 OK\r\n\r\n$body") }
            assertEquals(success, probe(listOf(endpoint(code = 200, body = "success"))).available)
        }
    }

    @Test fun trustedCertificateForAnotherHostIsRejected() {
        backend { socket -> socks(socket); runCatching { https(socket, "HTTP/1.1 204 OK\r\n\r\n") } }
        assertEquals(ProxyConnectivityProbe.Failure.TLS, probe(listOf(endpoint("wrong.invalid"))).failure)
    }

    @Test fun unknownCertificateIsRejectedByTheDefaultTrustStore() {
        backend { socket -> socks(socket); runCatching { https(socket, "HTTP/1.1 204 OK\r\n\r\n") } }
        assertEquals(ProxyConnectivityProbe.Failure.TLS,
            probe(factory = SSLSocketFactory.getDefault() as SSLSocketFactory).failure)
    }

    private fun InputStream.bytes(count: Int): ByteArray {
        check(count in 0..1024)
        val bytes = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val read = read(bytes, offset, count - offset)
            check(read > 0)
            offset += read
        }
        return bytes
    }
}
