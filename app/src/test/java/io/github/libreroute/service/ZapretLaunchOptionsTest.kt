package io.github.libreroute.service

import io.github.libreroute.data.ZapretProfile
import org.junit.Assert.*
import org.junit.Test

class ZapretLaunchOptionsTest {
    @Test fun standaloneLaunchKeepsTheSharedRoutingConfiguration() {
        val payload = listOf("--transport=direct-dpi", "--site-routes-file=/private/site-routes.json", "--dpi-proxy=127.0.0.1:9999")
        val options = zapretLaunchOptions(payload, ZapretProfile.DEFAULT, 11001)
        assertTrue(options.contains("--site-routes-file=/private/site-routes.json"))
        assertTrue(options.contains("--dpi-proxy=127.0.0.1:11001"))
        assertFalse(options.any { it.contains(":9999") })
    }
    @Test fun selectedProfileOverridesAllLegacyFlagsWithoutOrphaningValues() {
        val payload = listOf("--role=client", "--transport", "direct-dpi", "--dpi-proxy", "127.0.0.1:9999",
            "--dpi-block-quic=true", "--dpi-block-quic", "true", "--dpi-proxy=localhost:7777", "--doh-url=https://dns.example/query")
        assertEquals(listOf("--role=client", "--transport", "direct-dpi", "--doh-url=https://dns.example/query",
            "--dpi-proxy=127.0.0.1:1080", "--dpi-block-quic=false"),
            zapretLaunchOptions(payload, ZapretProfile.DEFAULT.copy(blockQuic = false), 1080))
    }

    @Test fun bareBooleanDoesNotConsumeTheNextCoreOption() {
        val result = zapretLaunchOptions(listOf("--dpi-block-quic", "--dns-server", "1.1.1.1"), ZapretProfile.DEFAULT, 1080)
        assertEquals(listOf("--dns-server", "1.1.1.1", "--dpi-proxy=127.0.0.1:1080", "--dpi-block-quic=true"), result)
    }
}
