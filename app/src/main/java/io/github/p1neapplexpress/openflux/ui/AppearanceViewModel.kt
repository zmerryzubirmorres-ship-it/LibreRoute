package io.github.p1neapplexpress.openflux.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.ThemePreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class AppearanceUiState(
    val themeMode: Int = ThemePreferences.THEME_SYSTEM,
    val showSpeedGraph: Boolean = true,
    val showPingInMainMenu: Boolean = true,
    val showMemoryUsage: Boolean = false,
    val showNotificationSpeed: Boolean = true
)

class AppearanceViewModel(app: Application) : AndroidViewModel(app) {

    private val appSettings = AppSettings(app)
    private val themePrefs = ThemePreferences(app)

    private val _uiState = MutableStateFlow(
        AppearanceUiState(
            themeMode = themePrefs.themeMode,
            showSpeedGraph = appSettings.showSpeedGraph,
            showPingInMainMenu = appSettings.showPingInMainMenu,
            showMemoryUsage = appSettings.showMemoryUsage,
            showNotificationSpeed = appSettings.showNotificationSpeed
        )
    )
    val uiState: StateFlow<AppearanceUiState> = _uiState.asStateFlow()

    fun setThemeMode(mode: Int) {
        if (themePrefs.themeMode != mode) {
            themePrefs.themeMode = mode
            themePrefs.applyTheme()
            _uiState.update { it.copy(themeMode = mode) }
        }
    }

    fun setShowSpeedGraph(show: Boolean) {
        appSettings.showSpeedGraph = show
        _uiState.update { it.copy(showSpeedGraph = show) }
    }

    fun setShowPing(show: Boolean) {
        appSettings.showPingInMainMenu = show
        _uiState.update { it.copy(showPingInMainMenu = show) }
    }

    fun setShowMemoryUsage(show: Boolean) {
        appSettings.showMemoryUsage = show
        _uiState.update { it.copy(showMemoryUsage = show) }
    }

    fun setShowNotificationSpeed(show: Boolean) {
        appSettings.showNotificationSpeed = show
        _uiState.update { it.copy(showNotificationSpeed = show) }
    }
}
