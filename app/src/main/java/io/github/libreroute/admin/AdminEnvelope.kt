package io.github.libreroute.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Canonical Administrative Envelope for Flux control plane.
 *
 * Signing string format:
 * "ver:type:subtype:profile_id:op_id:rev:exp:sender:nonce:payload"
 */
@Serializable
data class AdminEnvelope(
    val ver: Int = 1,
    val type: String = TYPE_ADMIN,
    val subtype: String,
    @SerialName("profile_id") val profileId: String,
    @SerialName("op_id") val opId: String = UUID.randomUUID().toString(),
    val rev: Long = 1L,
    val exp: Long = System.currentTimeMillis() / 1000L + 300L, // 5 minutes TTL
    val sender: String,
    val nonce: String = UUID.randomUUID().toString(),
    val payload: String = "",
    @SerialName("sig") val signature: String = ""
) {
    companion object {
        const val TYPE_ADMIN = "ADMIN"
        const val TYPE_PROBLEM = "PROBLEM"
        const val TYPE_SESSION = "SESSION"

        // Administrative subtypes
        const val SUBTYPE_REQUEST = "ADMIN_JOIN_REQUEST"
        const val SUBTYPE_GRANT = "ADMIN_GRANT"
        const val SUBTYPE_COMMAND = "ADMIN_COMMAND"
        const val SUBTYPE_RESPONSE = "ADMIN_RESPONSE"
        const val SUBTYPE_INVITATION_REDEEM = "ADMIN_INVITATION_REDEEM"
        const val SUBTYPE_CREATE = "create"
        const val SUBTYPE_ISSUE = "issue"
        const val SUBTYPE_SUSPEND = "suspend"
        const val SUBTYPE_RESUME = "resume"
        const val SUBTYPE_REVOKE = "revoke"
        const val SUBTYPE_UPDATE = "update"
        const val SUBTYPE_LIST = "list"

        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJson(jsonStr: String): AdminEnvelope? = runCatching {
            json.decodeFromString<AdminEnvelope>(jsonStr)
        }.getOrNull()
    }

    /**
     * Formats the exact canonical string used for ECDSA signing and verification.
     * "ver:type:subtype:profile_id:op_id:rev:exp:sender:nonce:payload"
     */
    fun canonicalSigningString(): String {
        return "$ver|$type|$subtype|$profileId|$opId|$rev|$exp|$sender|$nonce|$payload"
    }

    fun toJson(): String {
        return json.encodeToString(this)
    }

    fun toBase64(): String {
        val raw = toJson().toByteArray(Charsets.UTF_8)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
}
