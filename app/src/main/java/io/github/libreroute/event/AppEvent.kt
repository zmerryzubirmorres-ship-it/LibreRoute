package io.github.libreroute.event

sealed interface AppEvent {
    data class LogMessage(val message: String) : AppEvent
    data class JitsiModeratorRequired(val roomUrl: String, val reason: JitsiAuthReason = JitsiAuthReason.MODERATOR_REQUIRED) : AppEvent
    data class YandexAuthIssue(val documentUrl: String, val reason: YandexAuthReason) : AppEvent
    data class ToggleTunnel(val id: Long, val enabled: Boolean) : AppEvent
    data class SpeedUpdate(val rxSpeed: Long, val txSpeed: Long, val available: Boolean = true) : AppEvent
    data object TransportConnected : AppEvent
    data object TransportDisconnected : AppEvent
    data class Reconnecting(val tunnelName: String? = null) : AppEvent
    data object WaitingForNetwork : AppEvent
    data class VpnConnected(val tunnelName: String? = null) : AppEvent
    data object VpnDisconnected : AppEvent
    data object RefreshNotification : AppEvent
    data class AccessRevoked(val reason: String = "Доступ отозван сервером") : AppEvent
}

enum class YandexAuthReason { CAPTCHA, SESSION }
enum class JitsiAuthReason { MODERATOR_REQUIRED, TOKEN_EXPIRED }
