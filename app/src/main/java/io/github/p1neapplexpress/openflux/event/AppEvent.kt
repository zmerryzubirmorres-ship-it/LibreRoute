package io.github.p1neapplexpress.openflux.event

sealed interface AppEvent {
    data class LogMessage(val message: String) : AppEvent
    data class YandexAuthIssue(val documentUrl: String, val reason: YandexAuthReason) : AppEvent
    data class ToggleTunnel(val id: Long, val enabled: Boolean) : AppEvent
    data class SpeedUpdate(val rxSpeed: Long, val txSpeed: Long) : AppEvent
    data object TransportConnected : AppEvent
    data object TransportDisconnected : AppEvent
    data class Reconnecting(val tunnelName: String? = null) : AppEvent
    data object WaitingForNetwork : AppEvent
    data class VpnConnected(val tunnelName: String? = null) : AppEvent
    data object VpnDisconnected : AppEvent
    data object RefreshNotification : AppEvent
}

enum class YandexAuthReason { CAPTCHA, SESSION }
