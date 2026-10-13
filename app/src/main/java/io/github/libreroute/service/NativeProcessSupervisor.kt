package io.github.libreroute.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.libreroute.event.AppEvent
import io.github.libreroute.event.EventBus
import io.github.libreroute.util.Logx
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

class NativeProcessSupervisor(private val context: Context) {

    data class AdminCarrier(val path: String, val transport: String, val serverId: String? = null,
                            val bindingKey: String? = null, val ready: Boolean = false)

    companion object {
        private const val TAG = "NativeProcSupervisor"
        private const val STARTUP_FALLBACK_TIMEOUT_MS = 8_500L
        private const val NATIVE_LIB = "liblibreroute_client.so"
        private val siteRoutesJson = kotlinx.serialization.json.Json { encodeDefaults = true }
        @Volatile var activeLogLevel: String = ""
        @Volatile var activeAdminCarrier: AdminCarrier? = null
        @Volatile var activeTunnelId: Long? = null
        @Synchronized fun bindAdminCarrier(carrier: AdminCarrier, serverId: String) {
            if (activeAdminCarrier == carrier) activeAdminCarrier = carrier.copy(serverId = serverId)
        }
    }

    class ProcessInstance(
        val proc: Process,
        val isCancelled: AtomicBoolean = AtomicBoolean(false),
        val authIssueReported: AtomicBoolean = AtomicBoolean(false),
        val dpiEngines: List<TpwsEngine> = emptyList(),
        val siteRoutesFile: java.io.File? = null,
        val appRouteServer: AppRouteServer? = null
    )

    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var process: Process? = null
    @Volatile private var currentInstance: ProcessInstance? = null
    @Volatile private var stdoutThread: Thread? = null
    @Volatile private var gracePeriodRunnable: Runnable? = null
    @Volatile private var delayedConnectRunnable: Runnable? = null

    private val running = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private val shuttingDown = AtomicBoolean(false)
    private val launchGeneration = java.util.concurrent.atomic.AtomicLong()

    @Volatile private var currentAdminCarrierPath: String? = null
    @Volatile private var currentTunSocketName: String? = null
    val tunSocketName: String? get() = currentTunSocketName

    val isConnected: Boolean get() = connected.get()
    val isRunning: Boolean get() = running.get() && process?.isAlive == true

    fun passTunFd(fd: java.io.FileDescriptor): Boolean {
        val sockName = currentTunSocketName ?: return false
        return io.github.libreroute.util.NativeFdPasser.sendFd(sockName, fd)
    }

    fun start(transportType: String, payload: List<String>, tunnelId: Long? = null) {
        if (process?.isAlive == true || running.get()) {
            Logx.w(TAG, "Previous native process was still active during start(); stopping it first")
            stop()
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }
        shuttingDown.set(false)
        running.set(true)
        connected.set(false)
        EventBus.clearPendingJitsiIssue()
        if (transportType !in setOf("yandex", "vyandex")) EventBus.clearPendingYandexAuthIssue()
        Logx.i(TAG, "start transport=$transportType")
        spawn(io.github.libreroute.data.TransportType.fromOrNull(transportType)?.cliName ?: transportType, payload, tunnelId, launchGeneration.incrementAndGet())
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        shuttingDown.set(true)
        launchGeneration.incrementAndGet()
        running.set(false)
        connected.set(false)
        currentInstance?.isCancelled?.set(true)
        cleanup()
    }

