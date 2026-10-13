package io.github.libreroute.data

import org.junit.Assert.*
import org.junit.Test

class TrafficRoutingPresetsTest {
    private val profiles = ZapretProfileState().save(ZapretProfile("youtube", "YouTube"))

    @Test fun russianZonesAndServicesAreDirectWithNoCatchAllRule() {
        val state = TrafficRoutingPresets.russianSites(SiteRoutingState(), setOf("vk.com", "рф", "yastatic.net"), profiles)
        assertTrue(state.enabled)
        assertEquals(setOf("ru", "xn--p1ai", "su", "vk.com", "yastatic.net"), state.rules.map { it.domain }.toSet())
        assertTrue(state.rules.all { it.action == "direct" })
    }

    @Test fun explicitSiteExceptionsAndProfilesSurviveRepeatedPresetApplication() {
        val state = SiteRoutingState(rules = listOf(SiteRule("ru", "tunnel"), SiteRule("youtube.ru", "zapret", "youtube")))
        val once = TrafficRoutingPresets.russianSites(state, setOf("youtube.ru", "vk.com"), profiles)
        val twice = TrafficRoutingPresets.russianSites(once, setOf("youtube.ru", "vk.com"), profiles)
        assertEquals(once, twice)
        assertEquals(state.rules, once.rules.take(2))
        assertEquals("direct", once.rules.first { it.domain == "vk.com" }.action)
    }

    @Test fun onlyInstalledListedAppsAreAddedAndExistingAssignmentsWin() {
        val current = AppRoutingState(rules = listOf(AppRule("ru.example.app", "zapret", "youtube")))
        val packages = setOf("ru.example.app", "ru.example.bank", "ru.example.missing")
        val changed = TrafficRoutingPresets.russianApps(current, packages,
            mapOf("ru.example.app" to 10, "ru.example.bank" to 11, "com.example.foreign" to 12), profiles)
        assertTrue(changed.enabled)
        assertEquals(listOf(AppRule("ru.example.app", "zapret", "youtube"), AppRule("ru.example.bank", "direct")), changed.rules)
        assertEquals(changed, TrafficRoutingPresets.russianApps(changed, packages, mapOf("ru.example.app" to 10, "ru.example.bank" to 11), profiles))
    }

    @Test fun sharedUidDoesNotTurnExistingTunnelAssignmentIntoDirectAccess() {
        val current = AppRoutingState(rules = listOf(AppRule("com.example.custom", "tunnel")))
        val changed = TrafficRoutingPresets.russianApps(current, setOf("ru.example.bank", "ru.example.store"),
            mapOf("com.example.custom" to 10, "ru.example.bank" to 10, "ru.example.store" to 11), profiles)
        assertEquals(listOf(AppRule("com.example.custom", "tunnel"), AppRule("ru.example.store", "direct")), changed.rules)
    }

    @Test fun overflowingPresetIsRejectedWithoutChangingExistingRules() {
        val current = SiteRoutingState(rules = (1..998).map { SiteRule("site$it.example", "tunnel") })
        assertThrows(IllegalArgumentException::class.java) { TrafficRoutingPresets.russianSites(current, setOf("vk.com"), profiles) }
        assertFalse(current.enabled)
        assertEquals(998, current.rules.size)
    }
}
