package io.github.p1neapplexpress.openflux.ui

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.service.HotspotProxyBridge
import io.github.p1neapplexpress.openflux.util.AppIconManager
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.DomainRulesPreferences
import io.github.p1neapplexpress.openflux.util.LocalSocksSession
import io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsUiState(
    val splitAppsCount: Int = 0,
    val splitDomainsCount: Int = 0,
    val dnsSummary: String = "",
    val mtu: Int = 1400,
    val ipType: Int = AppSettings.IP_TYPE_AUTO,
    val bypassLan: Boolean = true,
    val killSwitch: Boolean = false,
    val shareHotspot: Boolean = false,
    val socks5AuthEnabled: Boolean = false,
    val proxyOnlyMode: Boolean = false,
    val autoFailover: Boolean = false,
    val autoBoot: Boolean = false,
    val autoClearLogs: Boolean = false,
    val autoUpdate: Boolean = true,
    val isBatteryOptimized: Boolean = false,
    val appIcon: String = AppIconManager.ICON_DARK,
    val language: String = "system",
    val hapticFeedback: Boolean = true
)

sealed interface SettingsEvent {
    data class ShowToast(val message: String) : SettingsEvent
    data class ShowToastRes(val resId: Int) : SettingsEvent
    data object OpenKillSwitchSettings : SettingsEvent
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val appSettings = AppSettings(app)
    private val splitPrefs = SplitTunnelPreferences(app)
    private val domainPrefs = DomainRulesPreferences(app)

    private fun createInitialState(): SettingsUiState {
        return SettingsUiState(
            splitAppsCount = 0,
            splitDomainsCount = domainPrefs.domains.size,
            dnsSummary = computeDnsSummary(),
            mtu = appSettings.mtu,
            ipType = appSettings.ipType,
            bypassLan = appSettings.bypassLan,
            killSwitch = appSettings.killSwitch,
            shareHotspot = appSettings.shareLanProxy,
            socks5AuthEnabled = appSettings.socks5AuthEnabled,
            proxyOnlyMode = appSettings.proxyOnlyMode,
            autoFailover = appSettings.autoFailover,
            autoBoot = appSettings.autoConnectOnBoot,
            autoClearLogs = appSettings.autoClearLogs,
            autoUpdate = appSettings.autoUpdateCheck,
            isBatteryOptimized = false,
            appIcon = AppIconManager.getCurrentIcon(getApplication()),
            language = appSettings.appLanguage,
            hapticFeedback = appSettings.hapticFeedback
        )
    }

    private val _uiState = MutableStateFlow(createInitialState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<SettingsEvent>()
    val events: SharedFlow<SettingsEvent> = _events.asSharedFlow()

    init {
        refreshState()
    }

    fun refreshState() {
        viewModelScope.launch(Dispatchers.IO) {
            val installed = runCatching {
                getApplication<Application>().packageManager
                    .getInstalledApplications(0).map { it.packageName }.toSet()
            }.getOrDefault(emptySet())

            val appCount = if (splitPrefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
                splitPrefs.bypassApps.count { it in installed }
            } else {
                splitPrefs.proxyApps.count { it in installed }
            }
            val domainCount = domainPrefs.domains.size

            val dnsDesc = computeDnsSummary()

            val isBatteryOptIgnored = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getApplication<Application>().getSystemService(Context.POWER_SERVICE) as? PowerManager
                pm?.isIgnoringBatteryOptimizations(getApplication<Application>().packageName) == true
            } else true

            val currentIcon = AppIconManager.getCurrentIcon(getApplication())

            _uiState.update {
                it.copy(
                    splitAppsCount = appCount,
                    splitDomainsCount = domainCount,
                    dnsSummary = dnsDesc,
                    mtu = appSettings.mtu,
                    ipType = appSettings.ipType,
                    bypassLan = appSettings.bypassLan,
                    killSwitch = appSettings.killSwitch,
                    shareHotspot = appSettings.shareLanProxy,
                    socks5AuthEnabled = appSettings.socks5AuthEnabled,
                    proxyOnlyMode = appSettings.proxyOnlyMode,
                    autoFailover = appSettings.autoFailover,
                    autoBoot = appSettings.autoConnectOnBoot,
                    autoClearLogs = appSettings.autoClearLogs,
                    autoUpdate = appSettings.autoUpdateCheck,
                    isBatteryOptimized = isBatteryOptIgnored,
                    appIcon = currentIcon,
                    language = appSettings.appLanguage,
                    hapticFeedback = appSettings.hapticFeedback
                )
            }
        }
    }

