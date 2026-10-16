package io.github.libreroute.service

import io.github.libreroute.util.AppSettings
import io.github.libreroute.util.LocalSocksSession

/** A saved sharing preference is not proof that a native listener exists. */
internal object ProxySharing {
    fun refresh(settings: AppSettings): Boolean {
        val session = LocalSocksSession.getReady()
        if (!settings.shareLanProxy || !canShare(session, settings.socks5AuthEnabled,
                settings.socks5CustomUser, settings.socks5CustomPass)) {
            HotspotProxyBridge.stop()
            return false
        }
        val upstreamPort = io.github.libreroute.util.ProxyRoutingStatus.lanIngressPort.takeIf { it in 1..65535 } ?: session!!.port
        return HotspotProxyBridge.start(settings.lanProxyPort, upstreamPort, session!!.isAuthEnabled,
            session.username, session.password)
    }

    fun canShare(session: LocalSocksSession.SessionInfo?, authEnabled: Boolean,
                 customUser: String, customPass: String): Boolean = session != null &&
        session.isAuthEnabled == authEnabled && (!authEnabled ||
            (session.username == customUser && (customPass.isBlank() || session.password == customPass)))
}
