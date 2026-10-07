package io.github.libreroute.admin

import java.util.Base64
import kotlinx.serialization.json.*

data class DelegatedAdminGrant(
    val response: AdminEnvelope,
    val grant: AdminEnvelope,
    val serverId: String,
    val serverPublicKey: String,
    val documentUrl: String,
    val clientKey: String,
    val serverKey: String,
    val transferId: String? = null
)

data class AdminInvitationTemplate(
    val response: AdminEnvelope,
    val profileId: String,
    val role: AdminRole,
    val serverId: String,
    val serverPublicKey: String,
    val documentUrl: String,
    val expiresAt: Long
)

/** Encoding is not verification. Repository verifies every server/device signature and binding. */
object AdminDelegationLink {
    const val REQUEST_PREFIX = "libreroute://admin-request?data="
    const val GRANT_PREFIX = "libreroute://admin-grant?data="
    const val INVITATION_PREFIX = "libreroute://admin-invite?data="
    const val TRANSFER_ACK_PREFIX = "libreroute://admin-transfer-ack?data="
    private const val MAX_LENGTH = 64 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    fun createRequest(join: AdminEnvelope): String {
        require(join.subtype == AdminEnvelope.SUBTYPE_REQUEST)
        return encode(REQUEST_PREFIX, join)
    }

    fun parseRequest(link: String): AdminEnvelope? = decode(REQUEST_PREFIX, link)
        ?.takeIf { it.type == AdminEnvelope.TYPE_ADMIN && it.subtype == AdminEnvelope.SUBTYPE_REQUEST }

    fun createTransferAck(acceptance: AdminEnvelope): String {
        require(acceptance.type == AdminEnvelope.TYPE_ADMIN && acceptance.subtype == AdminEnvelope.SUBTYPE_COMMAND)
        return encode(TRANSFER_ACK_PREFIX, acceptance)
    }

    fun parseTransferAck(link: String): AdminEnvelope? = decode(TRANSFER_ACK_PREFIX, link)
        ?.takeIf { it.type == AdminEnvelope.TYPE_ADMIN && it.subtype == AdminEnvelope.SUBTYPE_COMMAND }

    fun encodeGrant(response: AdminEnvelope): String {
        require(response.subtype == AdminEnvelope.SUBTYPE_RESPONSE)
        return encode(GRANT_PREFIX, response)
    }

    fun encodeInvitation(response: AdminEnvelope): String {
        require(response.subtype == AdminEnvelope.SUBTYPE_RESPONSE)
        return encode(INVITATION_PREFIX, response)
    }

    fun parseGrant(link: String): DelegatedAdminGrant? = runCatching {
        val receipt = decode(GRANT_PREFIX, link) ?: return null
        val data = receiptData(receipt) ?: return null
        val grant = AdminEnvelope.fromJson(data.getValue("grant").toString()) ?: return null
        require(grant.subtype == AdminEnvelope.SUBTYPE_GRANT && grant.profileId == receipt.profileId)
        DelegatedAdminGrant(receipt, grant, data.string("server_id"), data.string("server_public_key"),
            data["document_url"]?.jsonPrimitive?.content.orEmpty(), data["client_key"]?.jsonPrimitive?.content.orEmpty(),
            data["server_key"]?.jsonPrimitive?.content.orEmpty(), data["transfer_id"]?.jsonPrimitive?.content)
    }.getOrNull()

    fun parseInvitation(link: String): AdminInvitationTemplate? = runCatching {
        val receipt = decode(INVITATION_PREFIX, link) ?: return null
        val data = receiptData(receipt) ?: return null
        val role = AdminRole.valueOf(data.string("role").uppercase())
        require(role in listOf(AdminRole.OWNER, AdminRole.OPERATOR, AdminRole.VIEWER))
        val profile = data.string("profile_id")
        require(profile == receipt.profileId)
        AdminInvitationTemplate(receipt, profile, role, data.string("server_id"), data.string("server_public_key"),
            data["document_url"]?.jsonPrimitive?.content.orEmpty(), data.getValue("expires_at").jsonPrimitive.long)
    }.getOrNull()

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content.also { require(it.isNotBlank()) }

    private fun receiptData(receipt: AdminEnvelope): JsonObject? {
        if (receipt.type != AdminEnvelope.TYPE_ADMIN || receipt.subtype != AdminEnvelope.SUBTYPE_RESPONSE) return null
        val payload = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(receipt.payload), Charsets.UTF_8)).jsonObject
        if (payload["status"]?.jsonPrimitive?.content != "applied") return null
        return payload["data"]?.jsonObject
    }

    private fun encode(prefix: String, envelope: AdminEnvelope): String =
        (prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope.toJson().toByteArray(Charsets.UTF_8)))
            .also { require(it.length <= MAX_LENGTH) { "Подтверждение слишком большое для ссылки" } }

    private fun decode(prefix: String, value: String): AdminEnvelope? = runCatching {
        val link = value.trim()
        require(link.startsWith(prefix) && link.length <= MAX_LENGTH)
        val body = link.removePrefix(prefix)
        require(body.isNotBlank() && body.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        AdminEnvelope.fromJson(String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8))
    }.getOrNull()
}
