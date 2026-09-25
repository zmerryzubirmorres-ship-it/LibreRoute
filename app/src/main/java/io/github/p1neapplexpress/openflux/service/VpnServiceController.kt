package io.github.p1neapplexpress.openflux.service

import android.content.Intent

import android.content.pm.PackageManager

import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor

import io.github.p1neapplexpress.openflux.event.AppEvent

import io.github.p1neapplexpress.openflux.event.EventBus

import io.github.p1neapplexpress.openflux.util.AppSettings

import io.github.p1neapplexpress.openflux.util.Constants

import io.github.p1neapplexpress.openflux.util.Logx

import io.github.p1neapplexpress.openflux.util.Routes

import java.util.concurrent.atomic.AtomicBoolean

class VpnServiceController(private val service: VpnService) {

    companion object {

        private const val TAG = "VpnServiceController"

        private const val MTU = 1400

        private const val VPN_IPV4_ADDR = "26.26.26.1"

        private const val VPN_IPV4_PREFIX = 24

        private const val VPN_IPV6_ADDR = "fdfe:dcba:9876::1"

        private const val VPN_IPV6_PREFIX = 126

        private const val PRIMARY_DNS = "1.1.1.1"

        private const val SECONDARY_DNS = "8.8.8.8"

    }

    @Volatile private var iface: ParcelFileDescriptor? = null

    val isRunning = AtomicBoolean(false)

    val fd: Int get() = iface?.fd ?: -1

    val fileDescriptor: java.io.FileDescriptor? get() = iface?.fileDescriptor

    fun isConfigured(): Boolean = iface != null

