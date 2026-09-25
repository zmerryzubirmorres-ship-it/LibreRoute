package io.github.p1neapplexpress.openflux.vpn

import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.util.Constants

data class VPNConfig(
    val name: String,
    val server: String = "127.0.0.1",
    val port: Int = 1080,
    val username: String? = null,
    val password: String? = null,
    val route: String = Constants.ROUTE_ALL,
    val dns: String = "1.1.1.1",
    val secondaryDns: String = "8.8.8.8",
    val dnsPort: Int = 53,
    val mtu: Int = 1400,
    val perApp: Boolean = false,
    val appBypass: Boolean = false,
    val appList: Array<String> = emptyArray(),
    val ipv6Proxy: Boolean = false,
    val udpGw: String? = null,
    val bypassLan: Boolean = true,
    val killSwitch: Boolean = false,
    val ipType: Int = 0,
    val remoteServer: String? = null,
    val remotePort: Int = 443,
    val transportType: String? = null,
    val transportPayload: Array<String>? = null,
    val dohEnabled: Boolean = false,
    val dohUrl: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VPNConfig) return false
        return name == other.name &&
            server == other.server &&
            port == other.port &&
            username == other.username &&
            password == other.password &&
            route == other.route &&
            dns == other.dns &&
            secondaryDns == other.secondaryDns &&
            dnsPort == other.dnsPort &&
            mtu == other.mtu &&
            perApp == other.perApp &&
            appBypass == other.appBypass &&
            appList.contentEquals(other.appList) &&
            ipv6Proxy == other.ipv6Proxy &&
            udpGw == other.udpGw &&
            bypassLan == other.bypassLan &&
            killSwitch == other.killSwitch &&
            ipType == other.ipType &&
            remoteServer == other.remoteServer &&
            remotePort == other.remotePort &&
            transportType == other.transportType &&
            dohEnabled == other.dohEnabled &&
            dohUrl == other.dohUrl &&
            (transportPayload == null && other.transportPayload == null ||
                transportPayload != null && other.transportPayload != null &&
                transportPayload.contentEquals(other.transportPayload))
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + server.hashCode()
        result = 31 * result + port
        result = 31 * result + (username?.hashCode() ?: 0)
        result = 31 * result + (password?.hashCode() ?: 0)
        result = 31 * result + route.hashCode()
        result = 31 * result + dns.hashCode()
        result = 31 * result + secondaryDns.hashCode()
        result = 31 * result + dnsPort
        result = 31 * result + mtu
        result = 31 * result + perApp.hashCode()
        result = 31 * result + appBypass.hashCode()
        result = 31 * result + appList.contentHashCode()
        result = 31 * result + ipv6Proxy.hashCode()
        result = 31 * result + (udpGw?.hashCode() ?: 0)
        result = 31 * result + bypassLan.hashCode()
        result = 31 * result + killSwitch.hashCode()
        result = 31 * result + ipType
        result = 31 * result + (remoteServer?.hashCode() ?: 0)
        result = 31 * result + remotePort
        result = 31 * result + (transportType?.hashCode() ?: 0)
        result = 31 * result + dohEnabled.hashCode()
        result = 31 * result + (dohUrl?.hashCode() ?: 0)
        result = 31 * result + (transportPayload?.contentHashCode() ?: 0)
        return result
    }
}

object TunnelEndpointHelper {
    fun extractTarget(tunnel: Tunnel): Pair<String, Int> {
        val transportType = tunnel.transportType.lowercase()
        if (transportType == "max" || transportType == "oneme") {
            return Pair("ws-api.oneme.ru", 443)
        }
        if (transportType == "cups" || transportType == "cupsonline") {
            return Pair("interview.cups.online", 443)
        }
        val rawUrls = argValue(tunnel.transportConnPayload, "--urls")
        val rawUrl = argValue(tunnel.transportConnPayload, "--url")
        val urlStr = (if (rawUrls.isNotBlank()) rawUrls.split(",").firstOrNull()?.trim() else null)?.ifEmpty { null } ?: rawUrl
        if (urlStr.isNotBlank()) {
            val fixedUrl = if (!urlStr.startsWith("http://", ignoreCase = true) && !urlStr.startsWith("https://", ignoreCase = true)) {
                "https://$urlStr"
            } else {
                urlStr
            }
            val uri = runCatching { java.net.URI(fixedUrl) }.getOrNull()
            val h = uri?.host
            val p = if (uri != null && uri.port > 0) uri.port else if (uri?.scheme.equals("http", ignoreCase = true)) 80 else 443
            if (!h.isNullOrBlank()) return Pair(h, p)
        }
        if (transportType == "yandex" || transportType == "ydocs" || transportType == "vyandex") {
            return Pair("docs.yandex.ru", 443)
        }
        if (transportType == "mailru") {
            return Pair("docs.mail.ru", 443)
        }
        if (transportType == "gdocs" || transportType == "googledocs") {
            return Pair("docs.google.com", 443)
        }
        return Pair("1.1.1.1", 443)
    }

    private fun argValue(payload: List<String>, arg: String): String {
        val eqPrefix = "$arg="
        for (item in payload) {
            if (item.startsWith(eqPrefix, ignoreCase = true)) {
                return item.substring(eqPrefix.length).trim('"', '\'')
            }
        }
        val idx = payload.indexOfFirst { it.equals(arg, ignoreCase = true) }
        return if (idx != -1 && idx + 1 < payload.size) payload[idx + 1] else ""
    }
}
