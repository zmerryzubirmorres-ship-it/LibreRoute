package io.github.libreroute.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.util.Base64

/** A verified, decrypted candidate. It must still be redeemed by the server before activation. */
@Serializable
data class RouteCandidate(
    val route_id: String = "",
    val server_id: String = "",
    val transport: String = "",
    val profile_instance_id: String = "",
    val name: String = "",
    val url: String = "",
    val secret_key: String = "",
    val codec: String = "batched",
    val encryption_layout: String = "packet",
    val parameters: Map<String, String> = emptyMap(),
    val revision: Long = 1,
    val status: String = "ready"
)

/** A verified, decrypted candidate. It must still be redeemed by the server before activation. */
@Serializable
data class VerifiedInvitationCandidate(
    val invitationId: String,
    val profileId: String,
    val name: String,
    val documentUrl: String,
    val secretKey: String,
    val parameters: Map<String, String>,
    val revision: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val deviceId: String = "",
    val userId: String = "",
    val serverId: String = "",
    val routes: List<RouteCandidate> = emptyList()
)

@Serializable
private data class IssueResponse(
    val op_id: String,
    val status: String,
    val data: IssueData,
    val timestamp: Long = 0L
)

@Serializable
private data class IssueData(
    val profile_id: String,
    val invitation_id: String,
    val expires_at: Long,
    val revision: Long,
    val ephemeral_pub: String,
    val nonce: String,
    val encrypted_payload: String
)

@Serializable
private data class InvitationPlaintext(
    val invitation_id: String,
    val profile_id: String,
    val name: String,
    val url: String,
    val secret_key: String,
    val transport: String = "",
    val server_id: String = "",
    val codec: String = "batched",
    val encryption_layout: String = "packet",
    val parameters: Map<String, String> = emptyMap(),
    val revision: Long,
    val issued_time: Long,
    val expires_at: Long,
    val recipient_pub_sha256: String,
    val device_id: String = "",
    val user_id: String = "",
    val routes: List<RouteCandidate> = emptyList()
)

@Serializable
private data class RedeemRequest(
    val invitation_id: String,
    val recipient_pub: String,
    val revision: Long
)

@Serializable
private data class RedeemResponse(
    val op_id: String,
    val status: String,
    val data: RedeemData
)

@Serializable
private data class RedeemData(
    val invitation_id: String,
    val profile_id: String,
    val revision: Long
)

