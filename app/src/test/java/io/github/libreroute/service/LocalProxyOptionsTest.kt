package io.github.libreroute.service

import io.github.libreroute.util.LocalSocksSession
import org.junit.Assert.*
import org.junit.Test

class LocalProxyOptionsTest {
    private val protected = LocalSocksSession.SessionInfo(32123, "user", "secret", true)

    @Test fun vpnLaunchIncludesTheSameCredentialsAsTheActiveSession() {
        val options = localProxyOptions(listOf("--inbound=tun", "--socks5", ":1080", "--url", "https://example.org"), protected)
        assertEquals(listOf("--inbound=tun", "--url", "https://example.org", "--socks5", "127.0.0.1:32123",
            "--socks5-user", "user", "--socks5-pass", "secret"), options)
    }

    @Test fun proxyOnlyRemovesTunRoutingOptionsInAllFlagSpellings() {
        val options = localProxyOptions(listOf("-inbound=tun", "--tun-socket", "@old", "-tun-mtu=1400", "--tun",
            "--domain-rules-file", "/private/domains", "-ip-rules-file=/private/ips", "--site-routes-file=/private/routes",
            "--split-mode", "bypass", "-domain-mode=proxy", "--socks5-mode", "--peer-health", "--doh", "--transport=jazz"), protected, true)
        assertEquals(listOf("--peer-health", "--doh", "--transport=jazz", "--inbound=socks5", "--socks5", "127.0.0.1:32123",
            "--socks5-user", "user", "--socks5-pass", "secret"), options)
    }

    @Test fun disabledAuthenticationRemovesStaleCredentialsIncludingDashPrefixedValues() {
        val options = localProxyOptions(listOf("-socks5=:1080", "-socks5-user=old", "--socks5-pass", "-old-password",
            "--socks5-user", "old-user", "-socks5-pass=old-pass"), protected.copy(isAuthEnabled = false))
        assertEquals(listOf("--socks5", "127.0.0.1:32123"), options)
    }

    @Test fun credentialsAreLimitedByUtf8BytesRatherThanCharacterCount() {
        assertTrue(localProxyOptions(emptyList(), protected.copy(password = "я".repeat(127))).isNotEmpty())
        assertThrows(IllegalArgumentException::class.java) { localProxyOptions(emptyList(), protected.copy(password = "я".repeat(128))) }
        assertThrows(IllegalArgumentException::class.java) { localProxyOptions(emptyList(), protected.copy(password = "")) }
    }

    @Test fun sharingRequiresReadySessionWithMatchingAuthenticationAndCredentials() {
        assertFalse(ProxySharing.canShare(null, false, "", ""))
        assertFalse(ProxySharing.canShare(protected, false, "user", "secret"))
        assertFalse(ProxySharing.canShare(protected, true, "new-user", "secret"))
        assertFalse(ProxySharing.canShare(protected, true, "user", "new-pass"))
        assertTrue(ProxySharing.canShare(protected, true, "user", "secret"))
        assertTrue(ProxySharing.canShare(protected, true, "user", ""))
        assertTrue(ProxySharing.canShare(protected.copy(isAuthEnabled = false), false, "", ""))
    }

    @Test fun previousSessionCannotPublishOrClearNewSessionsReadiness() {
        val first = LocalSocksSession.generateNew(customPort = 32001)
        LocalSocksSession.markReady(first)
        assertSame(first, LocalSocksSession.getReady())
        val second = LocalSocksSession.generateNew(customPort = 32002)
        assertNull(LocalSocksSession.getReady())
        LocalSocksSession.markReady(first)
        assertNull(LocalSocksSession.getReady())
        LocalSocksSession.markReady(second)
        LocalSocksSession.clearReady(first)
        assertSame(second, LocalSocksSession.getReady())
        LocalSocksSession.clearReady(second)
        assertNull(LocalSocksSession.getReady())
    }

    @Test fun lanAddressDetectionCoversPrivateWifiAndHotspotRanges() {
        for (host in listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.43.1"))
            assertTrue(host, LocalSocksSession.isPrivateLanIpv4(host))
        for (host in listOf("172.15.0.1", "172.32.0.1", "127.0.0.1", "8.8.8.8", "10.999.0.1", "::1"))
            assertFalse(host, LocalSocksSession.isPrivateLanIpv4(host))
    }
}
