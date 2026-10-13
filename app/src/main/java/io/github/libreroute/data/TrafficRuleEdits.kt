package io.github.libreroute.data

/** A batch is validated in full before preferences are written. Null removes explicit app rules. */
fun AppRoutingState.assign(keys: Set<String>, action: String?, profile: String, profiles: ZapretProfileState): AppRoutingState {
    require(keys.isNotEmpty()) { "Отметьте приложения" }
    validate(profiles)
    return copy(rules = rules.filterNot { it.packageName in keys } + if (action == null) emptyList() else
        keys.sorted().map { AppRule(it, action, if (action == "zapret") profile else "") }).also { it.validate(profiles) }
}

fun SiteRoutingState.assign(keys: Set<String>, action: String, profile: String, profiles: ZapretProfileState): SiteRoutingState {
    require(keys.isNotEmpty() && keys.all { key -> rules.any { it.domain == key } }) { "Отметьте добавленные сайты" }
    validate(profiles)
    return copy(rules = rules.map { if (it.domain in keys) it.copy(action = action, profile = if (action == "zapret") profile else "") else it })
        .also { it.validate(profiles) }
}
