package io.github.libreroute.phone

import android.app.Activity
import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.view.View
import android.widget.TextView
import io.github.libreroute.R
import io.github.libreroute.ui.MainActivity
import io.github.libreroute.ui.TunnelsFragment
import io.github.libreroute.ui.LogsActivity

/** Opt-in checks against the actual installed home screen. */
internal fun Instrumentation.runHomeScreenChecks(): List<String> {
        fun onUi(block: () -> Unit) {
            var result: Result<Unit>? = null
            runOnMainSync { result = runCatching(block) }
            requireNotNull(result).getOrThrow()
        }
        val checks = mutableListOf<String>()
            val mainMonitor = addMonitor(MainActivity::class.java.name, null, false)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
                "am start -W -n ${targetContext.packageName}/io.github.libreroute.ui.MainActivity"
            )).use { it.readBytes() }
            fun awaitMain(previous: Activity? = null): Activity {
                val deadline = android.os.SystemClock.elapsedRealtime() + 15_000
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    val candidate = mainMonitor.lastActivity
                    var ready = false
                    if (candidate != null && candidate !== previous) onUi {
                        ready = !candidate.isDestroyed && candidate.findViewById<View>(R.id.connectButton)?.width?.let { it > 0 } == true
                    }
                    if (ready) return requireNotNull(candidate)
                    Thread.sleep(100)
                }
                error("MainActivity did not finish rendering")
            }
            var main = awaitMain()
            fun checkHome(activity: Activity) = onUi {
                for (id in listOf(R.id.connectButton, R.id.configSelector, R.id.homeModeCard, R.id.homeDiagnostics, R.id.btnLogs)) {
                    val view = requireNotNull(activity.findViewById<View>(id)) { "Missing ${activity.resources.getResourceEntryName(id)}" }
                    check(view.visibility == View.VISIBLE && view.width > 0 && view.height > 0) { "${activity.resources.getResourceEntryName(id)}: visibility=${view.visibility}, size=${view.width}x${view.height}" }
                }
                check(activity.findViewById<TextView>(R.id.homeModeTitle).text.isNotBlank())
                check(activity.findViewById<TextView>(R.id.homeLocalStatus).text.isNotBlank())
            }
            checkHome(main)
            onUi { check(main.findViewById<android.view.ViewGroup>(R.id.homeSharingCard).findViewById<View>(R.id.homeModeCard) != null) }
            checks += "PASS home controls and live diagnostics rendered"
            sendStatus(0, android.os.Bundle().apply { putString("stream", checks.last() + "\n") })
            val logsMonitor = addMonitor(LogsActivity::class.java.name, null, false)
            onUi { main.findViewById<View>(R.id.btnLogs).performClick() }
            val logs = requireNotNull(logsMonitor.waitForActivityWithTimeout(10_000))
            onUi { logs.finish() }
            removeMonitor(logsMonitor)
            checks += "PASS diagnostics opens journal"
            Thread.sleep(500)
            fun currentDialog(): android.app.Dialog {
                val fragment = (main as MainActivity).supportFragmentManager.fragments.filterIsInstance<TunnelsFragment>().first()
                val delegate = TunnelsFragment::class.java.getDeclaredField("dialogs\$delegate").apply { isAccessible = true }
                val owner = (delegate.get(fragment) as Lazy<*>).value!!
                val screens = owner.javaClass.getDeclaredField("screens").apply { isAccessible = true }.get(owner) as List<*>
                val screen = requireNotNull(screens.lastOrNull())
                return screen.javaClass.getDeclaredField("dialog").apply { isAccessible = true }.get(screen) as android.app.Dialog
            }
            onUi { main.findViewById<View>(R.id.homeModeCard).performClick() }
            onUi {
                val dialog = currentDialog() as androidx.appcompat.app.AlertDialog
                check(dialog.isShowing)
                check(dialog.findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.text == main.getString(R.string.home_connection_mode_title))
                val settings = io.github.libreroute.util.AppSettings(targetContext)
                val originalProxyOnly = settings.proxyOnlyMode
                val phone = requireNotNull(dialog.findViewById<android.widget.RadioGroup>(R.id.home_phone_mode))
                val routes = requireNotNull(dialog.findViewById<android.widget.RadioGroup>(R.id.home_proxy_routes))
                check(phone.checkedRadioButtonId == if (settings.proxyOnlyMode) R.id.home_phone_proxy else R.id.home_phone_vpn)
                check(routes.checkedRadioButtonId == if (settings.proxyRoutingMode == io.github.libreroute.util.ProxyRoutingMode.RULES) R.id.home_proxy_rules else R.id.home_proxy_server)
                phone.check(if (settings.proxyOnlyMode) R.id.home_phone_vpn else R.id.home_phone_proxy)
                dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick()
                check(io.github.libreroute.util.AppSettings(targetContext).proxyOnlyMode == originalProxyOnly)
            }
            checks += "PASS mode card opens its own connection controls"
            onUi {
                main.findViewById<View>(R.id.homeSharingSettings).performClick()
                val dialog = currentDialog()
                check(dialog.isShowing)
                val port = dialog.findViewById<android.widget.EditText>(R.id.home_proxy_port)
                port.setText("1")
                dialog.findViewById<View>(R.id.home_proxy_save).performClick()
                check(dialog.isShowing && port.error != null)
                port.setText("10808")
                dialog.findViewById<android.widget.EditText>(R.id.home_proxy_user).setText("phone-home-draft")
            }
            checks += "PASS SOCKS gear opens proxy parameters and rejects invalid port"
            Thread.sleep(500)
            onUi { main.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            main = awaitMain(main)
            Thread.sleep(500)
            checkHome(main)
            onUi {
                val dialog = currentDialog()
                check(dialog.findViewById<android.widget.EditText>(R.id.home_proxy_user).text.toString() == "phone-home-draft")
                check(dialog.findViewById<android.widget.EditText>(R.id.home_proxy_port).text.toString() == "10808")
            }
            onUi { main.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            main = awaitMain(main)
            Thread.sleep(500)
            checkHome(main)
            onUi { currentDialog().findViewById<View>(R.id.home_proxy_cancel).performClick() }
            checks += "PASS portrait and landscape recreation"
            removeMonitor(mainMonitor)
            return checks + "HOME_SCREEN_PASS"
}
