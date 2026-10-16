package io.github.libreroute.service

import io.github.libreroute.util.LocalSocksSession
import io.github.libreroute.util.ProxyRoutingMode
import io.github.libreroute.util.ProxyRoutingStatus
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A verified HTTPS response through the tunnel. Durations are not peer RTT. */
internal object ProxyConnectivityProbe {
    enum class Failure { NOT_READY, SOCKS, TLS, HTTP, TIMEOUT }
    data class Result(
        val totalMs: Long = -1,
        val socksMs: Long = 0,
        val tlsMs: Long = 0,
        val responseMs: Long = 0,
        val failure: Failure? = null
    ) { val available: Boolean get() = totalMs >= 0 && failure == null }

    internal data class Endpoint(val uri: URI, val status: Int, val body: String? = null)
    private val endpoints = listOf(
        Endpoint(URI("https://cp.cloudflare.com/generate_204"), 204),
        Endpoint(URI("https://connectivitycheck.gstatic.com/generate_204"), 204),
        Endpoint(URI("https://detectportal.firefox.com/success.txt"), 200, "success")
    )
    private val workers = Executors.newCachedThreadPool { task ->
        Thread(task, "ConnectivityProbe").apply { isDaemon = true }
    }

    private class Attempt : AutoCloseable {
        private var closed = false
        private val sockets = mutableListOf<Socket>()
        @Synchronized fun track(socket: Socket): Socket {
            if (closed) { socket.close(); throw SocketTimeoutException() }
            sockets += socket
            return socket
        }
        @Synchronized override fun close() {
            closed = true
            sockets.forEach { runCatching { it.close() } }
            sockets.clear()
        }
    }

    fun connect(session: LocalSocksSession.SessionInfo, host: String, port: Int, timeoutMs: Int): Socket =
        connect(session, host, port, timeoutMs, Socket())

    private fun connect(session: LocalSocksSession.SessionInfo, host: String, port: Int, timeoutMs: Int, socket: Socket): Socket {
        try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress("127.0.0.1", session.port), timeoutMs)
            check(HotspotProxyBridge.socks5Connect(socket.getInputStream(), socket.getOutputStream(), host, port,
                if (session.isAuthEnabled) session.username else "",
                if (session.isAuthEnabled) session.password else "")) { "Local proxy handshake failed" }
            return socket
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }

    fun measure(session: LocalSocksSession.SessionInfo, timeoutMs: Int): Long = probe(session, timeoutMs).totalMs

    fun probe(session: LocalSocksSession.SessionInfo, timeoutMs: Int): Result {
        val probeSession = if (ProxyRoutingStatus.active?.mode == ProxyRoutingMode.RULES) {
            val port = ProxyRoutingStatus.tunnelProbePort
            if (port !in 1..65535) return Result(failure = Failure.NOT_READY)
            session.copy(port = port)
        } else session
        return probeEndpoints(probeSession, timeoutMs, endpoints, SSLSocketFactory.getDefault() as SSLSocketFactory)
    }

    /** One deadline for SOCKS, TLS, HTTP and all fallback hosts together. */
    internal fun probeEndpoints(session: LocalSocksSession.SessionInfo, timeoutMs: Int,
                                targets: List<Endpoint>, tlsFactory: SSLSocketFactory): Result {
        require(timeoutMs > 0 && targets.isNotEmpty() && targets.size <= 3)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        val completion = ExecutorCompletionService<Result>(workers)
        val attempts = targets.map { Attempt() }
        val futures = targets.mapIndexed { index, endpoint ->
            completion.submit(java.util.concurrent.Callable {
                attempt(session, endpoint, timeoutMs, tlsFactory, attempts[index])
            })
        }
        try {
            var last = Result(failure = Failure.TIMEOUT)
            repeat(targets.size) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return Result(failure = Failure.TIMEOUT)
                val finished = completion.poll(remaining, TimeUnit.NANOSECONDS)
                    ?: return Result(failure = Failure.TIMEOUT)
                last = finished.get()
                if (last.available) return last
            }
            return last
        } finally {
            // runInterruptible interrupts this wait. Closing sockets also stops
            // reads and writes that do not respond to thread interruption.
            attempts.forEach { it.close() }
            futures.forEach { it.cancel(true) }
        }
    }

    private fun attempt(session: LocalSocksSession.SessionInfo, endpoint: Endpoint, timeoutMs: Int,
                        factory: SSLSocketFactory, attempt: Attempt): Result {
        var stage = Failure.SOCKS
        val started = System.nanoTime()
        fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        try {
            val socket = attempt.track(Socket())
            connect(session, endpoint.uri.host, endpoint.uri.port.takeIf { it > 0 } ?: 443, timeoutMs, socket)
            val socksMs = elapsed()
            stage = Failure.TLS
            val tls = factory.createSocket(socket, endpoint.uri.host,
                endpoint.uri.port.takeIf { it > 0 } ?: 443, true) as SSLSocket
            attempt.track(tls)
            tls.soTimeout = timeoutMs
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            tls.startHandshake()
            val handshakeMs = elapsed()
            stage = Failure.HTTP
            // HTTP/1.0 and Connection: close keep the tiny response body unchunked.
            tls.getOutputStream().write(("GET ${endpoint.uri.rawPath} HTTP/1.0\r\nHost: ${endpoint.uri.host}\r\n" +
                "User-Agent: LibreRoute\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            tls.getOutputStream().flush()
            val input = tls.getInputStream().buffered()
            val status = line(input, 1024)
            val code = Regex("^HTTP/1\\.[01] ([0-9]{3})(?: .*)?$").matchEntire(status)?.groupValues?.get(1)?.toIntOrNull()
            if (code != endpoint.status) return Result(failure = Failure.HTTP)
            var remaining = 8192
            while (true) {
                val header = line(input, minOf(remaining, 1024))
                remaining -= header.length + 2
                check(remaining >= 0)
                if (header.isEmpty()) break
                check(!header.startsWith("Transfer-Encoding:", ignoreCase = true))
            }
            if (endpoint.body != null) {
                val body = StringBuilder()
                while (true) {
                    val ch = input.read()
                    if (ch == -1) break
                    check(body.length < 128)
                    body.append(ch.toChar())
                }
                if (body.toString().trim() != endpoint.body) return Result(failure = Failure.HTTP)
            }
            val total = elapsed().coerceAtLeast(1)
            return Result(total, socksMs, handshakeMs - socksMs, total - handshakeMs)
        } catch (_: SocketTimeoutException) {
            return Result(failure = Failure.TIMEOUT)
        } catch (_: SSLException) {
            return Result(failure = Failure.TLS)
        } catch (_: Exception) {
            return Result(failure = stage)
        } finally { attempt.close() }
    }

    private fun line(input: InputStream, limit: Int): String {
        val text = StringBuilder()
        while (text.length < limit) {
            val ch = input.read()
            check(ch != -1) { "Incomplete HTTP line" }
            if (ch == 10) return text.toString().removeSuffix("\r")
            text.append(ch.toChar())
        }
        error("Oversized HTTP line")
    }
}
