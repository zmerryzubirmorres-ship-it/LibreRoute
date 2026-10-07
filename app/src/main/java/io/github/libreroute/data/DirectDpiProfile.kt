package io.github.libreroute.data

/** Built-in local bypass profile. It uses the bundled zapret tpws SOCKS process. */
object DirectDpiProfile {
    private const val ID = 0x4c5244495044L
    const val NAME = "Прямой обход DPI · zapret"

    fun create(): Tunnel = Tunnel(
        id = ID,
        name = NAME,
        transportType = TransportType.directDpi.name,
        transportConnPayload = listOf(
            "--role=client",
            "--transport", TransportType.directDpi.cliName,
            "--dpi-block-quic=true"
        ),
        encryptionKey = ""
    )

    /**
     * Keep the built-in zapret profile runnable after an upgrade from an older
     * build that stored an incomplete direct-dpi payload. User supplied relay
     * profiles are left untouched by TunnelRepository.
     */
    fun ensureConfigured(tunnel: Tunnel): Tunnel {
        if (!isDirect(tunnel)) return tunnel
        val payload = tunnel.transportConnPayload.toMutableList()
        fun optionValue(flag: String): String? {
            payload.firstOrNull { it.startsWith("$flag=", ignoreCase = true) }?.let {
                return it.substringAfter('=').trim()
            }
            val index = payload.indexOfFirst { it.equals(flag, ignoreCase = true) }
            return if (index >= 0) payload.getOrNull(index + 1)?.takeUnless { it.startsWith("-") } else null
        }
        fun replaceOption(flag: String, value: String) {
            var index = 0
            while (index < payload.size) {
                val item = payload[index]
                if (item.equals(flag, ignoreCase = true)) {
                    payload.removeAt(index)
                    if (index < payload.size && !payload[index].startsWith("-")) payload.removeAt(index)
                    continue
                }
                if (item.startsWith("$flag=", ignoreCase = true)) payload.removeAt(index) else index++
            }
            payload.add("$flag=$value")
        }
        if (optionValue("--role")?.equals("client", ignoreCase = true) != true) {
            replaceOption("--role", "client")
        }
        if (optionValue("--transport")?.equals(TransportType.directDpi.cliName, ignoreCase = true) != true) {
            replaceOption("--transport", TransportType.directDpi.cliName)
        }
        if (optionValue("--dpi-block-quic") == null) payload.add("--dpi-block-quic=true")
        return if (payload == tunnel.transportConnPayload) tunnel
        else tunnel.copy(transportConnPayload = payload, encryptionKey = "")
    }

    fun isDirect(tunnel: Tunnel): Boolean =
        TransportType.fromOrNull(tunnel.transportType) == TransportType.directDpi
}
