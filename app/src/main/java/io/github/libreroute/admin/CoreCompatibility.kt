package io.github.libreroute.admin

import io.github.libreroute.BuildConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
data class CoreCompatibility(
    val version: String = "",
    @SerialName("build_id") val buildId: String = "",
    @SerialName("api_revision") val apiRevision: Int = 0,
    val capabilities: List<String> = emptyList()
) {
    companion object {
        /** Minimum Core version shipped in this APK's install artifacts. */
        val REQUIRED_VERSION: String = BuildConfig.BUNDLED_CORE_VERSION
        val REQUIRED_CAPABILITIES = setOf("protocol-create-v2", "setup-auth-document-v1", "negotiated-batch-v1", "setup-auth-worker-cookies-v1", "mqtt-wss-readiness-v1", "worker-backend-readiness-v1")
    }

    fun supports(required: Set<String>): Boolean = apiRevision >= 1 &&
        version.isNotBlank() && Regex("[0-9a-f]{64}").matches(buildId) && capabilities.containsAll(required)

    val needsUpdate: Boolean get() = !supports(REQUIRED_CAPABILITIES) ||
        compareCoreVersions(version, REQUIRED_VERSION)?.let { it < 0 } != false
}

internal fun compareCoreVersions(current: String, expected: String): Int? {
    fun parts(value: String): List<Int>? = value.split('.').takeIf { it.size == 3 }
        ?.map { it.toIntOrNull()?.takeIf { number -> number >= 0 } ?: return null }
    val actual = parts(current) ?: return null
    val target = parts(expected) ?: return null
    return actual.zip(target).firstOrNull { it.first != it.second }?.let { it.first.compareTo(it.second) } ?: 0
}

internal fun requiresYandexBrowserAuth(error: String): Boolean {
    if (listOf("session negotiation", "encrypted transport", "empty document url", "core несовместим")
        .any { error.contains(it, true) }) return false
    return listOf("captcha", "auth", "cookie").any { error.contains(it, true) }
}

/** Display cache only. Operation checks always request a fresh signed snapshot. */
@Serializable
data class CoreObservation(
    val info: CoreCompatibility? = null,
    val checkedAt: Long = 0L,
    val error: String? = null
)

internal fun coreVersionSummary(observation: CoreObservation, now: Long = System.currentTimeMillis()): String {
    val info = observation.info
    val version = info?.version?.takeIf { it.isNotBlank() }?.let { "Core $it" } ?: "Версия ядра не определена"
    val status = when {
        observation.error != null -> "Не удалось обновить сведения"
        observation.checkedAt == 0L -> "Совместимость не проверена"
        now - observation.checkedAt > 300_000L -> "Сохранённые сведения; повторите проверку"
        info?.needsUpdate == false -> "Совместим с клиентом"
        else -> "Старое или несовместимое ядро. Требуется обновление до ${CoreCompatibility.REQUIRED_VERSION}"
    }
    val checked = if (observation.checkedAt > 0L) "\nПоследняя проверка: " +
        java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(observation.checkedAt)) else ""
    return "$version · $status$checked"
}
