package io.github.libreroute.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AppRule(@SerialName("package") val packageName: String, val action: String, val profile: String = "") {
    fun validate(profiles: ZapretProfileState) {
        require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName) && packageName.length <= 255) { "Некорректное приложение" }
        require(action in SiteRule.ACTIONS) { "Некорректное действие правила" }
        require(if (action == "zapret") profiles.profiles.any { it.id == profile } else profile.isEmpty()) { "Профиль zapret не найден" }
    }
}

@Serializable
data class AppRoutingState(val enabled: Boolean = false, val rules: List<AppRule> = emptyList()) {
    fun validate(profiles: ZapretProfileState) {
        profiles.validate()
        require(rules.size <= 1000 && rules.map { it.packageName }.distinct().size == rules.size) { "Повторяющиеся правила или слишком много приложений" }
        rules.forEach { it.validate(profiles) }
    }

    fun save(rule: AppRule, profiles: ZapretProfileState): AppRoutingState =
        copy(rules = rules.filterNot { it.packageName == rule.packageName } + rule).also { it.validate(profiles) }
}

/** Android can distinguish UIDs, not packages sharing the same UID. */
fun appRulesByUid(state: AppRoutingState, packageUids: Map<String, Int>): Map<Int, AppRule> {
    val result = mutableMapOf<Int, AppRule>()
    state.rules.forEach { rule ->
        val uid = packageUids[rule.packageName] ?: return@forEach
        require(uid >= 0)
        val previous = result[uid]
        require(previous == null || (previous.action == rule.action && previous.profile == rule.profile)) {
            "Приложения с общим UID должны использовать одинаковый способ подключения"
        }
        result[uid] = rule
    }
    return result
}
