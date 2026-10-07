package io.github.libreroute.util

import java.net.URI

/**
 * Parses the URL shared from the phone browser after Jitsi authentication.
 * The browser shares the room URL with its #jwt fragment; cookies never leave
 * the browser and are not needed by the native transport.
 */
object JitsiBrowserHandoff {
    data class TokenLink(val roomUrl: String, val token: String)

    fun parseSharedText(raw: String?): TokenLink? = raw.orEmpty()
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull(::parse)
        .firstOrNull()

    fun parse(raw: String): TokenLink? = runCatching {
        val value = raw.trim()
        val hash = value.indexOf('#')
        if (hash > 0) {
            val roomUrl = value.substring(0, hash)
            val fragment = value.substring(hash + 1)
            val uri = URI(roomUrl)
            require(uri.scheme.equals("https", true))
            require(!uri.host.isNullOrBlank() && !uri.path.orEmpty().trim('/').isBlank())
            require(uri.query == null)
            val token = JitsiTokenStore.fromFragment(fragment) ?: return null
            return@runCatching TokenLink(roomUrl, token)
        }
        val uri = URI(value)
        require(uri.scheme.equals("https", true))
        require(!uri.host.isNullOrBlank() && !uri.path.orEmpty().trim('/').isBlank())
        val token = JitsiTokenStore.fromQuery(uri.rawQuery) ?: return null
        val roomUrl = URI("https", uri.authority, uri.path.trimEnd('/'), null, null).toASCIIString()
        TokenLink(roomUrl, token)
    }.getOrNull()

    fun sameRoom(first: String, second: String): Boolean = runCatching {
        val a = URI(first)
        val b = URI(second)
        a.scheme.equals("https", true) && b.scheme.equals("https", true) &&
            a.host.equals(b.host, true) &&
            (a.port.takeIf { it != -1 } ?: 443) == (b.port.takeIf { it != -1 } ?: 443) &&
            a.path.trimEnd('/').equals(b.path.trimEnd('/'), true)
    }.getOrDefault(false)
}

