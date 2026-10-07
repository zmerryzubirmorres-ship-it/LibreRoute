package io.github.libreroute.util

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingAndBackupTest {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    // -------------------------------------------------------------------------
    // 1. IP Rules Validation (IPv4 / IPv6 / CIDR)
    // -------------------------------------------------------------------------

    @Test
    fun testValidIpv4AndCidr() {
        assertTrue(IpRulesPreferences.isValid("1.1.1.1"))
        assertTrue(IpRulesPreferences.isValid("8.8.8.8/32"))
        assertTrue(IpRulesPreferences.isValid("10.0.0.0/8"))
        assertTrue(IpRulesPreferences.isValid("172.16.0.0/12"))
        assertTrue(IpRulesPreferences.isValid("192.168.1.0/24"))
        assertTrue(IpRulesPreferences.isValid("0.0.0.0/0"))
    }

    @Test
    fun testInvalidIpv4AndCidr() {
        assertFalse(IpRulesPreferences.isValid("256.1.1.1"))
        assertFalse(IpRulesPreferences.isValid("1.2.3"))
        assertFalse(IpRulesPreferences.isValid("1.2.3.4.5"))
        assertFalse(IpRulesPreferences.isValid("1.1.1.1/33"))
        assertFalse(IpRulesPreferences.isValid("1.1.1.1/-1"))
        assertFalse(IpRulesPreferences.isValid("1.1.1.1/abc"))
        assertFalse(IpRulesPreferences.isValid("1.1.1.1//32"))
        assertFalse(IpRulesPreferences.isValid("example.com"))
        assertFalse(IpRulesPreferences.isValid("https://1.1.1.1"))
        assertFalse(IpRulesPreferences.isValid(""))
        assertFalse(IpRulesPreferences.isValid("   "))
    }

    @Test
    fun testValidIpv6AndCidr() {
        assertTrue(IpRulesPreferences.isValid("::1"))
        assertTrue(IpRulesPreferences.isValid("::/0"))
        assertTrue(IpRulesPreferences.isValid("2001:db8::1"))
        assertTrue(IpRulesPreferences.isValid("2001:db8::/32"))
        assertTrue(IpRulesPreferences.isValid("fe80::1/64"))
    }

    @Test
    fun testInvalidIpv6AndCidr() {
        assertFalse(IpRulesPreferences.isValid("2001:xyz::1"))
        assertFalse(IpRulesPreferences.isValid("::1/129"))
        assertFalse(IpRulesPreferences.isValid("::1/-5"))
        assertFalse(IpRulesPreferences.isValid("::1/xyz"))
    }

    // -------------------------------------------------------------------------
    // 2. Domain Rules Validation and Cleaning
    // -------------------------------------------------------------------------

    @Test
    fun testDomainCleaning() {
        assertEquals("example.com", DomainRulesPreferences.cleanDomain("https://example.com/"))
        assertEquals("sub.example.com", DomainRulesPreferences.cleanDomain("HTTP://Sub.Example.COM/"))
        assertEquals("test.org", DomainRulesPreferences.cleanDomain(".test.org."))
    }

    @Test
    fun testValidDomains() {
        assertTrue(DomainRulesPreferences.isValidDomain("example.com"))
        assertTrue(DomainRulesPreferences.isValidDomain("sub.domain.co.uk"))
        assertTrue(DomainRulesPreferences.isValidDomain("youtube.com"))
        assertTrue(DomainRulesPreferences.isValidDomain("googlevideo.com"))
        assertTrue(DomainRulesPreferences.isValidDomain("рф"))
        assertTrue(DomainRulesPreferences.isValidDomain("яндекс.рф"))
    }

    @Test
    fun testInvalidDomains() {
        assertFalse(DomainRulesPreferences.isValidDomain(""))
        assertFalse(DomainRulesPreferences.isValidDomain("   "))
        assertFalse(DomainRulesPreferences.isValidDomain("example..com"))
        assertFalse(DomainRulesPreferences.isValidDomain("-bad.com"))
        assertFalse(DomainRulesPreferences.isValidDomain("bad-.com"))
        assertFalse(DomainRulesPreferences.isValidDomain("example.com/path"))
        assertFalse(DomainRulesPreferences.isValidDomain("example.com:443"))
        assertFalse(DomainRulesPreferences.isValidDomain("invalid domain.com"))
    }

    // -------------------------------------------------------------------------
    // 3. Backup Compatibility: v1 and v2 Schemas
    // -------------------------------------------------------------------------

    @Test
    fun testBackupV1Compatibility() {
        // Legacy v1 backup JSON (without split tunneling fields)
        val v1Json = """
            {
                "version": 1,
                "timestamp": 1727000000000,
                "appVersion": "1.2.3",
                "tunnels": []
            }
        """.trimIndent()

        val parsed = json.decodeFromString<ConfigBackupManager.LibreRouteBackup>(v1Json)
        assertEquals(1, parsed.version)
        assertEquals(1727000000000L, parsed.timestamp)
        assertTrue(parsed.tunnels.isEmpty())
        assertTrue(parsed.ipRules.isEmpty())
        assertTrue(parsed.domains.isEmpty())
        assertTrue(parsed.bypassApps.isEmpty())
        assertTrue(parsed.proxyApps.isEmpty())
    }

    @Test
    fun testBackupV2RoundTripWithSplitRules() {
        val original = ConfigBackupManager.LibreRouteBackup(
            version = 2,
            timestamp = 1727430000000L,
            appVersion = "1.2.5",
            tunnels = emptyList(),
            splitTunnelMode = "bypass",
            splitEnabled = true,
            bypassApps = listOf("com.android.chrome", "org.telegram.messenger"),
            proxyApps = emptyList(),
            domainMode = DomainRulesPreferences.MODE_BYPASS,
            domains = listOf("gosuslugi.ru", "sberbank.ru", "yandex.ru"),
            ipRules = listOf("10.0.0.0/8", "192.168.0.0/16", "1.1.1.1/32")
        )

        val serialized = json.encodeToString(original)
        val restored = json.decodeFromString<ConfigBackupManager.LibreRouteBackup>(serialized)

        assertEquals(2, restored.version)
        assertEquals("bypass", restored.splitTunnelMode)
        assertEquals(true, restored.splitEnabled)
        assertEquals(2, restored.bypassApps.size)
        assertTrue(restored.bypassApps.contains("com.android.chrome"))
        assertEquals(3, restored.domains.size)
        assertTrue(restored.domains.contains("gosuslugi.ru"))
        assertEquals(3, restored.ipRules.size)
        assertTrue(restored.ipRules.contains("192.168.0.0/16"))
    }

    @Test
    fun testSplitTunnelSnapshotChangeDetection() {
        fun makeSnapshot(
            isEnabled: Boolean,
            mode: String,
            bypassApps: Set<String>,
            proxyApps: Set<String>,
            domainMode: Int,
            domains: Set<String>,
            ipRules: Set<String>
        ): String = "${isEnabled}:${mode}:${bypassApps.size}:${bypassApps.hashCode()}:" +
                "${proxyApps.size}:${proxyApps.hashCode()}:${domainMode}:" +
                "${domains.size}:${domains.hashCode()}:${ipRules.size}:${ipRules.hashCode()}"

        val base = makeSnapshot(
            isEnabled = true,
            mode = "bypass",
            bypassApps = setOf("com.android.chrome", "org.telegram.messenger"),
            proxyApps = emptySet(),
            domainMode = DomainRulesPreferences.MODE_BYPASS,
            domains = setOf("gosuslugi.ru", "sberbank.ru"),
            ipRules = setOf("10.0.0.0/8")
        )

        // 1. Same config in different set order produces identical snapshot
        val sameDiffOrder = makeSnapshot(
            isEnabled = true,
            mode = "bypass",
            bypassApps = setOf("org.telegram.messenger", "com.android.chrome"),
            proxyApps = emptySet(),
            domainMode = DomainRulesPreferences.MODE_BYPASS,
            domains = setOf("sberbank.ru", "gosuslugi.ru"),
            ipRules = setOf("10.0.0.0/8")
        )
        assertEquals(base, sameDiffOrder)

        // 2. Toggling isEnabled changes snapshot
        val disabled = makeSnapshot(
            isEnabled = false,
            mode = "bypass",
            bypassApps = setOf("com.android.chrome", "org.telegram.messenger"),
            proxyApps = emptySet(),
            domainMode = DomainRulesPreferences.MODE_BYPASS,
            domains = setOf("gosuslugi.ru", "sberbank.ru"),
            ipRules = setOf("10.0.0.0/8")
        )
        org.junit.Assert.assertNotEquals(base, disabled)

        // 3. Changing mode changes snapshot
        val proxyMode = makeSnapshot(
            isEnabled = true,
            mode = "proxy",
            bypassApps = setOf("com.android.chrome", "org.telegram.messenger"),
            proxyApps = emptySet(),
            domainMode = DomainRulesPreferences.MODE_BYPASS,
            domains = setOf("gosuslugi.ru", "sberbank.ru"),
            ipRules = setOf("10.0.0.0/8")
        )
        org.junit.Assert.assertNotEquals(base, proxyMode)

        // 4. Adding/removing an app changes snapshot
        val moreApps = makeSnapshot(
            isEnabled = true,
            mode = "bypass",
            bypassApps = setOf("com.android.chrome", "org.telegram.messenger", "ru.yandex.searchplugin"),
            proxyApps = emptySet(),
            domainMode = DomainRulesPreferences.MODE_BYPASS,
            domains = setOf("gosuslugi.ru", "sberbank.ru"),
            ipRules = setOf("10.0.0.0/8")
        )
        org.junit.Assert.assertNotEquals(base, moreApps)

        // 5. Adding domain changes snapshot
        val moreDomains = makeSnapshot(
            isEnabled = true,
            mode = "bypass",
            bypassApps = setOf("com.android.chrome", "org.telegram.messenger"),
            proxyApps = emptySet(),
            domainMode = DomainRulesPreferences.MODE_BYPASS,
            domains = setOf("gosuslugi.ru", "sberbank.ru", "yandex.ru"),
            ipRules = setOf("10.0.0.0/8")
        )
        org.junit.Assert.assertNotEquals(base, moreDomains)

        // 6. Adding IP rule changes snapshot
        val moreIps = makeSnapshot(
            isEnabled = true,
            mode = "bypass",
            bypassApps = setOf("com.android.chrome", "org.telegram.messenger"),
            proxyApps = emptySet(),
            domainMode = DomainRulesPreferences.MODE_BYPASS,
            domains = setOf("gosuslugi.ru", "sberbank.ru"),
            ipRules = setOf("10.0.0.0/8", "192.168.1.1/32")
        )
        org.junit.Assert.assertNotEquals(base, moreIps)
    }
}