    private fun spawn(transportType: String, payload: List<String>, tunnelId: Long?, generation: Long) {
        activeTunnelId = tunnelId
        val libPath = "${context.applicationInfo.nativeLibraryDir}/$NATIVE_LIB"
        val launchDpiEngines = mutableListOf<TpwsEngine>()
        var launchSiteRoutesFile: java.io.File? = null
        var launchAppRouteServer: AppRouteServer? = null
        var launchedProcess: Process? = null
        fun checkLaunchActive() {
            check(!shuttingDown.get() && launchGeneration.get() == generation) { "Запуск отменён" }
        }
        try {
            val appSettings = io.github.libreroute.util.AppSettings(context)
            val currentLogLevel = activeLogLevel.ifBlank { appSettings.connectionLogLevel }
            val isDebug = currentLogLevel == "DEBUG" || appSettings.verboseLog

            val cleanPayload = mutableListOf<String>()
            var fallbackUrls: String? = null
            var pIdx = 0
            while (pIdx < payload.size) {
                val arg = payload[pIdx]
                when {
                    arg == "--debug" -> pIdx++
                    arg == "--log-level" -> pIdx += 2
                    arg == "--urls" -> {
                        fallbackUrls = payload.getOrNull(pIdx + 1)
                        pIdx += 2
                    }
                    arg.startsWith("--urls=") -> {
                        fallbackUrls = arg.substringAfter('=')
                        pIdx++
                    }
                    arg == "--tun-socket" || arg == "--tun-mtu" -> pIdx += 2
                    arg.startsWith("--tun-socket=") || arg.startsWith("--tun-mtu=") -> pIdx++
                    arg == "--admin-carrier-socket" -> pIdx += 2
                    arg.startsWith("--admin-carrier-socket=") -> pIdx++
                    arg == "--site-routes-file" || arg == "-site-routes-file" -> pIdx += 2
                    arg.startsWith("--site-routes-file=") || arg.startsWith("-site-routes-file=") -> pIdx++
                    else -> {
                        cleanPayload.add(arg)
                        pIdx++
                    }
                }
            }
            if (cleanPayload.none { it == "--url" || it.startsWith("--url=") }) {
                fallbackUrls?.split(',')?.firstOrNull { it.isNotBlank() }?.let {
                    cleanPayload.add("--url")
                    cleanPayload.add(it.trim())
                }
            }
            val isServiceTransport = transportType.equals("mqtt", true) || transportType.equals("jitsi", true)
            val isDirectDpi = transportType.equals("direct-dpi", true) || transportType.equals("directDpi", true)
            val routingProfiles = if (isDirectDpi || !appSettings.proxyOnlyMode)
                io.github.libreroute.util.ZapretProfilePreferences(context).load() else null
            val startedProfilePorts = mutableMapOf<String, Int>()
            if (isDirectDpi) {
                val profile = requireNotNull(routingProfiles).active
                checkLaunchActive()
                val engine = TpwsEngine(context)
                launchDpiEngines.add(engine)
                val dpiPort = engine.start(profile)
                startedProfilePorts[profile.id] = dpiPort
                val runtimePayload = zapretLaunchOptions(cleanPayload, profile, dpiPort)
                cleanPayload.clear()
                cleanPayload.addAll(runtimePayload)
            }
            // All entry points (UI, tile, boot and reconnect) use this supervisor.
            // Both VPN modes use the same rules. In standalone mode the common
            // local zapret dialer supplies the path labelled "tunnel".
            if (isDirectDpi || !appSettings.proxyOnlyMode) {
                val routes = io.github.libreroute.util.SiteRoutingPreferences(context).load()
                val apps = io.github.libreroute.util.AppRoutingPreferences(context).load()
                // The common default also applies when both rule lists are disabled.
                run {
                    val profiles = requireNotNull(routingProfiles)
                    routes.validate(profiles)
                    apps.validate(profiles)
                    val usedProfiles = ((if (routes.enabled) routes.rules.filter { it.action == "zapret" }.map { it.profile } else emptyList()) +
                        (if (apps.enabled) apps.rules.filter { it.action == "zapret" }.map { it.profile } else emptyList())).distinct()
                    val ports = usedProfiles.associateWith { id ->
                        checkLaunchActive()
                        startedProfilePorts.getOrPut(id) {
                            val engine = TpwsEngine(context)
                            // Register before start so a partial launch is cleaned up.
                            launchDpiEngines.add(engine)
                            engine.start(profiles.profiles.first { it.id == id })
                        }
                    }
                    checkLaunchActive()
                    if (apps.enabled) launchAppRouteServer = AppRouteServer(context, apps)
                    val runtime = io.github.libreroute.data.nativeTrafficRoutes(routes, apps, profiles, ports, launchAppRouteServer?.address ?: "")
                    val file = java.io.File.createTempFile("site-routes-", ".json", context.filesDir)
                    launchSiteRoutesFile = file
                    file.writeText(siteRoutesJson.encodeToString(io.github.libreroute.data.NativeSiteRoutes.serializer(), runtime))
                    cleanPayload.add("--site-routes-file=${file.absolutePath}")
                }
            }
            if (isServiceTransport && cleanPayload.none { it == "--peer-health" || it == "--peer-health=true" }) {
                cleanPayload.removeAll { it == "--peer-health=false" }
                cleanPayload.add("--peer-health")
            }
            // Import identity only at runtime, into the existing private pipe.
            // A stored/exported Tunnel never contains a device private key.
            val pairConfig = cleanPayload.windowed(2).firstOrNull { it[0] == "--pair-channel-config" }?.getOrNull(1)
            val groupConfig = cleanPayload.windowed(2).firstOrNull { it[0] == "--channel-group-config" }?.getOrNull(1)
                ?: cleanPayload.firstOrNull { it.startsWith("--channel-group-config=") }?.substringAfter('=')
            val group = groupConfig?.let {
                require(transportType.equals("channel-group", true) && pairConfig == null) { "Некорректный тип объединения каналов" }
                val binding = kotlinx.serialization.json.Json.decodeFromString<io.github.libreroute.admin.ChannelGroupBinding>(it)
                val first = binding.lanes.firstOrNull()?.binding ?: error("В группе нет каналов")
                val crypto = io.github.libreroute.admin.routeIdentity(io.github.libreroute.admin.SecureInvitationStateStore(context), first.device_fingerprint)
                binding.validate(first.server_id, crypto.publicKeyHashHex(), crypto.getOrCreatePublicKeyBase64())
                cleanPayload.add("--pair-device-private-key")
                cleanPayload.add(crypto.privateKeyForNativePipe(first.device_fingerprint))
                binding
            }
            if (pairConfig != null) {
                require(transportType.equals("jazz", true)) { "Jazz V2 requires Jazz transport" }
                val binding = kotlinx.serialization.json.Json.decodeFromString<io.github.libreroute.admin.PairChannelBinding>(pairConfig)
                val crypto = io.github.libreroute.admin.routeIdentity(io.github.libreroute.admin.SecureInvitationStateStore(context), binding.device_fingerprint)
                binding.validate(binding.server_id, crypto.publicKeyHashHex(), crypto.getOrCreatePublicKeyBase64())
                cleanPayload.add("--pair-device-private-key")
                cleanPayload.add(crypto.privateKeyForNativePipe(binding.device_fingerprint))
            }
            val readiness = NativeTransportReadiness(cleanPayload)

            val documentUrl = cleanPayload.windowed(2, 1).firstOrNull { it[0] == "--url" }?.getOrNull(1)
                ?: cleanPayload.firstOrNull { it.startsWith("--url=") }?.substringAfter('=')
            val isYandexTransport = transportType.equals("vyandex", true) || transportType.equals("yandex", true) || group?.hasYandex == true
            val allowedYandexDocumentUrl = documentUrl?.takeIf { raw ->
                runCatching {
                    val uri = android.net.Uri.parse(raw)
                    uri.scheme == "https" &&
                        (uri.host == "yandex.ru" || uri.host?.endsWith(".yandex.ru") == true)
                }.getOrDefault(false)
            }

            val socketName = "@libreroute_tun_${System.currentTimeMillis()}"
            currentTunSocketName = socketName

            val adminCarrierPath = java.io.File(context.filesDir, "admin-carrier-${System.nanoTime()}.sock").absolutePath
            currentAdminCarrierPath = adminCarrierPath
            val cmd = buildList {
                add(libPath)
                if (transportType.lowercase() in setOf("vyandex", "yandex", "jazz", "jitsi", "mqtt", "channel-group")) add("--admin-carrier-socket=$adminCarrierPath")
                if (isDebug) {
                    add("--debug")
                }
                var hasRole = false
                var hasInbound = false
                var hasTunSocket = false
                var hasTunMtu = false
                for (arg in cleanPayload) {
                    if (arg == "--client" || arg == "-client" || arg == "--role=client" || arg == "-role=client" ||
                        arg == "--role" || arg == "-role" || arg.startsWith("--role=") || arg.startsWith("-role=")) {
                        hasRole = true
                    }
                    if (arg == "--exit-node" || arg == "-exit-node" || arg == "--role=exit" || arg == "-role=exit") {
                        hasRole = true
                    }
                    if (arg == "--inbound" || arg.startsWith("--inbound=") || arg == "-inbound" || arg.startsWith("-inbound=") ||
                        arg == "--tun" || arg == "-tun" || arg == "--socks5-mode" || arg == "-socks5-mode") {
                        hasInbound = true
                    }
                    if (arg == "--tun-socket" || arg.startsWith("--tun-socket=")) {
                        hasTunSocket = true
                    }
                    if (arg == "--tun-mtu" || arg.startsWith("--tun-mtu=")) {
                        hasTunMtu = true
                    }
                }
                if (!hasRole) {
                    add("--role=client")
                }
                if (!hasInbound) {
                    add("--inbound=tun")
                }
                if (!hasTunSocket) {
                    add("--tun-socket=$socketName")
                }
                if (!hasTunMtu) {
                    add("--tun-mtu=${appSettings.mtu}")
                }
                if (isYandexTransport) {
                    val cookieFile = java.io.File(context.filesDir, "yandex_webview_cookies.json")
                    add("--yandex-cookie-file=${cookieFile.absolutePath}")
                    if (transportType.equals("vyandex", true) || group != null) add("--yandex-mobile")
                }
                addAll(cleanPayload)
            }
            // The payload contains document URLs and may contain tokens or key paths.
            // Never print the command line, even with selective redaction.
            Logx.i(TAG, "starting locally built LibreRoute core for $transportType")

            // One native process owns the full group and its one tunnel.
            val isMultiLane = group != null && group.lanes.size > 1

            val pb = ProcessBuilder(libPath, "--config-stdin")
                .directory(context.filesDir)
                .redirectErrorStream(true)
            val carrierBinding = io.github.libreroute.admin.AdminRepository.getInstance(context).adminCarrierBinding(tunnelId)
            checkLaunchActive()
              val proc = pb.start()
            launchedProcess = proc
            checkLaunchActive()
            val logRedactor = io.github.libreroute.util.NativeLogRedactor(cmd.drop(1))
            val instance = ProcessInstance(proc, dpiEngines = launchDpiEngines.toList(), siteRoutesFile = launchSiteRoutesFile, appRouteServer = launchAppRouteServer)
            currentInstance = instance
            process = proc
            if (transportType.lowercase() in setOf("vyandex", "yandex", "jazz", "jitsi", "mqtt", "channel-group")) {
                val (serverId, bindingKey) = carrierBinding
                activeAdminCarrier = AdminCarrier(adminCarrierPath, transportType.lowercase(), serverId, bindingKey)
            }
            proc.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(org.json.JSONArray(cmd.drop(1)).toString())
            }

            stdoutThread = Thread {
                try {
                    proc.inputStream.bufferedReader().use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (l.isBlank()) continue
                            if (group != null && l.contains("[GROUP] failed:")) proc.destroy()
                            if (instance.isCancelled.get() || currentInstance !== instance) {
                                break
                            }
                            val lClean = logRedactor.redact(l.replace(" (gVisor)", ""))
                            android.util.Log.d("NativeStdout", lClean)
                            io.github.libreroute.util.FileLogger.append(lClean)
                            val groupAuthEvent = group?.let { ChannelGroupAuthEvent.parse(it, l) }
                            val authDocumentUrl = groupAuthEvent?.url ?: allowedYandexDocumentUrl?.takeIf { group == null }
                            if (authDocumentUrl != null && (groupAuthEvent?.reason == null && groupAuthEvent != null || group == null && "[VOLGA] authorization established" in l)) {
                                EventBus.clearPendingYandexAuthIssue(authDocumentUrl)
                                io.github.libreroute.admin.InstallationLog.append(context, "client_yandex_authorized", "cookies_present=${java.io.File(context.filesDir, "yandex_webview_cookies.json").isFile}")
                            }
                            val authIssue = groupAuthEvent?.reason ?: if (group == null && isYandexTransport && allowedYandexDocumentUrl != null) {
                                YandexAuthIssueDetector.classify(l)
                            } else null
                            if (authIssue != null) {
                                if (instance.authIssueReported.compareAndSet(false, true)) {
                                    io.github.libreroute.admin.InstallationLog.append(context, "client_yandex_auth_required", "reason=${authIssue.name} cookies_present=${java.io.File(context.filesDir, "yandex_webview_cookies.json").isFile}")
                                    EventBus.dispatch(AppEvent.YandexAuthIssue(authDocumentUrl!!, authIssue))
                                    EventBus.dispatch(AppEvent.LogMessage("[VOLGA] Яндекс требует проверки авторизации в WebView"))
                                }
                            }

                            if (transportType.equals("jitsi", true) &&
                                ("[JITSI_AUTH] moderator-required" in l || "[JITSI_AUTH] token-expired" in l || "[JITSI_AUTH] key-unavailable" in l) &&
                                documentUrl != null) {
                                val reason = if ("[JITSI_AUTH] key-unavailable" in l)
                                    io.github.libreroute.event.JitsiAuthReason.KEY_UNAVAILABLE
                                else if ("[JITSI_AUTH] token-expired" in l)
                                    io.github.libreroute.event.JitsiAuthReason.TOKEN_EXPIRED
                                else io.github.libreroute.event.JitsiAuthReason.MODERATOR_REQUIRED
                                EventBus.dispatch(AppEvent.JitsiModeratorRequired(documentUrl, reason))
                            }

                            // Multi-lane recovery observer
                            if (isMultiLane && (l.contains("lane", ignoreCase = true) || l.contains("channel", ignoreCase = true)) &&
                                (l.contains("fail", ignoreCase = true) || l.contains("disconnect", ignoreCase = true) || l.contains("reconnect", ignoreCase = true) || l.contains("recover", ignoreCase = true))
                            ) {
                                if (connected.get()) EventBus.dispatch(AppEvent.LogMessage("[TRANSPORT] Один из каналов восстанавливается; подключение продолжает работать"))
                                Logx.i(TAG, "Multi-lane recovery active: $lClean")
                            }

                            // CORE-06: Detect post-connect transport drops and Engine.IO close 1005 disconnects
                            val isGeneralDrop = (isServiceTransport && (l.contains("jitsi: session ended:") || l.contains("mqtt: connection lost:"))) || l.contains("transport disconnected", ignoreCase = true) ||
                                    l.contains("[HEALTH] peer round trip timed out", ignoreCase = true) ||
                                    l.contains("websocket: close", ignoreCase = true) ||
                                    l.contains("close 1005", ignoreCase = true) ||
                                    l.contains("connection reset by peer", ignoreCase = true) ||
                                    l.contains("broken pipe", ignoreCase = true)

                            val isDrop = if (group != null) {
                                l.contains("[GROUP] disconnected") || l.contains("[GROUP] failed:")
                            } else {
                                isGeneralDrop || l.contains("Read error:", ignoreCase = true)
                            }

                            if (connected.get() && isDrop) {
                                if (connected.getAndSet(false)) {
                                      setAdminCarrierReady(false)
                                    Logx.w(TAG, "Post-connect transport drop detected: $lClean")
                                    EventBus.dispatch(AppEvent.TransportDisconnected)
                                    EventBus.dispatch(AppEvent.LogMessage("[W] Обрыв транспорта: $lClean"))
                                }
                            }

                            // 1. Detect critical transport startup errors (ignore client proxy/routing lines)
                            if (!connected.get() && !l.contains("[ROUTER]") && !l.contains("[SOCKS5]")) {
                                if (l.contains("Failed to start transport", ignoreCase = true) ||
                                    l.contains("panic:", ignoreCase = true) ||
                                    l.contains("Unknown transport type", ignoreCase = true) ||
                                    l.contains("flag provided but not defined", ignoreCase = true) ||
                                    l.contains("close 1005", ignoreCase = true)
                                ) {
                                    delayedConnectRunnable?.let { handler.removeCallbacks(it) }
                                    delayedConnectRunnable = null
                                    gracePeriodRunnable?.let { handler.removeCallbacks(it) }
                                    gracePeriodRunnable = null
                                    connected.set(false)
                                    running.set(false)
                                    EventBus.dispatch(AppEvent.TransportDisconnected)
                                    EventBus.dispatch(AppEvent.LogMessage("[E] Transport error: $lClean"))
                                    Logx.e(TAG, "Transport error detected: $lClean")
                                    continue
                                }
                            }

                            // 2. Detect connection success immediately (MUST NOT be skipped by log level)
                            if (readiness.isReady(l)) {
                                if (isServiceTransport) EventBus.clearPendingJitsiIssue()
                                gracePeriodRunnable?.let { handler.removeCallbacks(it) }
                                gracePeriodRunnable = null

                                val isYandexWs = transportType.equals("yandex", ignoreCase = true) ||
                                        l.contains("[YDOCS]", ignoreCase = true)
                                val delayMs = if (isYandexWs && !readiness.requiresPeerHealth) 2000L else 0L

                                if (delayMs > 0) {
                                    Logx.i(TAG, "Transport connected, waiting ${delayMs}ms for doc auth to complete before tun2socks...")
                                    delayedConnectRunnable?.let { handler.removeCallbacks(it) }
                                    val r = Runnable {
                                        delayedConnectRunnable = null
                                        if (running.get() && !instance.isCancelled.get() && currentInstance === instance && proc.isAlive) {
                                            if (!connected.getAndSet(true)) {
                                                  setAdminCarrierReady(true)
                                                EventBus.dispatch(AppEvent.TransportConnected)
                                                Logx.i(TAG, "Native transport confirmed connected from stdout (delayed): $lClean")
                                            }
                                        }
                                    }
                                    delayedConnectRunnable = r
                                    handler.postDelayed(r, delayMs)
                                } else {
                                    delayedConnectRunnable?.let { handler.removeCallbacks(it) }
                                    delayedConnectRunnable = null
                                    if (running.get() && !instance.isCancelled.get() && currentInstance === instance && proc.isAlive) {
                                        if (!connected.getAndSet(true)) {
                                                  setAdminCarrierReady(true)
                                            EventBus.dispatch(AppEvent.TransportConnected)
                                            Logx.i(TAG, "Native transport confirmed connected from stdout: $lClean")
                                        }
                                    }
                                }
                            }

                            // 3. UI log level filtering (ONLY gates whether this line is forwarded to the log viewer)
                            val effLogLevel = activeLogLevel.ifBlank { appSettings.connectionLogLevel }
                            val isRoutingLog = l.contains("[ROUTER]") || l.contains("DIRECT") ||
                                    l.contains("PROXY") || l.contains("gVisor")
                            val isPacketLog = (l.startsWith("<- ") || l.startsWith("-> ") ||
                                    l.contains("bytes - TCP") || l.contains("bytes - UDP")) && !isRoutingLog
                            val isDebugLine = (isPacketLog || l.contains("[D]") || l.contains("[DEBUG]", ignoreCase = true)) && !isRoutingLog
                            val isWarnLine = l.contains("[W]") || l.contains("warning", ignoreCase = true)
                            val isErrorLine = l.contains("[E]") || l.contains("error", ignoreCase = true) ||
                                    l.contains("panic:", ignoreCase = true) || l.contains("Failed to start", ignoreCase = true)

                            val allowLog = when (effLogLevel) {
                                "DEBUG" -> true
                                "INFO" -> isRoutingLog || !isDebugLine
                                "WARN" -> isWarnLine || isErrorLine
                                "ERROR" -> isErrorLine
                                else -> isRoutingLog || !isDebugLine
                            }
                            if (allowLog) {
                                EventBus.dispatch(AppEvent.LogMessage(lClean), writeToFile = false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!instance.isCancelled.get() && currentInstance === instance) {
                        Logx.w(TAG, "stdout reader error: ${e.message}")
                    }
                } finally {
                    instance.dpiEngines.forEach { it.stop() }
                    instance.siteRoutesFile?.delete()
                    instance.appRouteServer?.close()
                    if (currentInstance === instance) {
                        delayedConnectRunnable?.let { handler.removeCallbacks(it) }
                        delayedConnectRunnable = null
                        gracePeriodRunnable?.let { handler.removeCallbacks(it) }
                        gracePeriodRunnable = null
                    }
                    val exitCode = try { proc.waitFor() } catch (_: Exception) { null }
                    if (!instance.isCancelled.get() && currentInstance === instance) {
                        Logx.e(TAG, "native process exited unexpectedly with code $exitCode")
                          setAdminCarrierReady(false)
                        connected.set(false)
                        running.set(false)
                        EventBus.dispatch(AppEvent.TransportDisconnected)
                        EventBus.dispatch(AppEvent.LogMessage("[E] Native transport process exited (code $exitCode)"))
                    }
                }
            }.apply {
                name = "NativeStdoutReader"
                isDaemon = true
                start()
            }

            gracePeriodRunnable?.let { handler.removeCallbacks(it) }
            val runnable = Runnable {
                if (!instance.isCancelled.get() && currentInstance === instance) {
                    if (proc.isAlive) {
                        if (!connected.get()) {
                            Logx.w(TAG, "Native transport did not report connection within $STARTUP_FALLBACK_TIMEOUT_MS ms")
                            EventBus.dispatch(AppEvent.LogMessage("[W] Transport startup waiting for connection..."))
                        }
                    } else {
                        Logx.e(TAG, "native process died during startup")
                        connected.set(false)
                        running.set(false)
                        EventBus.dispatch(AppEvent.TransportDisconnected)
                        EventBus.dispatch(AppEvent.LogMessage("[E] Native transport failed to start (process exited)"))
                    }
                }
            }
            gracePeriodRunnable = runnable
            handler.postDelayed(runnable, STARTUP_FALLBACK_TIMEOUT_MS)

        } catch (e: Exception) {
            launchedProcess?.let { runCatching { it.destroyForcibly() } }
            launchDpiEngines.forEach { it.stop() }
            launchSiteRoutesFile?.delete()
            launchAppRouteServer?.close()
            if (shuttingDown.get() || launchGeneration.get() != generation) return
            Logx.e(TAG, "spawn failed", e)
            running.set(false)
            connected.set(false)
            EventBus.dispatch(AppEvent.TransportDisconnected)
            EventBus.dispatch(AppEvent.LogMessage("[E] Spawn failed: ${e.message}"))
        }
    }

