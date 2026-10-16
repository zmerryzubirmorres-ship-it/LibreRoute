package io.github.libreroute.service

import android.annotation.SuppressLint
import android.content.Intent
import android.widget.Toast
import io.github.libreroute.R
import io.github.libreroute.util.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.IBinder
import io.github.libreroute.IUnifiedService
import io.github.libreroute.event.AppEvent
import io.github.libreroute.event.EventBus
import io.github.libreroute.NativeBridge
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import io.github.libreroute.util.Constants
import io.github.libreroute.util.LocalSocksSession
import io.github.libreroute.util.Logx

@SuppressLint("VpnServicePolicy")
class SocksVpnService : android.net.VpnService() {

    companion object {
        const val TAG = "SocksVpnService"
        const val ACTION_DISCONNECT = "io.github.libreroute.ACTION_DISCONNECT"
        const val ACTION_CHECK_PING = "io.github.libreroute.ACTION_CHECK_PING"

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

    private fun isDirectDpi(): Boolean = currentTransportType.equals("direct-dpi", true) || currentTransportType.equals("directDpi", true)
    private var startupJob: kotlinx.coroutines.Job? = null
    private var reconnectJob: kotlinx.coroutines.Job? = null
    private var reconnectAttempts = 0
    private val alreadyStopping = java.util.concurrent.atomic.AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var isNetworkStandby = false
    private val physicalNetworks = mutableMapOf<Network, PhysicalNetworkCandidate<Network>>()
    private var underlyingNetwork: Network? = null
    private var networkGeneration = 0L
    private val networkSelection = PhysicalNetworkTracker<Network>()
    @Volatile private var recoveryNetworkRevision = 0L

    private fun hasActiveInternet(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            } == true
        }
    }

    private fun enterNetworkStandby(reason: String = "[I] Нет подключения к интернету. Ожидание сети...") {
        if (isNetworkStandby) return
        isNetworkStandby = true
        reconnectJob?.cancel()
        reconnectJob = null
        reconnectAttempts = 0
        io.github.libreroute.util.ProxyRoutingStatus.tunnelReady = false
        supervisor.networkUnavailable()
        Logx.w(TAG, "Waiting for physical network")
        // Keep local routes and the listener alive. Restart the carrier when
        // Internet returns, including validation changes on the same Network.
        EventBus.dispatch(AppEvent.WaitingForNetwork)
        EventBus.dispatch(AppEvent.LogMessage(reason))
        notifications.updateStatus(getString(R.string.waiting_for_network))
    }

    private fun scheduleRecovery(networkReturned: Boolean = false) {
        if (!vpn.isRunning.get() || alreadyStopping.get() ||
            (isDirectDpi() && supervisor.isRunning) || supervisor.isTransportRejected) return
        if (!hasActiveInternet()) {
            enterNetworkStandby()
            return
        }
        if (networkReturned) recoveryNetworkRevision++
        if (reconnectJob?.isActive == true) return
        isNetworkStandby = false
        reconnectJob = serviceScope.launch(Dispatchers.IO) {
            EventBus.dispatch(AppEvent.Reconnecting(activeTunnelName))
            // Let native recovery finish first after a brief provider drop.
            // A physical network return invalidates the old RTC sockets.
            val initialRevision = recoveryNetworkRevision
            if (!networkReturned) {
                repeat(20) {
                    if (supervisor.isConnected) return@launch
                    if (recoveryNetworkRevision == initialRevision) kotlinx.coroutines.delay(500)
                }
            }
            while (isActive && !alreadyStopping.get() && vpn.isRunning.get() && !supervisor.isTransportRejected) {
                if (!hasActiveInternet()) {
                    withContext(Dispatchers.Main) { enterNetworkStandby() }
                    return@launch
                }
                if (supervisor.isConnected && recoveryNetworkRevision == initialRevision && !networkReturned) return@launch
                val attemptRevision = recoveryNetworkRevision
                reconnectAttempts++
                Logx.i(TAG, "Client recovery: restarting carrier, attempt=$reconnectAttempts")
                val payload = rotateActivePayloadLanes()?.toList() ?: currentTransportPayload.toList()
                if (!startCarrier(currentTransportType, payload)) return@launch
                val rawFd = vpn.fileDescriptor
                val passed = rawFd != null && supervisor.passTunFd(rawFd)
                val ready = passed && NativeTransportReadiness(payload).awaitReady(
                    currentTransportType,
                    canWait = { isActive && !alreadyStopping.get() && supervisor.isRunning && !supervisor.isTransportRejected && recoveryNetworkRevision == attemptRevision },
                    connected = { supervisor.isConnected }
                )
                if (!isActive || alreadyStopping.get() || supervisor.isTransportRejected) return@launch
                if (recoveryNetworkRevision != attemptRevision) continue
                if (ready) {
                    reconnectAttempts = 0
                    EventBus.dispatch(AppEvent.VpnConnected(activeTunnelName))
                    return@launch
                }
                // Transient outages never leave a live VPN permanently stalled
                // just because a fixed retry count was exhausted.
                kotlinx.coroutines.delay((reconnectAttempts * 1500L).coerceAtMost(15_000L))
            }
        }
    }

    private fun startCarrier(transport: String, payload: List<String>): Boolean = synchronized(this) {
        if (alreadyStopping.get()) return@synchronized false
        supervisor.start(transport, payload, currentTunnelId)
        !alreadyStopping.get()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val generation = ++networkGeneration
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                serviceScope.launch {
                    if (generation != networkGeneration || alreadyStopping.get()) return@launch
                    physicalNetworks[network] = PhysicalNetworkCandidate(
                        network,
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                        when {
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 3
                            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 2
                            else -> 1
                        }
                    )
                    updateUnderlyingNetwork()
                }
            }

            override fun onLost(network: Network) {
                serviceScope.launch {
                    if (generation != networkGeneration || alreadyStopping.get()) return@launch
                    physicalNetworks.remove(network)
                    updateUnderlyingNetwork()
                }
            }
        }
        networkCallback = callback
        runCatching {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            cm.registerNetworkCallback(request, callback)
            Logx.i(TAG, "Physical network callback registered")
            if (!hasActiveInternet()) enterNetworkStandby()
        }.onFailure {
            networkCallback = null
            Logx.w(TAG, "Failed to register physical network callback")
        }
    }

    private fun updateUnderlyingNetwork() {
        val transition = networkSelection.update(physicalNetworks.values)
        val selected = transition.network
        val changed = transition.changed
        val becameValidated = transition.validationRestored
        if (changed) {
            underlyingNetwork = selected
            runCatching { setUnderlyingNetworks(selected?.let { arrayOf(it) } ?: emptyArray()) }
            Logx.i(TAG, "Physical underlying network changed: $selected")
        }
        if (!vpn.isRunning.get() || alreadyStopping.get()) return
        if (selected == null) {
            enterNetworkStandby()
            return
        }
        // The callback's initial snapshot is not a network change. Do not
        // interrupt the provider's first handshake while it is still starting.
        if (transition.initial && !isNetworkStandby) return
        if (isDirectDpi()) {
            if (isNetworkStandby) {
                isNetworkStandby = false
                if (supervisor.isRunning) {
                    supervisor.localNetworkRestored()
                    EventBus.dispatch(AppEvent.VpnConnected(activeTunnelName))
                } else scheduleRecovery(networkReturned = true)
            }
            return
        }
        val channel = ClientChannel.state.value
        val recentlyConfirmed = channel.ready && channel.rttMs != null &&
            android.os.SystemClock.elapsedRealtime() - channel.measuredAt in 0..5_000L
        val needsRecovery = !supervisor.isConnected || (becameValidated && !recentlyConfirmed)
        if (isNetworkStandby || ((changed || becameValidated) && needsRecovery)) {
            val returned = isNetworkStandby || becameValidated
            scheduleRecovery(networkReturned = returned)
        }
    }

    private fun unregisterNetworkCallback() {
        networkGeneration++
        networkCallback?.let {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            runCatching { cm?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        physicalNetworks.clear()
        underlyingNetwork = null
        networkSelection.clear()
        runCatching { setUnderlyingNetworks(null) }
    }

    private val binder = object : IUnifiedService.Stub() {
        override fun isVpnRunning(): Boolean = if (::vpn.isInitialized) vpn.isRunning.get() else false
        override fun stopVpn() = stopEverything()
        override fun isFServiceRunning(): Boolean = if (::supervisor.isInitialized) supervisor.isConnected else false
        override fun stopLibreRouteNative() {
            if (::supervisor.isInitialized) supervisor.stop()
        }

        override fun startLibreRouteNative(transport: String?, args: Array<String>) {
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
                    EventBus.dispatch(AppEvent.LogMessage("[I] LibreRouteCore active (Direct TUN)"))
                    EventBus.dispatch(AppEvent.VpnConnected(tName))
                    Logx.i(TAG, "LibreRouteCore active")

                    val appSettings = AppSettings(applicationContext)
                    if (appSettings.shareLanProxy && !isDirectDpi()) {
                        ProxySharing.refresh(appSettings)
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
    @Volatile private var currentTunnelId: Long? = null

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
            while (isActive) {
                if (!alreadyStopping.get() && (vpn.isRunning.get() || startupJob?.isActive == true)) {
                    val channel = ClientChannel.state.value
                    val status = when {
                        supervisor.isTransportRejected -> R.string.jazz_room_rejected
                        isNetworkStandby -> R.string.waiting_for_network
                        isDirectDpi() && vpn.isRunning.get() -> R.string.running
                        channel.ready && vpn.isRunning.get() -> R.string.running
                        vpn.isRunning.get() -> R.string.reconnecting
                        else -> R.string.connecting
                    }
                    notifications.setConnection(getString(status), channel.freshRtt(android.os.SystemClock.elapsedRealtime()))
                }
                kotlinx.coroutines.delay(1000)
            }
        }

        serviceScope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is AppEvent.YandexAuthIssue -> {
                        val route = currentTransportPayload.toList().let { payload ->
                            payload.firstOrNull { it.startsWith("--url=") }?.substringAfter('=')
                                ?: payload.indexOf("--url").takeIf { it >= 0 }?.let { payload.getOrNull(it + 1) }
                        }
                        if (currentTransportType in setOf("yandex", "vyandex") && route == event.documentUrl) {
                            Logx.w(TAG, "Yandex browser check required; stopping automatic reconnect until user retries")
                            stopEverything()
                        }
                    }
                    is AppEvent.TransportConnected -> {
                        if (vpn.isRunning.get() && !alreadyStopping.get()) {
                            reconnectAttempts = 0
                            isNetworkStandby = false
                            EventBus.dispatch(AppEvent.VpnConnected(activeTunnelName))
                        }
                    }
                    is AppEvent.TransportDisconnected -> {
                        if (supervisor.isTransportRejected) {
                            reconnectJob?.cancel()
                            reconnectJob = null
                            notifications.updateStatus(getString(R.string.jazz_room_rejected))
                        } else scheduleRecovery()
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
        currentTunnelId = intent.getLongExtra(Constants.INTENT_TUNNEL_ID, -1L).takeIf { it >= 0 }

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
                if (!startCarrier(transportType, transportPayload.toList())) return@launch
                if (!supervisor.isRunning) {
                    EventBus.dispatch(AppEvent.LogMessage("[E] LibreRoute core did not start; VPN remains closed"))
                    stopEverything()
                    return@launch
                }

                // IPC-01: Pass TUN FileDescriptor directly to the unified LibreRouteCore via abstract socket
                val rawFd = vpn.fileDescriptor
                if (rawFd == null) {
                    Logx.e(TAG, "No TUN file descriptor available")
                    EventBus.dispatch(AppEvent.LogMessage("[E] No TUN file descriptor"))
                    stopEverything()
                    return@launch
                }

                EventBus.dispatch(AppEvent.LogMessage("[I] Передача дескриптора TUN в ядро LibreRouteCore..."))
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
                val ready = NativeTransportReadiness(transportPayload.toList()).awaitReady(
                    transportType,
                    canWait = { !alreadyStopping.get() && supervisor.isRunning && !supervisor.isTransportRejected },
                    connected = { supervisor.isConnected || (supervisor.isLocalProxyReady &&
                        io.github.libreroute.util.ProxyRoutingStatus.active?.mode == io.github.libreroute.util.ProxyRoutingMode.RULES) }
                )

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
                        EventBus.dispatch(AppEvent.LogMessage("[I] Единое ядро LibreRouteCore активно (Direct TUN)"))
                        EventBus.dispatch(AppEvent.VpnConnected(tName))
                        Logx.i(TAG, "LibreRouteCore active")

                        val appSettings = AppSettings(applicationContext)
                        if (appSettings.shareLanProxy && !isDirectDpi() && !HotspotProxyBridge.running) {
                            ProxySharing.refresh(appSettings)
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
        val channel = ClientChannel.state.value
        val pingMs = channel.freshRtt(android.os.SystemClock.elapsedRealtime())
        notifications.updatePing(pingMs ?: -2L)
        Toast.makeText(applicationContext,
            if (pingMs != null) getString(R.string.notify_ping_format, pingMs)
            else getString(R.string.notify_ping_timeout), Toast.LENGTH_SHORT).show()
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
        isNetworkStandby = false
        connectedAtRealtime = 0L
        activeTunnelName = null
        EventBus.dispatch(AppEvent.VpnDisconnected)
        notifications.stopSpeedUpdates()
        runCatching { unregisterNetworkCallback() }
        runCatching { HotspotProxyBridge.stop() }
        runCatching { tun2socks.stop() }
        runCatching { synchronized(this) { supervisor.stop() } }
        runCatching { vpn.stop() }
        runCatching { LocalSocksSession.clearAuthenticator() }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
}
