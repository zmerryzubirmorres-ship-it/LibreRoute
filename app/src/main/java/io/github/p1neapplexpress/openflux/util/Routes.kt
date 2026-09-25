package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.net.VpnService
import io.github.p1neapplexpress.openflux.R

object Routes {

    private val BYPASS_LAN_ROUTES = arrayOf(
        "0.0.0.0/5",
        "8.0.0.0/7",
        "11.0.0.0/8",
        "12.0.0.0/6",
        "16.0.0.0/4",
        "32.0.0.0/3",
        "64.0.0.0/2",
        "128.0.0.0/3",
        "160.0.0.0/5",
        "168.0.0.0/6",
        "172.0.0.0/12",
        "172.32.0.0/11",
        "172.64.0.0/10",
        "172.128.0.0/9",
        "173.0.0.0/8",
        "174.0.0.0/7",
        "176.0.0.0/4",
        "192.0.0.0/9",
        "192.128.0.0/11",
        "192.160.0.0/13",
        "192.169.0.0/16",
        "192.170.0.0/15",
        "192.172.0.0/14",
        "192.176.0.0/12",
        "192.192.0.0/10",
        "193.0.0.0/8",
        "194.0.0.0/7",
        "196.0.0.0/6",
        "200.0.0.0/5",
        "208.0.0.0/4"
    )

    fun addRoutes(context: Context, builder: VpnService.Builder, name: String?, bypassLan: Boolean = true) {
        val routes: Array<String> = if (Constants.ROUTE_CHN == name) {
            context.resources.getStringArray(R.array.simple_route)
        } else if (bypassLan) {
            BYPASS_LAN_ROUTES
        } else {
            arrayOf("0.0.0.0/0")
        }

        for (r in routes) {
            val parts = r.split("/")
            if (parts.size != 2) continue
            val network = parts[0]
            if (network.startsWith("127")) continue
            val prefix = parts[1].toIntOrNull() ?: continue
            if (prefix !in 0..32) continue
            runCatching { builder.addRoute(network, prefix) }
                .onFailure { Logx.w("Routes", "Failed to add route $r: ${it.message}") }
        }
    }
}
