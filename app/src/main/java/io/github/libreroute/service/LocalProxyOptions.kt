package io.github.libreroute.service

import io.github.libreroute.util.LocalSocksSession

/** One session supplies the listener address and credentials for every launch entry point. */
internal fun localProxyOptions(original: List<String>, session: LocalSocksSession.SessionInfo,
                               proxyOnly: Boolean = false,
                               routingMode: io.github.libreroute.util.ProxyRoutingMode = io.github.libreroute.util.ProxyRoutingMode.ALL_TUNNEL): List<String> {
    require(session.port in 1..65535) { "Некорректный порт SOCKS5" }
    if (session.isAuthEnabled) {
        require(session.username.toByteArray(Charsets.UTF_8).size in 1..255 &&
            session.password.toByteArray(Charsets.UTF_8).size in 1..255) {
            "Логин и пароль SOCKS5 должны содержать от 1 до 255 байт UTF-8"
        }
    }
    val valueFlags = mutableSetOf("socks5", "socks5-user", "socks5-pass")
    val booleanFlags = mutableSetOf<String>()
    if (proxyOnly) {
        valueFlags += listOf("inbound", "tun-socket", "tun-mtu", "domain-rules-file", "ip-rules-file",
            "split-mode", "domain-mode")
        if (routingMode == io.github.libreroute.util.ProxyRoutingMode.ALL_TUNNEL) valueFlags += "site-routes-file"
        booleanFlags += listOf("tun", "socks5-mode")
    }
    val result = mutableListOf<String>()
    var index = 0
    while (index < original.size) {
        val arg = original[index]
        val flag = arg.takeIf { it.startsWith('-') }?.trimStart('-')?.substringBefore('=')
        when (flag) {
            in valueFlags -> index += if ('=' !in arg && index + 1 < original.size) 2 else 1
            in booleanFlags -> index++
            else -> { result.add(arg); index++ }
        }
    }
    if (proxyOnly) result.add("--inbound=socks5")
    result += listOf("--socks5", "127.0.0.1:${session.port}")
    if (session.isAuthEnabled) result += listOf("--socks5-user", session.username, "--socks5-pass", session.password)
    return result
}
