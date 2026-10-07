package io.github.libreroute.service

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HotspotProxyAuthenticationTest {
    @Test fun bridgeAuthenticatesToTheProtectedLocalCoreBeforeConnect() {
        val input = ByteArrayInputStream(byteArrayOf(5, 2, 1, 0, 5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        val output = ByteArrayOutputStream()
        assertTrue(HotspotProxyBridge.socks5Connect(input, output, "example.org", 443, "user", "secret"))
        val sent = output.toByteArray()
        assertArrayEquals(byteArrayOf(5, 2, 0, 2, 1, 4, 117, 115, 101, 114, 6, 115, 101, 99, 114, 101, 116), sent.copyOf(17))
        assertEquals(5, sent[17].toInt()); assertEquals(1, sent[18].toInt())
    }

    @Test fun failedUpstreamAuthenticationNeverSendsConnectOrDestination() {
        val input = ByteArrayInputStream(byteArrayOf(5, 2, 1, 1))
        val output = ByteArrayOutputStream()
        assertFalse(HotspotProxyBridge.socks5Connect(input, output, "example.org", 443, "user", "secret"))
        assertEquals(17, output.size())
    }

    @Test fun tunListenerWithoutAuthenticationStillAcceptsBridgeSessionCredentials() {
        val input = ByteArrayInputStream(byteArrayOf(5, 0, 5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        val output = ByteArrayOutputStream()
        assertTrue(HotspotProxyBridge.socks5Connect(input, output, "example.org", 443, "user", "secret"))
        assertEquals(5, output.toByteArray()[4].toInt())
    }
}
