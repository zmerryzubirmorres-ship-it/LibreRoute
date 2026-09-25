package io.github.p1neapplexpress.openflux.data

sealed interface TunnelState {
    val tunnel: Tunnel?
    val color: Int

    data object Idle : TunnelState {
        override val tunnel: Tunnel? = null
        override val color: Int = 0xFF6B7280.toInt()
    }

    data class Connecting(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFFFBBF24.toInt()
    }

    data class StartingTransport(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFFFBBF24.toInt()
    }

    data class StartingTun2Socks(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFFFBBF24.toInt()
    }

    data class Running(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFF22C55E.toInt()
    }

    data class Reconnecting(override val tunnel: Tunnel) : TunnelState {
        override val color: Int = 0xFFF59E0B.toInt()
    }

    data class WaitingForNetwork(override val tunnel: Tunnel? = null) : TunnelState {
        override val color: Int = 0xFF3B82F6.toInt()
    }

    data class Error(val message: String, val detail: String? = null) : TunnelState {
        override val tunnel: Tunnel? = null
        override val color: Int = 0xFFEF4444.toInt()
    }

    val isActive: Boolean
        get() = this is Connecting ||
                this is StartingTransport ||
                this is StartingTun2Socks ||
                this is Running ||
                this is Reconnecting ||
                this is WaitingForNetwork
}

enum class TunnelHealth {
    UNKNOWN,
    CHECKING,
    AVAILABLE,
    UNAVAILABLE
}
