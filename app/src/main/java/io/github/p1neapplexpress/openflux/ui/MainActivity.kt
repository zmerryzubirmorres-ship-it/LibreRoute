package io.github.p1neapplexpress.openflux.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.YandexAuthReason
import io.github.p1neapplexpress.openflux.util.AppUpdateChecker
import io.github.p1neapplexpress.openflux.util.ThemePreferences
import io.github.p1neapplexpress.openflux.util.TunnelLinkParser
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val vm: TunnelsViewModel by viewModels()
    private var captchaOpen = false
    private var authDialogOpen = false
    private val captchaResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        captchaOpen = false
        EventBus.clearPendingYandexAuthIssue()
        if (result.resultCode == RESULT_OK) vm.restartCurrent()
    }

    override fun onResume() {
        super.onResume()
        AppUpdateChecker.checkForUpdate(this)
        showPendingYandexAuthIssue()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences(this).applyTheme()
        super.onCreate(savedInstanceState)

        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        supportActionBar?.hide()

        val themePrefs = ThemePreferences(this)
        val isNight = when (themePrefs.themeMode) {
            ThemePreferences.THEME_LIGHT -> false
            ThemePreferences.THEME_DARK -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNight
        insetsController.isAppearanceLightNavigationBars = !isNight

        setContentView(R.layout.activity_main)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
            }
        }

        supportFragmentManager.beginTransaction()
            .replace(R.id.main, TunnelsFragment(), "")
            .commit()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EventBus.events.collect { ev ->
                    if (ev is AppEvent.YandexAuthIssue) showPendingYandexAuthIssue()
                    supportFragmentManager.fragments.forEach { f ->
                        if (f is BaseFragment) f.onNewEvent(ev)
                    }
                }
            }
        }

        handleDeepLink(intent)
    }

    private fun showPendingYandexAuthIssue() {
        if (captchaOpen || authDialogOpen) return
        val issue = EventBus.pendingYandexAuthIssue() ?: return
        if (issue.reason == YandexAuthReason.CAPTCHA) {
            openYandexWebView(issue)
            return
        }
        authDialogOpen = true
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.yandex_auth_issue_title)
            .setMessage(R.string.yandex_auth_issue_message)
            .setPositiveButton(R.string.yandex_open_webview) { _, _ ->
                authDialogOpen = false
                openYandexWebView(issue)
            }
            .setNegativeButton(R.string.yandex_auth_later) { _, _ ->
                authDialogOpen = false
                EventBus.clearPendingYandexAuthIssue()
            }
            .setOnCancelListener {
                authDialogOpen = false
                EventBus.clearPendingYandexAuthIssue()
            }
            .show()
    }

    private fun openYandexWebView(issue: AppEvent.YandexAuthIssue) {
        captchaOpen = true
        captchaResult.launch(Intent(this, YandexCaptchaActivity::class.java).apply {
            putExtra(YandexCaptchaActivity.EXTRA_DOCUMENT_URL, issue.documentUrl)
            putExtra(YandexCaptchaActivity.EXTRA_REASON, issue.reason.name)
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent) {
        val uri = intent.data ?: return
        if (!uri.scheme.equals("openflux", ignoreCase = true) &&
            !uri.scheme.equals("fluxon", ignoreCase = true) &&
            !uri.scheme.equals("paperflux", ignoreCase = true)) return
        val tunnel = TunnelLinkParser.fromUri(uri, this) ?: return

        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(getString(R.string.import_confirm_message, tunnel.name))
            .setPositiveButton(R.string.btn_add_and_connect) { _, _ ->
                vm.addTunnel(tunnel)
                vm.startTunnel(tunnel)
                Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.btn_add_only) { _, _ ->
                vm.addTunnel(tunnel)
                Toast.makeText(this, R.string.config_saved, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
