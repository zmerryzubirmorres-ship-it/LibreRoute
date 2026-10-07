package io.github.libreroute.util

import android.content.Context
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest

/** Keystore-backed storage for the short-lived Jitsi room JWT obtained by browser handoff. */
class JitsiTokenStore(context: Context) {
    private val prefs = SecurePreferences(context.applicationContext, "jitsi_room_tokens")

    fun put(roomUrl: String, token: String) {
        val key = key(roomUrl) ?: return
        if (token.length in 32..8192 && token.count { it == '.' } >= 2 && !isExpired(token)) {
            prefs.putString(key, token)
        }
    }

    /** Returns a usable token. Expired JWTs are removed so callers cannot keep retrying them. */
    fun get(roomUrl: String): String? {
        val storageKey = key(roomUrl) ?: return null
        val token = prefs.getString(storageKey, null) ?: return null
        if (isExpired(token)) {
            prefs.remove(storageKey)
            return null
        }
        return token
    }

    fun clear(roomUrl: String) { key(roomUrl)?.let { prefs.remove(it) } }

    private fun key(raw: String): String? = runCatching {
        val uri = URI(raw)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
        val canonical = uri.host.lowercase() + uri.path.trim('/').lowercase()
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray())
        "room_${digest.joinToString("") { "%02x".format(it) }.take(32)}"
    }.getOrNull()

    companion object {
        /** JWT exp is in Unix seconds. Tokens without exp remain valid until the server rejects them. */
        fun isExpired(token: String, nowSeconds: Long = System.currentTimeMillis() / 1000L): Boolean =
            runCatching {
                val payload = token.split('.', limit = 3).getOrNull(1) ?: return false
                val json = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(payload), Charsets.UTF_8)).jsonObject
                val expiry = json["exp"]?.jsonPrimitive?.longOrNull ?: return false
                expiry <= nowSeconds
            }.getOrDefault(false)

        /** Extracts the JWT emitted by meet.jit.si's #jwt=... redirect. */
        fun fromFragment(fragment: String?): String? = runCatching {
            val raw = fragment.orEmpty().removePrefix("#")
            val value = raw.split('&').firstOrNull { it.startsWith("jwt=", true) }
                ?.substringAfter('=') ?: return null
            URLDecoder.decode(value, "UTF-8").trim().trim('"')
        }.getOrNull()?.takeIf { it.count { c -> c == '.' } >= 2 }

        /** Extracts the JWT emitted by ?jwt=... or ?token=... query parameters. */
        fun fromQuery(query: String?): String? = runCatching {
            val raw = query.orEmpty().removePrefix("?")
            val value = raw.split('&').firstOrNull { 
                it.startsWith("jwt=", true) || it.startsWith("token=", true) 
            }?.substringAfter('=') ?: return null
            URLDecoder.decode(value, "UTF-8").trim().trim('"')
        }.getOrNull()?.takeIf { it.count { c -> c == '.' } >= 2 }

        /** Extracts the JWT emitted in either URL fragment or query string. */
        fun fromUrl(url: String?): String? = runCatching {
            val uri = URI(url.orEmpty())
            fromFragment(uri.rawFragment) ?: fromQuery(uri.rawQuery)
        }.getOrNull()
    }
}
