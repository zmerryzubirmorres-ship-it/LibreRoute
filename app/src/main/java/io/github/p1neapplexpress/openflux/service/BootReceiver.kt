package io.github.p1neapplexpress.openflux.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import io.github.p1neapplexpress.openflux.data.TunnelRepository
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.SplitTunnelPreferences
import io.github.p1neapplexpress.openflux.util.TunnelLinkParser
import io.github.p1neapplexpress.openflux.vpn.VPNConfig
import io.github.p1neapplexpress.openflux.vpn.VpnIntentFactory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val appSettings = AppSettings(context)
                if (!appSettings.autoConnectOnBoot) {
                    Logx.d(TAG, "autoConnectOnBoot is disabled; ignoring boot")
                    return@launch
                }

                val repo = TunnelRepository(context)
                val selected = repo.getSelected() ?: run {
                    Logx.w(TAG, "No selected tunnel found for auto-connect on boot")
                    return@launch
                }

                Logx.i(TAG, "Auto-connecting tunnel '${selected.name}' on device boot")
        val prepared = TunnelLinkParser.ensureLocalKeyFile(context, selected)
        val splitPrefs = SplitTunnelPreferences(context)
        val isSplitEnabled = splitPrefs.isEnabled
        val appBypass = splitPrefs.mode == SplitTunnelPreferences.MODE_BYPASS
        val selectedApps = if (isSplitEnabled) {
            if (appBypass) splitPrefs.bypassApps else splitPrefs.proxyApps
        } else {
            emptySet()
        }
        val perApp = isSplitEnabled && selectedApps.isNotEmpty()
        val appList = selectedApps.toTypedArray()

        val session = io.github.p1neapplexpress.openflux.util.LocalSocksSession.generateNew(
            authEnabled = appSettings.socks5AuthEnabled,
            customUser = appSettings.socks5CustomUser,
            customPass = appSettings.socks5CustomPass,
            shareLan = appSettings.shareLanProxy
        )

        val modifiedPayload = prepared.transportConnPayload.toMutableList()
        fun removeFlag(flag: String) {
            val idx = modifiedPayload.indexOf(flag)
            if (idx != -1) {
                if (idx + 1 < modifiedPayload.size) modifiedPayload.removeAt(idx + 1)
                modifiedPayload.removeAt(idx)
            }
        }
        removeFlag("-socks5")
        removeFlag("--socks5")
        removeFlag("-socks5-user")
        removeFlag("--socks5-user")
        removeFlag("-socks5-pass")
        removeFlag("--socks5-pass")
        removeFlag("--domain-rules-file")
        removeFlag("-domain-rules-file")
        removeFlag("--domain-mode")
        removeFlag("-domain-mode")

        modifiedPayload.add("--socks5")
        modifiedPayload.add("127.0.0.1:${session.port}")

        val effectiveDoh = !appSettings.useSystemDns && appSettings.dohEnabled
        if (effectiveDoh && appSettings.dohUrl.isNotBlank()) {
            modifiedPayload.add("--doh-url")
            modifiedPayload.add(appSettings.dohUrl)
            modifiedPayload.add("--doh")
        }

        val domainPrefs = io.github.p1neapplexpress.openflux.util.DomainRulesPreferences(context)
        if (domainPrefs.hasActiveRules()) {
            val rulesFile = domainPrefs.writeRulesFile(context)
            modifiedPayload.add("--domain-rules-file")
            modifiedPayload.add(rulesFile.absolutePath)
            modifiedPayload.add("--domain-mode")
            modifiedPayload.add(domainPrefs.getModeString())
        }

        val (remoteHost, remotePort) = io.github.p1neapplexpress.openflux.vpn.TunnelEndpointHelper.extractTarget(prepared)
        val cfg = VPNConfig(
            name = prepared.name,
            port = session.port,
            username = if (session.isAuthEnabled && session.password.isNotEmpty()) session.username else null,
            password = if (session.isAuthEnabled && session.password.isNotEmpty()) session.password else null,
            dns = appSettings.primaryDns,
            secondaryDns = appSettings.secondaryDns,
            mtu = appSettings.mtu,
            ipv6Proxy = appSettings.ipv6Proxy,
            perApp = perApp,
            appBypass = appBypass,
            appList = appList,
            bypassLan = appSettings.bypassLan,
            killSwitch = appSettings.killSwitch,
            ipType = appSettings.ipType,
            remoteServer = remoteHost,
            remotePort = remotePort,
            transportType = prepared.transportType,
            transportPayload = modifiedPayload.toTypedArray(),
            dohEnabled = effectiveDoh,
            dohUrl = if (effectiveDoh) appSettings.dohUrl else null,
        )

        if (appSettings.proxyOnlyMode) {
            val proxyIntent = Intent(context, FluxonProxyService::class.java).apply {
                putExtra(io.github.p1neapplexpress.openflux.util.Constants.INTENT_NAME, prepared.name)
                putExtra(io.github.p1neapplexpress.openflux.util.Constants.INTENT_TRANSPORT_TYPE, prepared.transportType)
                putExtra(io.github.p1neapplexpress.openflux.util.Constants.INTENT_TRANSPORT_PAYLOAD, modifiedPayload.toTypedArray())
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(proxyIntent)
            } else {
                context.startService(proxyIntent)
            }
        } else {
            val vpnIntent = VpnIntentFactory.build(context, cfg).apply {
                putExtra(io.github.p1neapplexpress.openflux.util.Constants.INTENT_AUTONOMOUS, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(vpnIntent)
            } else {
                context.startService(vpnIntent)
            }
        }
            } catch (e: Exception) {
                Logx.e(TAG, "Error in boot auto-connect", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
