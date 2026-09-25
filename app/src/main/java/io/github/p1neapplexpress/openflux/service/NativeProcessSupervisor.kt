package io.github.p1neapplexpress.openflux.service

import android.content.Context
import android.os.Handler
import android.os.Looper
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.Logx
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class NativeProcessSupervisor(private val context: Context) {

    companion object {
        private const val TAG = "NativeProcSupervisor"
        private const val STARTUP_FALLBACK_TIMEOUT_MS = 8_500L
        private const val NATIVE_LIB = "libopenflux_client.so"
        @Volatile var activeLogLevel: String = ""
    }

    class ProcessInstance(
        val proc: Process,
        val isCancelled: AtomicBoolean = AtomicBoolean(false)
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
    private val lastYandexAuthIssueAt = AtomicLong(0L)

    @Volatile private var currentTunSocketName: String? = null
    val tunSocketName: String? get() = currentTunSocketName

    val isConnected: Boolean get() = connected.get()
    val isRunning: Boolean get() = running.get() && process?.isAlive == true

    fun passTunFd(fd: java.io.FileDescriptor): Boolean {
        val sockName = currentTunSocketName ?: return false
        return io.github.p1neapplexpress.openflux.util.NativeFdPasser.sendFd(sockName, fd)
    }

    fun start(transportType: String, payload: List<String>) {
        if (process?.isAlive == true || running.get()) {
            Logx.w(TAG, "Previous native process was still active during start(); stopping it first")
            stop()
            try { Thread.sleep(150) } catch (_: InterruptedException) {}
        }
        shuttingDown.set(false)
        running.set(true)
        connected.set(false)
        Logx.i(TAG, "start transport=$transportType")
        spawn(transportType, payload)
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        shuttingDown.set(true)
        running.set(false)
        connected.set(false)
        currentInstance?.isCancelled?.set(true)
        cleanup()
    }

    private fun spawn(transportType: String, payload: List<String>) {
        val libPath = "${context.applicationInfo.nativeLibraryDir}/$NATIVE_LIB"
        try {
            val appSettings = io.github.p1neapplexpress.openflux.util.AppSettings(context)
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
                    else -> {
                        cleanPayload.add(arg)
                        pIdx++
                    }
                }
            }
            val unsupported = listOf("--doh", "--doh-url", "--domain-rules-file", "--domain-mode", "--socks5-user", "--socks5-pass")
            if (cleanPayload.any { arg -> unsupported.any { flag -> arg == flag || arg.startsWith("$flag=") } }) {
                throw IllegalArgumentException("Этот профиль использует DoH, доменные правила или SOCKS-аутентификацию, пока не реализованные в новом ядре OpenFlux")
            }
            if (cleanPayload.none { it == "--url" || it.startsWith("--url=") }) {
                fallbackUrls?.split(',')?.firstOrNull { it.isNotBlank() }?.let {
                    cleanPayload.add("--url")
                    cleanPayload.add(it.trim())
                }
            }

            val documentUrl = cleanPayload.windowed(2, 1).firstOrNull { it[0] == "--url" }?.getOrNull(1)
                ?: cleanPayload.firstOrNull { it.startsWith("--url=") }?.substringAfter('=')
            val isYandexTransport = transportType.equals("vyandex", true) || transportType.equals("yandex", true)
            val allowedYandexDocumentUrl = documentUrl?.takeIf { raw ->
                runCatching {
                    val uri = android.net.Uri.parse(raw)
                    uri.scheme == "https" &&
                        (uri.host == "yandex.ru" || uri.host?.endsWith(".yandex.ru") == true)
                }.getOrDefault(false)
            }

            val socketName = "@openflux_tun_${System.currentTimeMillis()}"
            currentTunSocketName = socketName

            val cmd = buildList {
                add(libPath)
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
                if (transportType.equals("vyandex", true) || transportType.equals("yandex", true)) {
                    val cookieFile = java.io.File(context.filesDir, "yandex_webview_cookies.json")
                    if (cookieFile.isFile) add("--yandex-cookie-file=${cookieFile.absolutePath}")
                }
                addAll(cleanPayload)
            }
            // The payload contains document URLs and may contain tokens or key paths.
            // Never print the command line, even with selective redaction.
            Logx.i(TAG, "starting locally built OpenFlux core for $transportType")

            // This core uses one document per process. The service can rotate
            // --url on reconnect, but it does not maintain simultaneous lanes.
            val isMultiLane = false

            val pb = ProcessBuilder(cmd)
                .directory(context.filesDir)
                .redirectErrorStream(true)
            val proc = pb.start()
            val instance = ProcessInstance(proc)
            currentInstance = instance
            process = proc
            runCatching { proc.outputStream.close() }

            stdoutThread = Thread {
                try {
                    proc.inputStream.bufferedReader().use { r ->
                        var line: String?
                        while (r.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (l.isBlank()) continue
                            if (instance.isCancelled.get() || currentInstance !== instance) {
                                break
                            }
                            val lClean = l.replace(" (gVisor)", "")
                            android.util.Log.d("NativeStdout", l)
                            val authIssue = if (isYandexTransport && allowedYandexDocumentUrl != null) {
                                YandexAuthIssueDetector.classify(l)
                            } else null
                            if (authIssue != null) {
                                val now = android.os.SystemClock.elapsedRealtime()
                                val previous = lastYandexAuthIssueAt.get()
                                if (previous == 0L || now - previous >= 5 * 60_000L) {
                                    lastYandexAuthIssueAt.set(now)
                                    EventBus.dispatch(AppEvent.YandexAuthIssue(allowedYandexDocumentUrl!!, authIssue))
                                    EventBus.dispatch(AppEvent.LogMessage("[VOLGA] Яндекс требует проверки авторизации в WebView"))
                                }
                            }

                            // Multi-lane recovery observer
                            if (isMultiLane && (l.contains("lane", ignoreCase = true) || l.contains("channel", ignoreCase = true)) &&
                                (l.contains("fail", ignoreCase = true) || l.contains("disconnect", ignoreCase = true) || l.contains("reconnect", ignoreCase = true) || l.contains("recover", ignoreCase = true))
                            ) {
                                EventBus.dispatch(AppEvent.LogMessage("[TRANSPORT] Один канал Yandex Docs восстанавливается; туннель продолжает работать"))
                                Logx.i(TAG, "Multi-lane recovery active: $l")
                            }

                            // CORE-06: Detect post-connect transport drops and Engine.IO close 1005 disconnects
                            val isGeneralDrop = l.contains("transport disconnected", ignoreCase = true) ||
                                    l.contains("[HEALTH] peer round trip timed out", ignoreCase = true) ||
                                    l.contains("websocket: close", ignoreCase = true) ||
                                    l.contains("close 1005", ignoreCase = true) ||
                                    l.contains("connection reset by peer", ignoreCase = true) ||
                                    l.contains("broken pipe", ignoreCase = true)

                            val isDrop = if (isMultiLane) {
                                l.contains("close 1005", ignoreCase = true) ||
                                        l.contains("all lanes failed", ignoreCase = true) ||
                                        l.contains("all channels disconnected", ignoreCase = true)
                            } else {
                                isGeneralDrop || l.contains("Read error:", ignoreCase = true)
                            }

                            if (connected.get() && isDrop) {
                                if (connected.getAndSet(false)) {
                                    Logx.w(TAG, "Post-connect transport drop detected: $l")
                                    EventBus.dispatch(AppEvent.TransportDisconnected)
                                    EventBus.dispatch(AppEvent.LogMessage("[W] Обрыв транспорта: $l"))
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
                                    EventBus.dispatch(AppEvent.LogMessage("[E] Transport error: $l"))
                                    Logx.e(TAG, "Transport error detected: $l")
                                    continue
                                }
                            }

                            // 2. Detect connection success immediately (MUST NOT be skipped by log level)
                            if (l.contains("WebSocket connected", ignoreCase = true) ||
                                l.contains("WS connected", ignoreCase = true) ||
                                l.contains("WS ready", ignoreCase = true) ||
                                l.contains("[MAX] Connected", ignoreCase = true) ||
                                l.contains("*** CONNECTED! ***", ignoreCase = true) ||
                                l.contains("Signaling connected", ignoreCase = true) ||
                                (l.contains("[VOLGA]", ignoreCase = true) && l.contains("transport started", ignoreCase = true)) ||
                                (l.contains("[CUPS]", ignoreCase = true) && l.contains("transport started", ignoreCase = true)) ||
                                l.contains("Running as CLIENT", ignoreCase = true) ||
                                l.contains("Tunnel active", ignoreCase = true) ||
                                l.contains("Direct TUN FD", ignoreCase = true)
                            ) {
                                gracePeriodRunnable?.let { handler.removeCallbacks(it) }
                                gracePeriodRunnable = null

                                val isYandexWs = transportType.equals("yandex", ignoreCase = true) ||
                                        l.contains("[YDOCS]", ignoreCase = true)
                                val delayMs = if (isYandexWs) 2000L else 0L

                                if (delayMs > 0) {
                                    Logx.i(TAG, "Transport connected, waiting ${delayMs}ms for doc auth to complete before tun2socks...")
                                    delayedConnectRunnable?.let { handler.removeCallbacks(it) }
                                    val r = Runnable {
                                        delayedConnectRunnable = null
                                        if (running.get() && !instance.isCancelled.get() && currentInstance === instance && proc.isAlive) {
                                            if (!connected.getAndSet(true)) {
                                                EventBus.dispatch(AppEvent.TransportConnected)
                                                Logx.i(TAG, "Native transport confirmed connected from stdout (delayed): $l")
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
                                            EventBus.dispatch(AppEvent.TransportConnected)
                                            Logx.i(TAG, "Native transport confirmed connected from stdout: $l")
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
                                EventBus.dispatch(AppEvent.LogMessage(l))
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (!instance.isCancelled.get() && currentInstance === instance) {
                        Logx.w(TAG, "stdout reader error: ${e.message}")
                    }
                } finally {
                    delayedConnectRunnable?.let { handler.removeCallbacks(it) }
                    delayedConnectRunnable = null
                    gracePeriodRunnable?.let { handler.removeCallbacks(it) }
                    gracePeriodRunnable = null
                    val exitCode = try { proc.waitFor() } catch (_: Exception) { null }
                    if (!instance.isCancelled.get() && currentInstance === instance) {
                        Logx.e(TAG, "native process exited unexpectedly with code $exitCode")
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
            Logx.e(TAG, "spawn failed", e)
            running.set(false)
            connected.set(false)
            EventBus.dispatch(AppEvent.TransportDisconnected)
            EventBus.dispatch(AppEvent.LogMessage("[E] Spawn failed: ${e.message}"))
        }
    }

    private fun cleanupInstance(inst: ProcessInstance) {
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
