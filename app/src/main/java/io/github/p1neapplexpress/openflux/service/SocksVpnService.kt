package io.github.p1neapplexpress.openflux.service

import android.annotation.SuppressLint
import android.content.Intent
import android.widget.Toast
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.util.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.IBinder
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.NativeBridge
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.LocalSocksSession
import io.github.p1neapplexpress.openflux.util.Logx

@SuppressLint("VpnServicePolicy")
class SocksVpnService : android.net.VpnService() {

    companion object {
        const val TAG = "SocksVpnService"
        const val ACTION_DISCONNECT = "io.github.p1neapplexpress.openflux.ACTION_DISCONNECT"
        const val ACTION_CHECK_PING = "io.github.p1neapplexpress.openflux.ACTION_CHECK_PING"

        @Volatile var connectedAtRealtime: Long = 0L
        @Volatile var activeTunnelName: String? = null
    }

    private lateinit var vpn: VpnServiceController
    private lateinit var supervisor: NativeProcessSupervisor
    private lateinit var tun2socks: Tun2SocksLauncher
    private lateinit var notifications: VpnNotificationManager

    @Volatile private var lastIntent: Intent? = null
    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())
    private var pingJob: kotlinx.coroutines.Job? = null
    private var startupJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 5
    private val alreadyStopping = java.util.concurrent.atomic.AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Logx.i(TAG, "Underlying network changed: available $network")
                runCatching { setUnderlyingNetworks(arrayOf(network)) }
                if (vpn.isRunning.get() && !alreadyStopping.get()) {
                    // Network availability alone does not restore tunnel traffic.
                    serviceScope.launch(Dispatchers.IO) {
                        val port = lastIntent?.getIntExtra(Constants.INTENT_PORT, 1080) ?: 1080
                        val pingMs = measureRealEndToEndPing(port)
                        if (vpn.isRunning.get() && !alreadyStopping.get()) {
                            if (pingMs >= 0) {
                                EventBus.dispatch(AppEvent.VpnConnected(activeTunnelName))
                            } else {
                                Logx.w(TAG, "Ping failed over newly connected network; restarting transport")
                                EventBus.dispatch(AppEvent.TransportDisconnected)
                            }
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                Logx.i(TAG, "Underlying network lost: $network")
                runCatching { setUnderlyingNetworks(null) }
                if (vpn.isRunning.get()) {
                    EventBus.dispatch(AppEvent.WaitingForNetwork)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    runCatching { setUnderlyingNetworks(arrayOf(network)) }
                }
            }
        }
        networkCallback = callback
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                cm.registerDefaultNetworkCallback(callback)
            } else {
                val req = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                cm.registerNetworkCallback(req, callback)
            }
            Logx.i(TAG, "Underlying network callback registered")
        }.onFailure {
            Logx.w(TAG, "Failed to register network callback: ${it.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(it) }
            networkCallback = null
            Logx.i(TAG, "Underlying network callback unregistered")
        }
        runCatching { setUnderlyingNetworks(null) }
    }

    private val binder = object : IUnifiedService.Stub() {
        override fun isVpnRunning(): Boolean = if (::vpn.isInitialized) vpn.isRunning.get() else false
        override fun stopVpn() = stopEverything()
        override fun isFServiceRunning(): Boolean = if (::supervisor.isInitialized) supervisor.isConnected else false
        override fun stopOpenFluxNative() {
            if (::supervisor.isInitialized) supervisor.stop()
        }

        override fun startOpenFluxNative(transport: String?, args: Array<String>) {
            transport ?: return
            if (::supervisor.isInitialized) supervisor.start(transport, args.toList())
        }

        override fun startTun2Socks() {
            synchronized(this@SocksVpnService) {
                if (!::vpn.isInitialized || !::supervisor.isInitialized) return
                if (vpn.isRunning.get()) {
                    Logx.d(TAG, "VPN is already running, ignoring duplicate start")
                    return
                }
                val rawFd = vpn.fileDescriptor ?: run {
                    Logx.e(TAG, "no tun fd; aborting start")
                    return
                }
                val i = lastIntent ?: run {
                    Logx.e(TAG, "no lastIntent; aborting start")
                    return
                }

                val ok = supervisor.passTunFd(rawFd)
                if (ok) {
                    vpn.isRunning.set(true)
                    connectedAtRealtime = android.os.SystemClock.elapsedRealtime()
                    val tName = i.getStringExtra(Constants.INTENT_NAME)
                    activeTunnelName = tName
                    notifications.startSpeedUpdates()
                    registerNetworkCallback()
                    EventBus.dispatch(AppEvent.LogMessage("[I] FluxonCore active (Direct TUN)"))
                    EventBus.dispatch(AppEvent.VpnConnected(tName))
                    Logx.i(TAG, "FluxonCore active")

                    val appSettings = AppSettings(applicationContext)
                    if (appSettings.shareLanProxy) {
                        val session = LocalSocksSession.getActive()
                        HotspotProxyBridge.start(
                            lanPort = appSettings.lanProxyPort,
                            targetLocalPort = session.port,
                            authEnabled = appSettings.socks5AuthEnabled,
                            username = session.username,
                            password = session.password
                        )
                    }
                } else {
                    Logx.e(TAG, "Failed to pass TUN FD to native core")
                }
            }
        }

        override fun getFd(): Int = if (::vpn.isInitialized) vpn.fd else -1
    }

    @Volatile private var currentTransportType: String = "yandex"
    @Volatile private var currentTransportPayload: Array<String> = emptyArray()

    private fun rotateActivePayloadLanes(): Array<String>? {
        val payload = currentTransportPayload.toMutableList()
        val urlsIdx = payload.indexOf("--urls")
        if (urlsIdx == -1 || urlsIdx + 1 >= payload.size) return null
        val rawUrls = payload[urlsIdx + 1]
        val list = rawUrls.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (list.size <= 1) return null
        val rotatedList = list.drop(1) + list.take(1)
        payload[urlsIdx + 1] = rotatedList.joinToString(",")
        val urlIdx = payload.indexOf("--url")
        if (urlIdx != -1 && urlIdx + 1 < payload.size) {
            payload[urlIdx + 1] = rotatedList.first()
        }
        val result = payload.toTypedArray()
        currentTransportPayload = result
        return result
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { NativeBridge.ensureLoaded(applicationContext) }
        vpn = VpnServiceController(this)
        supervisor = NativeProcessSupervisor(applicationContext)
        tun2socks = Tun2SocksLauncher(applicationContext)
        notifications = VpnNotificationManager(this)

        serviceScope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is AppEvent.TransportDisconnected -> {
                        if (vpn.isRunning.get() && !alreadyStopping.get()) {
                            reconnectJob?.cancel()
                            val rotated = rotateActivePayloadLanes()
                            val payloadToUse = rotated?.toList() ?: currentTransportPayload.toList()

                            if (rotated != null || reconnectAttempts < maxReconnectAttempts) {
                                reconnectAttempts++
                                val backoffMs = (reconnectAttempts * 1500L).coerceAtMost(6000L)
                                val modeDesc = if (rotated != null) "ротация каналов" else "авто-переподключение ($reconnectAttempts/$maxReconnectAttempts)"
                                Logx.w(TAG, "Transport disconnected; scheduling attempt $reconnectAttempts/$maxReconnectAttempts ($modeDesc) in ${backoffMs}ms")

                                reconnectJob = serviceScope.launch(Dispatchers.IO) {
                                    EventBus.dispatch(AppEvent.Reconnecting(activeTunnelName))
                                    EventBus.dispatch(AppEvent.LogMessage("[I] [TRANSPORT] $modeDesc через ${backoffMs}мс..."))
                                    kotlinx.coroutines.delay(backoffMs)
                                    if (alreadyStopping.get() || !isActive) return@launch

                                    supervisor.start(currentTransportType, payloadToUse)
                                    val rawFd = vpn.fileDescriptor
                                    if (rawFd != null && supervisor.passTunFd(rawFd)) {
                                        var ready = false
                                        for (step in 1..40) {
                                            kotlinx.coroutines.delay(250)
                                            if (alreadyStopping.get() || !isActive) return@launch
                                            if (supervisor.isConnected) {
                                                ready = true
                                                break
                                            }
                                        }
                                        if (ready) {
                                            reconnectAttempts = 0
                                            EventBus.dispatch(AppEvent.VpnConnected(activeTunnelName))
                                            EventBus.dispatch(AppEvent.LogMessage("[S] Связь с транспортом успешно восстановлена"))
                                            Logx.i(TAG, "Reconnection successful")
                                        } else {
                                            Logx.w(TAG, "Reconnection attempt $reconnectAttempts failed or timed out")
                                            if (reconnectAttempts >= maxReconnectAttempts) {
                                                Logx.e(TAG, "Maximum reconnection attempts reached; stopping service")
                                                stopEverything()
                                            } else {
                                                EventBus.dispatch(AppEvent.TransportDisconnected)
                                            }
                                        }
                                    } else {
                                        Logx.e(TAG, "Failed to pass TUN FD during reconnection")
                                        if (reconnectAttempts >= maxReconnectAttempts) {
                                            stopEverything()
                                        } else {
                                            EventBus.dispatch(AppEvent.TransportDisconnected)
                                        }
                                    }
                                }
                            } else {
                                Logx.e(TAG, "Transport disconnected and reconnect attempts exhausted; stopping service")
                                stopEverything()
                            }
                        }
                    }
                    is AppEvent.RefreshNotification -> {
                        if (::notifications.isInitialized && vpn.isRunning.get()) {
                            notifications.refresh()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent ?: return START_NOT_STICKY
        if (intent.action == ACTION_DISCONNECT) {
            Logx.i(TAG, "ACTION_DISCONNECT received from notification")
            stopEverything()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_CHECK_PING) {
            Logx.i(TAG, "ACTION_CHECK_PING received from notification")
            handlePingRequest()
            return START_STICKY
        }
        lastIntent = intent
        val tunnelName = intent.getStringExtra(Constants.INTENT_NAME) ?: getString(R.string.app_name)
        notifications.startForeground(tunnelName)

        if (vpn.isConfigured() && supervisor.isConnected && vpn.isRunning.get()) {
            Logx.d(TAG, "VPN already configured and running, ignoring duplicate start")
            return START_STICKY
        }

        alreadyStopping.set(false)
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        val transportType = intent.getStringExtra(Constants.INTENT_TRANSPORT_TYPE) ?: "yandex"
        val transportPayload = try {
            intent.getStringArrayExtra(Constants.INTENT_TRANSPORT_PAYLOAD)
                ?: intent.getCharSequenceArrayExtra(Constants.INTENT_TRANSPORT_PAYLOAD)?.map { it.toString() }?.toTypedArray()
                ?: intent.getStringArrayListExtra(Constants.INTENT_TRANSPORT_PAYLOAD)?.toTypedArray()
                ?: (intent.getSerializableExtra(Constants.INTENT_TRANSPORT_PAYLOAD) as? Array<*>)?.filterIsInstance<String>()?.toTypedArray()
                ?: emptyArray()
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to read INTENT_TRANSPORT_PAYLOAD", e)
            emptyArray()
        }
        currentTransportType = transportType
        currentTransportPayload = transportPayload

        startupJob?.cancel()
        startupJob = serviceScope.launch(Dispatchers.IO) {
            try {
                if (supervisor.isRunning) {
                    Logx.i(TAG, "Stopping active supervisor before reconfiguring VPN interface")
                    supervisor.stop()
                    kotlinx.coroutines.delay(100)
                }
                if (!vpn.configure(intent)) {
                    Logx.e(TAG, "VPN configure failed, aborting startup")
                    EventBus.dispatch(AppEvent.LogMessage("[E] VPN configure failed"))
                    EventBus.dispatch(AppEvent.TransportDisconnected)
                    stopEverything()
                    return@launch
                }
                EventBus.dispatch(AppEvent.LogMessage("[S] VPN configured"))
                Logx.i(TAG, "VPN configured")

                val dohEnabled = intent.getBooleanExtra(Constants.INTENT_DOH_ENABLED, false)
                val dohUrl = intent.getStringExtra(Constants.INTENT_DOH_URL)
                if (dohEnabled && !dohUrl.isNullOrBlank()) {
                    EventBus.dispatch(AppEvent.LogMessage("[I] DNS-over-HTTPS (DoH) active: $dohUrl"))
                    Logx.i(TAG, "DNS-over-HTTPS (DoH) active: $dohUrl")
                }

                EventBus.dispatch(AppEvent.LogMessage("[I] Starting native transport ($transportType)..."))
                supervisor.start(transportType, transportPayload.toList())
                if (!supervisor.isRunning) {
                    EventBus.dispatch(AppEvent.LogMessage("[E] OpenFlux core did not start; VPN remains closed"))
                    stopEverything()
                    return@launch
                }

                // IPC-01: Pass TUN FileDescriptor directly to the unified FluxonCore via abstract socket
                val rawFd = vpn.fileDescriptor
                if (rawFd == null) {
                    Logx.e(TAG, "No TUN file descriptor available")
                    EventBus.dispatch(AppEvent.LogMessage("[E] No TUN file descriptor"))
                    stopEverything()
                    return@launch
                }

                EventBus.dispatch(AppEvent.LogMessage("[I] Передача дескриптора TUN в ядро FluxonCore..."))
                val fdPassed = supervisor.passTunFd(rawFd)
                if (!fdPassed) {
                    Logx.e(TAG, "Failed to pass TUN FD to native core")
                    EventBus.dispatch(AppEvent.LogMessage("[E] Ошибка передачи TUN FD в ядро"))
                    EventBus.dispatch(AppEvent.TransportDisconnected)
                    stopEverything()
                    return@launch
                }
                EventBus.dispatch(AppEvent.LogMessage("[S] Дескриптор TUN успешно принят ядром"))

                // Wait for transport readiness inside the service
                var ready = false
                for (step in 1..80) {
                    kotlinx.coroutines.delay(250)
                    if (alreadyStopping.get() || !isActive) return@launch
                    if (supervisor.isConnected) {
                        ready = true
                        Logx.i(TAG, "Transport ready after ${step * 250}ms")
                        break
                    }
                }

                if (alreadyStopping.get() || !isActive) return@launch

                if (ready) {
                    synchronized(this@SocksVpnService) {
                        reconnectAttempts = 0
                        vpn.isRunning.set(true)
                        connectedAtRealtime = android.os.SystemClock.elapsedRealtime()
                        val tName = intent.getStringExtra(Constants.INTENT_NAME) ?: activeTunnelName
                        activeTunnelName = tName
                        notifications.startSpeedUpdates()
                        registerNetworkCallback()
                        EventBus.dispatch(AppEvent.LogMessage("[I] Единое ядро FluxonCore активно (Direct TUN)"))
                        EventBus.dispatch(AppEvent.VpnConnected(tName))
                        Logx.i(TAG, "FluxonCore active")

                        val appSettings = AppSettings(applicationContext)
                        if (appSettings.shareLanProxy && !HotspotProxyBridge.running) {
                            val session = LocalSocksSession.getActive()
                            HotspotProxyBridge.start(
                                lanPort = appSettings.lanProxyPort,
                                targetLocalPort = session.port,
                                authEnabled = appSettings.socks5AuthEnabled,
                                username = session.username,
                                password = session.password
                            )
                        }
                    }
                } else {
                    Logx.e(TAG, "Transport startup timed out")
                    EventBus.dispatch(AppEvent.LogMessage("[E] Transport startup timed out"))
                    EventBus.dispatch(AppEvent.TransportDisconnected)
                    stopEverything()
                }
            } catch (e: Exception) {
                if (!alreadyStopping.get() && isActive) {
                    Logx.e(TAG, "Error in service startup", e)
                    EventBus.dispatch(AppEvent.TransportDisconnected)
                    stopEverything()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onRevoke() {
        Logx.w(TAG, "onRevoke")
        stopEverything()
        super.onRevoke()
    }

    override fun onDestroy() {
        startupJob?.cancel()
        startupJob = null
        pingJob?.cancel()
        pingJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        stopEverything()
        serviceScope.cancel()
        super.onDestroy()
    }


    private fun handlePingRequest() {
        pingJob?.cancel()
        notifications.updatePing(-1L)
        pingJob = serviceScope.launch {
            val port = lastIntent?.getIntExtra(Constants.INTENT_PORT, 1080) ?: 1080
            val pingMs = withContext(Dispatchers.IO) {
                measureRealEndToEndPing(port)
            }

            if (!isActive) return@launch

            if (pingMs >= 0) {
                notifications.updatePing(pingMs)
                Toast.makeText(
                    applicationContext,
                    getString(R.string.notify_ping_format, pingMs),
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                notifications.updatePing(-2L)
                Toast.makeText(
                    applicationContext,
                    getString(R.string.notify_ping_timeout),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun measureRealEndToEndPing(port: Int): Long {
        val socksProxy = java.net.Proxy(
            java.net.Proxy.Type.SOCKS,
            java.net.InetSocketAddress("127.0.0.1", port)
        )
        val endpoints = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://detectportal.firefox.com/success.txt"
        )
        for (ep in endpoints) {
            try {
                val url = java.net.URL(ep)
                val conn = url.openConnection(socksProxy) as java.net.HttpURLConnection
                conn.connectTimeout = 4000
                conn.readTimeout = 4000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Fluxon/1.2.0")
                conn.setRequestProperty("Connection", "close")
                val t0 = android.os.SystemClock.elapsedRealtime()
                try {
                    val code = conn.responseCode
                    if (code in 200..399 || code == 204) {
                        val rtt = android.os.SystemClock.elapsedRealtime() - t0
                        return rtt.coerceAtLeast(1L)
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Logx.d(TAG, "Ping probe failed for $ep: ${e.message}")
            }
        }
        return -1L
    }

    private fun stopEverything() {
        if (alreadyStopping.getAndSet(true)) {
            Logx.d(TAG, "stopEverything already in progress, skipping")
            return
        }
        Logx.i(TAG, "stopEverything")
        startupJob?.cancel()
        startupJob = null
        pingJob?.cancel()
        pingJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        connectedAtRealtime = 0L
        activeTunnelName = null
        EventBus.dispatch(AppEvent.VpnDisconnected)
        notifications.stopSpeedUpdates()
        runCatching { unregisterNetworkCallback() }
        runCatching { HotspotProxyBridge.stop() }
        runCatching { tun2socks.stop() }
        runCatching { supervisor.stop() }
        runCatching { vpn.stop() }
        runCatching { LocalSocksSession.clearAuthenticator() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
