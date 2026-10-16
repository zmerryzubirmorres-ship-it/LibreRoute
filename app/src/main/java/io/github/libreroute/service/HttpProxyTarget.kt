package io.github.libreroute.service

import java.net.IDN
import java.net.URI
import java.util.Locale

internal data class HttpProxyTarget(val host: String, val port: Int, val path: String) {
    val authority: String get() = if (port == 80) host else "$host:$port"
}

/** Parsing only: never resolves a host before Core chooses the route. */
internal fun parseHttpProxyTarget(method: String, target: String, hosts: List<String>): HttpProxyTarget? = runCatching {
    require(hosts.size <= 1 && target.none { it.isWhitespace() || it.code < 32 })
    fun authority(raw: String, defaultPort: Int): Pair<String, Int> {
        require(raw.isNotEmpty() && raw.none { it.isWhitespace() || it in "/\\?#@[]" } && raw.count { it == ':' } <= 1)
        val hasPort = ':' in raw
        val host = IDN.toASCII(if (hasPort) raw.substringBefore(':').removeSuffix(".") else raw.removeSuffix("."), IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        require(io.github.libreroute.util.DomainRulesPreferences.isValidDomain(host))
        val port = if (hasPort) raw.substringAfter(':').toInt() else defaultPort
        require(port in 1..65535)
        return host to port
    }
    if (method == "CONNECT") {
        require(':' in target)
        val (host, port) = authority(target, 443)
        if (hosts.isNotEmpty()) require(authority(hosts.single(), port) == host to port)
        HttpProxyTarget(host, port, "")
    } else {
        val uri = URI(target)
        require(uri.rawFragment == null && uri.rawUserInfo == null)
        val absolute = uri.isAbsolute
        val destination = if (absolute) {
            require(uri.scheme.equals("http", true))
            authority(requireNotNull(uri.rawAuthority), 80)
        } else {
            require(target.startsWith('/') && !target.startsWith("//") && hosts.size == 1)
            authority(hosts.single(), 80)
        }
        if (absolute && hosts.isNotEmpty()) require(authority(hosts.single(), 80) == destination)
        HttpProxyTarget(destination.first, destination.second,
            uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: ""))
    }
}.getOrNull()
