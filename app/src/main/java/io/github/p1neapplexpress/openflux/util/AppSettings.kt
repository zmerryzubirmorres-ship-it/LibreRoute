package io.github.p1neapplexpress.openflux.util



import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class AppSettings(private val context: Context) {



    companion object {

        private const val PREFS_NAME = "fluxon_settings"



        const val DNS_MODE_CLOUDFLARE = 0

        const val DNS_MODE_GOOGLE = 1

        const val DNS_MODE_ADGUARD = 2

        const val DNS_MODE_QUAD9 = 3

        const val DNS_MODE_CUSTOM = 4

        const val DNS_MODE_XBOX = 5



        const val DOH_PROVIDER_CLOUDFLARE = 0

        const val DOH_PROVIDER_GOOGLE = 1

        const val DOH_PROVIDER_ADGUARD = 2

        const val DOH_PROVIDER_QUAD9 = 3

        const val DOH_PROVIDER_CUSTOM = 4

        const val DOH_PROVIDER_XBOX = 5



        const val IP_TYPE_AUTO = 0

        const val IP_TYPE_IPV4 = 1

        const val IP_TYPE_IPV6 = 2



        const val DEFAULT_MTU = 1400

        const val MIN_MTU = 1280

        const val MAX_MTU = 1500



        private const val KEY_DNS_MODE = "dns_mode"

        private const val KEY_CUSTOM_DNS_PRIMARY = "custom_dns_primary"

        private const val KEY_CUSTOM_DNS_SECONDARY = "custom_dns_secondary"

        private const val KEY_USE_SYSTEM_DNS = "use_system_dns"

        private const val KEY_DOH_ENABLED = "doh_enabled"

        private const val KEY_DOH_PROVIDER = "doh_provider"

        private const val KEY_DOH_CUSTOM_URL = "doh_custom_url"

        private const val KEY_MTU = "mtu"

        private const val KEY_IPV6_PROXY = "ipv6_proxy"

        private const val KEY_IP_TYPE = "ip_type"

        private const val KEY_BYPASS_LAN = "bypass_lan"

        private const val KEY_KILL_SWITCH = "kill_switch"

        private const val KEY_SHARE_LAN_PROXY = "share_lan_proxy"

        private const val KEY_LAN_PROXY_PORT = "lan_proxy_port"

        private const val KEY_NOTIFY_SPEED = "show_notify_speed"

        private const val KEY_SOCKS5_AUTH_ENABLED = "socks5_auth_enabled"

        private const val KEY_SOCKS5_USER = "socks5_custom_user"

        private const val KEY_SOCKS5_PASS = "socks5_custom_pass"

        private const val KEY_SHOW_MEMORY_USAGE = "show_memory_usage"
        private const val KEY_SHOW_PING_IN_MAIN_MENU = "show_ping_in_main_menu"
        private const val KEY_SHOW_SPEED_GRAPH = "show_speed_graph"

        private const val KEY_AUTO_UPDATE_CHECK = "auto_update_check"

        private const val KEY_AUTO_FAILOVER = "auto_failover"

        private const val KEY_AUTO_BOOT = "auto_boot"

        private const val KEY_AUTO_CLEAR_LOGS = "auto_clear_logs"

        private const val KEY_VERBOSE_LOG = "verbose_log"
        private const val KEY_CONNECTION_LOG_LEVEL = "connection_log_level"

        private const val KEY_HAPTIC_FEEDBACK = "haptic_feedback"
        private const val KEY_APP_LANGUAGE = "app_language"
        private const val KEY_APP_ICON = "app_icon"
        private const val KEY_BATTERY_OPT_PROMPTED = "battery_opt_prompted"
        private const val KEY_PROXY_ONLY_MODE = "proxy_only_mode"
    }




    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Keystore-backed prefs for sensitive string values (credentials, keys). */
    private val securePrefs: SecurePreferences by lazy {
        SecurePreferences(context.applicationContext, "${PREFS_NAME}_secure").also { sp ->
            // One-time migration: move any plain-text credentials to encrypted storage
            sp.migrateIfNeeded(KEY_SOCKS5_USER)
            sp.migrateIfNeeded(KEY_SOCKS5_PASS)
        }
    }




    var dnsMode: Int

        get() = prefs.getInt(KEY_DNS_MODE, DNS_MODE_CLOUDFLARE)

        set(value) = prefs.edit().putInt(KEY_DNS_MODE, value).apply()



    var customDnsPrimary: String

        get() = prefs.getString(KEY_CUSTOM_DNS_PRIMARY, "1.1.1.1") ?: "1.1.1.1"

        set(value) = prefs.edit().putString(KEY_CUSTOM_DNS_PRIMARY, value.trim()).apply()



    var customDnsSecondary: String

        get() = prefs.getString(KEY_CUSTOM_DNS_SECONDARY, "8.8.8.8") ?: "8.8.8.8"

        set(value) = prefs.edit().putString(KEY_CUSTOM_DNS_SECONDARY, value.trim()).apply()



    var useSystemDns: Boolean

        get() = prefs.getBoolean(KEY_USE_SYSTEM_DNS, false)

        set(value) = prefs.edit().putBoolean(KEY_USE_SYSTEM_DNS, value).apply()



    var dohEnabled: Boolean

        get() = prefs.getBoolean(KEY_DOH_ENABLED, false)

        set(value) = prefs.edit().putBoolean(KEY_DOH_ENABLED, value).apply()



    var dohProvider: Int

        get() = prefs.getInt(KEY_DOH_PROVIDER, DOH_PROVIDER_CLOUDFLARE)

        set(value) = prefs.edit().putInt(KEY_DOH_PROVIDER, value).apply()



    var dohCustomUrl: String

        get() = prefs.getString(KEY_DOH_CUSTOM_URL, "https://cloudflare-dns.com/dns-query") ?: "https://cloudflare-dns.com/dns-query"

        set(value) = prefs.edit().putString(KEY_DOH_CUSTOM_URL, value.trim()).apply()



    val dohUrl: String

        get() = when (dohProvider) {

            DOH_PROVIDER_CLOUDFLARE -> "https://cloudflare-dns.com/dns-query"

            DOH_PROVIDER_GOOGLE -> "https://dns.google/dns-query"

            DOH_PROVIDER_ADGUARD -> "https://dns.adguard-dns.com/dns-query"

            DOH_PROVIDER_QUAD9 -> "https://dns.quad9.net/dns-query"

            DOH_PROVIDER_XBOX -> "https://xbox-dns.ru/dns-query"

            DOH_PROVIDER_CUSTOM -> dohCustomUrl.ifBlank { "https://cloudflare-dns.com/dns-query" }

            else -> "https://cloudflare-dns.com/dns-query"

        }



    val systemDnsServers: List<String>
        get() = detectSystemDnsServers()

    val systemPrimaryDns: String?
        get() = systemDnsServers.firstOrNull()

    val systemSecondaryDns: String?
        get() = systemDnsServers.getOrNull(1)

    val primaryDns: String
        get() {
            if (useSystemDns) {
                val sys = systemPrimaryDns
                if (!sys.isNullOrBlank()) return sys
            }
            if (dohEnabled) {
                return when (dohProvider) {
                    DOH_PROVIDER_CLOUDFLARE -> "1.1.1.1"
                    DOH_PROVIDER_GOOGLE -> "8.8.8.8"
                    DOH_PROVIDER_ADGUARD -> "94.140.14.14"
                    DOH_PROVIDER_QUAD9 -> "9.9.9.9"
                    DOH_PROVIDER_XBOX -> "176.99.11.77"
                    DOH_PROVIDER_CUSTOM -> {
                        val u = dohCustomUrl.lowercase()
                        if (u.contains("cloudflare")) "1.1.1.1"
                        else if (u.contains("google")) "8.8.8.8"
                        else if (u.contains("adguard")) "94.140.14.14"
                        else if (u.contains("quad9")) "9.9.9.9"
                        else "1.1.1.1"
                    }
                    else -> "1.1.1.1"
                }
            }
            return when (dnsMode) {
                DNS_MODE_CLOUDFLARE -> "1.1.1.1"
                DNS_MODE_GOOGLE -> "8.8.8.8"
                DNS_MODE_ADGUARD -> "94.140.14.14"
                DNS_MODE_QUAD9 -> "9.9.9.9"
                DNS_MODE_XBOX -> "111.88.96.50"
                DNS_MODE_CUSTOM -> customDnsPrimary.ifBlank { "111.88.96.50" }
                else -> "1.1.1.1"
            }
        }

    val secondaryDns: String
        get() {
            if (useSystemDns) {
                val sys = systemSecondaryDns
                if (!sys.isNullOrBlank()) return sys
            }
            if (dohEnabled) {
                return when (dohProvider) {
                    DOH_PROVIDER_CLOUDFLARE -> "1.0.0.1"
                    DOH_PROVIDER_GOOGLE -> "8.8.4.4"
                    DOH_PROVIDER_ADGUARD -> "94.140.15.15"
                    DOH_PROVIDER_QUAD9 -> "149.112.112.112"
                    DOH_PROVIDER_XBOX -> "77.88.8.8"
                    DOH_PROVIDER_CUSTOM -> {
                        val u = dohCustomUrl.lowercase()
                        if (u.contains("cloudflare")) "1.0.0.1"
                        else if (u.contains("google")) "8.8.4.4"
                        else if (u.contains("adguard")) "94.140.15.15"
                        else if (u.contains("quad9")) "149.112.112.112"
                        else "8.8.8.8"
                    }
                    else -> "1.0.0.1"
                }
            }
            return when (dnsMode) {
                DNS_MODE_CLOUDFLARE -> "1.0.0.1"
                DNS_MODE_GOOGLE -> "8.8.4.4"
                DNS_MODE_ADGUARD -> "94.140.15.15"
                DNS_MODE_QUAD9 -> "149.112.112.112"
                DNS_MODE_XBOX -> "111.88.96.51"
                DNS_MODE_CUSTOM -> customDnsSecondary.ifBlank { "8.8.8.8" }
                else -> "8.8.8.8"
            }
        }



    var mtu: Int

        get() = prefs.getInt(KEY_MTU, DEFAULT_MTU).coerceIn(MIN_MTU, MAX_MTU)

        set(value) = prefs.edit().putInt(KEY_MTU, value.coerceIn(MIN_MTU, MAX_MTU)).apply()



    var ipv6Proxy: Boolean

        get() = prefs.getBoolean(KEY_IPV6_PROXY, false)

        set(value) = prefs.edit().putBoolean(KEY_IPV6_PROXY, value).apply()



    var ipType: Int

        get() = prefs.getInt(KEY_IP_TYPE, IP_TYPE_AUTO)

        set(value) = prefs.edit().putInt(KEY_IP_TYPE, value).apply()



    var bypassLan: Boolean

        get() = prefs.getBoolean(KEY_BYPASS_LAN, true)

        set(value) = prefs.edit().putBoolean(KEY_BYPASS_LAN, value).apply()



    var killSwitch: Boolean

        get() = prefs.getBoolean(KEY_KILL_SWITCH, false)

        set(value) = prefs.edit().putBoolean(KEY_KILL_SWITCH, value).apply()



    var shareLanProxy: Boolean

        get() = prefs.getBoolean(KEY_SHARE_LAN_PROXY, true)

        set(value) = prefs.edit().putBoolean(KEY_SHARE_LAN_PROXY, value).apply()



    var lanProxyPort: Int

        get() = prefs.getInt(KEY_LAN_PROXY_PORT, 10808)

        set(value) = prefs.edit().putInt(KEY_LAN_PROXY_PORT, value).apply()



    var showNotificationSpeed: Boolean

        get() = prefs.getBoolean(KEY_NOTIFY_SPEED, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_SPEED, value).apply()

    var socks5AuthEnabled: Boolean
        get() = prefs.getBoolean(KEY_SOCKS5_AUTH_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_SOCKS5_AUTH_ENABLED, value).apply()

    var socks5CustomUser: String
        get() {
            var user = securePrefs.getString(KEY_SOCKS5_USER, null)
            if (user.isNullOrBlank() || user == "fluxon") {
                user = "fluxon-" + java.util.UUID.randomUUID().toString().replace("-", "").take(8)
                securePrefs.putString(KEY_SOCKS5_USER, user)
            }
            return user
        }
        set(value) = securePrefs.putString(KEY_SOCKS5_USER, value.trim())

    var socks5CustomPass: String
        get() = securePrefs.getString(KEY_SOCKS5_PASS, "") ?: ""
        set(value) = securePrefs.putString(KEY_SOCKS5_PASS, value.trim())

    var batteryOptPrompted: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_OPT_PROMPTED, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_OPT_PROMPTED, value).apply()

    /**
     * When true, Fluxon starts [FluxonProxyService] instead of [SocksVpnService].
     * No TUN interface, no VPN key icon — just a plain SOCKS5 local proxy.
     * Useful when another VPN is already active or the user doesn't want
     * system-wide traffic routing.
     */
    var proxyOnlyMode: Boolean
        get() = prefs.getBoolean(KEY_PROXY_ONLY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_PROXY_ONLY_MODE, value).apply()




    var showMemoryUsage: Boolean
        get() = prefs.getBoolean(KEY_SHOW_MEMORY_USAGE, false)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_MEMORY_USAGE, value).apply()

    var showPingInMainMenu: Boolean
        get() = prefs.getBoolean(KEY_SHOW_PING_IN_MAIN_MENU, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_PING_IN_MAIN_MENU, value).apply()

    var showSpeedGraph: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SPEED_GRAPH, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_SPEED_GRAPH, value).apply()



    var autoUpdateCheck: Boolean

        get() = prefs.getBoolean(KEY_AUTO_UPDATE_CHECK, true)

        set(value) = prefs.edit().putBoolean(KEY_AUTO_UPDATE_CHECK, value).apply()



    var autoFailover: Boolean

        get() = prefs.getBoolean(KEY_AUTO_FAILOVER, false)

        set(value) = prefs.edit().putBoolean(KEY_AUTO_FAILOVER, value).apply()



    var autoConnectOnBoot: Boolean

        get() = prefs.getBoolean(KEY_AUTO_BOOT, false)

        set(value) = prefs.edit().putBoolean(KEY_AUTO_BOOT, value).apply()



    var autoClearLogs: Boolean

        get() = prefs.getBoolean(KEY_AUTO_CLEAR_LOGS, true)

        set(value) = prefs.edit().putBoolean(KEY_AUTO_CLEAR_LOGS, value).apply()



    var verboseLog: Boolean
        get() = prefs.getBoolean(KEY_VERBOSE_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_VERBOSE_LOG, value).apply()

    var connectionLogLevel: String
        get() = prefs.getString(KEY_CONNECTION_LOG_LEVEL, "INFO") ?: "INFO"
        set(value) = prefs.edit().putString(KEY_CONNECTION_LOG_LEVEL, value.uppercase().trim()).apply()

    var hapticFeedback: Boolean
        get() = prefs.getBoolean(KEY_HAPTIC_FEEDBACK, true)
        set(value) = prefs.edit().putBoolean(KEY_HAPTIC_FEEDBACK, value).apply()

    var appLanguage: String
        get() = prefs.getString(KEY_APP_LANGUAGE, "system") ?: "system"
        set(value) = prefs.edit().putString(KEY_APP_LANGUAGE, value).apply()

    var appIcon: String
        get() = prefs.getString(KEY_APP_ICON, "default") ?: "default"
        set(value) = prefs.edit().putString(KEY_APP_ICON, value).apply()



    private fun detectSystemDnsServers(): List<String> {
        val result = mutableListOf<String>()

        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return@runCatching

            fun extractDns(servers: List<InetAddress>?) {
                if (servers.isNullOrEmpty()) return
                // Prioritize IPv4 addresses for pdnsd and routing stability
                val v4 = servers.filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
                for (ip in v4) {
                    if (ip.isNotBlank() && ip !in result && !ip.startsWith("127.")) {
                        result.add(ip)
                    }
                }
                // Then IPv6 addresses (clean scope ID)
                val v6 = servers.filterIsInstance<Inet6Address>().mapNotNull {
                    it.hostAddress?.substringBefore('%')
                }
                for (ip in v6) {
                    if (ip.isNotBlank() && ip !in result && ip != "::1") {
                        result.add(ip)
                    }
                }
            }

            // 1. Try active network if it's NOT a VPN
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val caps = cm.getNetworkCapabilities(activeNet)
                val isVpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                if (!isVpn) {
                    val lp = cm.getLinkProperties(activeNet)
                    extractDns(lp?.dnsServers)
                }
            }

            // 2. If empty or active was VPN, search all physical networks (Wi-Fi, Ethernet, Cellular)
            if (result.isEmpty()) {
                val physicalNetworks = cm.allNetworks.mapNotNull { net ->
                    val caps = cm.getNetworkCapabilities(net) ?: return@mapNotNull null
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
                    val lp = cm.getLinkProperties(net) ?: return@mapNotNull null
                    val priority = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 1
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 3
                        else -> 4
                    }
                    Triple(net, lp, priority)
                }.sortedBy { it.third }

                for ((_, lp, _) in physicalNetworks) {
                    extractDns(lp.dnsServers)
                    if (result.isNotEmpty()) break
                }
            }

            // 3. Fallback to Android SystemProperties (e.g. net.dns1, net.dns2)
            if (result.isEmpty()) {
                runCatching {
                    val sysPropClass = Class.forName("android.os.SystemProperties")
                    val getMethod = sysPropClass.getMethod("get", String::class.java)
                    for (prop in listOf("net.dns1", "net.dns2", "net.dns3", "net.dns4")) {
                        val value = (getMethod.invoke(null, prop) as? String)?.trim()?.substringBefore('%')
                        if (!value.isNullOrBlank() && value !in result && !value.startsWith("127.") && value != "::1") {
                            result.add(value)
                        }
                    }
                }
            }
        }

        return result
    }
}
