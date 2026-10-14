package io.github.libreroute.service

import io.github.libreroute.util.LocalSocksSession
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Per-connection SOCKS credentials; never changes Java's global Authenticator. */
internal object ProxyConnectivityProbe {
    fun connect(session: LocalSocksSession.SessionInfo, host: String, port: Int, timeoutMs: Int): Socket {
        val socket = Socket()
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

    fun measure(session: LocalSocksSession.SessionInfo, timeoutMs: Int): Long {
        val endpoints = listOf("https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204", "https://detectportal.firefox.com/success.txt")
        for (endpoint in endpoints) {
            try {
                val uri = URI(endpoint)
                val started = System.nanoTime()
                connect(session, uri.host, 443, timeoutMs).use { socket ->
                    ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, uri.host, 443, true) as SSLSocket).use { tls ->
                        tls.soTimeout = timeoutMs
                        tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        tls.startHandshake()
                        tls.getOutputStream().write(("GET ${uri.rawPath} HTTP/1.1\r\nHost: ${uri.host}\r\n" +
                            "User-Agent: LibreRoute\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        tls.getOutputStream().flush()
                        val code = tls.getInputStream().bufferedReader(Charsets.US_ASCII).readLine()
                            ?.split(' ')?.getOrNull(1)?.toIntOrNull()
                        if (code != null && code in 200..399) return ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(1)
                    }
                }
            } catch (_: Exception) { /* Try another independently hosted endpoint. */ }
        }
        return -1
    }
}
