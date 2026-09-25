package io.github.p1neapplexpress.openflux.vpn

import android.content.Context
import android.content.Intent
import io.github.p1neapplexpress.openflux.service.SocksVpnService
import io.github.p1neapplexpress.openflux.util.Constants

object VpnIntentFactory {
    fun build(context: Context, cfg: VPNConfig): Intent =
        Intent(context, SocksVpnService::class.java).apply {
            putExtra(Constants.INTENT_NAME, cfg.name)
            putExtra(Constants.INTENT_SERVER, cfg.server)
            putExtra(Constants.INTENT_PORT, cfg.port)
            putExtra(Constants.INTENT_ROUTE, cfg.route)
            putExtra(Constants.INTENT_DNS, cfg.dns)
            putExtra(Constants.INTENT_SECONDARY_DNS, cfg.secondaryDns)
            putExtra(Constants.INTENT_DNS_PORT, cfg.dnsPort)
            putExtra(Constants.INTENT_MTU, cfg.mtu)
            putExtra(Constants.INTENT_PER_APP, cfg.perApp)
            putExtra(Constants.INTENT_APP_BYPASS, cfg.appBypass)
            putExtra(Constants.INTENT_APP_LIST, cfg.appList)
            putExtra(Constants.INTENT_IPV6_PROXY, cfg.ipv6Proxy)
            putExtra(Constants.INTENT_BYPASS_LAN, cfg.bypassLan)
            putExtra(Constants.INTENT_KILL_SWITCH, cfg.killSwitch)
            putExtra(Constants.INTENT_IP_TYPE, cfg.ipType)
            cfg.remoteServer?.let {
                putExtra(Constants.INTENT_REMOTE_SERVER, it)
                putExtra(Constants.INTENT_REMOTE_PORT, cfg.remotePort)
            }
            val user = if (cfg.username.isNullOrEmpty() || cfg.password.isNullOrEmpty()) null else cfg.username
            val pass = if (cfg.username.isNullOrEmpty() || cfg.password.isNullOrEmpty()) null else cfg.password
            user?.let { putExtra(Constants.INTENT_USERNAME, it) }
            pass?.let { putExtra(Constants.INTENT_PASSWORD, it) }
            cfg.transportType?.let { putExtra(Constants.INTENT_TRANSPORT_TYPE, it) }
            cfg.transportPayload?.let { putExtra(Constants.INTENT_TRANSPORT_PAYLOAD, it) }
            putExtra(Constants.INTENT_DOH_ENABLED, cfg.dohEnabled)
            cfg.dohUrl?.let { putExtra(Constants.INTENT_DOH_URL, it) }
        }
}