    fun configure(intent: Intent): Boolean {
        if (iface != null) {
            Logx.w(TAG, "configure() called with existing iface; closing previous instance")
            runCatching { iface?.close() }
            iface = null
        }

        val name = intent.getStringExtra(Constants.INTENT_NAME) ?: "Fluxon"

        val route = intent.getStringExtra(Constants.INTENT_ROUTE)

        val perApp = intent.getBooleanExtra(Constants.INTENT_PER_APP, false)

        val appBypass = intent.getBooleanExtra(Constants.INTENT_APP_BYPASS, false)

        val appList = intent.getStringArrayExtra(Constants.INTENT_APP_LIST) ?: emptyArray()

        val ipv6 = intent.getBooleanExtra(Constants.INTENT_IPV6_PROXY, false)

        val dns = intent.getStringExtra(Constants.INTENT_DNS)?.ifBlank { null } ?: PRIMARY_DNS

        val secDns = intent.getStringExtra(Constants.INTENT_SECONDARY_DNS)?.ifBlank { null } ?: SECONDARY_DNS

        val mtu = intent.getIntExtra(Constants.INTENT_MTU, MTU).coerceIn(1280, 1500)

        val bypassLan = intent.getBooleanExtra(Constants.INTENT_BYPASS_LAN, true)

        val killSwitch = intent.getBooleanExtra(Constants.INTENT_KILL_SWITCH, false)

        val ipType = intent.getIntExtra(Constants.INTENT_IP_TYPE, AppSettings.IP_TYPE_AUTO)

        val isNumericIp = { ip: String? ->
            if (ip.isNullOrBlank()) false
            else runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.net.InetAddresses.parseNumericAddress(ip) != null
                } else {
                    @Suppress("DEPRECATION")
                    java.net.InetAddress.getByName(ip.filter { it.isDigit() || it == '.' || it == ':' }) != null
                }
            }.getOrDefault(false)
        }

        val safeDns = if (isNumericIp(dns)) dns else PRIMARY_DNS
        val safeSecDns = if (isNumericIp(secDns)) secDns else SECONDARY_DNS

        val builder = service.Builder()
            .setMtu(mtu)
            .setSession(name)

        runCatching { builder.addDnsServer(safeDns) }
        runCatching { builder.addDnsServer(safeSecDns) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(killSwitch)
        }

        when (ipType) {
            AppSettings.IP_TYPE_IPV4 -> {
                builder.addAddress(VPN_IPV4_ADDR, VPN_IPV4_PREFIX)
                Routes.addRoutes(service, builder, route, bypassLan)
                // Blackhole route ::/0 to tun0 to prevent cleartext carrier bypass (CORE-12)
                runCatching {
                    builder.addAddress(VPN_IPV6_ADDR, VPN_IPV6_PREFIX)
                        .addRoute("::", 0)
                }
            }
            AppSettings.IP_TYPE_IPV6 -> {
                builder.addAddress(VPN_IPV6_ADDR, VPN_IPV6_PREFIX)
                    .addRoute("::", 0)
            }
            else -> { // IP_TYPE_AUTO
                builder.addAddress(VPN_IPV4_ADDR, VPN_IPV4_PREFIX)
                Routes.addRoutes(service, builder, route, bypassLan)
                if (ipv6) {
                    builder.addAddress(VPN_IPV6_ADDR, VPN_IPV6_PREFIX)
                        .addRoute("::", 0)
                } else {
                    // Blackhole route ::/0 to tun0 to prevent cleartext carrier bypass (CORE-12)
                    runCatching {
                        builder.addAddress(VPN_IPV6_ADDR, VPN_IPV6_PREFIX)
                            .addRoute("::", 0)
                    }
                }
            }
        }

        // Use numeric parsing to avoid InetAddress.getByName() performing a DNS lookup on the calling thread
        listOfNotNull(safeDns, safeSecDns).forEach { server ->
            runCatching {
                val addr = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    android.net.InetAddresses.parseNumericAddress(server)
                } else {
                    @Suppress("DEPRECATION")
                    java.net.InetAddress.getByName(server.filter { it.isDigit() || it == '.' || it == ':' })
                }
                if (addr is java.net.Inet6Address) {
                    builder.addRoute(addr, 128)
                } else {
                    if (ipType != AppSettings.IP_TYPE_IPV6) {
                        builder.addRoute(addr, 32)
                    }
                }
            }.onFailure { Logx.w(TAG, "Failed to add DNS host route for $server: ${it.message}") }
        }

        val effectivePerApp = perApp && appList.isNotEmpty()

        if (effectivePerApp && !appBypass) {

            configureAppRouting(builder, bypass = false, appList)

        } else {

            runCatching { builder.addDisallowedApplication(service.packageName) }

                .onFailure { Logx.w(TAG, "disallow self failed: ${it.message}") }

            if (effectivePerApp && appBypass) {

                configureAppRouting(builder, bypass = true, appList)

            }

        }

        iface = builder.establish()

        if (iface == null) {

            Logx.e(TAG, "Failed to establish VPN interface")

            EventBus.dispatch(AppEvent.LogMessage("[E] VPN establish failed"))

            return false

        } else {

            Logx.d(TAG, "VPN interface established fd=${iface?.fd}")

            return true

        }

    }

    private fun configureAppRouting(

        builder: VpnService.Builder,

        bypass: Boolean,

        apps: Array<String>,

    ) {

        val self = service.packageName

        for (raw in apps) {

            val pkg = raw.trim()

            if (pkg.isEmpty() || pkg == self) continue

            try {

                if (bypass) {

                    builder.addDisallowedApplication(pkg)

                } else {

                    builder.addAllowedApplication(pkg)

                }

            } catch (e: PackageManager.NameNotFoundException) {

                Logx.w(TAG, "Package not installed, skipping: $pkg")

            } catch (e: Exception) {

                Logx.w(TAG, "Error adding package $pkg: ${e.message}")

            }

        }

    }

    fun stop() {

        isRunning.set(false)

        iface?.let {

            runCatching { it.close() }

            iface = null

            Logx.d(TAG, "VPN interface closed")

        }

    }

}
