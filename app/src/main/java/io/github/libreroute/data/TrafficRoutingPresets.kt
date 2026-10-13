package io.github.libreroute.data

/** Additive defaults: explicit assignments always win over the preset. */
object TrafficRoutingPresets {
    val RUSSIAN_ZONES = setOf("ru", "xn--p1ai", "su")

    fun russianSites(current: SiteRoutingState, domains: Set<String>, profiles: ZapretProfileState): SiteRoutingState {
        current.validate(profiles)
        val existing = current.rules.map { it.domain }.toSet()
        val added = (domains + RUSSIAN_ZONES).map(SiteRule::clean).distinct().sorted()
            .filterNot { it in existing }.map { SiteRule(it, "direct") }
        return current.copy(enabled = true, rules = current.rules + added).also { it.validate(profiles) }
    }

    fun russianApps(current: AppRoutingState, packages: Set<String>, installedUids: Map<String, Int>,
                    profiles: ZapretProfileState): AppRoutingState {
        current.validate(profiles)
        val assigned = current.rules.associateBy { it.packageName }
        val byUid = appRulesByUid(current, installedUids)
        val added = packages.sorted().mapNotNull { key ->
            val uid = installedUids[key] ?: return@mapNotNull null
            if (key in assigned) return@mapNotNull null
            // An existing tunnel/zapret rule for a shared UID must not be changed indirectly.
            val previous = byUid[uid]
            if (previous != null && previous.action != "direct") return@mapNotNull null
            AppRule(key, "direct")
        }
        return current.copy(enabled = true, rules = current.rules + added).also {
            it.validate(profiles)
            appRulesByUid(it, installedUids)
        }
    }
}
