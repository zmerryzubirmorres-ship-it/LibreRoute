package io.github.libreroute.data

import io.github.libreroute.util.ConfigBackupManager
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AppRoutingTest {
    @Test fun standaloneRuntimeKeepsDirectTunnelAndSeparateProfileAssignments() {
        val saved = ZapretProfileState().save(ZapretProfile("video", "Видео")).save(ZapretProfile("social", "Соцсети"))
        val apps = AppRoutingState(true, listOf(AppRule("org.example.direct", "direct"),
            AppRule("org.example.common", "tunnel"), AppRule("org.example.video", "zapret", "video"),
            AppRule("org.example.social", "zapret", "social")))
        val sites = SiteRoutingState(true, listOf(SiteRule("direct.example", "direct"),
            SiteRule("common.example", "tunnel"), SiteRule("social.example", "zapret", "social")), defaultTunnel = false)
        val runtime = nativeTrafficRoutes(sites, apps, saved, mapOf("video" to 11001, "social" to 11002), "@libreroute_app_test")
        assertEquals(sites.rules, runtime.rules)
        assertEquals("direct", runtime.defaultAction)
        assertEquals(listOf("social", "video"), runtime.profiles.map { it.id })
        assertEquals(setOf("127.0.0.1:11001", "127.0.0.1:11002"), runtime.profiles.map { it.address }.toSet())
        assertEquals("@libreroute_app_test", runtime.appSocket)
        assertEquals("tunnel", nativeTrafficRoutes(sites.copy(defaultTunnel = true), apps, saved,
            mapOf("video" to 11001, "social" to 11002), "@libreroute_app_test").defaultAction)
    }
    private val profiles = ZapretProfileState().save(ZapretProfile("custom", "Мой профиль"))

    @Test fun appRulesReplaceWithoutDuplicatesAndValidateProfiles() {
        val state = AppRoutingState(true).save(AppRule("org.example.app", "direct"), profiles)
            .save(AppRule("org.example.app", "zapret", "custom"), profiles)
        assertTrue(state.enabled)
        assertEquals(listOf(AppRule("org.example.app", "zapret", "custom")), state.rules)
        for (rule in listOf(AppRule("bad", "direct"), AppRule("org.example.app", "invalid"),
            AppRule("org.example.app", "zapret", "missing"), AppRule("org.example.app", "direct", "custom"))) {
            assertThrows(IllegalArgumentException::class.java) { rule.validate(profiles) }
        }
    }

    @Test fun sharedUidsCannotDisagreeButUninstalledAppsArePreserved() {
        val a = AppRule("org.example.a", "direct")
        val b = AppRule("org.example.b", "tunnel")
        val uids = mapOf(a.packageName to 10123, b.packageName to 10123)
        assertThrows(IllegalArgumentException::class.java) { appRulesByUid(AppRoutingState(rules = listOf(a, b)), uids) }
        assertEquals(1, appRulesByUid(AppRoutingState(rules = listOf(a, b.copy(action = "direct"))), uids).size)
        assertEquals(emptyMap<Int, AppRule>(), appRulesByUid(AppRoutingState(rules = listOf(a)), emptyMap()))
    }

    @Test fun runtimeUsesUnionOfEnabledAppAndSiteProfiles() {
        val apps = AppRoutingState(true, listOf(AppRule("org.example.app", "zapret", "custom")))
        val sites = SiteRoutingState(false, listOf(SiteRule("unused.example", "zapret", "missing")))
        // Invalid unused profile references are still rejected before launch.
        assertThrows(IllegalArgumentException::class.java) { nativeTrafficRoutes(sites, apps, profiles, mapOf("custom" to 10000), "@libreroute_app_test") }
        val runtime = nativeTrafficRoutes(SiteRoutingState(), apps, profiles, mapOf("custom" to 10000), "@libreroute_app_test")
        assertFalse(runtime.siteRulesEnabled)
        assertTrue(runtime.rules.isEmpty())
        assertEquals(listOf("custom"), runtime.profiles.map { it.id })
        assertEquals("@libreroute_app_test", runtime.appSocket)
        val enabledSites = SiteRoutingState(true, listOf(SiteRule("a.example", "zapret", "custom")))
        assertEquals(1, nativeTrafficRoutes(enabledSites, apps, profiles, mapOf("custom" to 10000), "@libreroute_app_test").profiles.size)
        assertThrows(IllegalArgumentException::class.java) { nativeTrafficRoutes(SiteRoutingState(), apps, profiles, emptyMap(), "@libreroute_app_test") }
    }

    @Test fun backupHasPortableRulesAndOldBackupLeavesThemAbsent() {
        val state = AppRoutingState(true, listOf(AppRule("org.example.app", "zapret", "custom")))
        val json = Json { encodeDefaults = true }
        val original = ConfigBackupManager.LibreRouteBackup(appRouting = state, zapretProfiles = profiles)
        val restored = json.decodeFromString<ConfigBackupManager.LibreRouteBackup>(json.encodeToString(original))
        assertEquals(state, restored.appRouting)
        restored.appRouting!!.validate(restored.zapretProfiles!!)
        assertNull(json.decodeFromString<ConfigBackupManager.LibreRouteBackup>("{\"version\":2}").appRouting)
        assertFalse(json.encodeToString(state).contains("app_socket"))
        assertFalse(json.encodeToString(state).contains("10123"))
    }
}
