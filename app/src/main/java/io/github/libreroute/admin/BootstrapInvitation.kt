package io.github.libreroute.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.Base64
import java.util.UUID

@Serializable
data class EnrollmentRoute(val server_id: String, val transport: String = "vyandex", val url: String = "")

data class BootstrapInvitation(
    val enrollmentId: String, val token: String, val profileId: String,
    val documentUrl: String, val serverPublicKey: String, val expiresAt: Long
) {
    companion object {
        const val PREFIX = "libreroute://invitation?bootstrap="
        fun toLink(envelope: AdminEnvelope): String = PREFIX + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(envelope.toJson().toByteArray(Charsets.UTF_8))

        fun parse(link: String, verifier: AdminKeyManager): BootstrapInvitation {
            require(link.startsWith(PREFIX) && link.length < 48 * 1024) { "Некорректное bootstrap приглашение" }
            val envelope = AdminEnvelope.fromJson(String(Base64.getUrlDecoder().decode(link.removePrefix(PREFIX)), Charsets.UTF_8))
                ?: error("Некорректный конверт приглашения")
            val body = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(envelope.payload), Charsets.UTF_8)).jsonObject
            val data = body.getValue("data").jsonObject
            fun field(key: String) = data.getValue(key).jsonPrimitive.content
            val pub = field("server_public_key")
            val now = System.currentTimeMillis() / 1000
            require(envelope.type == AdminEnvelope.TYPE_ADMIN && envelope.subtype == AdminEnvelope.SUBTYPE_RESPONSE &&
                envelope.sender == "server" && envelope.exp > now &&
                body["op_id"]?.jsonPrimitive?.content == envelope.opId &&
                body["status"]?.jsonPrimitive?.content == "applied" && verifier.verifyServerResponse(envelope, pub, pub)) {
                "Подпись приглашения не подтверждена"
            }
            val expires = field("expires_at").toLong()
            val document = field("document_url")
            val uri = java.net.URI(document)
            require(envelope.profileId == field("control_profile_id") && field("enrollment_id").isNotBlank() &&
                expires > now && expires <= now + 15 * 60 && field("token").matches(Regex("[A-Za-z0-9_-]{43}")) &&
                uri.scheme == "https" && uri.host == "docs.yandex.ru" && uri.userInfo == null && uri.port == -1 &&
                uri.path.startsWith("/edit/d/") && uri.query == null && uri.fragment == null) { "Приглашение истекло или канал некорректен" }
            return BootstrapInvitation(field("enrollment_id"), field("token"), field("control_profile_id"), document, pub, expires)
        }
    }

    fun createRequest(crypto: RecipientInvitationCrypto): AdminEnvelope {
        val body = buildJsonObject {
            put("enrollment_id", enrollmentId); put("token", token); put("public_key", crypto.getOrCreatePublicKeyBase64())
        }
        return crypto.signRedeemEnvelope(AdminEnvelope(subtype = "ADMIN_ENROLLMENT_REDEEM", profileId = profileId,
            sender = crypto.publicKeyHashHex(), opId = UUID.randomUUID().toString(), rev = 1,
            exp = expiresAt, payload = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().toByteArray(Charsets.UTF_8))))
    }
}
