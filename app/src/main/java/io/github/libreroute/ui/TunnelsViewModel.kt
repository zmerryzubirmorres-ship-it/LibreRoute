package io.github.libreroute.ui

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
import io.github.libreroute.IUnifiedService
import io.github.libreroute.util.Constants
import io.github.libreroute.data.Tunnel
import io.github.libreroute.data.TunnelHealth
import io.github.libreroute.data.TunnelRepository
import io.github.libreroute.data.TunnelState
import io.github.libreroute.data.TunnelViewType
import io.github.libreroute.event.AppEvent
import io.github.libreroute.event.EventBus
import io.github.libreroute.service.SocksVpnService
import io.github.libreroute.util.Logx
import io.github.libreroute.util.JitsiTokenStore
import io.github.libreroute.util.TunnelLinkParser
import io.github.libreroute.data.TransportType
import io.github.libreroute.vpn.VPNConfig
import io.github.libreroute.vpn.VpnIntentFactory
import java.io.File
import io.github.libreroute.admin.AdminRepository
import io.github.libreroute.admin.ProblemReport
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
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
            // Losing the Activity binder is not a tunnel teardown. Recover
            // the latest service state instead of displaying a false disconnect.
            syncRunningServiceState()
        }
    }

    private val _tunnels = MutableStateFlow<List<TunnelViewType>>(emptyList())
    val tunnels: StateFlow<List<TunnelViewType>> = _tunnels.asStateFlow()

    private val _accessRevoked = MutableStateFlow(false)
    val accessRevoked: StateFlow<Boolean> = _accessRevoked.asStateFlow()

    private val _active = MutableStateFlow<TunnelState>(TunnelState.Idle)
    val active: StateFlow<TunnelState> = _active.asStateFlow()

    private val _uptimeSeconds = MutableStateFlow(0L)
    val uptimeSeconds: StateFlow<Long> = _uptimeSeconds.asStateFlow()

    private val _speedSampleAvailable = MutableStateFlow(false)
    val speedSampleAvailable: StateFlow<Boolean> = _speedSampleAvailable.asStateFlow()

    private val _rxSpeed = MutableStateFlow(0L)
    val rxSpeed: StateFlow<Long> = _rxSpeed.asStateFlow()

    private val _txSpeed = MutableStateFlow(0L)
    val txSpeed: StateFlow<Long> = _txSpeed.asStateFlow()

    private val _tunnelHealth = MutableStateFlow(TunnelHealth.UNKNOWN)
    val tunnelHealth: StateFlow<TunnelHealth> = _tunnelHealth.asStateFlow()

    private val _healthMap = MutableStateFlow<Map<Long, TunnelHealth>>(emptyMap())
    val healthMap: StateFlow<Map<Long, TunnelHealth>> = _healthMap.asStateFlow()

    private val healthPrefs: SharedPreferences by lazy {
        getApplication<Application>().getSharedPreferences("libreroute_health_cache", Context.MODE_PRIVATE)
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
    private val healthProbeMutex = Mutex()
    private var problemReportJob: Job? = null
    private val reportedOutages = mutableMapOf<Long, Long>()
    private var periodicHealthJob: Job? = null
    private var connectionJob: Job? = null
    private var refreshJob: Job? = null

    init {
        refresh()
        syncRunningServiceState()
        viewModelScope.launch {
            EventBus.events.collect { event ->
                when (event) {
                    is AppEvent.SpeedUpdate -> {
                        _speedSampleAvailable.value = event.available && _active.value is TunnelState.Running
                        _rxSpeed.value = event.rxSpeed
                        _txSpeed.value = event.txSpeed
                    }
                    is AppEvent.VpnConnected -> {
                        val tName = event.tunnelName ?: io.github.libreroute.service.SocksVpnService.activeTunnelName
                        val tunnel = repo.load().firstOrNull { it.id == io.github.libreroute.service.NativeProcessSupervisor.activeTunnelId }
                            ?: (if (!tName.isNullOrEmpty()) repo.load().firstOrNull { it.name == tName } else null)
                            ?: _selected.value ?: repo.getSelected()
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
                    is AppEvent.TransportConnected -> {
                        // The local listener can start before Jazz has a peer. Check
                        // the recovered tunnel now, without the periodic 30s delay.
                        val tunnel = _active.value.tunnel
                        if (tunnel != null) startHealthCheckLoop(tunnel)
                        else syncRunningServiceState()
                    }
                    is AppEvent.TransportDisconnected -> {
                        if (io.github.libreroute.service.NativeProcessSupervisor.rejectedSession ===
                            io.github.libreroute.util.LocalSocksSession.getActive()) {
                            (_active.value as? TunnelState.Running)?.tunnel?.let { startHealthCheckLoop(it) }
                        }
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
                            io.github.libreroute.service.SocksVpnService.connectedAtRealtime = 0L
                            io.github.libreroute.service.LibreRouteProxyService.connectedAtRealtime = 0L
                            _active.value = TunnelState.Idle
                            _speedSampleAvailable.value = false
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
                    is AppEvent.AccessRevoked -> {
                        _accessRevoked.value = true
                        val ctx = getApplication<Application>()
                        android.widget.Toast.makeText(ctx, event.reason, android.widget.Toast.LENGTH_LONG).show()
                        stopInternal()
                        refresh()
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
        if (!TransportType.isAvailable(tunnel.transportType)) return
        val running = _active.value
        if (!forceRestart && running is TunnelState.Running && running.tunnel.id == tunnel.id) return

        connectionJob?.cancel()
        connectionJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val adminRepo = io.github.libreroute.admin.AdminRepository.getInstance(ctx)
            var actualTunnel = tunnel
            if (!io.github.libreroute.data.DirectDpiProfile.isDirect(tunnel) && adminRepo.isEnrolledInRouteSync()) {
                val syncResult = withContext(Dispatchers.IO) {
                    adminRepo.syncRoutes(force = false)
                }
                if (syncResult is io.github.libreroute.admin.RouteSyncResult.Revoked) {
                    _active.value = TunnelState.Idle
                    android.widget.Toast.makeText(ctx, "Доступ отозван сервером", android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                } else if (syncResult is io.github.libreroute.admin.RouteSyncResult.Updated) {
                    val updatedMatching = syncResult.routes.firstOrNull { it.name == tunnel.name }
                        ?: syncResult.routes.firstOrNull()
                    if (updatedMatching != null) {
                        actualTunnel = updatedMatching
                    }
                }
            }
            if (_active.value.isActive) {
                _active.value = TunnelState.Connecting(actualTunnel)
                _tunnelHealth.value = TunnelHealth.CHECKING
                isSwitchingTunnel = true
                stopInternal(preserveActiveState = true)
                delay(200)
            }
            startTunnelInternal(actualTunnel)
        }
    }

    private fun startTunnelInternal(tunnel: Tunnel) {
        Logx.i(TAG, "Starting tunnel: '${tunnel.name}' (transport: ${tunnel.transportType})")
        io.github.libreroute.service.SocksVpnService.connectedAtRealtime = 0L
        io.github.libreroute.service.LibreRouteProxyService.connectedAtRealtime = 0L
        _uptimeSeconds.value = 0L
        _active.value = TunnelState.Connecting(tunnel)
        _tunnelHealth.value = TunnelHealth.CHECKING
        saveHealth(tunnel.id, TunnelHealth.CHECKING)
        pingCache.remove(tunnel.id)
        _pingMap.value = pingCache.toMap()

        val ctx = getApplication<Application>()
        val prepared = TunnelLinkParser.ensureLocalKeyFile(ctx, tunnel)
        val splitPrefs = io.github.libreroute.util.SplitTunnelPreferences(ctx)
        val isSplitEnabled = splitPrefs.isEnabled
        val appBypass = splitPrefs.mode == io.github.libreroute.util.SplitTunnelPreferences.MODE_BYPASS
        val selectedApps = if (isSplitEnabled) {
            if (appBypass) splitPrefs.bypassApps else splitPrefs.proxyApps
        } else {
            emptySet()
        }
        val appSettings = io.github.libreroute.util.AppSettings(ctx)
        val proxyOnly = appSettings.proxyOnlyMode
        if (!proxyOnly && isSplitEnabled && !appBypass && selectedApps.isEmpty()) {
            _active.value = TunnelState.Idle
            EventBus.dispatch(AppEvent.LogMessage("[E] Выберите хотя бы одно приложение для режима «только через туннель»"))
            android.widget.Toast.makeText(ctx, "Выберите приложение для туннеля", android.widget.Toast.LENGTH_LONG).show()
            return
        }
        val perApp = isSplitEnabled && (!appBypass || selectedApps.isNotEmpty())
        val appList = selectedApps.toTypedArray()

        val session = io.github.libreroute.util.LocalSocksSession.generateNew(
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
        removeFlag("--domain-mode")
        removeFlag("--ip-rules-file")
        removeFlag("--split-mode")
        removeFlag("--doh-url")
        removeFlag("-doh-url")
        removeFlag("--doh")
        removeFlag("-doh")
        removeFlag("--jitsi-token")
        if (prepared.transportType.equals("jitsi", true)) {
            val roomUrl = prepared.transportConnPayload.let { payload ->
                val i = payload.indexOf("--url")
                if (i >= 0) payload.getOrNull(i + 1).orEmpty()
                else payload.firstOrNull { it.startsWith("--url=") }?.let { raw -> raw.substring(raw.indexOf('=') + 1) }.orEmpty()
            }
            JitsiTokenStore(ctx).get(roomUrl)?.let {
                modifiedPayload.add("--jitsi-token")
                modifiedPayload.add(it)
            }
        }

        modifiedPayload.add("--socks5")
        modifiedPayload.add("127.0.0.1:${session.port}")

        val effectiveDoh = !appSettings.useSystemDns && appSettings.dohEnabled
        if (effectiveDoh && appSettings.dohUrl.isNotBlank()) {
            modifiedPayload.add("--doh-url")
            modifiedPayload.add(appSettings.dohUrl)
            modifiedPayload.add("--doh")
        }

        val domainPrefs = io.github.libreroute.util.DomainRulesPreferences(ctx)
        val ipPrefs = io.github.libreroute.util.IpRulesPreferences(ctx)
        if (isSplitEnabled && domainPrefs.hasActiveRules()) {
            val rulesFile = domainPrefs.writeRulesFile(ctx)
            modifiedPayload.add("--domain-rules-file")
            modifiedPayload.add(rulesFile.absolutePath)
        }
        if (isSplitEnabled && ipPrefs.rules.isNotEmpty()) {
            modifiedPayload.add("--ip-rules-file")
            modifiedPayload.add(ipPrefs.writeRulesFile().absolutePath)
        }
        if (isSplitEnabled && (domainPrefs.hasActiveRules() || ipPrefs.rules.isNotEmpty())) {
            modifiedPayload.add("--split-mode")
            modifiedPayload.add(domainPrefs.getModeString())
        }

        val (remoteHost, remotePort) = io.github.libreroute.vpn.TunnelEndpointHelper.extractTarget(prepared)
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
            putExtra(Constants.INTENT_TUNNEL_ID, prepared.id)
        }

        // Proxy-only uses the SOCKS service for both remote and autonomous profiles.
        if (appSettings.proxyOnlyMode) {
            // ── Proxy-only mode: start LibreRouteProxyService (no VPN key icon) ──
            Logx.i(TAG, "Proxy-only mode: starting LibreRouteProxyService")
            EventBus.dispatch(AppEvent.LogMessage("[I] Режим прокси (без VPN-иконки): запуск LibreRouteProxyService"))
            val proxyIntent = Intent(ctx, io.github.libreroute.service.LibreRouteProxyService::class.java).apply {
                putExtra(Constants.INTENT_TUNNEL_ID, prepared.id)
                putExtra(Constants.INTENT_NAME, prepared.name)
                putExtra(Constants.INTENT_TRANSPORT_TYPE, prepared.transportType)
                putExtra(Constants.INTENT_TRANSPORT_PAYLOAD, modifiedPayload.toTypedArray())
            }
            ContextCompat.startForegroundService(ctx, proxyIntent)
            // VpnConnected is emitted only after native peer readiness, as in VPN mode.
        } else {
            // ── Normal VPN mode: start SocksVpnService ────────────────────────
            ContextCompat.startForegroundService(ctx, intent)
            ctx.bindService(
                Intent(ctx, SocksVpnService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
        }

        val activeLevel = if (io.github.libreroute.service.NativeProcessSupervisor.activeLogLevel.isNotBlank()) {
            io.github.libreroute.service.NativeProcessSupervisor.activeLogLevel
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
                service?.stopLibreRouteNative()
                service?.stopVpn()
            } catch (e: Exception) {
                Logx.e(TAG, "stop failed", e)
            }
            try { ctx.unbindService(connection) } catch (_: Exception) {}
            bound = false
            service = null
            activeTunnelData = null
            Logx.setVerbose(false)
            // Stop an in-progress proxy launch as well as a connected proxy,
            // including switches between profiles and between VPN/proxy modes.
            ctx.stopService(Intent(ctx, io.github.libreroute.service.LibreRouteProxyService::class.java))

            if (!preserveActiveState) {
                try {
                    val disconnectIntent = Intent(ctx, SocksVpnService::class.java).apply {
                        action = SocksVpnService.ACTION_DISCONNECT
                    }
                    ctx.startService(disconnectIntent)
                } catch (e: Exception) {
                    Logx.w(TAG, "Failed sending VPN disconnect intent: ${e.message}")
                }
            }
        }
        if (!preserveActiveState) {
            io.github.libreroute.service.SocksVpnService.connectedAtRealtime = 0L
            io.github.libreroute.service.LibreRouteProxyService.connectedAtRealtime = 0L
            _active.value = TunnelState.Idle
            _speedSampleAvailable.value = false
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
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { repo.load() }
            list.forEach { t ->
                val saved = loadSavedHealth(t.id)
                if (saved != TunnelHealth.UNKNOWN) healthCache.putIfAbsent(t.id, saved)
            }
            _healthMap.value = healthCache.toMap()
            val running = (_active.value as? TunnelState.Running)?.tunnel
            _tunnels.value = list.map { TunnelViewType(it, enabled = it == running) }
            val selectedId = repo.getSelectedId()
            val sel = list.firstOrNull { it.id == selectedId } ?: list.firstOrNull()
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
        val target = running ?: _selected.value
        if (target != null) checkTunnelHealth(target, force = force)
        else viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) { repo.getSelected() }
            checkTunnelHealth(_active.value.tunnel ?: _selected.value ?: saved, force = force)
        }
    }

    fun checkTunnelHealth(tunnel: Tunnel?, force: Boolean = false) {
        healthCheckJob?.cancel()
        if (tunnel == null) {
            _tunnelHealth.value = TunnelHealth.UNKNOWN
            return
        }
        val running = (_active.value as? TunnelState.Running)?.tunnel
        if (running != null && running.id == tunnel.id) {
            if (force || periodicHealthJob?.isActive != true) {
                _tunnelHealth.value = TunnelHealth.CHECKING
                startHealthCheckLoop(tunnel)
            }
        } else {
            _tunnelHealth.value = getTunnelHealth(tunnel.id)
            pingCache.remove(tunnel.id)
            _pingMap.value = pingCache.toMap()
        }
    }

    private suspend fun checkRunningHealthInternal(tunnel: Tunnel): Boolean = healthProbeMutex.withLock {
        currentCoroutineContext().ensureActive()
        val channel = io.github.libreroute.service.ClientChannel.state.value
        if (channel.tunnelId != tunnel.id || !channel.running || !channel.ready) {
            pingCache.remove(tunnel.id)
            _pingMap.value = pingCache.toMap()
            return@withLock false
        }
        val rtt = channel.freshRtt(android.os.SystemClock.elapsedRealtime())
        if (rtt == null) pingCache.remove(tunnel.id) else pingCache[tunnel.id] = rtt
        _pingMap.value = pingCache.toMap()
        if (_active.value.tunnel?.id == tunnel.id) {
            _active.value = TunnelState.Running(tunnel)
        }
        if (healthCache[tunnel.id] != TunnelHealth.AVAILABLE) saveHealth(tunnel.id, TunnelHealth.AVAILABLE)
        _tunnelHealth.value = TunnelHealth.AVAILABLE
        true
    }

    private fun triggerFailoverIfNeeded(failedTunnel: Tunnel) {
        val appSettings = io.github.libreroute.util.AppSettings(getApplication())
        if (!appSettings.autoFailover) return

        val all = repo.load()
        if (all.size <= 1) return
        val nextTunnel = all.firstOrNull { it.id != failedTunnel.id } ?: return

        Logx.i(TAG, "Failover triggered: switching from '${failedTunnel.name}' to '${nextTunnel.name}'")
        EventBus.dispatch(io.github.libreroute.event.AppEvent.LogMessage("[I] Failover: переключение на '${nextTunnel.name}'..."))

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
        if (!TransportType.isAvailable(tunnel.transportType)) return
        _accessRevoked.value = false
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

    fun clearAccessRevocation() { _accessRevoked.value = false }

    fun removeTunnel(tunnel: Tunnel) {
        if (_active.value.tunnel == tunnel) stop()
        val current = repo.load().toMutableList()
        current.removeAll { it.id == tunnel.id }
        repo.save(current)
        runCatching { File(getApplication<Application>().filesDir, "key_${tunnel.id}.txt").delete() }
        refresh()
    }

    fun updateTunnel(old: Tunnel, new: Tunnel) {
        if (!TransportType.isAvailable(new.transportType)) return
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
        val isProxy = io.github.libreroute.service.LibreRouteProxyService.isProxyRunning
        if (!isProxy && !bound) {
            try {
                ctx.bindService(
                    Intent(ctx, io.github.libreroute.service.SocksVpnService::class.java),
                    connection,
                    Context.BIND_AUTO_CREATE
                )
            } catch (e: Exception) {
                Logx.w(TAG, "bindService failed in syncRunningServiceState: ${e.message}")
            }
        }
        val isRunning = isProxy ||
                runCatching { service?.isVpnRunning == true }.getOrDefault(false) ||
                (io.github.libreroute.service.SocksVpnService.connectedAtRealtime > 0L) ||
                (io.github.libreroute.service.LibreRouteProxyService.connectedAtRealtime > 0L)
        if (isRunning) {
            val tName = if (isProxy) {
                io.github.libreroute.service.LibreRouteProxyService.activeTunnelName
            } else {
                io.github.libreroute.service.SocksVpnService.activeTunnelName
            }
            viewModelScope.launch {
                val tunnel = withContext(Dispatchers.IO) {
                    repo.load().firstOrNull { it.id == io.github.libreroute.service.NativeProcessSupervisor.activeTunnelId }
                        ?: (if (!tName.isNullOrEmpty()) repo.load().firstOrNull { it.name == tName } else null)
                        ?: repo.getSelected()
                }
                val currentName = if (isProxy) io.github.libreroute.service.LibreRouteProxyService.activeTunnelName
                    else io.github.libreroute.service.SocksVpnService.activeTunnelName
                if (currentName != tName ||
                    (io.github.libreroute.service.LibreRouteProxyService.connectedAtRealtime == 0L &&
                        io.github.libreroute.service.SocksVpnService.connectedAtRealtime == 0L &&
                        !io.github.libreroute.service.LibreRouteProxyService.isProxyRunning &&
                        runCatching { service?.isVpnRunning != true }.getOrDefault(true))) return@launch
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
            }
        } else {
            val noRealtime = (io.github.libreroute.service.SocksVpnService.connectedAtRealtime == 0L) &&
                    (io.github.libreroute.service.LibreRouteProxyService.connectedAtRealtime == 0L)
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
            val baseTime = if (io.github.libreroute.service.LibreRouteProxyService.connectedAtRealtime > 0L) {
                io.github.libreroute.service.LibreRouteProxyService.connectedAtRealtime
            } else if (io.github.libreroute.service.SocksVpnService.connectedAtRealtime > 0L) {
                io.github.libreroute.service.SocksVpnService.connectedAtRealtime
            } else {
                android.os.SystemClock.elapsedRealtime().also {
                    if (io.github.libreroute.service.LibreRouteProxyService.isProxyRunning) {
                        io.github.libreroute.service.LibreRouteProxyService.connectedAtRealtime = it
                    } else {
                        io.github.libreroute.service.SocksVpnService.connectedAtRealtime = it
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
        periodicHealthJob = viewModelScope.launch {
            fun ownsSession() = _active.value.isActive && _active.value.tunnel?.id == tunnel.id
            var consecutiveFailures = 0
            var wasReady = io.github.libreroute.service.ClientChannel.state.value.ready
            while (isActive && ownsSession()) {
                val channel = io.github.libreroute.service.ClientChannel.state.value
                val direct = io.github.libreroute.data.DirectDpiProfile.isDirect(tunnel)
                if (channel.waitingForNetwork && channel.tunnelId == tunnel.id) {
                    _active.value = TunnelState.WaitingForNetwork(tunnel)
                    consecutiveFailures = 0
                    pingCache.remove(tunnel.id)
                    _pingMap.value = pingCache.toMap()
                    _tunnelHealth.value = TunnelHealth.UNAVAILABLE
                } else if (direct || checkRunningHealthInternal(tunnel)) {
                    wasReady = true
                    if (direct) _active.value = TunnelState.Running(tunnel)
                    consecutiveFailures = 0
                    if (healthCache[tunnel.id] != TunnelHealth.AVAILABLE) saveHealth(tunnel.id, TunnelHealth.AVAILABLE)
                    _tunnelHealth.value = TunnelHealth.AVAILABLE
                } else {
                    consecutiveFailures++
                    val health = if (channel.rejected || consecutiveFailures >= 15) TunnelHealth.UNAVAILABLE else TunnelHealth.CHECKING
                    if (healthCache[tunnel.id] != health) saveHealth(tunnel.id, health)
                    _tunnelHealth.value = health
                    val failoverAfter = if (wasReady) 15 else 60
                    if (consecutiveFailures >= failoverAfter && !channel.rejected) {
                        reportOutage(tunnel, consecutiveFailures)
                        if (consecutiveFailures == failoverAfter) triggerFailoverIfNeeded(tunnel)
                    }
                }
                delay(1_000L)
            }
        }
    }

    private fun reportOutage(tunnel: Tunnel, failures: Int) {
        val now = android.os.SystemClock.elapsedRealtime()
        val previous = reportedOutages[tunnel.id]
        if (problemReportJob?.isActive == true || (previous != null && now - previous < 300_000L)) return
        reportedOutages[tunnel.id] = now
        // SSH delivery can be slow. It must never block connectivity checks or recovery.
        problemReportJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                runInterruptible {
                    AdminRepository.getInstance(getApplication()).submitProblemReport(
                        profileId = tunnel.name,
                        code = ProblemReport.CODE_ENDPOINT_UNREACHABLE,
                        details = mapOf(
                            "tunnel_id" to tunnel.id.toString(),
                            "consecutive_failures" to failures.toString(),
                            "reason" to "Server channel unavailable"
                        )
                    )
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Logx.w(TAG, "Connectivity incident delivery failed")
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
        problemReportJob?.cancel()
        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) {}
    }
}
