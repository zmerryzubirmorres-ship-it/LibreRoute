package io.github.p1neapplexpress.openflux.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import androidx.core.content.ContextCompat
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.net.TrafficStats
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import io.github.p1neapplexpress.openflux.NativeBridge
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.ui.MainActivity
import io.github.p1neapplexpress.openflux.util.AppIconManager
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.LocalSocksSession
import io.github.p1neapplexpress.openflux.util.Logx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Lightweight SOCKS5-only foreground service.
 *
 * Unlike [SocksVpnService] this does NOT extend [android.net.VpnService]:
 * - No TUN network interface is created
 * - No VPN key icon appears in the status bar
 * - No VPN permission consent dialog is needed
 * - Traffic is NOT routed system-wide — apps must be manually pointed
 *   at the local SOCKS5 port (shown in notification)
 *
 * Use-cases:
 *  1. User already has a VPN active and wants to route only specific apps
 *  2. User does not want the VPN indicator (e.g. streaming devices)
 *  3. Quick proxy without changing routing tables
 *
 * The service starts FluxonCore in SOCKS5-inbound mode (no --inbound=tun,
 * no --tun-socket). The local proxy port is shown in the persistent
 * notification so the user can configure their apps.
 */
class FluxonProxyService : Service() {

    companion object {
        private const val TAG = "FluxonProxyService"

        const val ACTION_DISCONNECT = "io.github.p1neapplexpress.openflux.PROXY_DISCONNECT"

        private const val CHANNEL_ID = "io.github.p1neapplexpress.openflux.proxy"
        private const val NOTIFICATION_ID = 2

        @Volatile var isProxyRunning = false
        @Volatile var proxyPort = 0
        @Volatile var activeTunnelName: String? = null
        @Volatile var connectedAtRealtime: Long = 0L
    }

