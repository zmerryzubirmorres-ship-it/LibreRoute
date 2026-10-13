package io.github.libreroute.data

import org.junit.Assert.*
import org.junit.Test

class TrafficRuleEditsTest {
    private val profiles = ZapretProfileState().save(ZapretProfile("youtube", "YouTube")).save(ZapretProfile("instagram", "Instagram", split = "1"))

    @Test fun differentAppsKeepIndependentNamedProfilesAcrossBatchEdits() {
        val state = AppRoutingState(true)
            .assign(setOf("com.google.android.youtube"), "zapret", "youtube", profiles)
            .assign(setOf("com.instagram.android"), "zapret", "instagram", profiles)
            .assign(setOf("org.example.browser", "org.example.mail"), "tunnel", "", profiles)
        val byApp = state.rules.associateBy { it.packageName }
        assertEquals("youtube", byApp.getValue("com.google.android.youtube").profile)
        assertEquals("instagram", byApp.getValue("com.instagram.android").profile)
        assertEquals("tunnel", byApp.getValue("org.example.browser").action)
        assertTrue(state.enabled)
    }

    @Test fun movingAppsReplacesTheirRulesAndClearsOldProfileReferences() {
        val keys = setOf("org.example.a", "org.example.b")
        val state = AppRoutingState().assign(keys, "zapret", "youtube", profiles).assign(keys, "direct", "youtube", profiles)
        assertEquals(2, state.rules.size)
        assertTrue(state.rules.all { it.action == "direct" && it.profile.isEmpty() })
        assertFalse(state.enabled)
        assertTrue(state.assign(keys, null, "", profiles).rules.isEmpty())
    }

    @Test fun invalidBatchDoesNotAlterTheSourceAndRejectsMissingProfiles() {
        val state = AppRoutingState(rules = listOf(AppRule("org.example.a", "direct")))
        assertThrows(IllegalArgumentException::class.java) { state.assign(setOf("org.example.b", "invalid"), "tunnel", "", profiles) }
        assertThrows(IllegalArgumentException::class.java) { state.assign(setOf("org.example.b"), "zapret", "missing", profiles) }
        assertEquals(listOf(AppRule("org.example.a", "direct")), state.rules)
    }

    @Test fun siteBatchChangesOnlySelectedSitesAndPreservesEnablement() {
        val state = SiteRoutingState(true, listOf(SiteRule("youtube.com", "tunnel"), SiteRule("instagram.com", "tunnel"), SiteRule("direct.example", "direct")))
        val changed = state.assign(setOf("youtube.com", "instagram.com"), "zapret", "youtube", profiles)
        assertEquals(SiteRule("direct.example", "direct"), changed.rules.last())
        assertTrue(changed.rules.take(2).all { it.profile == "youtube" })
        assertTrue(changed.enabled)
        assertThrows(IllegalArgumentException::class.java) { state.assign(setOf("missing.example"), "direct", "", profiles) }
    }
}
