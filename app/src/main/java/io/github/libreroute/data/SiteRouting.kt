package io.github.libreroute.data

import io.github.libreroute.util.DomainRulesPreferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SiteRule(val domain: String, val action: String = "tunnel", val profile: String = "") {
    fun validate(profiles: ZapretProfileState) {
        require(domain == clean(domain) && DomainRulesPreferences.isValidDomain(domain)) { "Введите домен сайта без пути и порта" }
        require(action in ACTIONS) { "Некорректное действие правила" }
        require(if (action == "zapret") profiles.profiles.any { it.id == profile } else profile.isEmpty()) { "Профиль zapret не найден" }
    }

    companion object {
        val ACTIONS = listOf("direct", "zapret", "tunnel")
        fun clean(raw: String): String {
            val domain = DomainRulesPreferences.cleanDomain(raw)
            return runCatching { java.net.IDN.toASCII(domain).lowercase(java.util.Locale.ROOT) }.getOrDefault(domain)
        }
    }
}

@Serializable
data class SiteRoutingState(val enabled: Boolean = false, val rules: List<SiteRule> = emptyList(),
    val defaultTunnel: Boolean = true) {
    fun validate(profiles: ZapretProfileState) {
        profiles.validate()
        require(rules.size <= 1000 && rules.map { it.domain }.distinct().size == rules.size) { "Повторяющиеся правила или слишком много сайтов" }
        rules.forEach { it.validate(profiles) }
    }

    fun save(rule: SiteRule, previousDomain: String?, profiles: ZapretProfileState): SiteRoutingState {
        validate(profiles)
        rule.validate(profiles)
        require(rules.none { it.domain == rule.domain && it.domain != previousDomain }) { "Для этого сайта уже есть правило" }
        return copy(rules = rules.filterNot { it.domain == previousDomain } + rule).also { it.validate(profiles) }
    }
}

@Serializable
data class SiteProxy(val id: String, val address: String, @SerialName("block_quic") val blockQuic: Boolean)

@Serializable
data class NativeSiteRoutes(val version: Int = 1, val rules: List<SiteRule>, val profiles: List<SiteProxy>,
    @SerialName("app_socket") val appSocket: String = "",
    @SerialName("site_rules_enabled") val siteRulesEnabled: Boolean = true,
    @SerialName("default_action") val defaultAction: String = "tunnel")

/** Runtime ports are never added to persisted rules or invitations. */
fun nativeSiteRoutes(state: SiteRoutingState, profiles: ZapretProfileState, ports: Map<String, Int>): NativeSiteRoutes {
    state.validate(profiles)
    require(state.enabled)
    val used = state.rules.filter { it.action == "zapret" }.map { it.profile }.distinct()
    require(ports.keys == used.toSet()) { "Не все профили zapret запущены" }
    return NativeSiteRoutes(rules = state.rules, profiles = used.map { id ->
        val profile = profiles.profiles.first { it.id == id }
        val port = ports.getValue(id)
        require(port in 1..65535)
        SiteProxy(id, "127.0.0.1:$port", profile.blockQuic)
    }, defaultAction = if (state.defaultTunnel) "tunnel" else "direct")
}

fun nativeTrafficRoutes(sites: SiteRoutingState, apps: AppRoutingState, profiles: ZapretProfileState,
                        ports: Map<String, Int>, appSocket: String): NativeSiteRoutes {
    sites.validate(profiles); apps.validate(profiles)
    require(if (apps.enabled) appSocket.startsWith("@libreroute_app_") else appSocket.isEmpty())
    val used = ((if (sites.enabled) sites.rules.filter { it.action == "zapret" }.map { it.profile } else emptyList()) +
        (if (apps.enabled) apps.rules.filter { it.action == "zapret" }.map { it.profile } else emptyList())).distinct()
    require(ports.keys == used.toSet()) { "Не все профили zapret запущены" }
    return NativeSiteRoutes(rules = if (sites.enabled) sites.rules else emptyList(), profiles = used.map { id ->
        val profile = profiles.profiles.first { it.id == id }
        val port = ports.getValue(id); require(port in 1..65535)
        SiteProxy(id, "127.0.0.1:$port", profile.blockQuic)
    }, appSocket = appSocket, siteRulesEnabled = sites.enabled,
        defaultAction = if (sites.defaultTunnel) "tunnel" else "direct")
}
