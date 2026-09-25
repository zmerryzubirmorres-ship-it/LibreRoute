package io.github.p1neapplexpress.openflux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import io.github.p1neapplexpress.openflux.BuildConfig
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.AppIconManager
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.AppUpdateChecker
import io.github.p1neapplexpress.openflux.util.ConfigBackupManager
import io.github.p1neapplexpress.openflux.util.DomainRulesPreferences
import io.github.p1neapplexpress.openflux.util.LocalSocksSession
import io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences
import io.github.p1neapplexpress.openflux.util.ThemePreferences
import io.github.p1neapplexpress.openflux.util.performAppHaptics
import kotlinx.coroutines.launch
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private val vm: SettingsViewModel by viewModels()
    private lateinit var appSettings: AppSettings
    private lateinit var themePrefs: ThemePreferences
    private lateinit var splitPrefs: SplitTunnelPreferences

    private lateinit var textSplitTunnelSummary: TextView
    private lateinit var textDnsSummary: TextView
    private lateinit var textMtuSummary: TextView
    private lateinit var textIpTypeSummary: TextView
    private lateinit var switchHapticFeedback: MaterialSwitch
    private lateinit var textLanguageSummary: TextView
    private lateinit var textAppIconSummary: TextView

    private lateinit var switchBypassLan: MaterialSwitch

    private lateinit var switchKillSwitch: MaterialSwitch
    private lateinit var switchHotspot: MaterialSwitch
    private lateinit var switchSocks5Auth: MaterialSwitch
    private lateinit var switchFailover: MaterialSwitch
    private lateinit var switchAutoBoot: MaterialSwitch
    private lateinit var switchAutoClearLogs: MaterialSwitch

    private lateinit var switchAutoUpdate: MaterialSwitch
    private lateinit var textVersion: TextView

    private var isBindingState = false

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            lifecycleScope.launch {
                val result = ConfigBackupManager.exportBackup(this@SettingsActivity, uri)
                result.fold(
                    onSuccess = { count ->
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.settings_backup_exported, count),
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { error ->
                        Toast.makeText(
                            this@SettingsActivity,
                            getString(R.string.settings_backup_error, error.localizedMessage ?: "Unknown error"),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            showStyledImportBottomSheet(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        themePrefs = ThemePreferences(this)
        themePrefs.applyTheme()

        val isNight = when (themePrefs.themeMode) {
            ThemePreferences.THEME_LIGHT -> false
            ThemePreferences.THEME_DARK -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNight
        insetsController.isAppearanceLightNavigationBars = !isNight

        setContentView(R.layout.activity_settings)

        appSettings = AppSettings(this)
        splitPrefs = SplitTunnelPreferences(this)

        initViews()
        setupTopBar()
        setupInterface()
        setupNetwork()
        setupAutomation()
        setupLogs()
        setupBackup()
        setupUpdates()
        setupAbout()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshState()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.uiState.collect { state ->
                        isBindingState = true
                        try {
                            textSplitTunnelSummary.text = when {
                                state.splitAppsCount == 0 && state.splitDomainsCount == 0 -> getString(R.string.settings_split_tunnel_none)
                                else -> getString(R.string.split_summary_format, state.splitAppsCount, state.splitDomainsCount)
                            }
                            textDnsSummary.text = state.dnsSummary
                            textMtuSummary.text = state.mtu.toString()
                            textIpTypeSummary.text = when (state.ipType) {
                                AppSettings.IP_TYPE_IPV4 -> getString(R.string.settings_ip_type_ipv4)
                                AppSettings.IP_TYPE_IPV6 -> getString(R.string.settings_ip_type_ipv6)
                                else -> getString(R.string.settings_ip_type_auto)
                            }
                            textLanguageSummary.text = when (state.language) {
                                "ru" -> "Русский"
                                "en" -> "English"
                                else -> getString(R.string.settings_language_system)
                            }
                            textAppIconSummary.text = if (state.appIcon == AppIconManager.ICON_LIGHT) {
                                getString(R.string.settings_icon_light)
                            } else {
                                getString(R.string.settings_icon_dark)
                            }

                            if (switchBypassLan.isChecked != state.bypassLan) {
                                switchBypassLan.isChecked = state.bypassLan
                            }
                            if (switchHapticFeedback.isChecked != state.hapticFeedback) {
                                switchHapticFeedback.isChecked = state.hapticFeedback
                            }
                            if (switchKillSwitch.isChecked != state.killSwitch) {
                                switchKillSwitch.isChecked = state.killSwitch
                            }
                            if (switchHotspot.isChecked != state.shareHotspot) {
                                switchHotspot.isChecked = state.shareHotspot
                            }
                            if (switchSocks5Auth.isChecked != state.socks5AuthEnabled) {
                                switchSocks5Auth.isChecked = state.socks5AuthEnabled
                            }
                            val swProxyOnly = findViewById<MaterialSwitch?>(R.id.switch_proxy_only_mode)
                            if (swProxyOnly != null && swProxyOnly.isChecked != state.proxyOnlyMode) {
                                swProxyOnly.isChecked = state.proxyOnlyMode
                            }
                            if (switchFailover.isChecked != state.autoFailover) {
                                switchFailover.isChecked = state.autoFailover
                            }
                            if (switchAutoBoot.isChecked != state.autoBoot) {
                                switchAutoBoot.isChecked = state.autoBoot
                            }
                            if (switchAutoClearLogs.isChecked != state.autoClearLogs) {
                                switchAutoClearLogs.isChecked = state.autoClearLogs
                            }
                            if (switchAutoUpdate.isChecked != state.autoUpdate) {
                                switchAutoUpdate.isChecked = state.autoUpdate
                            }

                            val subBattery = findViewById<TextView>(R.id.text_battery_opt_subtitle)
                            if (subBattery != null) {
                                if (state.isBatteryOptimized) {
                                    subBattery.text = getString(R.string.battery_opt_disabled)
                                    subBattery.setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.colorSuccess))
                                } else {
                                    subBattery.text = getString(R.string.battery_opt_enabled)
                                    subBattery.setTextColor(androidx.core.content.ContextCompat.getColor(this@SettingsActivity, R.color.text_secondary))
                                }
                            }
                        } finally {
                            isBindingState = false
                        }
                    }
                }
                launch {
                    vm.events.collect { event ->
                        when (event) {
                            is SettingsEvent.ShowToast -> Toast.makeText(this@SettingsActivity, event.message, Toast.LENGTH_SHORT).show()
                            is SettingsEvent.ShowToastRes -> Toast.makeText(this@SettingsActivity, event.resId, Toast.LENGTH_SHORT).show()
                            is SettingsEvent.OpenKillSwitchSettings -> {
                                Toast.makeText(this@SettingsActivity, R.string.settings_kill_switch_system, Toast.LENGTH_SHORT).show()
                                try {
                                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                                } catch (_: Exception) {}
                            }
                        }
                    }
                }
            }
        }
    }

    private fun initViews() {
        textSplitTunnelSummary = findViewById(R.id.text_split_tunnel_summary)
        textDnsSummary = findViewById(R.id.text_dns_summary)
        textMtuSummary = findViewById(R.id.text_mtu_summary)
        textIpTypeSummary = findViewById(R.id.text_ip_type_summary)

        switchHapticFeedback = findViewById(R.id.switch_haptic_feedback)
        textLanguageSummary = findViewById(R.id.text_language_summary)
        textAppIconSummary = findViewById(R.id.text_app_icon_summary)

        switchBypassLan = findViewById(R.id.switch_bypass_lan)

        switchKillSwitch = findViewById(R.id.switch_kill_switch)
        switchHotspot = findViewById(R.id.switch_hotspot)
        switchSocks5Auth = findViewById(R.id.switch_socks5_auth)
        switchFailover = findViewById(R.id.switch_failover)
        switchAutoBoot = findViewById(R.id.switch_auto_boot)
        switchAutoClearLogs = findViewById(R.id.switch_auto_clear_logs)

        switchAutoUpdate = findViewById(R.id.switch_auto_update)
        textVersion = findViewById(R.id.text_version)
    }

    private fun setupTopBar() {
        findViewById<View>(R.id.btn_back).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }
    }


    private fun setupNetwork() {
        // 1. Split Tunneling
        findViewById<View>(R.id.row_split_tunnel).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(this, SplitTunnelActivity::class.java))
        }
        updateSplitTunnelSummary()

        // 2. DNS
        findViewById<View>(R.id.row_dns).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(this, DnsSettingsActivity::class.java))
        }
        updateDnsSummary()

        // 3. MTU BottomSheet
        findViewById<View>(R.id.row_mtu).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            MtuBottomSheetDialog(this, appSettings.mtu) { newMtu ->
                vm.setMtu(newMtu)
            }.show()
        }

        // 4. IP Type
        findViewById<View>(R.id.row_ip_type).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showIpTypeBottomSheet()
        }

        // 5. Bypass LAN
        switchBypassLan.isChecked = appSettings.bypassLan
        switchBypassLan.jumpDrawablesToCurrentState()
        switchBypassLan.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            vm.toggleBypassLan(isChecked)
        }
        switchBypassLan.setOnClickListener {
            switchBypassLan.performAppHaptics()
        }
        findViewById<View>(R.id.row_bypass_lan).setOnClickListener {
            it.performAppHaptics()
            switchBypassLan.toggle()
        }
    }

    private fun setupInterface() {
        // 1. Appearance (Внешний вид)
        findViewById<View>(R.id.row_appearance_elements)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(this, AppearanceActivity::class.java))
        }

        // 2. Haptic Feedback (Виброотклик)
        switchHapticFeedback.isChecked = appSettings.hapticFeedback
        switchHapticFeedback.jumpDrawablesToCurrentState()
        switchHapticFeedback.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            vm.toggleHapticFeedback(isChecked)
        }
        switchHapticFeedback.setOnClickListener {
            switchHapticFeedback.performAppHaptics()
        }
        findViewById<View>(R.id.row_haptic_feedback)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            switchHapticFeedback.toggle()
        }

        // 3. App Language (Язык приложения)
        updateLanguageSummary()
        findViewById<View>(R.id.row_language)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showLanguageBottomSheet()
        }

        // 4. App Icon (Иконка приложения)
        updateAppIconSummary()
        findViewById<View>(R.id.row_app_icon)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showAppIconBottomSheet()
        }
    }

    private fun setupAutomation() {
        // 1. Kill Switch
        switchKillSwitch.isChecked = appSettings.killSwitch
        switchKillSwitch.jumpDrawablesToCurrentState()
        switchKillSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            vm.toggleKillSwitch(isChecked)
        }
        switchKillSwitch.setOnClickListener {
            switchKillSwitch.performAppHaptics()
        }
        findViewById<View>(R.id.row_kill_switch).setOnClickListener {
            it.performAppHaptics()
            switchKillSwitch.toggle()
        }

        // 2. Hotspot Proxy Sharing
        switchHotspot.isChecked = appSettings.shareLanProxy
        switchHotspot.jumpDrawablesToCurrentState()
        switchHotspot.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            vm.toggleHotspot(isChecked)
            if (isChecked) {
                showHotspotBottomSheet()
            }
        }
        switchHotspot.setOnClickListener {
            switchHotspot.performAppHaptics()
        }
        findViewById<View>(R.id.row_hotspot).setOnClickListener {
            it.performAppHaptics()
            showHotspotBottomSheet()
        }

        // 3. SOCKS5 RFC 1929 Auth
        switchSocks5Auth.isChecked = appSettings.socks5AuthEnabled
        switchSocks5Auth.jumpDrawablesToCurrentState()
        switchSocks5Auth.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            vm.toggleSocks5Auth(isChecked)
        }
        switchSocks5Auth.setOnClickListener {
            switchSocks5Auth.performAppHaptics()
        }
        findViewById<View>(R.id.row_socks5_auth).setOnClickListener {
            it.performAppHaptics()
            showSocks5AuthBottomSheet()
        }

        // 4. Proxy-only mode (no VPN, no TUN, no key icon)
        val switchProxyOnly = findViewById<com.google.android.material.materialswitch.MaterialSwitch?>(R.id.switch_proxy_only_mode)
        switchProxyOnly?.let { sw ->
            sw.isChecked = appSettings.proxyOnlyMode
            sw.jumpDrawablesToCurrentState()
            sw.setOnCheckedChangeListener { _, isChecked ->
                if (isBindingState) return@setOnCheckedChangeListener
                vm.toggleProxyOnlyMode(isChecked)
            }
            sw.setOnClickListener { sw.performAppHaptics() }
        }
        findViewById<View?>(R.id.row_proxy_only_mode)?.setOnClickListener {
            it.performAppHaptics()
            switchProxyOnly?.toggle()
        }

        // 5. Failover
        switchFailover.isChecked = appSettings.autoFailover
        switchFailover.jumpDrawablesToCurrentState()
        switchFailover.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            vm.toggleAutoFailover(isChecked)
        }
        switchFailover.setOnClickListener {
            switchFailover.performAppHaptics()
        }
        findViewById<View>(R.id.row_failover).setOnClickListener {
            it.performAppHaptics()
            switchFailover.toggle()
        }

        // 5. Auto-boot
        switchAutoBoot.isChecked = appSettings.autoConnectOnBoot
        switchAutoBoot.jumpDrawablesToCurrentState()
        switchAutoBoot.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            vm.toggleAutoBoot(isChecked)
        }
        switchAutoBoot.setOnClickListener {
            switchAutoBoot.performAppHaptics()
        }
        findViewById<View>(R.id.row_auto_boot).setOnClickListener {
            it.performAppHaptics()
            switchAutoBoot.toggle()
        }

        // 6. Battery Optimization
        findViewById<View>(R.id.row_battery_opt)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            requestIgnoreBatteryOptimizations()
        }
        updateBatteryOptStatus()
    }

    private fun updateBatteryOptStatus() {
        val subtitle = findViewById<TextView>(R.id.text_battery_opt_subtitle) ?: return
        val isIgnored = isBatteryOptimizationIgnored()
        if (isIgnored) {
            subtitle.text = getString(R.string.battery_opt_disabled)
            subtitle.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.colorSuccess))
        } else {
            subtitle.text = getString(R.string.battery_opt_enabled)
            subtitle.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.text_secondary))
        }
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            return pm?.isIgnoringBatteryOptimizations(packageName) == true
        }
        return true
    }

    private fun requestIgnoreBatteryOptimizations() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val isIgnored = isBatteryOptimizationIgnored()
            if (!isIgnored) {
                try {
                    @android.annotation.SuppressLint("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                    return
                } catch (e: Exception) {
                    // Fallback to general settings
                }
            }
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                startActivity(intent)
            } catch (e: Exception) {
                try {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", packageName, null)
                    }
                    startActivity(intent)
                } catch (e2: Exception) {
                    Toast.makeText(this, "Не удалось открыть настройки батареи", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setupLogs() {
        findViewById<View>(R.id.row_event_logs)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(this, LogsActivity::class.java))
        }

        switchAutoClearLogs.isChecked = appSettings.autoClearLogs
        switchAutoClearLogs.jumpDrawablesToCurrentState()
        switchAutoClearLogs.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            vm.toggleAutoClearLogs(isChecked)
        }
        switchAutoClearLogs.setOnClickListener {
            switchAutoClearLogs.performAppHaptics()
        }
        findViewById<View>(R.id.row_auto_clear_logs).setOnClickListener {
            it.performAppHaptics()
            switchAutoClearLogs.toggle()
        }
    }

    private fun setupBackup() {
        findViewById<View>(R.id.row_export_backup).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            exportBackupLauncher.launch("fluxon_backup.json")
        }

        findViewById<View>(R.id.row_import_backup).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            importBackupLauncher.launch(arrayOf("application/json", "*/*"))
        }
    }

    private fun setupUpdates() {
        switchAutoUpdate.isChecked = appSettings.autoUpdateCheck
        switchAutoUpdate.jumpDrawablesToCurrentState()
        switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            if (isBindingState) return@setOnCheckedChangeListener
            vm.toggleAutoUpdate(isChecked)
        }
        switchAutoUpdate.setOnClickListener {
            switchAutoUpdate.performAppHaptics()
        }
        findViewById<View>(R.id.row_auto_update).setOnClickListener {
            it.performAppHaptics()
            switchAutoUpdate.toggle()
        }

        findViewById<View>(R.id.row_check_update_now).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            Toast.makeText(this, R.string.update_checking, Toast.LENGTH_SHORT).show()
            AppUpdateChecker.checkForUpdate(this, force = true) { hasUpdate, versionOrError ->
                if (!hasUpdate) {
                    if (AppUpdateChecker.isVersionTag(versionOrError)) {
                        Toast.makeText(this, getString(R.string.update_latest_installed, versionOrError), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, getString(R.string.update_error, versionOrError), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private var versionClickCount = 0
    private var lastVersionClickTime = 0L

    private fun setupAbout() {
        textVersion.text = BuildConfig.VERSION_NAME

        val onVersionClicked: (View) -> Unit = { view ->
            val now = System.currentTimeMillis()
            if (now - lastVersionClickTime > 1500L) {
                versionClickCount = 0
            }
            lastVersionClickTime = now
            versionClickCount++

            if (versionClickCount >= 5) {
                versionClickCount = 0
                view.performAppHaptics(HapticFeedbackConstants.CONFIRM)
                DebugMenuHelper.show(this)
            } else if (versionClickCount >= 2) {
                view.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                val remaining = 5 - versionClickCount
                val msg = if (remaining == 1) {
                    getString(R.string.debug_click_step_one)
                } else {
                    getString(R.string.debug_click_steps_left, remaining)
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<View>(R.id.row_version)?.setOnClickListener(onVersionClicked)
        textVersion.setOnClickListener(onVersionClicked)

        val projectUrl = AppUpdateChecker.projectUrlOrNull()
        findViewById<View>(R.id.row_github)?.apply {
            visibility = if (projectUrl == null) View.GONE else View.VISIBLE
            if (projectUrl != null) {
                findViewById<TextView>(R.id.text_github_repository)?.text = BuildConfig.UPDATE_REPOSITORY
                setOnClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(projectUrl)))
                    } catch (e: Exception) {
                        Toast.makeText(this@SettingsActivity, R.string.error_open_browser, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun updateSplitTunnelSummary() {
        val installed = packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        val appCount = if (splitPrefs.mode == SplitTunnelPreferences.MODE_BYPASS) {
            splitPrefs.bypassApps.count { it in installed }
        } else {
            splitPrefs.proxyApps.count { it in installed }
        }
        val domainPrefs = DomainRulesPreferences(this)
        val domainCount = domainPrefs.domains.size
        textSplitTunnelSummary.text = when {
            appCount == 0 && domainCount == 0 -> getString(R.string.settings_split_tunnel_none)
            else -> getString(R.string.split_summary_format, appCount, domainCount)
        }
    }

    private fun updateDnsSummary() {
        textDnsSummary.text = when {
            appSettings.useSystemDns -> {
                val sysDns = appSettings.systemPrimaryDns ?: appSettings.primaryDns
                "${getString(R.string.dns_use_system_title)} ($sysDns)"
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
                AppSettings.DNS_MODE_CUSTOM -> "${getString(R.string.settings_dns_custom)} (${appSettings.primaryDns})"
                else -> "Cloudflare"
            }
        }
    }

    private fun updateMtuSummary() {
        textMtuSummary.text = getString(R.string.settings_mtu_summary, appSettings.mtu)
    }

    private fun updateIpTypeSummary() {
        textIpTypeSummary.text = when (appSettings.ipType) {
            AppSettings.IP_TYPE_IPV4 -> getString(R.string.settings_ip_type_ipv4)
            AppSettings.IP_TYPE_IPV6 -> getString(R.string.settings_ip_type_ipv6)
            else -> getString(R.string.settings_ip_type_auto)
        }
    }

    private fun showIpTypeBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_ip_type, null)
        dialog.setContentView(view)

        val cardAuto = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_ip_auto)
        val cardIpv4 = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_ip_v4)
        val cardIpv6 = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_ip_v6)
        val btnCancel = view.findViewById<View>(R.id.btn_cancel_ip_type)

        val primaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.m3_primary)
        val activeStrokeWidth = (2 * resources.displayMetrics.density).toInt()

        when (appSettings.ipType) {
            AppSettings.IP_TYPE_AUTO -> {
                cardAuto?.strokeColor = primaryColor
                cardAuto?.strokeWidth = activeStrokeWidth
            }
            AppSettings.IP_TYPE_IPV4 -> {
                cardIpv4?.strokeColor = primaryColor
                cardIpv4?.strokeWidth = activeStrokeWidth
            }
            AppSettings.IP_TYPE_IPV6 -> {
                cardIpv6?.strokeColor = primaryColor
                cardIpv6?.strokeWidth = activeStrokeWidth
            }
        }

        cardAuto?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            vm.setIpType(AppSettings.IP_TYPE_AUTO)
            dialog.dismiss()
        }
        cardIpv4?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            vm.setIpType(AppSettings.IP_TYPE_IPV4)
            dialog.dismiss()
        }
        cardIpv6?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            vm.setIpType(AppSettings.IP_TYPE_IPV6)
            dialog.dismiss()
        }
        btnCancel?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun showHotspotBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_hotspot, null)
        dialog.setContentView(view)

        val textIp = view.findViewById<TextView>(R.id.text_hotspot_ip)
        val textPort = view.findViewById<TextView>(R.id.text_hotspot_port)
        val btnCopy = view.findViewById<View>(R.id.btn_copy_hotspot)
        val btnClose = view.findViewById<View>(R.id.btn_close_hotspot)

        val ip = LocalSocksSession.getLocalIpAddress()
        val port = appSettings.lanProxyPort

        textIp.text = ip
        textPort.text = port.toString()

        btnCopy.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("SOCKS5 Proxy", "$ip:$port")
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, R.string.hotspot_sheet_copied, Toast.LENGTH_SHORT).show()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSocks5AuthBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_socks5_auth, null)
        dialog.setContentView(view)

        val inputUser = view.findViewById<TextInputEditText>(R.id.input_socks_user)
        val inputPass = view.findViewById<TextInputEditText>(R.id.input_socks_pass)
        val btnCancel = view.findViewById<View>(R.id.btn_cancel_socks)
        val btnSave = view.findViewById<View>(R.id.btn_save_socks)

        val active = LocalSocksSession.getActive()
        val activeUser = appSettings.socks5CustomUser.ifEmpty { active.username }
        val activePass = appSettings.socks5CustomPass.ifEmpty { active.password }

        inputUser.setText(activeUser)
        inputPass.setText(activePass)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            appSettings.socks5CustomUser = inputUser.text?.toString().orEmpty().trim()
            appSettings.socks5CustomPass = inputPass.text?.toString().orEmpty().trim()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showStyledImportBottomSheet(uri: Uri) {
        lifecycleScope.launch {
            val peek = ConfigBackupManager.peekBackup(this@SettingsActivity, uri)
            peek.fold(
                onSuccess = { backup ->
                    val dialog = BottomSheetDialog(this@SettingsActivity)
                    val view = layoutInflater.inflate(R.layout.bottom_sheet_import_backup, null)
                    dialog.setContentView(view)

                    val textTunnels = view.findViewById<TextView>(R.id.text_import_tunnels_count)
                    val textRules = view.findViewById<TextView>(R.id.text_import_rules_count)
                    val btnCancel = view.findViewById<View>(R.id.btn_cancel_import)
                    val btnConfirm = view.findViewById<View>(R.id.btn_confirm_import)

                    textTunnels.text = getString(R.string.import_sheet_tunnels, backup.tunnels.size)
                    val rulesCount = backup.bypassApps.size + backup.proxyApps.size + backup.domains.size
                    textRules.text = getString(R.string.import_sheet_rules, rulesCount)

                    btnCancel.setOnClickListener {
                        dialog.dismiss()
                    }

                    btnConfirm.setOnClickListener {
                        it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                        dialog.dismiss()
                        lifecycleScope.launch {
                            val applyResult = ConfigBackupManager.applyBackup(this@SettingsActivity, backup)
                            applyResult.fold(
                                onSuccess = { count ->
                                    updateSplitTunnelSummary()
                                    Toast.makeText(
                                        this@SettingsActivity,
                                        getString(R.string.settings_import_success, count),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                },
                                onFailure = { err ->
                                    Toast.makeText(
                                        this@SettingsActivity,
                                        getString(R.string.settings_backup_error, err.localizedMessage ?: "Import failed"),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            )
                        }
                    }

                    dialog.show()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@SettingsActivity,
                        getString(R.string.settings_backup_error, error.localizedMessage ?: "Invalid file"),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    private fun updateLanguageSummary() {
        if (!::textLanguageSummary.isInitialized) return
        textLanguageSummary.text = when (appSettings.appLanguage) {
            "ru" -> getString(R.string.settings_language_ru)
            "en" -> getString(R.string.settings_language_en)
            else -> getString(R.string.settings_language_system)
        }
    }

    private fun showLanguageBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_language, null)
        dialog.setContentView(view)

        val cardSystem = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_lang_system)
        val cardRu = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_lang_ru)
        val cardEn = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_lang_en)

        val checkSystem = view.findViewById<ImageView>(R.id.check_lang_system)
        val checkRu = view.findViewById<ImageView>(R.id.check_lang_ru)
        val checkEn = view.findViewById<ImageView>(R.id.check_lang_en)

        val btnCancel = view.findViewById<View>(R.id.btn_cancel_language)

        val primaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.m3_primary)
        val activeStrokeWidth = (2 * resources.displayMetrics.density).toInt()

        when (appSettings.appLanguage) {
            "ru" -> {
                cardRu?.strokeColor = primaryColor
                cardRu?.strokeWidth = activeStrokeWidth
                checkRu?.visibility = View.VISIBLE
            }
            "en" -> {
                cardEn?.strokeColor = primaryColor
                cardEn?.strokeWidth = activeStrokeWidth
                checkEn?.visibility = View.VISIBLE
            }
            else -> {
                cardSystem?.strokeColor = primaryColor
                cardSystem?.strokeWidth = activeStrokeWidth
                checkSystem?.visibility = View.VISIBLE
            }
        }

        fun selectLanguage(tag: String) {
            appSettings.appLanguage = tag
            updateLanguageSummary()
            val appLocales = if (tag == "system") {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
            AppCompatDelegate.setApplicationLocales(appLocales)
            dialog.dismiss()
        }

        cardSystem?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            selectLanguage("system")
        }
        cardRu?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            selectLanguage("ru")
        }
        cardEn?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            selectLanguage("en")
        }
        btnCancel?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun updateAppIconSummary() {
        if (!::textAppIconSummary.isInitialized) return
        val current = AppIconManager.getCurrentIcon(this)
        textAppIconSummary.text = if (current == AppIconManager.ICON_LIGHT) {
            getString(R.string.settings_icon_light)
        } else {
            getString(R.string.settings_icon_dark)
        }
    }

    private fun showAppIconBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_app_icon, null)
        dialog.setContentView(view)

        val cardDark = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_icon_dark)
        val cardLight = view.findViewById<com.google.android.material.card.MaterialCardView>(R.id.card_icon_light)
        val checkDark = view.findViewById<ImageView>(R.id.check_icon_dark)
        val checkLight = view.findViewById<ImageView>(R.id.check_icon_light)
        val btnCancel = view.findViewById<View>(R.id.btn_cancel_icon)

        val primaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.m3_primary)
        val activeStrokeWidth = (2 * resources.displayMetrics.density).toInt()

        val currentIcon = AppIconManager.getCurrentIcon(this)
        if (currentIcon == AppIconManager.ICON_LIGHT) {
            cardLight?.strokeColor = primaryColor
            cardLight?.strokeWidth = activeStrokeWidth
            checkLight?.visibility = View.VISIBLE
        } else {
            cardDark?.strokeColor = primaryColor
            cardDark?.strokeWidth = activeStrokeWidth
            checkDark?.visibility = View.VISIBLE
        }

        fun selectIcon(iconKey: String) {
            AppIconManager.setAppIcon(this, iconKey)
            updateAppIconSummary()
            EventBus.dispatch(AppEvent.RefreshNotification)
            dialog.dismiss()
        }

        cardDark?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            selectIcon(AppIconManager.ICON_DARK)
        }
        cardLight?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            selectIcon(AppIconManager.ICON_LIGHT)
        }
        btnCancel?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }
        dialog.show()
    }
}
