package io.github.p1neapplexpress.openflux.util

import java.net.Authenticator
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.PasswordAuthentication
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
        return "fluxon-$randSuffix"
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

    fun setupAuthenticator(session: SessionInfo = currentSession) {
        // Internal Go SOCKS5 listener does not support RFC 1929 authentication.
        // We do not set global Authenticator.setDefault so loopback probes stay unauthenticated.
    }

    fun clearAuthenticator() {
        Authenticator.setDefault(null)
    }

    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: return "192.168.43.1"

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
                            if (host.startsWith("192.168.") || host.startsWith("172.")) {
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
                        if (host.startsWith("192.168.")) {
                            return host
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return "192.168.43.1"
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

