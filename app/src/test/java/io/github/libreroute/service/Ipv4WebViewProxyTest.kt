package io.github.libreroute.service

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Ipv4WebViewProxyTest {
    @Test fun failedProviderConnectionDoesNotCrashAndNextRequestStillWorks() {
        val uncaught = ConcurrentLinkedQueue<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught.add(error) }
        val proxy = Ipv4WebViewProxy()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val closedPort = ServerSocket(0).use { it.localPort }
            Socket("127.0.0.1", proxy.port).use { browser ->
                browser.soTimeout = 5000
                browser.getOutputStream().write("CONNECT 127.0.0.1:$closedPort HTTP/1.1\r\n\r\n".toByteArray())
                assertEquals("HTTP/1.1 502 Bad Gateway", browser.getInputStream().bufferedReader().readLine())
            }
            ServerSocket(0).use { upstream ->
                val accepted = worker.submit<String> {
                    upstream.accept().use { peer ->
                        peer.soTimeout = 5000
                        val request = peer.getInputStream().bufferedReader().readLine()
                        peer.getOutputStream().write("reply\n".toByteArray())
                        request
                    }
                }
                Socket("127.0.0.1", proxy.port).use { browser ->
                    browser.soTimeout = 5000
                    browser.getOutputStream().write("CONNECT 127.0.0.1:${upstream.localPort} HTTP/1.1\r\n\r\n".toByteArray())
                    val input = browser.getInputStream().bufferedReader()
                    assertEquals("HTTP/1.1 200 Connection Established", input.readLine())
                    assertEquals("", input.readLine())
                    browser.getOutputStream().write("request\n".toByteArray())
                    assertEquals("reply", input.readLine())
                    assertEquals("request", accepted.get(5, TimeUnit.SECONDS))
                }
            }
            assertTrue("Provider failure escaped the proxy worker", uncaught.isEmpty())
        } finally {
            proxy.close()
            worker.shutdownNow()
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
