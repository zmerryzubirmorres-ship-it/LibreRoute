package io.github.libreroute.util

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class ProxyRoutingMode(val cliValue: String) {
    @SerialName("all_tunnel") ALL_TUNNEL("all_tunnel"), @SerialName("rules") RULES("rules")
}

/** Autonomous proxy has no server; keep the saved server mode untouched. */
internal fun effectiveProxyRoutingMode(saved: ProxyRoutingMode, autonomous: Boolean): ProxyRoutingMode =
    if (autonomous) ProxyRoutingMode.RULES else saved

internal fun initialProxyRoutingMode(hasSettings: Boolean, firstInstallTime: Long?, lastUpdateTime: Long?): ProxyRoutingMode =
    if (!hasSettings && firstInstallTime != null && firstInstallTime > 0 && firstInstallTime == lastUpdateTime)
        ProxyRoutingMode.RULES else ProxyRoutingMode.ALL_TUNNEL

/** Session state is separate from saved preferences and server readiness. */
object ProxyRoutingStatus {
    data class Snapshot(val mode: ProxyRoutingMode, val signature: String, val profiles: Set<String>, val autonomous: Boolean = false)
    @Volatile var active: Snapshot? = null
        internal set
    @Volatile var tunnelReady: Boolean = false
        internal set
    @Volatile var tunnelProbePort: Int = 0
        internal set
    @Volatile var lanIngressPort: Int = 0
        internal set
    @Volatile var profileReadiness: (() -> Map<String, Boolean>)? = null
        internal set
    @Volatile var routeCounts: String = ""
        internal set

    fun signature(context: Context, autonomous: Boolean = false): String {
        return signature(effectiveProxyRoutingMode(AppSettings(context).proxyRoutingMode, autonomous), SiteRoutingPreferences(context).load(), ZapretProfilePreferences(context).load())
    }

    fun signature(mode: ProxyRoutingMode, sitesState: io.github.libreroute.data.SiteRoutingState,
                  profilesState: io.github.libreroute.data.ZapretProfileState): String {
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        val sites = json.encodeToString(io.github.libreroute.data.SiteRoutingState.serializer(), sitesState)
        val profiles = json.encodeToString(io.github.libreroute.data.ZapretProfileState.serializer(), profilesState)
        val bytes = "${mode.cliValue}|$sites|$profiles".toByteArray(Charsets.UTF_8)
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    fun pending(context: Context): Boolean = active?.let { it.signature != signature(context, it.autonomous) } ?: false
}