    private fun setAdminCarrierReady(ready: Boolean) {
        val updated = synchronized(Companion) {
            val carrier = activeAdminCarrier ?: return
            if (carrier.path != currentAdminCarrierPath || carrier.ready == ready) return
            carrier.copy(ready = ready).also { activeAdminCarrier = it }
        }
        if (ready && updated.serverId != null) {
            Thread {
                runCatching { io.github.libreroute.admin.AdminRepository.getInstance(context).connectAdminCarrier(updated) }
            }.apply { name = "AdminCarrierReadiness"; isDaemon = true; start() }
        }
    }

    private fun cleanupInstance(inst: ProcessInstance) {
        inst.dpiEngines.forEach { it.stop() }
        inst.siteRoutesFile?.delete()
        inst.appRouteServer?.close()
        val p = inst.proc
        runCatching { p.outputStream?.close() }
        runCatching { p.inputStream?.close() }
        runCatching { p.errorStream?.close() }
        if (p.isAlive) {
            p.destroy()
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    p.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } catch (_: Exception) {}
            if (p.isAlive) {
                p.destroyForcibly()
            }
        }
    }

    private fun cleanup() {
        if (activeAdminCarrier?.path == currentAdminCarrierPath) activeAdminCarrier = null
        activeTunnelId = null
        currentAdminCarrierPath?.let { java.io.File(it).delete() }
        currentAdminCarrierPath = null
        delayedConnectRunnable?.let { handler.removeCallbacks(it) }
        delayedConnectRunnable = null
        gracePeriodRunnable?.let { handler.removeCallbacks(it) }
        gracePeriodRunnable = null
        stdoutThread?.interrupt()
        stdoutThread = null
        currentInstance?.let { inst ->
            cleanupInstance(inst)
        }
        currentInstance = null
        process = null
        currentTunSocketName = null
    }

}
