package io.github.libreroute.phone

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
import io.github.libreroute.R
import io.github.libreroute.data.*
import io.github.libreroute.service.HotspotProxyBridge
import io.github.libreroute.service.LibreRouteProxyService
import io.github.libreroute.ui.MainActivity
import io.github.libreroute.ui.SettingsActivity
import io.github.libreroute.util.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/** Opt-in live acceptance. Restores every preference it changes in finally. */
class PhoneAcceptanceInstrumentation : Instrumentation() {
    private val checks = mutableListOf<String>()
    private val backups = linkedMapOf<SharedPreferences, Map<String, *>>()
    private var endpoint: ServerSocket? = null
    private val received = CopyOnWriteArrayList<String>()
    private var lanPort = 0
    private var external = false
    private var serverChecks = false
    private var homeChecks = false
    private var jazzChecks = false
    private var adminFeedbackChecks = false
    private var originalSnapshots = emptySet<String>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        external = arguments?.getString("external") == "1"
        serverChecks = arguments?.getString("server") == "1"
        homeChecks = arguments?.getString("home") == "1"
        jazzChecks = arguments?.getString("jazz") == "1"
        adminFeedbackChecks = arguments?.getString("admin_feedback") == "1"
        start()
    }

    override fun onStart() {
        var failure: Throwable? = null
        try {
            if (adminFeedbackChecks) checks += runAdminFeedbackChecks()
            else if (jazzChecks) jazzAcceptance() else if (homeChecks) checks += runHomeScreenChecks() else acceptance()
        } catch (e: Throwable) { failure = e }
        finally {
            if (!homeChecks && !jazzChecks && !adminFeedbackChecks) runCatching { disconnect() }
            runCatching { endpoint?.close() }
            backups.forEach { (prefs, values) ->
                val edit = prefs.edit().clear()
                values.forEach { (key, value) -> when (value) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Set<*> -> @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>)
                } }
                check(edit.commit()) { "Preference restoration failed" }
            }
        }
        val result = Bundle().apply {
            putString("stream", checks.joinToString("\n", postfix = "\n") +
                if (failure == null) "PHONE_ACCEPTANCE_PASS\n" else "PHONE_ACCEPTANCE_FAIL: ${failure.javaClass.simpleName}: ${failure.message}\n${failure.stackTraceToString()}\n")
        }
        finish(if (failure == null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
    }

    private fun record(name: String) {
        checks += "PASS $name"
        sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") })
    }

    /** Uses the selected profile and current settings without replacing either. */
    private fun jazzAcceptance() {
        check(TunnelRepository(targetContext).getSelected()?.transportType.equals("jazz", true))
        val monitor = addMonitor(MainActivity::class.java.name, null, false)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
            "am start -W -n ${targetContext.packageName}/io.github.libreroute.ui.MainActivity"
        )).use { it.readBytes() }
        waitFor("home rendered", 15_000) {
            val activity = monitor.lastActivity
            activity != null && !activity.isDestroyed && activity.findViewById<android.view.View>(io.github.libreroute.R.id.connectButton)?.width?.let { it > 0 } == true
        }
        var clickResult: Result<Unit>? = null
        runOnMainSync { clickResult = runCatching {
            monitor.lastActivity.findViewById<android.view.View>(io.github.libreroute.R.id.connectButton).performClick()
            Unit
        } }
        requireNotNull(clickResult).getOrThrow()
        removeMonitor(monitor)
        waitFor("selected Jazz ready", 75_000) { LocalSocksSession.getReady() != null }
        waitFor("selected Jazz service running", 10_000) {
            io.github.libreroute.service.SocksVpnService.connectedAtRealtime > 0 || LibreRouteProxyService.isProxyRunning
        }
        record("selected Jazz connected using saved settings")
        val session = requireNotNull(LocalSocksSession.getReady())
        val started = io.github.libreroute.service.SocksVpnService.connectedAtRealtime to LibreRouteProxyService.connectedAtRealtime
        repeat(6) {
            val latency = io.github.libreroute.service.ProxyConnectivityProbe.measure(session, 5000)
            check(latency >= 0) { "Jazz HTTPS probe failed at sample $it" }
            record("Jazz HTTPS probe ${it + 1}: ${latency}ms")
            // Exercise a refused individual TCP flow through the same transport.
            runCatching {
                io.github.libreroute.service.ProxyConnectivityProbe.connect(session, "example.org", 1, 1500).close()
            }
            Thread.sleep(10_000)
            check((io.github.libreroute.service.SocksVpnService.connectedAtRealtime to LibreRouteProxyService.connectedAtRealtime) == started)
            check(LocalSocksSession.getReady() === session) { "Native Jazz restarted during traffic" }
        }
        record("Jazz session survives 60 seconds of HTTPS and refused TCP flows without native restart")
    }

    private fun waitFor(name: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + timeout
        while (!predicate() && SystemClock.elapsedRealtime() < end) Thread.sleep(50)
        check(predicate()) { "Timeout: $name" }
    }

    private fun remember(name: String) {
        val prefs = targetContext.getSharedPreferences(name, Context.MODE_PRIVATE)
        backups[prefs] = HashMap(prefs.all)
    }

    private fun acceptance() {
        check(!LibreRouteProxyService.isProxyRunning && !LibreRouteProxyService.isProxyStarting) { "Stop active debug proxy before testing" }
        originalSnapshots = targetContext.filesDir.listFiles().orEmpty().filter { it.name.startsWith("site-routes-") }.map { it.name }.toSet()
        listOf("libreroute_settings", "libreroute_site_routing", "libreroute_zapret_profiles", "profile").forEach(::remember)
        val homeMonitor = addMonitor(MainActivity::class.java.name, null, false)
        android.os.ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(
            "am start -W -n ${targetContext.packageName}/io.github.libreroute.ui.MainActivity"
        )).use { it.readBytes() }
        var homeActivity = requireNotNull(homeMonitor.waitForActivityWithTimeout(10_000)) { "Home did not open" }
        Thread.sleep(1500)
        homeActivity = homeMonitor.lastActivity ?: homeActivity
        removeMonitor(homeMonitor)
        val repo = TunnelRepository(targetContext)
        val originalSelected = repo.getSelected()
        val direct = repo.load().first(DirectDpiProfile::isDirect)
        repo.setSelectedId(direct.id)
        val settings = AppSettings(targetContext)
        settings.proxyOnlyMode = true
        settings.proxyRoutingMode = ProxyRoutingMode.ALL_TUNNEL
        settings.shareLanProxy = true
        settings.socks5AuthEnabled = false
        settings.verboseLog = true
        lanPort = ServerSocket(0).use { it.localPort }
        settings.lanProxyPort = lanPort
        val standard = ZapretProfile.DEFAULT
        val profiles = ZapretProfileState(listOf(standard,
            standard.copy(id = "phone-test-a", name = "Phone test A"),
            standard.copy(id = "phone-test-b", name = "Phone test B")))
        ZapretProfilePreferences(targetContext).store(profiles)
        val controlIp = LocalSocksSession.getLocalIpAddress()
        check(controlIp.isNotBlank()) { "Wi-Fi address required" }
        val controlDomain = "$controlIp.nip.io"
        check(InetAddress.getAllByName(controlDomain).any { it.hostAddress == controlIp }) { "Control domain does not resolve to Wi-Fi address" }
        val domainA = controlIp.replace('.', '-') + ".nip.io"
        val domainB = "$controlIp.sslip.io"
        for (domain in listOf(domainA, domainB)) check(InetAddress.getAllByName(domain).any { it.hostAddress == controlIp })
        val sites = SiteRoutingPreferences(targetContext)
        sites.store(SiteRoutingState(true, listOf(SiteRule(controlDomain, "direct"),
            SiteRule(domainA, "zapret", "phone-test-a"), SiteRule(domainB, "zapret", "phone-test-b")), defaultTunnel = true), profiles)
        val server = ServerSocket(0, 32, InetAddress.getByName("0.0.0.0"))
        endpoint = server
        thread(isDaemon = true, name = "PhoneControlEndpoint") {
            while (!server.isClosed) {
                val peer = runCatching { server.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    peer.use {
                        it.soTimeout = 60_000
                        val reader = it.getInputStream().bufferedReader()
                        val request = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) { }
                        received += request
                        if ("/half-close" in request) while (reader.read() != -1) { }
                        val body = if ("/half-close" in request) "PHONE_ENDPOINT_OK".repeat(65536) else "PHONE_ENDPOINT_OK"
                        it.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body").toByteArray())
                    }
                }
            }
        }
        launch(direct)
        record("autonomous native Core/tpws ready without TUN")
        check(!ProxyRoutingStatus.tunnelReady && ProxyRoutingStatus.tunnelProbePort == 0)
        check(AppSettings(targetContext).proxyRoutingMode == ProxyRoutingMode.ALL_TUNNEL)
        record("no remote readiness; saved server mode preserved")
        val originalSession = LocalSocksSession.getReady()
        val originalSnapshot = ProxyRoutingStatus.active
        val originalStartedAt = LibreRouteProxyService.connectedAtRealtime
        runOnMainSync { homeActivity.findViewById<MaterialSwitch>(R.id.homeSharingSwitch).isChecked = false }
        waitFor("home LAN switch off") { !HotspotProxyBridge.running }
        check(!settings.shareLanProxy)
        request("local-socks", controlDomain, server.localPort)
        check(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", lanPort), 1000) } }.isFailure)
        waitFor("home LAN switch enabled") { homeActivity.findViewById<MaterialSwitch>(R.id.homeSharingSwitch).isEnabled }
        runOnMainSync { homeActivity.findViewById<MaterialSwitch>(R.id.homeSharingSwitch).isChecked = true }
        waitFor("home LAN switch on") { HotspotProxyBridge.running }
        check(settings.shareLanProxy)
        request("lan-socks", controlDomain, server.localPort)
        check(originalSession === LocalSocksSession.getReady() && originalSnapshot == ProxyRoutingStatus.active)
        check(originalStartedAt == LibreRouteProxyService.connectedAtRealtime)
        record("home LAN switch stops/restores sharing without restarting native proxy or local SOCKS")
        val homeRotationMonitor = addMonitor(MainActivity::class.java.name, null, false)
        runOnMainSync { homeActivity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        homeActivity = requireNotNull(homeRotationMonitor.waitForActivityWithTimeout(10_000))
        Thread.sleep(500)
        request("lan-socks", controlDomain, server.localPort)
        runOnMainSync { homeActivity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        homeActivity = requireNotNull(homeRotationMonitor.waitForActivityWithTimeout(10_000))
        Thread.sleep(500)
        removeMonitor(homeRotationMonitor)
        request("lan-socks", controlDomain, server.localPort)
        check(settings.shareLanProxy && LocalSocksSession.getReady() === originalSession)
        check(originalStartedAt == LibreRouteProxyService.connectedAtRealtime)
        record("home rotation keeps LAN sharing and native session alive")
        for (protocol in listOf("local-socks", "lan-socks", "http", "connect")) request(protocol, controlDomain, server.localPort)
        record("direct TCP local SOCKS/LAN SOCKS/HTTP/CONNECT")
        request("local-socks", controlIp, server.localPort)
        request("lan-socks", controlIp, server.localPort)
        record("IP default=tunnel uses common production tpws")
        for (domain in listOf(domainA, domainB)) for (protocol in listOf("local-socks", "lan-socks", "http", "connect")) request(protocol, domain, server.localPort)
        check(ProxyRoutingStatus.profileReadiness?.invoke()?.values?.count { it } == 3)
        record("two assigned profiles and common tpws ready simultaneously")
        if (external) {
            val ack = java.io.File(targetContext.filesDir, "phone-acceptance-continue")
            ack.delete()
            sendStatus(0, Bundle().apply { putString("stream", "PHONE_LAN_CHECK proxy=$controlIp:$lanPort target=$controlDomain:${server.localPort}\n") })
            waitFor("external LAN client", 60_000) { ack.exists() }
            ack.delete()
        }
        val snapshot = requireNotNull(ProxyRoutingStatus.active)
        sites.store(SiteRoutingState(true, listOf(SiteRule(controlDomain, "zapret", "phone-test-a")), true), profiles)
        check(ProxyRoutingStatus.pending(targetContext) && ProxyRoutingStatus.active == snapshot)
        request("local-socks", controlDomain, server.localPort)
        record("settings changes remain pending until reconnect")
        disconnect()
        launch(direct)
        for (protocol in listOf("local-socks", "lan-socks", "http", "connect")) request(protocol, controlDomain, server.localPort)
        check(ProxyRoutingStatus.profileReadiness?.invoke()?.get("phone-test-a") == true)
        record("assigned production tpws A across all proxy protocols")
        disconnect()
        sites.store(SiteRoutingState(true, listOf(SiteRule(controlDomain, "zapret", "phone-test-b")), false), profiles)
        launch(direct)
        request("local-socks", controlDomain, server.localPort)
        request("http", controlDomain, server.localPort)
        request("local-socks", controlIp, server.localPort)
        record("assigned production tpws B; IP default=direct")
        disconnect()
        settings.socks5AuthEnabled = true
        sites.store(SiteRoutingState(true, listOf(SiteRule(controlDomain, "tunnel")), true), profiles)
        launch(direct)
        for (protocol in listOf("local-socks", "lan-socks", "http", "connect")) request(protocol, controlDomain, server.localPort)
        record("common production tpws with SOCKS/HTTP authentication")
        for (protocol in listOf("local-socks", "lan-socks", "http", "connect")) {
            request(protocol, controlDomain, server.localPort, halfClose = true)
            record("half-close complete via $protocol and common tpws")
        }
        record("large complete response after upload half-close across all proxy protocols")
        request("connect", controlDomain, server.localPort, idleMillis = 35_000)
        record("CONNECT survives 35 seconds idle after handshake")
        val session = requireNotNull(LocalSocksSession.getReady())
        val before = received.size
        check(runCatching { request("local-socks", controlDomain, server.localPort, badAuth = true) }.isFailure)
        check(runCatching { request("http", controlDomain, server.localPort, badAuth = true) }.isFailure)
        check(received.size == before)
        record("bad SOCKS/HTTP credentials rejected before endpoint")
        uiAutomation.executeShellCommand("am start -n ${targetContext.packageName}/io.github.libreroute.ui.MainActivity").close()
        Thread.sleep(1500)
        val activity = startActivitySync(Intent(targetContext, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        runOnMainSync {
            val sw = activity.findViewById<MaterialSwitch>(R.id.switch_proxy_routing)
            check(!sw.isEnabled && sw.isChecked)
            check(activity.findViewById<TextView>(R.id.text_proxy_routing).text.contains("Удалённого сервера нет"))
        }
        record("live settings disable all_tunnel and explain absent server")
        val monitor = addMonitor(SettingsActivity::class.java.name, null, false)
        runOnMainSync {
            activity.findViewById<android.view.View>(R.id.row_socks5_auth).performClick()
            val dialog = authDialog(activity)
            dialog.findViewById<android.widget.EditText>(R.id.input_socks_user).setText("phone-draft-user")
            dialog.findViewById<android.widget.EditText>(R.id.input_socks_pass).setText("phone-draft-only")
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        val rotated = requireNotNull(monitor.waitForActivityWithTimeout(10_000)) { "Settings did not rotate" }
        runOnMainSync {
            val dialog = authDialog(rotated)
            check(dialog.findViewById<android.widget.EditText>(R.id.input_socks_user).text.toString() == "phone-draft-user")
            check(dialog.findViewById<android.widget.EditText>(R.id.input_socks_pass).text.toString() == "phone-draft-only")
            check(!rotated.findViewById<MaterialSwitch>(R.id.switch_proxy_routing).isEnabled)
            rotated.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        val portrait = requireNotNull(monitor.waitForActivityWithTimeout(10_000)) { "Settings did not return to portrait" }
        runOnMainSync { portrait.finish() }
        removeMonitor(monitor)
        record("rotation restores auth dialog, unsaved credentials and autonomous mode")
        val oldPort = session.port
        disconnect()
        check(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", oldPort), 1000) } }.isFailure)
        check(!HotspotProxyBridge.running && ProxyRoutingStatus.active == null)
        check(targetContext.filesDir.listFiles().orEmpty().none { it.name.startsWith("site-routes-") && it.name !in originalSnapshots })
        record("disconnect closes local/LAN listeners and deletes session snapshot")
        if (serverChecks) {
            val remote = requireNotNull(originalSelected?.takeUnless(DirectDpiProfile::isDirect)) { "Select a remote profile before server acceptance" }
            repo.setSelectedId(remote.id)
            settings.socks5AuthEnabled = false
            settings.proxyRoutingMode = ProxyRoutingMode.RULES
            sites.store(SiteRoutingState(true, listOf(SiteRule(controlDomain, "direct"), SiteRule("example.org", "tunnel")), true), profiles)
            launch(remote)
            request("local-socks", controlDomain, server.localPort)
            waitFor("remote transport ready", 75_000) { ProxyRoutingStatus.tunnelReady }
            for (protocol in listOf("local-socks", "lan-socks", "http", "connect")) request(protocol, "example.org", 80, publicEndpoint = true)
            record("selected remote transport tunnel via local/LAN SOCKS HTTP CONNECT")
            disconnect()
            settings.proxyRoutingMode = ProxyRoutingMode.ALL_TUNNEL
            sites.store(SiteRoutingState(true, listOf(SiteRule("example.org", "direct")), false), profiles)
            launch(remote)
            waitFor("all_tunnel remote ready", 75_000) { ProxyRoutingStatus.tunnelReady }
            request("local-socks", "example.org", 80, publicEndpoint = true)
            request("connect", "example.org", 80, publicEndpoint = true)
            check(ProxyRoutingStatus.active?.mode == ProxyRoutingMode.ALL_TUNNEL)
            record("all_tunnel with saved direct site and default direct")
            disconnect()
        }
    }

    // Inspect our own dialog owner, avoiding system input injection restrictions.
    private fun authDialog(activity: Activity): android.app.Dialog {
        val delegateField = SettingsActivity::class.java.getDeclaredField("dialogs\$delegate").apply { isAccessible = true }
        val owner = (delegateField.get(activity) as Lazy<*>).value!!
        val screens = owner.javaClass.getDeclaredField("screens").apply { isAccessible = true }.get(owner) as List<*>
        val screen = requireNotNull(screens.lastOrNull()) { "Auth dialog not restored" }
        return screen.javaClass.getDeclaredField("dialog").apply { isAccessible = true }.get(screen) as android.app.Dialog
    }

    private fun launch(tunnel: Tunnel) {
        val prepared = TunnelLinkParser.ensureLocalKeyFile(targetContext, tunnel)
        targetContext.startForegroundService(Intent(targetContext, LibreRouteProxyService::class.java).apply {
            putExtra(Constants.INTENT_NAME, prepared.name)
            putExtra(Constants.INTENT_TUNNEL_ID, prepared.id)
            putExtra(Constants.INTENT_TRANSPORT_TYPE, prepared.transportType)
            putExtra(Constants.INTENT_TRANSPORT_PAYLOAD, prepared.transportConnPayload.toTypedArray())
        })
        waitFor("autonomous listener") { LibreRouteProxyService.isProxyRunning && LocalSocksSession.getReady() != null }
        waitFor("LAN bridge") { HotspotProxyBridge.running }
        check(ProxyRoutingStatus.active?.autonomous == DirectDpiProfile.isDirect(tunnel))
    }

    private fun disconnect() {
        if (LibreRouteProxyService.isProxyRunning || LibreRouteProxyService.isProxyStarting) {
            targetContext.startService(Intent(targetContext, LibreRouteProxyService::class.java).setAction(LibreRouteProxyService.ACTION_DISCONNECT))
            waitFor("proxy shutdown") { !LibreRouteProxyService.isProxyRunning && !LibreRouteProxyService.isProxyStarting && LocalSocksSession.getReady() == null }
            Thread.sleep(250)
        }
    }

    private fun request(protocol: String, host: String, port: Int, badAuth: Boolean = false,
                        halfClose: Boolean = false, idleMillis: Long = 0, publicEndpoint: Boolean = false) {
        val session = requireNotNull(LocalSocksSession.getReady())
        val auth = if (session.isAuthEnabled) "Proxy-Authorization: Basic " + android.util.Base64.encodeToString(
            "${session.username}:${if (badAuth) "incorrect-test-password" else session.password}".toByteArray(), android.util.Base64.NO_WRAP) + "\r\n" else ""
        Socket().use { socket ->
            socket.soTimeout = 15_000
            socket.connect(InetSocketAddress("127.0.0.1", if (protocol == "local-socks") session.port else lanPort), 5000)
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            if (protocol.endsWith("socks")) {
                check(HotspotProxyBridge.socks5Connect(input, output, host, port,
                    if (session.isAuthEnabled) session.username else "",
                    if (session.isAuthEnabled) if (badAuth) "incorrect-test-password" else session.password else "")) { "SOCKS connection refused" }
            } else if (protocol == "connect") {
                output.write("CONNECT $host:$port HTTP/1.1\r\nHost: $host:$port\r\n$auth\r\n".toByteArray())
                output.flush()
                val header = StringBuilder()
                while (!header.endsWith("\r\n\r\n")) { val b = input.read(); check(b >= 0); header.append(b.toChar()) }
                check(header.startsWith("HTTP/1.1 200")) { "CONNECT refused" }
            }
            if (idleMillis > 0) Thread.sleep(idleMillis)
            val path = if (halfClose) "/half-close" else if (publicEndpoint) "/" else "/phone-check"
            val target = if (protocol == "http") "http://$host:$port$path" else path
            output.write("GET $target HTTP/1.1\r\nHost: $host:$port\r\n${if (protocol == "http") auth else ""}Connection: close\r\n\r\n".toByteArray())
            output.flush()
            if (halfClose) socket.shutdownOutput()
            val response = input.readBytes().toString(Charsets.UTF_8)
            if (publicEndpoint) check(Regex("HTTP/1\\.[01] [23]\\d\\d").containsMatchIn(response)) { "Public endpoint failed: ${response.take(40)}" }
            else if (halfClose) check(response.substringAfter("\r\n\r\n") == "PHONE_ENDPOINT_OK".repeat(65536)) { "Half-close response truncated: ${response.length}" }
            else check(response.contains("PHONE_ENDPOINT_OK")) { "Endpoint response missing: ${response.take(40)}" }
        }
    }
}
