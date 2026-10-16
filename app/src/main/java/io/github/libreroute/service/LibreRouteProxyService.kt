package io.github.libreroute.service

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import android.content.pm.ServiceInfo
import io.github.libreroute.NativeBridge
import io.github.libreroute.R
import io.github.libreroute.event.AppEvent
import io.github.libreroute.event.EventBus
import io.github.libreroute.util.AppIconManager
import io.github.libreroute.util.AppSettings
import io.github.libreroute.util.Constants
import io.github.libreroute.util.LocalSocksSession
import io.github.libreroute.util.Logx
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
 * The service starts LibreRouteCore in SOCKS5-inbound mode (no --inbound=tun,
 * no --tun-socket). The local proxy port is shown in the persistent
 * notification so the user can configure their apps.
 */
class LibreRouteProxyService : Service() {

    companion object {
        private const val TAG = "LibreRouteProxyService"

        const val ACTION_DISCONNECT = "io.github.libreroute.PROXY_DISCONNECT"

        private const val CHANNEL_ID = "io.github.libreroute.proxy"
        private const val NOTIFICATION_ID = 2

        @Volatile var isProxyRunning = false
        @Volatile var isProxyStarting = false
        @Volatile var proxyPort = 0
        @Volatile var activeTunnelName: String? = null
        @Volatile var connectedAtRealtime: Long = 0L
    }

    private lateinit var supervisor: NativeProcessSupervisor
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val alreadyStopping = java.util.concurrent.atomic.AtomicBoolean(false)
    private var startupJob: kotlinx.coroutines.Job? = null

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
                if (ev is AppEvent.TransportDisconnected && (isProxyRunning || isProxyStarting) &&
                    !(io.github.libreroute.util.ProxyRoutingStatus.active?.mode == io.github.libreroute.util.ProxyRoutingMode.RULES && supervisor.isRunning)) stopProxy()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            Logx.i(TAG, "ACTION_DISCONNECT received")
            stopProxy()
            return START_NOT_STICKY
        }
        if (intent == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (isProxyRunning || isProxyStarting) {
            Logx.d(TAG, "Proxy already starting or running, ignoring duplicate start")
            return START_NOT_STICKY
        }
        alreadyStopping.set(false)
        isProxyStarting = true
        val transportType = intent.getStringExtra(Constants.INTENT_TRANSPORT_TYPE) ?: "yandex"
        val transportPayload = try {
            intent.getStringArrayExtra(Constants.INTENT_TRANSPORT_PAYLOAD)
                ?: intent.getStringArrayListExtra(Constants.INTENT_TRANSPORT_PAYLOAD)?.toTypedArray()
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
        val autonomous = transportType in setOf("direct-dpi", "directDpi")
        val proxyPayload = runCatching { buildProxyPayload(transportPayload, session, autonomous) }.getOrElse {
            EventBus.dispatch(AppEvent.LogMessage("[E] [PROXY] Проверьте длину логина и пароля SOCKS5 (1–255 байт UTF-8)"))
            stopProxy()
            return START_NOT_STICKY
        }
        val tunnelName = intent.getStringExtra(Constants.INTENT_NAME) ?: getString(R.string.app_name)
        activeTunnelName = tunnelName
        startForegroundProxy(tunnelName, session.port)
        startupJob = serviceScope.launch(Dispatchers.IO) {
            try {
                EventBus.dispatch(AppEvent.LogMessage("[I] [PROXY] Запуск LibreRouteCore в режиме SOCKS5-прокси (порт ${session.port})..."))
                supervisor.start(transportType, proxyPayload, intent.getLongExtra(Constants.INTENT_TUNNEL_ID, -1L).takeIf { it >= 0 })
                if (!supervisor.isRunning) {
                    EventBus.dispatch(AppEvent.LogMessage("[E] [PROXY] LibreRoute core did not start"))
                    stopProxy()
                    return@launch
                }
                val ready = if (supervisor.isFlexibleProxy) {
                    val deadline = android.os.SystemClock.elapsedRealtime() + 15_000L
                    while (!supervisor.isLocalProxyReady && supervisor.isRunning && !alreadyStopping.get() &&
                        android.os.SystemClock.elapsedRealtime() < deadline) kotlinx.coroutines.delay(50)
                    supervisor.isLocalProxyReady
                } else NativeTransportReadiness(proxyPayload).awaitReady(transportType,
                    canWait = { !alreadyStopping.get() && supervisor.isRunning },
                    connected = { supervisor.isConnected })
                if (alreadyStopping.get()) return@launch
                if (ready) {
                    isProxyRunning = true
                    isProxyStarting = false
                    connectedAtRealtime = android.os.SystemClock.elapsedRealtime()
                    EventBus.dispatch(AppEvent.LogMessage("[S] [PROXY] SOCKS5-прокси активен на 127.0.0.1:${session.port}"))
                    EventBus.dispatch(AppEvent.VpnConnected(tunnelName))
                    Logx.i(TAG, "Proxy mode active on port ${session.port}")
                    updateNotificationConnected(tunnelName, session.port)
                    if (appSettings.shareLanProxy) ProxySharing.refresh(appSettings)
                } else {
                    EventBus.dispatch(AppEvent.LogMessage("[E] [PROXY] Таймаут подключения прокси"))
                    EventBus.dispatch(AppEvent.TransportDisconnected)
                    stopProxy()
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                EventBus.dispatch(AppEvent.LogMessage("[E] [PROXY] Не удалось запустить прокси"))
                stopProxy()
            }
        }
        return START_NOT_STICKY
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
        isProxyStarting = false
        startupJob?.cancel()
        startupJob = null
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
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name) + " SOCKS5",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "LibreRoute SOCKS5 proxy-only mode"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        createChannel()
        val isLight = AppIconManager.getCurrentIcon(this) == AppIconManager.ICON_LIGHT
        val targetAlias = if (isLight) {
            "io.github.libreroute.ui.MainActivityLight"
        } else {
            "io.github.libreroute.ui.MainActivityDark"
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
            Intent(this, LibreRouteProxyService::class.java).apply { action = ACTION_DISCONNECT },
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
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
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
     * SOCKS5 inbound args so LibreRouteCore starts in proxy-only mode.
     *
     * Removed: --inbound=tun, --tun-socket, --tun-mtu
     * Injected: --inbound=socks5  --socks5 127.0.0.1:<port>
     */
    private fun buildProxyPayload(original: Array<String>, session: LocalSocksSession.SessionInfo, autonomous: Boolean): List<String> =
        localProxyOptions(original.toList(), session, proxyOnly = true,
            routingMode = io.github.libreroute.util.effectiveProxyRoutingMode(AppSettings(applicationContext).proxyRoutingMode, autonomous))
}
