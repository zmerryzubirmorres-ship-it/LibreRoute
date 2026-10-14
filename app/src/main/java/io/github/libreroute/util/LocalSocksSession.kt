package io.github.libreroute.util

import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.UUID


object LocalSocksSession {

    data class SessionInfo(
        val port: Int,
        val username: String,
        val password: String,
        val isAuthEnabled: Boolean = true,
        val isSharedLan: Boolean = false
    )

    fun generateRandomUsername(): String {
        val randSuffix = UUID.randomUUID().toString().replace("-", "").take(8)
        return "libreroute-$randSuffix"
    }

    @Volatile
    private var currentSession: SessionInfo = SessionInfo(
        port = 1080,
        username = generateRandomUsername(),
        password = "",
        isAuthEnabled = false,
        isSharedLan = false
    )

    fun generateNew(
        authEnabled: Boolean = false,
        customUser: String? = null,
        customPass: String? = null,
        shareLan: Boolean = false,
        customPort: Int? = null
    ): SessionInfo {
        val port = customPort ?: findAvailablePort()
        val username = customUser?.ifBlank { null } ?: generateRandomUsername()
        val password = if (!authEnabled) {
            ""
        } else {
            customPass?.ifBlank { null } ?: UUID.randomUUID().toString()
        }

        val session = SessionInfo(
            port = port,
            username = username,
            password = password,
            isAuthEnabled = authEnabled,
            isSharedLan = shareLan
        )
        currentSession = session
        Logx.i("LocalSocksSession", "Generated SOCKS5 session on port $port, auth=$authEnabled, shareLan=$shareLan, user=$username")
        return session
    }

    fun getActive(): SessionInfo = currentSession

    @Volatile private var readySession: SessionInfo? = null

    fun getReady(): SessionInfo? = readySession?.takeIf { it === currentSession }

    @Synchronized fun markReady(session: SessionInfo) {
        if (session === currentSession) readySession = session
    }

    @Synchronized fun clearReady(session: SessionInfo) {
        if (readySession === session) readySession = null
    }

    fun setupAuthenticator(session: SessionInfo = currentSession) {
        // Core proxy authentication uses explicit per-session credentials. Keep
        // Java's global authenticator untouched; SSH/document control uses its own sockets.
    }

    fun clearAuthenticator() {
        // Session credentials are explicit; no process-global authenticator was installed.
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return ""

            fun isCellularOrVpn(name: String): Boolean {
                val n = name.lowercase()
                return n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") ||
                        n.startsWith("wwan") || n.startsWith("tun") || n.startsWith("dummy") ||
                        n.startsWith("ppp") || n.startsWith("v4-") || n.startsWith("radio")
            }

            // Priority 1: SoftAP / Wi-Fi Hotspot / USB Tethering interfaces (ap, swlan, rndis)
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (isCellularOrVpn(name)) continue

                val isAp = name.contains("ap") || name.contains("rndis") || name.contains("swlan") || name == "wlan1"
                if (isAp) {
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress ?: continue
                            if (isPrivateLanIpv4(host)) {
                                return host
                            }
                        }
                    }
                }
            }

            // Priority 2: General Wi-Fi LAN interfaces (wlan0, eth0) with private LAN IP
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (isCellularOrVpn(name)) continue

                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                          if (isPrivateLanIpv4(host)) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return ""
    }

    internal fun isPrivateLanIpv4(host: String): Boolean {
        val fields = host.split('.')
        if (fields.size != 4) return false
        val parts = fields.map { it.toIntOrNull() ?: return false }
        if (parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 192 && parts[1] == 168)
    }

    private fun findAvailablePort(): Int {
        try {
            ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { socket ->
                return socket.localPort
            }
        } catch (be: java.net.BindException) {
            Logx.w("LocalSocksSession", "BindException on ephemeral port selection: ${be.message}")
        } catch (_: Exception) {
        }

        for (attempt in 1..25) {
            val candidate = (30000..60000).random()
            try {
                if (isPortFree(candidate)) return candidate
            } catch (_: java.net.BindException) {
                continue
            }
        }
        return (30000..60000).random()
    }

    private fun isPortFree(port: Int): Boolean {
        return try {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
                true
            }
        } catch (_: java.net.BindException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
