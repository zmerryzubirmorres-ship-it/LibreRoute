package io.github.libreroute.data

import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class RoutingDefaultsTest {
    private fun bundled(): RoutingDefaults = Json.decodeFromString(
        File("src/main/assets/routing-defaults.json").readText(Charsets.UTF_8))

    @Test fun bundledDevAssignmentsAreValidAndProduceRuntimeRoutes() {
        val defaults = bundled().also { it.validate() }
        val profiles = defaults.profiles ?: ZapretProfileState()
        assertTrue(defaults.apps.enabled)
        assertTrue(defaults.apps.directRuPackages)
        assertTrue(defaults.sites.enabled)
        assertFalse(defaults.sites.defaultTunnel)
        assertEquals(107, defaults.apps.rules.size)
        assertEquals(646, defaults.sites.rules.size)
        assertEquals("tunnel", defaults.apps.rules.single { it.packageName == "com.openai.chatgpt" }.action)
        assertEquals(AppRule("com.google.android.youtube", "zapret", "standard"),
            defaults.apps.rules.single { it.packageName == "com.google.android.youtube" })
        assertTrue(defaults.sites.rules.any { it.domain == "ru" && it.action == "direct" })
        val runtime = nativeTrafficRoutes(defaults.sites, defaults.apps, profiles,
            mapOf("standard" to 12345), "@libreroute_app_defaults")
        assertEquals("direct", runtime.defaultAction)
        assertEquals(listOf("standard"), runtime.profiles.map { it.id })
    }

    @Test fun russianPackageDefaultIncludesNewAppsAndKeepsExplicitExceptions() {
        val state = AppRoutingState(true, listOf(AppRule("ru.example.video", "zapret", "standard"),
            AppRule("com.example.shared", "tunnel")), directRuPackages = true)
        val uids = mapOf("ru.example.video" to 10001, "ru.example.bank" to 10002,
            "ru.example.shared" to 10003, "com.example.shared" to 10003,
            "com.ru.example" to 10004, "rude.example.app" to 10005)
        val once = state.withPackageDefaults(uids)
        assertEquals(state.rules + AppRule("ru.example.bank", "direct"), once.rules)
        assertEquals(once, once.withPackageDefaults(uids))
        val runtime = appRulesByUid(once, uids)
        assertEquals("zapret", runtime[10001]?.action)
        assertEquals("direct", runtime[10002]?.action)
        assertEquals("tunnel", runtime[10003]?.action)
        assertNull(runtime[10004])
        assertNull(runtime[10005])
        assertEquals("direct", once.withPackageDefaults(uids + ("ru.example.newapp" to 10006))
            .rules.single { it.packageName == "ru.example.newapp" }.action)
    }

    @Test fun existingStatesKeepTheirSavedPolicyAndNewFlagSurvivesSerialization() {
        val oldApps = Json.decodeFromString<AppRoutingState>("""{"enabled":false,"rules":[]}""")
        assertFalse(oldApps.directRuPackages)
        assertSame(oldApps, oldApps.withPackageDefaults(mapOf("ru.example.app" to 10001)))
        // Missing fields in an old saved configuration retain their previous meaning.
        assertTrue(Json.decodeFromString<SiteRoutingState>("""{"enabled":true,"rules":[]}""").defaultTunnel)
        val defaults = bundled()
        assertEquals(defaults, Json.decodeFromString<RoutingDefaults>(Json.encodeToString(defaults)))
    }
}