    private lateinit var supervisor: NativeProcessSupervisor
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val alreadyStopping = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        runCatching { NativeBridge.ensureLoaded(applicationContext) }
        supervisor = NativeProcessSupervisor(applicationContext)
        serviceScope.launch {
            EventBus.events.collect { ev ->
                if (ev is AppEvent.RefreshNotification && isProxyRunning) {
                    val nm = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager
                    val notif = buildNotification(
                        getString(R.string.app_name) + " — SOCKS5 ✓",
                        "SOCKS5 127.0.0.1:$proxyPort"
                    )
                    nm?.notify(NOTIFICATION_ID, notif)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Logx.i(TAG, "ACTION_DISCONNECT received")
            stopProxy()
            return START_NOT_STICKY
        }

        if (isProxyRunning) {
            Logx.d(TAG, "Proxy already running, ignoring duplicate start")
            return START_STICKY
        }

        alreadyStopping.set(false)

        val transportType = intent?.getStringExtra(Constants.INTENT_TRANSPORT_TYPE) ?: "yandex"
        val transportPayload = try {
            intent?.getStringArrayExtra(Constants.INTENT_TRANSPORT_PAYLOAD)
                ?: intent?.getStringArrayListExtra(Constants.INTENT_TRANSPORT_PAYLOAD)?.toTypedArray()
                ?: emptyArray()
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to read payload", e)
            emptyArray()
        }

        val appSettings = AppSettings(applicationContext)
        val session = LocalSocksSession.generateNew(
            authEnabled = appSettings.socks5AuthEnabled,
            customUser = appSettings.socks5CustomUser,
            customPass = appSettings.socks5CustomPass,
            shareLan = appSettings.shareLanProxy
        )
        proxyPort = session.port

        // Build the payload: inject SOCKS5 inbound, strip any TUN args
        val proxyPayload = buildProxyPayload(transportPayload, session.port)

        val tunnelName = intent?.getStringExtra(Constants.INTENT_NAME) ?: getString(R.string.app_name)
        startForegroundProxy(tunnelName, session.port)

        serviceScope.launch(Dispatchers.IO) {
            EventBus.dispatch(AppEvent.LogMessage("[I] [PROXY] Запуск FluxonCore в режиме SOCKS5-прокси (порт ${session.port})..."))
            supervisor.start(transportType, proxyPayload)
            if (!supervisor.isRunning) {
                EventBus.dispatch(AppEvent.LogMessage("[E] [PROXY] OpenFlux core did not start"))
                stopProxy()
                return@launch
            }

            // Wait for connection (max 20 sec)
            var ready = false
            for (step in 1..80) {
                kotlinx.coroutines.delay(250)
                if (alreadyStopping.get()) return@launch
                if (supervisor.isConnected) {
                    ready = true
                    break
                }
            }

            if (ready) {
                isProxyRunning = true
                activeTunnelName = tunnelName
                connectedAtRealtime = android.os.SystemClock.elapsedRealtime()
                EventBus.dispatch(AppEvent.LogMessage("[S] [PROXY] SOCKS5-прокси активен на 127.0.0.1:${session.port}"))
                EventBus.dispatch(AppEvent.VpnConnected(tunnelName))
                Logx.i(TAG, "Proxy mode active on port ${session.port}")

                updateNotificationConnected(tunnelName, session.port)

                if (appSettings.shareLanProxy) {
                    HotspotProxyBridge.start(
                        lanPort = appSettings.lanProxyPort,
                        targetLocalPort = session.port,
                        authEnabled = appSettings.socks5AuthEnabled,
                        username = session.username,
                        password = session.password
                    )
                }
            } else {
                EventBus.dispatch(AppEvent.LogMessage("[E] [PROXY] Таймаут подключения прокси"))
                EventBus.dispatch(AppEvent.TransportDisconnected)
                stopProxy()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopProxy()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun stopProxy() {
        if (alreadyStopping.getAndSet(true)) return
        Logx.i(TAG, "stopProxy()")
        isProxyRunning = false
        proxyPort = 0
        activeTunnelName = null
        connectedAtRealtime = 0L
        EventBus.dispatch(AppEvent.VpnDisconnected)
        runCatching { HotspotProxyBridge.stop() }
        runCatching { supervisor.stop() }
        runCatching { LocalSocksSession.clearAuthenticator() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val ch = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name) + " Proxy",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Fluxon SOCKS5 proxy-only mode"
                    setShowBadge(false)
                }
                nm.createNotificationChannel(ch)
            }
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        createChannel()
        val isLight = AppIconManager.getCurrentIcon(this) == AppIconManager.ICON_LIGHT
        val targetAlias = if (isLight) {
            "io.github.p1neapplexpress.openflux.ui.MainActivityLight"
        } else {
            "io.github.p1neapplexpress.openflux.ui.MainActivityDark"
        }
        val mainIntent = Intent().setComponent(ComponentName(this, targetAlias)).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val mainPi = PendingIntent.getActivity(
            this, 0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val disconnectPi = PendingIntent.getService(
            this, 1,
            Intent(this, FluxonProxyService::class.java).apply { action = ACTION_DISCONNECT },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val accentColor = if (isLight) {
            ContextCompat.getColor(this, R.color.ic_launcher_background_light)
        } else {
            ContextCompat.getColor(this, R.color.ic_launcher_background_dark)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(accentColor)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setShowWhen(false)
            .setContentIntent(mainPi)
            .addAction(0, getString(R.string.action_disconnect), disconnectPi)
            .build()
    }

    private fun startForegroundProxy(tunnelName: String, port: Int) {
        val notif = buildNotification(
            getString(R.string.app_name) + " — SOCKS5",
            "Подключение к «$tunnelName»... · порт $port"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun updateNotificationConnected(tunnelName: String, port: Int) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val notif = buildNotification(
            getString(R.string.app_name) + " — SOCKS5 ✓",
            "«$tunnelName» · SOCKS5 127.0.0.1:$port"
        )
        nm.notify(NOTIFICATION_ID, notif)
    }

    // ── Payload builder ───────────────────────────────────────────────────────

    /**
     * Strip any TUN-specific flags from the transport payload and inject
     * SOCKS5 inbound args so FluxonCore starts in proxy-only mode.
     *
     * Removed: --inbound=tun, --tun-socket, --tun-mtu
     * Injected: --inbound=socks5  --socks5 127.0.0.1:<port>
     */
    private fun buildProxyPayload(original: Array<String>, port: Int): List<String> {
        val list = original.toMutableList()

        // Remove TUN-related args
        fun removeArg(flag: String) {
            val i = list.indexOf(flag)
            if (i != -1) {
                if (i + 1 < list.size && !list[i + 1].startsWith("-")) list.removeAt(i + 1)
                list.removeAt(i)
            }
        }
        fun removeArgWithValue(flagPrefix: String) {
            val it = list.iterator()
            while (it.hasNext()) {
                val arg = it.next()
                if (arg == flagPrefix || arg.startsWith("$flagPrefix=")) it.remove()
            }
        }

        removeArg("--inbound")
        removeArg("-inbound")
        removeArgWithValue("--inbound")
        removeArg("--tun-socket")
        removeArgWithValue("--tun-socket")
        removeArg("--tun-mtu")
        removeArgWithValue("--tun-mtu")
        removeArg("--tun")
        removeArg("-tun")

        // Remove stale --socks5 flag if present (we'll re-add with correct port)
        removeArg("--socks5")
        removeArg("-socks5")

        // Inject SOCKS5 inbound
        list.add(0, "--inbound=socks5")
        list.add("--socks5")
        list.add("127.0.0.1:$port")

        return list
    }
}
