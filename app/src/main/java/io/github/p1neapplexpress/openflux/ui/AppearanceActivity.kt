package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.ThemePreferences
import io.github.p1neapplexpress.openflux.util.performAppHaptics
import kotlinx.coroutines.launch

class AppearanceActivity : AppCompatActivity() {

    private val vm: AppearanceViewModel by viewModels()
    private lateinit var appSettings: AppSettings
    private lateinit var themePrefs: ThemePreferences

    private lateinit var themeToggleGroup: MaterialButtonToggleGroup
    private lateinit var switchSpeedGraph: MaterialSwitch
    private lateinit var switchShowPing: MaterialSwitch
    private lateinit var switchMemoryMonitor: MaterialSwitch
    private lateinit var switchNotifySpeed: MaterialSwitch

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

        setContentView(R.layout.activity_appearance)

        appSettings = AppSettings(this)

        setupTopBar()
        initViews()
        setupThemeToggle()
        setupSwitches()
        observeViewModel()
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { state ->
                    val checkedBtnId = when (state.themeMode) {
                        ThemePreferences.THEME_LIGHT -> R.id.btn_theme_light
                        ThemePreferences.THEME_DARK -> R.id.btn_theme_dark
                        else -> R.id.btn_theme_system
                    }
                    if (themeToggleGroup.checkedButtonId != checkedBtnId) {
                        themeToggleGroup.check(checkedBtnId)
                    }
                    if (switchSpeedGraph.isChecked != state.showSpeedGraph) {
                        switchSpeedGraph.isChecked = state.showSpeedGraph
                    }
                    if (switchShowPing.isChecked != state.showPingInMainMenu) {
                        switchShowPing.isChecked = state.showPingInMainMenu
                    }
                    if (switchMemoryMonitor.isChecked != state.showMemoryUsage) {
                        switchMemoryMonitor.isChecked = state.showMemoryUsage
                    }
                    if (switchNotifySpeed.isChecked != state.showNotificationSpeed) {
                        switchNotifySpeed.isChecked = state.showNotificationSpeed
                    }
                }
            }
        }
    }

    private fun setupTopBar() {
        findViewById<ImageView>(R.id.btn_back).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            finish()
        }
    }

    private fun initViews() {
        themeToggleGroup = findViewById(R.id.theme_toggle_group)
        switchSpeedGraph = findViewById(R.id.switch_speed_graph)
        switchShowPing = findViewById(R.id.switch_show_ping)
        switchMemoryMonitor = findViewById(R.id.switch_memory_monitor)
        switchNotifySpeed = findViewById(R.id.switch_notify_speed)
    }

    private fun setupThemeToggle() {
        val checkedBtnId = when (themePrefs.themeMode) {
            ThemePreferences.THEME_LIGHT -> R.id.btn_theme_light
            ThemePreferences.THEME_DARK -> R.id.btn_theme_dark
            else -> R.id.btn_theme_system
        }
        themeToggleGroup.check(checkedBtnId)

        themeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                themeToggleGroup.performAppHaptics()
                val newMode = when (checkedId) {
                    R.id.btn_theme_light -> ThemePreferences.THEME_LIGHT
                    R.id.btn_theme_dark -> ThemePreferences.THEME_DARK
                    else -> ThemePreferences.THEME_SYSTEM
                }
                if (newMode != themePrefs.themeMode) {
                    vm.setThemeMode(newMode)
                    overridePendingTransition(R.anim.fade_in, R.anim.fade_out)
                }
            }
        }
    }

    private fun setupSwitches() {
        // Speed graph
        switchSpeedGraph.isChecked = appSettings.showSpeedGraph
        switchSpeedGraph.jumpDrawablesToCurrentState()
        switchSpeedGraph.setOnCheckedChangeListener { _, isChecked ->
            vm.setShowSpeedGraph(isChecked)
        }
        switchSpeedGraph.setOnClickListener {
            switchSpeedGraph.performAppHaptics()
        }
        findViewById<View>(R.id.row_speed_graph).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            switchSpeedGraph.toggle()
        }

        // Show ping
        switchShowPing.isChecked = appSettings.showPingInMainMenu
        switchShowPing.jumpDrawablesToCurrentState()
        switchShowPing.setOnCheckedChangeListener { _, isChecked ->
            vm.setShowPing(isChecked)
        }
        switchShowPing.setOnClickListener {
            switchShowPing.performAppHaptics()
        }
        findViewById<View>(R.id.row_show_ping).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            switchShowPing.toggle()
        }

        // Memory monitor
        switchMemoryMonitor.isChecked = appSettings.showMemoryUsage
        switchMemoryMonitor.jumpDrawablesToCurrentState()
        switchMemoryMonitor.setOnCheckedChangeListener { _, isChecked ->
            vm.setShowMemoryUsage(isChecked)
        }
        switchMemoryMonitor.setOnClickListener {
            switchMemoryMonitor.performAppHaptics()
        }
        findViewById<View>(R.id.row_memory_monitor).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            switchMemoryMonitor.toggle()
        }

        // Notify speed
        switchNotifySpeed.isChecked = appSettings.showNotificationSpeed
        switchNotifySpeed.jumpDrawablesToCurrentState()
        switchNotifySpeed.setOnCheckedChangeListener { _, isChecked ->
            vm.setShowNotificationSpeed(isChecked)
        }
        switchNotifySpeed.setOnClickListener {
            switchNotifySpeed.performAppHaptics()
        }
        findViewById<View>(R.id.row_notify_speed).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            switchNotifySpeed.toggle()
        }
    }
}
