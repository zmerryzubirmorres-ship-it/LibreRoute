package io.github.libreroute.data

import io.github.libreroute.util.ConfigBackupManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class SiteRoutingTest {
    private val profiles = ZapretProfileState().save(ZapretProfile("custom", "Мой профиль", blockQuic = false))
    private val json = Json { encodeDefaults = true }

    @Test fun missingDefaultKeepsTunnelAndBackupsPreserveDirectDefault() {
        val old = json.decodeFromString<SiteRoutingState>("{\"enabled\":false,\"rules\":[]}")
        assertTrue(old.defaultTunnel)
        val state = old.copy(defaultTunnel = false)
        val backup = ConfigBackupManager.LibreRouteBackup(zapretProfiles = profiles, siteRouting = state)
        val restored = json.decodeFromString<ConfigBackupManager.LibreRouteBackup>(json.encodeToString(backup))
        assertEquals(state, restored.siteRouting)
        assertFalse(restored.siteRouting!!.defaultTunnel)
    }

    @Test fun directDefaultWorksWithBothListsDisabledAndDoesNotActivateStoredRules() {
        val sites = SiteRoutingState(false, listOf(SiteRule("saved.example", "zapret", "custom")), defaultTunnel = false)
        val apps = AppRoutingState()
        val runtime = nativeTrafficRoutes(sites, apps, profiles, emptyMap(), "")
        assertEquals("direct", runtime.defaultAction)
        assertFalse(runtime.siteRulesEnabled)
        assertTrue(runtime.rules.isEmpty())
        assertTrue(runtime.profiles.isEmpty())
        assertEquals("", runtime.appSocket)
        assertEquals("tunnel", nativeTrafficRoutes(sites.copy(defaultTunnel = true), apps, profiles, emptyMap(), "").defaultAction)
    }

    @Test fun changingDefaultAndApplyingPresetPreserveExplicitAssignments() {
        val initial = SiteRoutingState(true, listOf(SiteRule("a.example", "zapret", "custom"), SiteRule("private.ru", "tunnel")))
        val direct = initial.copy(defaultTunnel = false)
        val preset = TrafficRoutingPresets.russianSites(direct, setOf("ru"), profiles)
        assertFalse(preset.defaultTunnel)
        assertEquals(initial.rules, preset.rules.take(initial.rules.size))
        assertEquals("direct", nativeSiteRoutes(preset, profiles, mapOf("custom" to 12345)).defaultAction)
    }

    @Test fun domainsAreCanonicalAndDuplicatesAreRejected() {
        val domain = SiteRule.clean("https://Пример.рф/")
        assertEquals("xn--e1afmkfd.xn--p1ai", domain)
        val rule = SiteRule(domain, "direct")
        val state = SiteRoutingState().save(rule, null, profiles)
        assertThrows(IllegalArgumentException::class.java) { state.save(rule, null, profiles) }
        assertThrows(IllegalArgumentException::class.java) { SiteRule(SiteRule.clean("https://example.org/path"), "direct").validate(profiles) }
        assertThrows(IllegalArgumentException::class.java) { SiteRule("example.org:443", "direct").validate(profiles) }
    }

    @Test fun editingAndDeletingDoNotDuplicateRulesOrToggleMode() {
        val initial = SiteRoutingState(enabled = true).save(SiteRule("example.org", "direct"), null, profiles)
        val changed = initial.save(SiteRule("new.example", "zapret", "custom"), "example.org", profiles)
        assertEquals(1, changed.rules.size)
        assertEquals("new.example", changed.rules.single().domain)
        assertTrue(changed.enabled)
        changed.copy(rules = emptyList()).validate(profiles)
    }

    @Test fun missingProfilesAndInvalidActionsAreRejectedEvenWhenDisabled() {
        for (rule in listOf(SiteRule("example.org", "zapret", "missing"), SiteRule("example.org", "direct", "custom"), SiteRule("example.org", "automatic"))) {
            assertThrows(IllegalArgumentException::class.java) { SiteRoutingState(rules = listOf(rule)).validate(profiles) }
        }
        val saved = SiteRoutingState(rules = listOf(SiteRule("example.org", "zapret", "custom")))
        assertThrows(IllegalArgumentException::class.java) { saved.validate(profiles.delete("custom")) }
    }

    @Test fun runtimeConfigHasOnlyUsedProfilesAndFreshLoopbackPorts() {
        val state = SiteRoutingState(true, listOf(SiteRule("a.example", "zapret", "custom"), SiteRule("b.example", "zapret", "custom"), SiteRule("c.example", "tunnel")))
        val config = nativeSiteRoutes(state, profiles, mapOf("custom" to 12345))
        assertEquals(listOf(SiteProxy("custom", "127.0.0.1:12345", false)), config.profiles)
        val encoded = json.encodeToString(config)
        assertEquals("1", Json.parseToJsonElement(encoded).jsonObject.getValue("version").jsonPrimitive.content)
        assertTrue(encoded.contains("\"block_quic\":false"))
        assertTrue(encoded.contains("\"action\":\"tunnel\""))
        assertThrows(IllegalArgumentException::class.java) { nativeSiteRoutes(state, profiles, emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { nativeSiteRoutes(state, profiles, mapOf("custom" to 0)) }
        assertThrows(IllegalArgumentException::class.java) { nativeSiteRoutes(state.copy(enabled = false), profiles, mapOf("custom" to 12345)) }
    }

    @Test fun backupsKeepRulesAndProfileBindingsTogether() {
        val state = SiteRoutingState(true, listOf(SiteRule("a.example", "zapret", "custom"), SiteRule("b.example", "direct")))
        val backup = ConfigBackupManager.LibreRouteBackup(zapretProfiles = profiles, siteRouting = state)
        val restored = json.decodeFromString<ConfigBackupManager.LibreRouteBackup>(json.encodeToString(backup))
        assertEquals(state, restored.siteRouting)
        restored.siteRouting!!.validate(restored.zapretProfiles!!)
        assertNull(json.decodeFromString<ConfigBackupManager.LibreRouteBackup>("{\"version\":2}").siteRouting)
        assertFalse(json.encodeToString(state).contains("127.0.0.1"))
    }
}
