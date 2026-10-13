package io.github.libreroute.service

import kotlinx.coroutines.delay

/** Match the last readiness layer configured in the native process. */
internal class NativeTransportReadiness(arguments: List<String>) {
	private val channelGroup = arguments.any { it == "--channel-group-config" || it.startsWith("--channel-group-config=") }
	private val pairChannel = arguments.any { it == "--pair-channel-config" || it.startsWith("--pair-channel-config=") }
    val requiresPeerHealth = flagEnabled(arguments, "peer-health")
    private val sessionNegotiated = flagEnabled(arguments, "session-negotiate")

    /** Reconnects need the same provider handshake window as initial startup. */
    suspend fun awaitReady(transportType: String, canWait: () -> Boolean, connected: () -> Boolean): Boolean {
        val steps = if (channelGroup || pairChannel || requiresPeerHealth || sessionNegotiated ||
            transportType.lowercase() in setOf("mqtt", "jitsi", "jazz")) 240 else 80
        repeat(steps) {
            delay(250)
            if (!canWait()) return false
            if (connected()) return true
        }
        return false
    }

    fun isReady(line: String): Boolean {
        if (channelGroup) return line.contains("[GROUP] peer ready")
        if (pairChannel) return line.contains("[CHANNEL] peer ready")
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
            line.contains("Running as CLIENT", ignoreCase = true) ||
            line.contains("Tunnel active", ignoreCase = true) ||
            line.contains("Direct TUN FD", ignoreCase = true)
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