    private fun computeDnsSummary(): String {
        val app = getApplication<Application>()
        return when {
            appSettings.useSystemDns -> {
                val sysDns = appSettings.systemPrimaryDns ?: appSettings.primaryDns
                "${app.getString(R.string.dns_use_system_title)} ($sysDns)"
            }
            appSettings.dohEnabled -> {
                val providerName = when (appSettings.dohProvider) {
                    AppSettings.DOH_PROVIDER_CLOUDFLARE -> "Cloudflare"
                    AppSettings.DOH_PROVIDER_GOOGLE -> "Google"
                    AppSettings.DOH_PROVIDER_ADGUARD -> "AdGuard"
                    AppSettings.DOH_PROVIDER_QUAD9 -> "Quad9"
                    AppSettings.DOH_PROVIDER_XBOX -> "Xbox DNS"
                    AppSettings.DOH_PROVIDER_CUSTOM -> "Custom"
                    else -> "Cloudflare"
                }
                "DoH: $providerName"
            }
            else -> when (appSettings.dnsMode) {
                AppSettings.DNS_MODE_CLOUDFLARE -> "Cloudflare"
                AppSettings.DNS_MODE_GOOGLE -> "Google"
                AppSettings.DNS_MODE_ADGUARD -> "AdGuard DNS"
                AppSettings.DNS_MODE_QUAD9 -> "Quad9"
                AppSettings.DNS_MODE_XBOX -> "Xbox DNS"
                AppSettings.DNS_MODE_CUSTOM -> "${app.getString(R.string.settings_dns_custom)} (${appSettings.primaryDns})"
                else -> "Cloudflare"
            }
        }
    }

    fun toggleBypassLan(enable: Boolean) {
        appSettings.bypassLan = enable
        _uiState.update { it.copy(bypassLan = enable) }
    }

    fun toggleKillSwitch(enable: Boolean) {
        appSettings.killSwitch = enable
        _uiState.update { it.copy(killSwitch = enable) }
        if (enable) {
            viewModelScope.launch {
                _events.emit(SettingsEvent.OpenKillSwitchSettings)
            }
        }
    }

    fun toggleHotspot(enable: Boolean) {
        appSettings.shareLanProxy = enable
        _uiState.update { it.copy(shareHotspot = enable) }
        viewModelScope.launch(Dispatchers.IO) {
            if (enable) {
                val session = LocalSocksSession.getActive()
                HotspotProxyBridge.start(
                    lanPort = appSettings.lanProxyPort,
                    targetLocalPort = session.port,
                    authEnabled = appSettings.socks5AuthEnabled,
                    username = session.username,
                    password = session.password
                )
            } else {
                HotspotProxyBridge.stop()
            }
        }
    }

    fun toggleSocks5Auth(enable: Boolean) {
        appSettings.socks5AuthEnabled = enable
        _uiState.update { it.copy(socks5AuthEnabled = enable) }
    }

    fun toggleProxyOnlyMode(enable: Boolean) {
        appSettings.proxyOnlyMode = enable
        _uiState.update { it.copy(proxyOnlyMode = enable) }
        if (enable) {
            viewModelScope.launch {
                _events.emit(SettingsEvent.ShowToast("Режим прокси: VPN-иконка не будет показана. Перезапустите туннель."))
            }
        }
    }

    fun toggleAutoFailover(enable: Boolean) {
        appSettings.autoFailover = enable
        _uiState.update { it.copy(autoFailover = enable) }
    }

    fun toggleAutoBoot(enable: Boolean) {
        appSettings.autoConnectOnBoot = enable
        _uiState.update { it.copy(autoBoot = enable) }
    }

    fun toggleAutoClearLogs(enable: Boolean) {
        appSettings.autoClearLogs = enable
        _uiState.update { it.copy(autoClearLogs = enable) }
    }

    fun toggleAutoUpdate(enable: Boolean) {
        appSettings.autoUpdateCheck = enable
        _uiState.update { it.copy(autoUpdate = enable) }
    }

    fun toggleHapticFeedback(enable: Boolean) {
        appSettings.hapticFeedback = enable
        _uiState.update { it.copy(hapticFeedback = enable) }
    }

    fun updateMtu(newMtu: Int) {
        setMtu(newMtu)
    }

    fun setMtu(newMtu: Int) {
        appSettings.mtu = newMtu
        _uiState.update { it.copy(mtu = newMtu) }
    }

    fun updateIpType(type: Int) {
        setIpType(type)
    }

    fun setIpType(type: Int) {
        appSettings.ipType = type
        _uiState.update { it.copy(ipType = type) }
    }

    fun updateLanguage(lang: String) {
        appSettings.appLanguage = lang
        refreshState()
    }

    fun updateAppIcon(iconKey: String) {
        AppIconManager.setAppIcon(getApplication(), iconKey)
        refreshState()
    }
}
