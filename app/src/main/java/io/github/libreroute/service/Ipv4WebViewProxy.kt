package io.github.libreroute.service

import android.os.Handler
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loopback CONNECT proxy used only by the Yandex handoff WebView. Chromium's
 * WebView API has no per-WebView address-family switch; resolving each CONNECT
 * to an Inet4Address keeps the browser's public Yandex identity on IPv4.
 */
internal class Ipv4WebViewProxy {
    private val running = AtomicBoolean(true)
    private val executor = Executors.newCachedThreadPool()
    private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    init {
        executor.execute {
            while (running.get()) {
                try {
                    val client = server.accept()
                    executor.execute { handle(client) }
                } catch (_: Exception) {
                    if (running.get()) continue
                }
            }
        }
    }

    fun install(callback: (Boolean) -> Unit) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            callback(false)
            return
        }
        val config = ProxyConfig.Builder()
            .addProxyRule("http://127.0.0.1:$port")
            .build()
        ProxyController.getInstance().setProxyOverride(config, Executors.newSingleThreadExecutor(), Runnable {
            Handler(Looper.getMainLooper()).post { callback(true) }
        })
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { server.close() }
        ProxyController.getInstance().clearProxyOverride(Executors.newSingleThreadExecutor(), Runnable {})
        executor.shutdownNow()
    }

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size != 3 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                writeError(socket.getOutputStream(), "501 Not Implemented")
                return
            }
            val target = parts[1]
            val colon = target.lastIndexOf(':')
            val host = if (colon > 0) target.substring(0, colon) else target
            val port = target.substringAfterLast(':', "443").toIntOrNull() ?: 443
            // Consume CONNECT headers before opening the IPv4 socket.
            while (reader.readLine()?.isNotEmpty() == true) { }
            val address = java.net.InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
                ?: run { writeError(socket.getOutputStream(), "502 Bad Gateway"); return }
            val upstream = Socket()
            upstream.use {
                it.connect(InetSocketAddress(address, port), 10_000)
                socket.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                relay(socket, it)
            }
        }
    }

    private fun relay(left: Socket, right: Socket) {
        val a = executor.submit { copy(left.getInputStream(), right.getOutputStream()) }
        val b = executor.submit { copy(right.getInputStream(), left.getOutputStream()) }
        runCatching { a.get() }
        runCatching { b.cancel(true) }
    }

    private fun copy(input: java.io.InputStream, output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) return
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun writeError(output: OutputStream, status: String) {
        output.write("HTTP/1.1 $status\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
    }
}
