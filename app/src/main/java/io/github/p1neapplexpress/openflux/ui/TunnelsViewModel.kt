package io.github.p1neapplexpress.openflux.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.p1neapplexpress.openflux.IUnifiedService
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelHealth
import io.github.p1neapplexpress.openflux.data.TunnelRepository
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.data.TunnelViewType
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.service.SocksVpnService
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.TunnelLinkParser
import io.github.p1neapplexpress.openflux.vpn.VPNConfig
import io.github.p1neapplexpress.openflux.vpn.VpnIntentFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TunnelsViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "TunnelsViewModel"
    }

    private val repo = TunnelRepository(app)

    @Volatile private var service: IUnifiedService? = null
    @Volatile private var bound = false
    @Volatile private var isSwitchingTunnel = false
    private var activeTunnelData: Tunnel? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IUnifiedService.Stub.asInterface(binder)
            bound = true
            Logx.d(TAG, "service connected")
            syncRunningServiceState()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
            Logx.d(TAG, "service disconnected")
            if (_active.value !is TunnelState.Idle) {
                _active.value = TunnelState.Idle
                _rxSpeed.value = 0L
                _txSpeed.value = 0L
                stopUptimeCounter()
                refresh()
            }
        }
    }

    private val _tunnels = MutableStateFlow<List<TunnelViewType>>(emptyList())
    val tunnels: StateFlow<List<TunnelViewType>> = _tunnels.asStateFlow()

    private val _active = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val active: StateFlow<TunnelState> = _active.asStateFlow()

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

    private val _rxSpeed = MutableStateFlow(0L)
    val rxSpeed: StateFlow<Long> = _rxSpeed.asStateFlow()

    private val _txSpeed = MutableStateFlow(0L)
    val txSpeed: StateFlow<Long> = _txSpeed.asStateFlow()

    private val _tunnelHealth = MutableStateFlow(TunnelHealth.UNKNOWN)
    val tunnelHealth: StateFlow<TunnelHealth> = _tunnelHealth.asStateFlow()

    private val _healthMap = MutableStateFlow<Map<Long, TunnelHealth>>(emptyMap())
    val healthMap: StateFlow<Map<Long, TunnelHealth>> = _healthMap.asStateFlow()

    private val healthPrefs: SharedPreferences by lazy {
        getApplication<Application>().getSharedPreferences("fluxon_health_cache", Context.MODE_PRIVATE)
    }

    private fun loadSavedHealth(tunnelId: Long): TunnelHealth {
        val name = healthPrefs.getString("health_$tunnelId", null) ?: return TunnelHealth.UNKNOWN
        return runCatching { TunnelHealth.valueOf(name) }.getOrDefault(TunnelHealth.UNKNOWN)
    }

    private fun saveHealth(tunnelId: Long, health: TunnelHealth) {
        healthCache[tunnelId] = health
        healthPrefs.edit().putString("health_$tunnelId", health.name).apply()
        _healthMap.value = healthCache.toMap()
    }

    private val healthCache = ConcurrentHashMap<Long, TunnelHealth>()

    fun getTunnelHealth(tunnelId: Long): TunnelHealth {
        val running = (_active.value as? TunnelState.Running)?.tunnel
        return if (running != null && running.id == tunnelId) {
            _tunnelHealth.value
        } else {
            healthCache[tunnelId] ?: loadSavedHealth(tunnelId)
        }
    }

    private val _pingMap = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val pingMap: StateFlow<Map<Long, Long>> = _pingMap.asStateFlow()
    private val pingCache = ConcurrentHashMap<Long, Long>()

    fun getTunnelPing(tunnelId: Long): Long? {
        val running = (_active.value as? TunnelState.Running)?.tunnel
        return if (running != null && running.id == tunnelId) {
            pingCache[tunnelId]
        } else {
            null
        }
    }

    private val _selected = MutableStateFlow<Tunnel?>(null)
    val selected: StateFlow<Tunnel?> = _selected.asStateFlow()

    val selectedTunnelId: Long? get() = _selected.value?.id

    private var uptimeJob: Job? = null
    private var healthCheckJob: Job? = null
    private var periodicHealthJob: Job? = null
    private var connectionJob: Job? = null

    init {
        repo.load().forEach { t ->
            val saved = loadSavedHealth(t.id)
            if (saved != TunnelHealth.UNKNOWN) {
                healthCache[t.id] = saved
            }
        }
        _healthMap.value = healthCache.toMap()
        refresh()
        syncRunningServiceState()
        viewModelScope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is AppEvent.SpeedUpdate -> {
                        _rxSpeed.value = event.rxSpeed
                        _txSpeed.value = event.txSpeed
                    }
                    is AppEvent.VpnConnected -> {
                        val tName = event.tunnelName ?: io.github.p1neapplexpress.openflux.service.SocksVpnService.activeTunnelName
                        val tunnel = (if (!tName.isNullOrEmpty()) {
                            repo.load().firstOrNull { it.name == tName }
                        } else null) ?: _selected.value ?: repo.getSelected()
                        if (tunnel != null) {
                            _active.value = TunnelState.Running(tunnel)
                            // A native transport connection is not proof that packets
                            // reach the exit node or the Internet.
                            saveHealth(tunnel.id, TunnelHealth.CHECKING)
                            _tunnelHealth.value = TunnelHealth.CHECKING
                            pingCache.remove(tunnel.id)
                            _pingMap.value = pingCache.toMap()
                            startUptimeCounter()
                            startHealthCheckLoop(tunnel)
                            refresh()
                        }
                    }
                    is AppEvent.TransportDisconnected -> {
                        // Handled exclusively by SocksVpnService to prevent split-brain failover collision
                        Logx.d(TAG, "Transport disconnected event received (handled by SocksVpnService)")
                    }
                    is AppEvent.Reconnecting -> {
                        val cur = _active.value.tunnel ?: _selected.value
                        if (cur != null) {
                            _active.value = TunnelState.Reconnecting(cur)
                        }
                    }
                    is AppEvent.WaitingForNetwork -> {
                        val cur = _active.value.tunnel ?: _selected.value
                        _active.value = TunnelState.WaitingForNetwork(cur)
                    }
                    is AppEvent.VpnDisconnected -> {
                        if (_active.value.isActive) {
                            if (isSwitchingTunnel) {
                                Logx.d(TAG, "Ignoring VpnDisconnected from tunnel switch")
                                isSwitchingTunnel = false
                                return@collect
                            }
                            Logx.i(TAG, "VpnDisconnected event received")
                            val ctx = getApplication<Application>()
                            try { ctx.unbindService(connection) } catch (_: Exception) {}
                            bound = false
                            service = null
                            activeTunnelData = null
                            io.github.p1neapplexpress.openflux.service.SocksVpnService.connectedAtRealtime = 0L
                            io.github.p1neapplexpress.openflux.service.FluxonProxyService.connectedAtRealtime = 0L
                            _active.value = TunnelState.Idle
                            _rxSpeed.value = 0L
                            _txSpeed.value = 0L
                            stopUptimeCounter()
                            healthCheckJob?.cancel()
                            healthCheckJob = null
                            periodicHealthJob?.cancel()
                            periodicHealthJob = null
                            pingCache.clear()
                            _tunnelHealth.value = getTunnelHealth(selectedTunnelId ?: -1)
                            _pingMap.value = emptyMap()
                            refresh()
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun startCurrent() {
        val tunnel = _active.value.tunnel
            ?: _selected.value
            ?: repo.getSelected()
            ?: return
        startTunnel(tunnel)
    }

    fun restartCurrent() {
        val tunnel = _active.value.tunnel
            ?: _selected.value
            ?: repo.getSelected()
            ?: return
        startTunnel(tunnel, forceRestart = true)
    }

    fun startTunnel(tunnel: Tunnel, forceRestart: Boolean = false) {
        val running = _active.value
        if (!forceRestart && running is TunnelState.Running && running.tunnel.id == tunnel.id) return

        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            if (_active.value.isActive) {
                _active.value = TunnelState.Connecting(tunnel)
                _tunnelHealth.value = TunnelHealth.CHECKING
                isSwitchingTunnel = true
                stopInternal(preserveActiveState = true)
                delay(200)
            }
            startTunnelInternal(tunnel)
        }
    }

    private fun startTunnelInternal(tunnel: Tunnel) {
        Logx.i(TAG, "Starting tunnel: '${tunnel.name}' (transport: ${tunnel.transportType})")
        io.github.p1neapplexpress.openflux.service.SocksVpnService.connectedAtRealtime = 0L
        io.github.p1neapplexpress.openflux.service.FluxonProxyService.connectedAtRealtime = 0L
        _uptimeSeconds.value = 0L
        _active.value = TunnelState.Connecting(tunnel)
        _tunnelHealth.value = TunnelHealth.CHECKING
        saveHealth(tunnel.id, TunnelHealth.CHECKING)
        pingCache.remove(tunnel.id)
        _pingMap.value = pingCache.toMap()

        val ctx = getApplication<Application>()
        val prepared = TunnelLinkParser.ensureLocalKeyFile(ctx, tunnel)
        val splitPrefs = io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences(ctx)
        val isSplitEnabled = splitPrefs.isEnabled
        val appBypass = splitPrefs.mode == io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences.MODE_BYPASS
        val selectedApps = if (isSplitEnabled) {
            if (appBypass) splitPrefs.bypassApps else splitPrefs.proxyApps
        } else {
            emptySet()
        }
        val perApp = isSplitEnabled && selectedApps.isNotEmpty()
        val appList = selectedApps.toTypedArray()

        val appSettings = io.github.p1neapplexpress.openflux.util.AppSettings(ctx)
        val session = io.github.p1neapplexpress.openflux.util.LocalSocksSession.generateNew(
            authEnabled = appSettings.socks5AuthEnabled,
            customUser = appSettings.socks5CustomUser,
            customPass = appSettings.socks5CustomPass,
            shareLan = appSettings.shareLanProxy
        )

        val modifiedPayload = prepared.transportConnPayload.toMutableList()
        fun removeFlag(flag: String) {
            val idx = modifiedPayload.indexOf(flag)
            if (idx != -1) {
                if (idx + 1 < modifiedPayload.size) {
                    modifiedPayload.removeAt(idx + 1)
                }
                modifiedPayload.removeAt(idx)
            }
        }
        removeFlag("-socks5")
        removeFlag("--socks5")
        removeFlag("-socks5-user")
        removeFlag("--socks5-user")
        removeFlag("-socks5-pass")
        removeFlag("--socks5-pass")
        removeFlag("--domain-rules-file")
        removeFlag("-domain-rules-file")
        removeFlag("--doh-url")
        removeFlag("-doh-url")
        removeFlag("--doh")
        removeFlag("-doh")

        modifiedPayload.add("--socks5")
        modifiedPayload.add("127.0.0.1:${session.port}")

        val effectiveDoh = !appSettings.useSystemDns && appSettings.dohEnabled
        if (effectiveDoh && appSettings.dohUrl.isNotBlank()) {
            modifiedPayload.add("--doh-url")
            modifiedPayload.add(appSettings.dohUrl)
            modifiedPayload.add("--doh")
        }

        val domainPrefs = io.github.p1neapplexpress.openflux.util.DomainRulesPreferences(ctx)
        if (domainPrefs.hasActiveRules()) {
            val rulesFile = domainPrefs.writeRulesFile(ctx)
            modifiedPayload.add("--domain-rules-file")
            modifiedPayload.add(rulesFile.absolutePath)
            modifiedPayload.add("--domain-mode")
            modifiedPayload.add(domainPrefs.getModeString())
        }

        val (remoteHost, remotePort) = io.github.p1neapplexpress.openflux.vpn.TunnelEndpointHelper.extractTarget(prepared)
        val cfg = VPNConfig(
            name = prepared.name,
            port = session.port,
            username = if (session.isAuthEnabled && session.password.isNotEmpty()) session.username else null,
            password = if (session.isAuthEnabled && session.password.isNotEmpty()) session.password else null,
            dns = appSettings.primaryDns,
            secondaryDns = appSettings.secondaryDns,
            mtu = appSettings.mtu,
            ipv6Proxy = appSettings.ipv6Proxy,
            perApp = perApp,
            appBypass = appBypass,
            appList = appList,
            bypassLan = appSettings.bypassLan,
            killSwitch = appSettings.killSwitch,
            ipType = appSettings.ipType,
            remoteServer = remoteHost,
            remotePort = remotePort,
            transportType = prepared.transportType,
            transportPayload = modifiedPayload.toTypedArray(),
            dohEnabled = effectiveDoh,
            dohUrl = if (effectiveDoh) appSettings.dohUrl else null,
        )
        val intent = VpnIntentFactory.build(ctx, cfg).apply {
            putExtra(Constants.INTENT_AUTONOMOUS, true)
        }

        if (appSettings.proxyOnlyMode) {
            // ── Proxy-only mode: start FluxonProxyService (no VPN key icon) ──
            Logx.i(TAG, "Proxy-only mode: starting FluxonProxyService")
            EventBus.dispatch(AppEvent.LogMessage("[I] Режим прокси (без VPN-иконки): запуск FluxonProxyService"))
            val proxyIntent = Intent(ctx, io.github.p1neapplexpress.openflux.service.FluxonProxyService::class.java).apply {
                putExtra(Constants.INTENT_NAME, prepared.name)
                putExtra(Constants.INTENT_TRANSPORT_TYPE, prepared.transportType)
                putExtra(Constants.INTENT_TRANSPORT_PAYLOAD, modifiedPayload.toTypedArray())
            }
            ContextCompat.startForegroundService(ctx, proxyIntent)
            _active.value = TunnelState.Running(prepared)
            _tunnelHealth.value = TunnelHealth.CHECKING
            startUptimeCounter()
            startHealthCheckLoop(prepared)
        } else {
            // ── Normal VPN mode: start SocksVpnService ────────────────────────
            ContextCompat.startForegroundService(ctx, intent)
            ctx.bindService(
                Intent(ctx, SocksVpnService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }

        val activeLevel = if (io.github.p1neapplexpress.openflux.service.NativeProcessSupervisor.activeLogLevel.isNotBlank()) {
            io.github.p1neapplexpress.openflux.service.NativeProcessSupervisor.activeLogLevel
        } else {
            appSettings.connectionLogLevel
        }
        val isDebug = activeLevel == "DEBUG" || modifiedPayload.contains("--debug") || appSettings.verboseLog
        Logx.setVerbose(isDebug)
        Logx.i(TAG, "logging initialized: verbose=$isDebug (level: $activeLevel)")

        refresh()
    }


    suspend fun stopInternal(preserveActiveState: Boolean = false) {
        Logx.i(TAG, "stopInternal(preserveActiveState=$preserveActiveState)")
        withContext(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            try {
                service?.stopOpenFluxNative()
                service?.stopVpn()
            } catch (e: Exception) {
                Logx.e(TAG, "stop failed", e)
            }
            try { ctx.unbindService(connection) } catch (_: Exception) {}
            bound = false
            service = null
            activeTunnelData = null
            Logx.setVerbose(false)

            if (!preserveActiveState) {
                try {
                    val disconnectIntent = Intent(ctx, SocksVpnService::class.java).apply {
                        action = SocksVpnService.ACTION_DISCONNECT
                    }
                    ctx.startService(disconnectIntent)
                } catch (e: Exception) {
                    Logx.w(TAG, "Failed sending VPN disconnect intent: ${e.message}")
                }
                // Also stop proxy service if it's running
                if (io.github.p1neapplexpress.openflux.service.FluxonProxyService.isProxyRunning) {
                    try {
                        val proxyDisconnect = Intent(ctx, io.github.p1neapplexpress.openflux.service.FluxonProxyService::class.java).apply {
                            action = io.github.p1neapplexpress.openflux.service.FluxonProxyService.ACTION_DISCONNECT
                        }
                        ctx.startService(proxyDisconnect)
                    } catch (e: Exception) {
                        Logx.w(TAG, "Failed sending proxy disconnect intent: ${e.message}")
                    }
                }
            }
        }
        if (!preserveActiveState) {
            io.github.p1neapplexpress.openflux.service.SocksVpnService.connectedAtRealtime = 0L
            io.github.p1neapplexpress.openflux.service.FluxonProxyService.connectedAtRealtime = 0L
            _active.value = TunnelState.Idle
            _rxSpeed.value = 0L
            _txSpeed.value = 0L
            stopUptimeCounter()
            healthCheckJob?.cancel()
            healthCheckJob = null
            periodicHealthJob?.cancel()
            periodicHealthJob = null
            pingCache.clear()
            _tunnelHealth.value = getTunnelHealth(selectedTunnelId ?: -1)
            _healthMap.value = healthCache.toMap()
            _pingMap.value = emptyMap()
            refresh()
        }
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        connectionJob?.cancel()
        connectionJob = null
        viewModelScope.launch {
            stopInternal(preserveActiveState = false)
        }
    }

    fun refresh() {
        val list = repo.load()
        val running = (_active.value as? TunnelState.Running)?.tunnel
        _tunnels.value = list.map { TunnelViewType(it, enabled = it == running) }
        val sel = repo.getSelected()
        _selected.value = sel
        if (running != null) {
            checkTunnelHealth(running)
        } else {
            _tunnelHealth.value = getTunnelHealth(sel?.id ?: -1)
            pingCache.clear()
            _healthMap.value = healthCache.toMap()
            _pingMap.value = emptyMap()
        }
        if (periodicHealthJob?.isActive != true) probeAllTunnels()
    }

    fun probeAllTunnels() {
        val running = (_active.value as? TunnelState.Running)?.tunnel
        _healthMap.value = healthCache.toMap()
        _pingMap.value = pingCache.toMap()

        if (running != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val ok = checkRunningHealthInternal(running)
                val status = if (ok) TunnelHealth.AVAILABLE else TunnelHealth.UNAVAILABLE
                saveHealth(running.id, status)
                _tunnelHealth.value = status
            }
        }
    }

    fun selectTunnel(tunnel: Tunnel) {
        val wasActive = _active.value.isActive
        val currentRunning = _active.value.tunnel
        repo.setSelectedId(tunnel.id)
        _selected.value = tunnel
        _tunnelHealth.value = getTunnelHealth(tunnel.id)
        if (wasActive && currentRunning?.id != tunnel.id) {
            startTunnel(tunnel)
        } else {
            refresh()
        }
    }

    fun checkSelectedHealth(force: Boolean = false) {
        val running = (_active.value as? TunnelState.Running)?.tunnel
        val target = running ?: _selected.value ?: repo.getSelected()
        checkTunnelHealth(target, force = force)
    }

    fun checkTunnelHealth(tunnel: Tunnel?, force: Boolean = false) {
        healthCheckJob?.cancel()
        if (tunnel == null) {
            _tunnelHealth.value = TunnelHealth.UNKNOWN
            return
        }
        val running = (_active.value as? TunnelState.Running)?.tunnel
        if (running != null && running.id == tunnel.id) {
            _tunnelHealth.value = healthCache[tunnel.id] ?: TunnelHealth.CHECKING
            if (force || !healthCache.containsKey(tunnel.id)) {
                _tunnelHealth.value = TunnelHealth.CHECKING
                healthCheckJob = viewModelScope.launch(Dispatchers.IO) {
                    val ok = checkRunningHealthInternal(tunnel)
                    val status = if (ok) TunnelHealth.AVAILABLE else TunnelHealth.UNAVAILABLE
                    saveHealth(tunnel.id, status)
                    _tunnelHealth.value = status
                }
            }
        } else {
            _tunnelHealth.value = getTunnelHealth(tunnel.id)
            pingCache.remove(tunnel.id)
            _pingMap.value = pingCache.toMap()
        }
    }

    private suspend fun probeRunningTunnel(tunnel: Tunnel): Boolean = withContext(Dispatchers.IO) {
        val svcRunning = io.github.p1neapplexpress.openflux.service.FluxonProxyService.isProxyRunning ||
            runCatching {
                service?.isFServiceRunning() == true && service?.isVpnRunning() == true
            }.getOrDefault(false)
        if (!svcRunning) {
            return@withContext false
        }
        for (attempt in 1..2) {
            val ok = checkRunningHealthInternal(tunnel)
            if (ok) return@withContext true
            if (attempt < 2) kotlinx.coroutines.delay(1000L)
        }
        false
    }

    private fun checkRunningHealthInternal(tunnel: Tunnel): Boolean {
        val isProxy = io.github.p1neapplexpress.openflux.service.FluxonProxyService.isProxyRunning
        val svcRunning = isProxy || runCatching {
            service?.isFServiceRunning() == true && service?.isVpnRunning() == true
        }.getOrDefault(false)

        if (!svcRunning) {
            saveHealth(tunnel.id, TunnelHealth.UNAVAILABLE)
            _tunnelHealth.value = TunnelHealth.UNAVAILABLE
            pingCache.remove(tunnel.id)
            _pingMap.value = pingCache.toMap()
            return false
        }

        val sessionPort = if (isProxy && io.github.p1neapplexpress.openflux.service.FluxonProxyService.proxyPort in 1..65535) {
            io.github.p1neapplexpress.openflux.service.FluxonProxyService.proxyPort
        } else {
            io.github.p1neapplexpress.openflux.util.LocalSocksSession.getActive().port
        }
        if (sessionPort !in 1..65535) {
            saveHealth(tunnel.id, TunnelHealth.UNAVAILABLE)
            _tunnelHealth.value = TunnelHealth.UNAVAILABLE
            pingCache.remove(tunnel.id)
            _pingMap.value = pingCache.toMap()
            return false
        }

        // 1. Verify that the local SOCKS proxy is listening and responsive
        val socksPortOpen = try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", sessionPort), 1500)
                s.isConnected
            }
        } catch (_: Exception) {
            false
        }
        if (!socksPortOpen) {
            saveHealth(tunnel.id, TunnelHealth.UNAVAILABLE)
            _tunnelHealth.value = TunnelHealth.UNAVAILABLE
            pingCache.remove(tunnel.id)
            _pingMap.value = pingCache.toMap()
            return false
        }

        // 2. Verify true end-to-end connectivity through SOCKS5 proxy via bridge to remote VPS
        val socksProxy = java.net.Proxy(java.net.Proxy.Type.SOCKS, java.net.InetSocketAddress("127.0.0.1", sessionPort))
        val latency = probeLiveConnectivityWithLatency(socksProxy)
        if (latency < 0) {
            saveHealth(tunnel.id, TunnelHealth.UNAVAILABLE)
            _tunnelHealth.value = TunnelHealth.UNAVAILABLE
            pingCache.remove(tunnel.id)
            _pingMap.value = pingCache.toMap()
            return false
        }

        pingCache[tunnel.id] = latency
        _pingMap.value = pingCache.toMap()
        saveHealth(tunnel.id, TunnelHealth.AVAILABLE)
        _tunnelHealth.value = TunnelHealth.AVAILABLE
        return true
    }

    private fun probeLiveConnectivityWithLatency(proxy: java.net.Proxy): Long {
        val endpoints = listOf(
            "https://cp.cloudflare.com/generate_204",
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://detectportal.firefox.com/success.txt"
        )
        for (ep in endpoints) {
            try {
                val url = java.net.URL(ep)
                val conn = url.openConnection(proxy) as java.net.HttpURLConnection
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
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
                Logx.d(TAG, "End-to-end probe failed for $ep: ${e.message}")
            }
        }
        return -1L
    }

    private fun triggerFailoverIfNeeded(failedTunnel: Tunnel) {
        val appSettings = io.github.p1neapplexpress.openflux.util.AppSettings(getApplication())
        if (!appSettings.autoFailover) return

        val all = repo.load()
        if (all.size <= 1) return
        val nextTunnel = all.firstOrNull { it.id != failedTunnel.id } ?: return

        Logx.i(TAG, "Failover triggered: switching from '${failedTunnel.name}' to '${nextTunnel.name}'")
        EventBus.dispatch(io.github.p1neapplexpress.openflux.event.AppEvent.LogMessage("[I] Failover: переключение на '${nextTunnel.name}'..."))

        connectionJob?.cancel()
        connectionJob = viewModelScope.launch(Dispatchers.Main) {
            stop()
            kotlinx.coroutines.delay(1000L)
            selectTunnel(nextTunnel)
            startTunnel(nextTunnel)
        }
    }

    private fun argValue(payload: List<String>, key: String): String {
        val i = payload.indexOf(key)
        return if (i != -1 && i + 1 < payload.size) payload[i + 1] else ""
    }

    fun addTunnel(tunnel: Tunnel) {
        val current = repo.load().toMutableList()
        val uniqueTunnel = if (current.any { it.id == tunnel.id }) {
            tunnel.copy(id = System.currentTimeMillis())
        } else {
            tunnel
        }
        val prepared = TunnelLinkParser.ensureLocalKeyFile(getApplication(), uniqueTunnel)
        current.add(prepared)
        repo.save(current)
        repo.setSelectedId(prepared.id)
        refresh()
    }

    fun removeTunnel(tunnel: Tunnel) {
        if (_active.value.tunnel == tunnel) stop()
        val current = repo.load().toMutableList()
        current.removeAll { it.id == tunnel.id }
        repo.save(current)
        runCatching { File(getApplication<Application>().filesDir, "key_${tunnel.id}.txt").delete() }
        refresh()
    }

    fun updateTunnel(old: Tunnel, new: Tunnel) {
        val current = repo.load().toMutableList()
        val idx = current.indexOfFirst { it.id == old.id }
        if (idx < 0) return
        if (_active.value.tunnel == old) stop()
        val prepared = TunnelLinkParser.ensureLocalKeyFile(getApplication(), new)
        current[idx] = prepared
        repo.save(current)
        refresh()
    }

    fun rotateYandexLanes(tunnel: Tunnel): Tunnel? {
        val payload = tunnel.transportConnPayload.toMutableList()
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
        val rotatedTunnel = tunnel.copy(transportConnPayload = payload)
        val current = repo.load().toMutableList()
        val idx = current.indexOfFirst { it.id == tunnel.id }
        if (idx >= 0) {
            current[idx] = rotatedTunnel
            repo.save(current)
            refresh()
        }
        return rotatedTunnel
    }

    fun syncRunningServiceState() {
        val ctx = getApplication<Application>()
        val isProxy = io.github.p1neapplexpress.openflux.service.FluxonProxyService.isProxyRunning
        if (!isProxy && !bound) {
            try {
                ctx.bindService(
                    Intent(ctx, io.github.p1neapplexpress.openflux.service.SocksVpnService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            } catch (e: Exception) {
                Logx.w(TAG, "bindService failed in syncRunningServiceState: ${e.message}")
            }
        }
        val isRunning = isProxy ||
                runCatching { service?.isVpnRunning == true }.getOrDefault(false) ||
                (io.github.p1neapplexpress.openflux.service.SocksVpnService.connectedAtRealtime > 0L) ||
                (io.github.p1neapplexpress.openflux.service.FluxonProxyService.connectedAtRealtime > 0L)
        if (isRunning) {
            val tName = if (isProxy) {
                io.github.p1neapplexpress.openflux.service.FluxonProxyService.activeTunnelName
            } else {
                io.github.p1neapplexpress.openflux.service.SocksVpnService.activeTunnelName
            }
            val tunnel = (if (!tName.isNullOrEmpty()) {
                repo.load().firstOrNull { it.name == tName }
            } else null) ?: _selected.value ?: repo.getSelected()
            if (tunnel != null && _active.value !is TunnelState.Running) {
                _active.value = TunnelState.Running(tunnel)
                saveHealth(tunnel.id, TunnelHealth.CHECKING)
                _tunnelHealth.value = TunnelHealth.CHECKING
                pingCache.remove(tunnel.id)
                _pingMap.value = pingCache.toMap()
                startUptimeCounter()
                startHealthCheckLoop(tunnel)
                refresh()
            }
        } else {
            val noRealtime = (io.github.p1neapplexpress.openflux.service.SocksVpnService.connectedAtRealtime == 0L) &&
                    (io.github.p1neapplexpress.openflux.service.FluxonProxyService.connectedAtRealtime == 0L)
            if (_active.value is TunnelState.Running && noRealtime && !isProxy) {
                _active.value = TunnelState.Idle
                stopUptimeCounter()
                healthCheckJob?.cancel()
                healthCheckJob = null
                periodicHealthJob?.cancel()
                periodicHealthJob = null
                refresh()
            }
        }
    }

    private fun startUptimeCounter() {
        uptimeJob?.cancel()
        _uptimeSeconds.value = 0L
        uptimeJob = viewModelScope.launch {
            val baseTime = if (io.github.p1neapplexpress.openflux.service.FluxonProxyService.connectedAtRealtime > 0L) {
                io.github.p1neapplexpress.openflux.service.FluxonProxyService.connectedAtRealtime
            } else if (io.github.p1neapplexpress.openflux.service.SocksVpnService.connectedAtRealtime > 0L) {
                io.github.p1neapplexpress.openflux.service.SocksVpnService.connectedAtRealtime
            } else {
                android.os.SystemClock.elapsedRealtime().also {
                    if (io.github.p1neapplexpress.openflux.service.FluxonProxyService.isProxyRunning) {
                        io.github.p1neapplexpress.openflux.service.FluxonProxyService.connectedAtRealtime = it
                    } else {
                        io.github.p1neapplexpress.openflux.service.SocksVpnService.connectedAtRealtime = it
                    }
                }
            }
            while (isActive) {
                val now = android.os.SystemClock.elapsedRealtime()
                _uptimeSeconds.value = ((now - baseTime) / 1000L).coerceAtLeast(0L)
                delay(1000L)
            }
        }
    }

    private fun startHealthCheckLoop(tunnel: Tunnel) {
        periodicHealthJob?.cancel()
        periodicHealthJob = viewModelScope.launch(Dispatchers.IO) {
            delay(1000L)
            var consecutiveFailures = 0
            while (isActive) {
                val currentTunnel = (_active.value as? TunnelState.Running)?.tunnel ?: break
                if (currentTunnel.id != tunnel.id) break
                val isAlive = probeRunningTunnel(currentTunnel)
                if (!isActive || (_active.value as? TunnelState.Running)?.tunnel?.id != currentTunnel.id) break
                if (isAlive) {
                    consecutiveFailures = 0
                    if (_tunnelHealth.value != TunnelHealth.AVAILABLE) {
                        _tunnelHealth.value = TunnelHealth.AVAILABLE
                        saveHealth(currentTunnel.id, TunnelHealth.AVAILABLE)
                    }
                } else {
                    consecutiveFailures++
                    val isDead = if (io.github.p1neapplexpress.openflux.service.FluxonProxyService.isProxyRunning) {
                        consecutiveFailures >= 2
                    } else {
                        service?.isFServiceRunning() != true || service?.isVpnRunning() != true || consecutiveFailures >= 2
                    }
                    if (isDead) {
                        _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                        saveHealth(currentTunnel.id, TunnelHealth.UNAVAILABLE)
                        pingCache.remove(currentTunnel.id)
                        _pingMap.value = pingCache.toMap()
                        triggerFailoverIfNeeded(currentTunnel)
                    }
                }
                delay(30_000L)
            }
        }
    }

    private fun stopUptimeCounter() {
        uptimeJob?.cancel()
        uptimeJob = null
        _uptimeSeconds.value = 0L
    }

    override fun onCleared() {
        super.onCleared()
        stopUptimeCounter()
        healthCheckJob?.cancel()
        healthCheckJob = null
        periodicHealthJob?.cancel()
        periodicHealthJob = null
        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
    }
}
