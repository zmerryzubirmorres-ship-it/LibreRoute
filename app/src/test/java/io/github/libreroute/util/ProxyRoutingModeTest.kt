package io.github.libreroute.util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ProxyRoutingModeTest {
    @Test fun autonomousModeDoesNotChangeSavedServerChoice() {
        for (saved in ProxyRoutingMode.entries) {
            assertEquals(ProxyRoutingMode.RULES, effectiveProxyRoutingMode(saved, true))
            assertEquals(saved, effectiveProxyRoutingMode(saved, false))
        }
    }
    @Test fun freshInstallationUpgradeAndAmbiguousHistory() {
        assertEquals(ProxyRoutingMode.RULES, initialProxyRoutingMode(false, 10, 10))
        assertEquals(ProxyRoutingMode.ALL_TUNNEL, initialProxyRoutingMode(false, 10, 20))
        assertEquals(ProxyRoutingMode.ALL_TUNNEL, initialProxyRoutingMode(true, 10, 10))
        assertEquals(ProxyRoutingMode.ALL_TUNNEL, initialProxyRoutingMode(false, null, null))
        assertEquals(ProxyRoutingMode.ALL_TUNNEL, initialProxyRoutingMode(false, 0, 0))
    }
    @Test fun oldAndNewBackupsPreserveCompatibleSemantics() {
        val old = Json.decodeFromString<ConfigBackupManager.LibreRouteBackup>("{\"appVersion\":\"old\"}")
        assertEquals(ProxyRoutingMode.ALL_TUNNEL, old.proxyRoutingMode)
        for (mode in ProxyRoutingMode.entries) {
            val backup = old.copy(proxyRoutingMode = mode)
            assertEquals(mode, Json.decodeFromString<ConfigBackupManager.LibreRouteBackup>(Json.encodeToString(backup)).proxyRoutingMode)
        }
    }
}
