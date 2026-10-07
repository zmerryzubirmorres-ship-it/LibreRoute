package io.github.libreroute.ui

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
import io.github.libreroute.R
import io.github.libreroute.event.EventBus
import io.github.libreroute.event.AppEvent
import io.github.libreroute.event.YandexAuthReason
import io.github.libreroute.util.AppUpdateChecker
import io.github.libreroute.util.ThemePreferences
import io.github.libreroute.util.TunnelLinkParser
import io.github.libreroute.util.JitsiBrowserHandoff
import io.github.libreroute.util.JitsiTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val vm: TunnelsViewModel by viewModels()
    private var captchaOpen = false
    private var captchaDocumentUrl: String? = null
    private var authDialogOpen = false
    private val captchaResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        captchaOpen = false
        val checkedDocument = captchaDocumentUrl
        captchaDocumentUrl = null
        if (result.resultCode == RESULT_OK && checkedDocument != null) {
            EventBus.clearPendingYandexAuthIssue(checkedDocument)
            val selected = vm.selected.value
            if (selected?.transportType in setOf("yandex", "vyandex") &&
                selected?.let { routeUrl(it) } == checkedDocument) vm.restartCurrent()
        }
    }

    override fun onResume() {
        super.onResume()
        AppUpdateChecker.checkForUpdate(this)
        // A pending challenge stays available through the explicit check button.
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemePreferences(this).applyTheme()
        super.onCreate(savedInstanceState)
        captchaOpen = savedInstanceState?.getBoolean("captcha_open") ?: false
        captchaDocumentUrl = savedInstanceState?.getString("captcha_document")

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

        if (savedInstanceState == null) supportFragmentManager.beginTransaction()
            .replace(R.id.main, TunnelsFragment(), "")
            .commit()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                EventBus.events.collect { ev ->
                    supportFragmentManager.fragments.forEach { f ->
                        if (f is BaseFragment) f.onNewEvent(ev)
                    }
                }
            }
        }

        handleIncomingIntent(intent)
    }

    fun openPendingYandexAuthIssue() {
        if (captchaOpen) return
        val issue = EventBus.pendingYandexAuthIssue() ?: return
        val selected = vm.selected.value ?: return
        if (selected.transportType !in setOf("yandex", "vyandex") || routeUrl(selected) != issue.documentUrl) return
        openYandexWebView(issue)
    }

    private fun openYandexWebView(issue: AppEvent.YandexAuthIssue) {
        captchaOpen = true
        captchaDocumentUrl = issue.documentUrl
        captchaResult.launch(Intent(this, YandexCaptchaActivity::class.java).apply {
            putExtra(YandexCaptchaActivity.EXTRA_DOCUMENT_URL, issue.documentUrl)
            putExtra(YandexCaptchaActivity.EXTRA_REASON, issue.reason.name)
            putExtra(YandexCaptchaActivity.EXTRA_TRANSPORT, vm.selected.value?.transportType ?: "vyandex")
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("captcha_open", captchaOpen)
        outState.putString("captcha_document", captchaDocumentUrl)
        super.onSaveInstanceState(outState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent) {
        if (intent.action == Intent.ACTION_SEND) {
            handleJitsiBrowserShare(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            return
        }
        handleDeepLink(intent)
    }

    private fun handleJitsiBrowserShare(sharedText: String?) {
        val handoff = JitsiBrowserHandoff.parseSharedText(sharedText)
        if (handoff == null) {
            Toast.makeText(this, "Ссылка не содержит проверяемый Jitsi-токен", Toast.LENGTH_LONG).show()
            return
        }

        val tunnel = vm.selected.value
        val expectedRoom = tunnel?.takeIf { it.transportType.equals("jitsi", true) }?.let { routeUrl(it) }
        if (expectedRoom.isNullOrBlank() || !JitsiBrowserHandoff.sameRoom(expectedRoom, handoff.roomUrl)) {
            Toast.makeText(this, "Ссылка Jitsi не соответствует выбранному маршруту", Toast.LENGTH_LONG).show()
            return
        }

        if (JitsiTokenStore.isExpired(handoff.token)) {
            Toast.makeText(this, R.string.jitsi_token_expired, Toast.LENGTH_LONG).show()
            return
        }
        JitsiTokenStore(this).put(handoff.roomUrl, handoff.token)
        EventBus.clearPendingJitsiIssue()
        Toast.makeText(this, "Jitsi-вход получен. Подключение будет перезапущено", Toast.LENGTH_LONG).show()
        if (vm.active.value.isActive) {
            vm.restartCurrent()
        } else {
            supportFragmentManager.fragments.filterIsInstance<TunnelsFragment>().firstOrNull()?.connectFromInvitation()
        }
    }

    private fun routeUrl(tunnel: io.github.libreroute.data.Tunnel): String {
        val payload = tunnel.transportConnPayload
        return payload.firstOrNull { it.startsWith("--url=") }?.substringAfter('=')
            ?: payload.indexOf("--url").takeIf { it >= 0 }?.let { payload.getOrNull(it + 1) }.orEmpty()
    }

    private fun handleDeepLink(intent: Intent) {
        val uri = intent.data ?: return
        if (!uri.scheme.equals("libreroute", ignoreCase = true) &&
            !uri.scheme.equals("libreroute", ignoreCase = true) &&
              !uri.scheme.equals("paperflux", ignoreCase = true)) return
        if (uri.scheme.equals("libreroute", ignoreCase = true) &&
            (uri.host.equals("admin-request", ignoreCase = true) ||
             uri.host.equals("admin-grant", ignoreCase = true) ||
             uri.host.equals("admin-invite", ignoreCase = true))) {
            startActivity(Intent(this, io.github.libreroute.admin.AdminSetupActivity::class.java).putExtra("admin_link", uri.toString()))
            intent.data = null
            return
        }
        if (uri.scheme.equals("libreroute", ignoreCase = true) && uri.host.equals("admin-transfer-ack", ignoreCase = true)) {
            startActivity(Intent(this, io.github.libreroute.admin.AdminActivity::class.java).putExtra("admin_transfer_ack", uri.toString()))
            intent.data = null
            return
        }
        if (uri.scheme.equals("libreroute", ignoreCase = true) &&
            uri.host.equals("invitation", ignoreCase = true)) {
            handleInvitationUri(uri)
            return
        }
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

    private val invitationResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) vm.clearAccessRevocation()
        vm.refresh()
        if (result.resultCode == RESULT_OK && result.data?.getBooleanExtra("connect", false) == true) {
            // Use the existing permission flow on the main screen.
            supportFragmentManager.fragments.filterIsInstance<TunnelsFragment>().firstOrNull()?.connectFromInvitation()
        }
    }

    private fun handleInvitationUri(uri: android.net.Uri) {
        invitationResult.launch(Intent(this, AccessActivity::class.java).putExtra("invitation", uri.toString()))
        intent.data = null
    }
}