/** Parses the Go server's signed ADMIN_RESPONSE and decrypts it only for the intended device. */
class ServerIssuedInvitation(
    private val recipientCrypto: RecipientInvitationCrypto,
    private val signatureVerifier: AdminKeyManager = AdminKeyManager()
) {
    companion object {
        private const val LINK_PREFIX = "libreroute://invitation?data="
        private const val MAX_ENVELOPE_BYTES = 32 * 1024
        private val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
        }

        fun toLink(response: AdminEnvelope): String {
            require(response.subtype == AdminEnvelope.SUBTYPE_RESPONSE) { "Not an invitation response" }
            val encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(response.toJson().toByteArray(Charsets.UTF_8))
            return LINK_PREFIX + encoded
        }

        private fun decode(value: String): ByteArray =
            runCatching { Base64.getUrlDecoder().decode(value) }
                .getOrElse { Base64.getDecoder().decode(value) }
    }

    fun verifyCandidate(link: String, trustedServerPublicKeyBase64: String, nowSeconds: Long = System.currentTimeMillis() / 1000L): VerifiedInvitationCandidate {
        require(link.startsWith(LINK_PREFIX, ignoreCase = true)) { "Unsupported invitation link" }
        val encoded = link.substring(LINK_PREFIX.length)
        require(encoded.isNotBlank() && encoded.length < MAX_ENVELOPE_BYTES * 2 && !encoded.contains('&')) {
            "Invalid invitation link"
        }
        val envelopeBytes = decode(encoded)
        require(envelopeBytes.size <= MAX_ENVELOPE_BYTES) { "Invitation response is too large" }
        val envelope = json.decodeFromString<AdminEnvelope>(String(envelopeBytes, Charsets.UTF_8))
        require(envelope.ver == 1 && envelope.type == AdminEnvelope.TYPE_ADMIN &&
            envelope.subtype == AdminEnvelope.SUBTYPE_RESPONSE && envelope.sender == "server") {
            "Invalid server response type"
        }
        require(envelope.exp > nowSeconds && envelope.exp <= nowSeconds + 15 * 60 + 60) {
            "Server response has expired or has an invalid clock"
        }
        require(signatureVerifier.verifyServerResponse(envelope, trustedServerPublicKeyBase64, trustedServerPublicKeyBase64)) {
            "Server signature does not match the trusted key"
        }
        val response = json.decodeFromString<IssueResponse>(String(decode(envelope.payload), Charsets.UTF_8))
        require(response.status == "applied" && response.op_id.isNotBlank() &&
            response.op_id == response.data.invitation_id && response.data.profile_id.isNotBlank() &&
            envelope.profileId.isNotBlank()) {
            "Invitation response does not match its envelope"
        }
        val raw = recipientCrypto.decrypt(response.data.ephemeral_pub, response.data.nonce, response.data.encrypted_payload)
        val plain = try {
            json.decodeFromString<InvitationPlaintext>(String(raw, Charsets.UTF_8))
        } finally {
            raw.fill(0)
        }
        require(plain.invitation_id == response.op_id && plain.profile_id == response.data.profile_id &&
            plain.expires_at == response.data.expires_at && plain.revision == response.data.revision &&
            plain.recipient_pub_sha256.equals(recipientCrypto.publicKeyHashHex(), ignoreCase = true)) {
            "Invitation is addressed to another device or profile"
        }
        require(plain.issued_time <= nowSeconds + 60 && plain.expires_at > nowSeconds &&
            plain.expires_at - plain.issued_time in 1L..900L) { "Invitation has expired" }
        require(plain.revision > 0 && plain.secret_key.length >= 16 && plain.name.isNotBlank()) {
            "Invitation profile is incomplete"
        }
        val url = URI(plain.url)
        require(url.scheme == "https" && !url.host.isNullOrBlank() && url.userInfo == null) {
            "Invitation document URL is invalid"
        }
        val resolvedRoutes = if (plain.routes.isNotEmpty()) {
            plain.routes
        } else {
            listOf(
                RouteCandidate(
                    route_id = plain.profile_id,
                    server_id = plain.server_id,
                    transport = plain.transport.ifBlank { plain.parameters["transport"] ?: "vyandex" },
                    profile_instance_id = plain.profile_id,
                    name = plain.name,
                    url = plain.url,
                    secret_key = plain.secret_key,
                    codec = plain.codec,
                    encryption_layout = plain.encryption_layout,
                    parameters = plain.parameters,
                    revision = plain.revision,
                    status = "ready"
                )
            )
        }
        for (r in resolvedRoutes) {
            require(r.name.isNotBlank()) { "Route name is missing" }
            if (r.url.isNotBlank()) {
                val transport = io.github.libreroute.data.TransportType.from(r.transport.ifBlank { "vyandex" })
                val validEndpoint = if (transport in setOf(io.github.libreroute.data.TransportType.mqtt, io.github.libreroute.data.TransportType.jitsi)) {
                    io.github.libreroute.util.ServiceRouteEndpoint.isValid(transport, r.url)
                } else {
                    val rUrl = URI(r.url)
                    rUrl.scheme == "https" && !rUrl.host.isNullOrBlank() && rUrl.userInfo == null
                }
                require(validEndpoint) {
                    "Route endpoint is invalid"
                }
            }
        }
        return VerifiedInvitationCandidate(
            invitationId = plain.invitation_id,
            profileId = plain.profile_id,
            name = plain.name,
            documentUrl = plain.url,
            secretKey = plain.secret_key,
            parameters = plain.parameters,
            revision = plain.revision,
            issuedAt = plain.issued_time,
            expiresAt = plain.expires_at,
            deviceId = plain.device_id,
            userId = plain.user_id,
            serverId = plain.server_id,
            routes = resolvedRoutes
        )
    }

    fun createRedemption(
        candidate: VerifiedInvitationCandidate,
        nowSeconds: Long = System.currentTimeMillis() / 1000L
    ): AdminEnvelope {
        require(candidate.expiresAt > nowSeconds) { "Invitation has expired" }
        val request = RedeemRequest(
            invitation_id = candidate.invitationId,
            recipient_pub = recipientCrypto.getOrCreatePublicKeyBase64(),
            revision = candidate.revision
        )
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.encodeToString(request).toByteArray(Charsets.UTF_8))
        val envelope = AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_INVITATION_REDEEM,
            profileId = candidate.profileId,
            opId = java.util.UUID.randomUUID().toString(),
            rev = candidate.revision,
            exp = minOf(nowSeconds + 300L, candidate.expiresAt),
            sender = recipientCrypto.publicKeyHashHex(),
            payload = encoded
        )
        return recipientCrypto.signRedeemEnvelope(envelope)
    }

    fun verifyRedemptionReceipt(
        request: AdminEnvelope,
        receipt: AdminEnvelope,
        candidate: VerifiedInvitationCandidate,
        trustedServerPublicKeyBase64: String,
        nowSeconds: Long = System.currentTimeMillis() / 1000L
    ): Boolean {
        if (request.ver != 1 || request.type != AdminEnvelope.TYPE_ADMIN ||
            request.subtype != AdminEnvelope.SUBTYPE_INVITATION_REDEEM ||
            request.profileId != candidate.profileId || request.rev != candidate.revision ||
            request.sender != recipientCrypto.publicKeyHashHex() || request.exp <= nowSeconds ||
            receipt.ver != 1 || receipt.type != AdminEnvelope.TYPE_ADMIN ||
            receipt.subtype != AdminEnvelope.SUBTYPE_RESPONSE || receipt.sender != "server" ||
            receipt.profileId != candidate.profileId || receipt.rev != request.rev + 1L ||
            receipt.exp <= nowSeconds || receipt.exp > nowSeconds + 24 * 3600L ||
            candidate.expiresAt <= nowSeconds ||
            !signatureVerifier.verifyServerResponse(receipt, trustedServerPublicKeyBase64, trustedServerPublicKeyBase64)) return false
        return runCatching {
            val redeemed = json.decodeFromString<RedeemRequest>(String(decode(request.payload), Charsets.UTF_8))
            val response = json.decodeFromString<RedeemResponse>(String(decode(receipt.payload), Charsets.UTF_8))
            redeemed.invitation_id == candidate.invitationId &&
                redeemed.recipient_pub == recipientCrypto.getOrCreatePublicKeyBase64() &&
                redeemed.revision == candidate.revision &&
                response.op_id == request.opId && response.status == "verified" &&
                response.data.invitation_id == candidate.invitationId &&
                response.data.profile_id == candidate.profileId &&
                response.data.revision == candidate.revision
        }.getOrDefault(false)
    }
}
