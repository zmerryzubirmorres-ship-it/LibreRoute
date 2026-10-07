package io.github.libreroute.service

/** Match the last readiness layer configured in the native process. */
internal class NativeTransportReadiness(arguments: List<String>) {
    val requiresPeerHealth = flagEnabled(arguments, "peer-health")
    private val sessionNegotiated = flagEnabled(arguments, "session-negotiate")

    fun isReady(line: String): Boolean {
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
