package io.github.libreroute.service

import kotlinx.coroutines.delay

/** Match the last readiness layer configured in the native process. */
internal class NativeTransportReadiness(arguments: List<String>) {
    /** Explicit provider refusals require user action; socket/flow failures do not. */
    fun isJazzRoomRejected(line: String): Boolean =
        line.contains("[JAZZ] session ended: Jazz: room access rejected", ignoreCase = true)

    private val flexible = arguments.any { it == "--proxy-routing-mode=rules" }
	private val channelGroup = arguments.any { it == "--channel-group-config" || it.startsWith("--channel-group-config=") }
	private val pairChannel = arguments.any { it == "--pair-channel-config" || it.startsWith("--pair-channel-config=") }
    val requiresPeerHealth = flagEnabled(arguments, "peer-health")
    private val requiresCoreHealth = flagEnabled(arguments, "core-health") || channelGroup || pairChannel
    private val sessionNegotiated = flagEnabled(arguments, "session-negotiate")

    /** Reconnects need the same provider handshake window as initial startup. */
    suspend fun awaitReady(transportType: String, canWait: () -> Boolean, connected: () -> Boolean): Boolean {
        val steps = if (requiresCoreHealth || requiresPeerHealth || sessionNegotiated ||
            transportType.lowercase() in setOf("mqtt", "jitsi", "jazz")) 240 else 80
        repeat(steps) {
            delay(250)
            if (!canWait()) return false
            if (connected()) return true
        }
        return false
    }

    fun isReady(line: String): Boolean {
        if (line.contains("[CORE_CONNECTION] ready")) return true
        if (flexible) return false
        if (requiresCoreHealth) return line.contains("[CORE_HEALTH] peer ready")
        if (requiresPeerHealth) return line.contains("[HEALTH] peer ready")
        if (sessionNegotiated) return line.contains("[SESSION] ready")
        return line.contains("WebSocket connected", ignoreCase = true) ||
            line.contains("WS connected", ignoreCase = true) ||
            line.contains("WS ready", ignoreCase = true) ||
            line.contains("[MAX] Connected", ignoreCase = true) ||
            line.contains("*** CONNECTED! ***", ignoreCase = true) ||
            line.contains("Signaling connected", ignoreCase = true) ||
            (line.contains("[VOLGA]", ignoreCase = true) && line.contains("transport started", ignoreCase = true)) ||
            (line.contains("[CUPS]", ignoreCase = true) && line.contains("transport started", ignoreCase = true)) ||
            (!flexible && line.contains("Running as CLIENT", ignoreCase = true)) ||
            (!flexible && line.contains("Tunnel active", ignoreCase = true)) ||
            line.contains("Direct TUN FD", ignoreCase = true)
    }

    /** Flow errors do not describe the provider connection. Health is authoritative when enabled. */
    fun isDisconnected(line: String): Boolean {
        if (line.contains("[CORE_CONNECTION] unavailable")) return true
        if (line.contains("[CORE_HEALTH] peer round trip timed out")) return true
        if (requiresCoreHealth) return false
        if (line.contains("[HEALTH] peer round trip timed out", ignoreCase = true)) return true
        if (requiresPeerHealth) return false
        if (listOf("[PKT]", "[SOCKS5]", "[ROUTER]", "[TUNNEL]", "[PROXY_ROUTE]").any { it in line }) return false
        return line.contains("jitsi: session ended:", ignoreCase = true) ||
            line.contains("mqtt: connection lost:", ignoreCase = true) ||
            line.contains("transport disconnected", ignoreCase = true) ||
            line.contains("websocket: close", ignoreCase = true) ||
            line.contains("close 1005", ignoreCase = true) ||
            line.contains("connection reset by peer", ignoreCase = true) ||
            line.contains("broken pipe", ignoreCase = true) ||
            line.contains("Read error:", ignoreCase = true)
    }

    private fun flagEnabled(arguments: List<String>, name: String): Boolean {
        // Go boolean flags accept one or two dashes; repeated flags use the last value.
        val flag = arguments.lastOrNull {
            it == "--$name" || it == "-$name" ||
                it.startsWith("--$name=") || it.startsWith("-$name=")
        } ?: return false
        return '=' !in flag || flag.substringAfter('=') in setOf("1", "t", "T", "TRUE", "true", "True")
    }
}
