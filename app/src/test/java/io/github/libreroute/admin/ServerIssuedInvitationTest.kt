package io.github.libreroute.admin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import io.github.libreroute.util.TunnelLinkParser
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class ServerIssuedInvitationTest {
    private class MemoryStore : InvitationStateStore {
        val values = mutableMapOf<String, String>()
        override fun get(key: String): String? = values[key]
        override fun put(key: String, value: String) { values[key] = value }
    }

    private val fixture by lazy {
        val source = javaClass.classLoader!!.getResourceAsStream("invitation_go_fixture.json")!!
        Json.parseToJsonElement(source.bufferedReader().use { it.readText() }).jsonObject
    }

    private fun recipient(): RecipientInvitationCrypto {
        val store = MemoryStore()
        store.put("recipient_p256_private_v1", fixture.getValue("recipient_pkcs8").jsonPrimitive.content)
        store.put("recipient_p256_public_v1", fixture.getValue("recipient_spki").jsonPrimitive.content)
        return RecipientInvitationCrypto(store)
    }

    private fun response(): AdminEnvelope = Json.decodeFromJsonElement(AdminEnvelope.serializer(), fixture.getValue("envelope"))

    @Test fun decryptsGoIssuedInvitationOnlyForItsRecipient() {
        val crypto = recipient()
        assertEquals(fixture.getValue("recipient_spki").jsonPrimitive.content, crypto.getOrCreatePublicKeyBase64())
        val decoder = ServerIssuedInvitation(crypto)
        val serverKey = fixture.getValue("server_pub").jsonPrimitive.content
        val candidate = decoder.verifyCandidate(ServerIssuedInvitation.toLink(response()), serverKey, 1_700_000_010)
        assertEquals("fixture-profile", candidate.profileId)
        assertEquals("Fixture User", candidate.name)
        assertEquals("https://docs.yandex.ru/docs/view/fixture", candidate.documentUrl)
        assertEquals(1L, candidate.revision)
        assertEquals(1_700_000_900L, candidate.expiresAt)
        val invitationLink = ServerIssuedInvitation.toLink(response())
        assertEquals(true, TunnelLinkParser.parseTunnels(invitationLink).isEmpty())
        assertEquals(true, TunnelLinkParser.parseTunnels(response().toJson()).isEmpty())
        assertEquals(true, TunnelLinkParser.parseTunnels("libreroute://import?data=${response().toBase64()}").isEmpty())
        assertEquals(true, TunnelLinkParser.parseTunnels("""{"type":"ADMIN","subtype":{},"profile_id":"fixture-profile","payload":"bad"}""").isEmpty())
        val redemption = decoder.createRedemption(candidate, 1_700_000_010)
        assertEquals(AdminEnvelope.SUBTYPE_INVITATION_REDEEM, redemption.subtype)
        val recipientPublicKey = AdminKeyManager().parsePublicKey(crypto.getOrCreatePublicKeyBase64())!!
        assertEquals(crypto.publicKeyHashHex(), redemption.sender)
        assertEquals(true, AdminKeyManager().verifyEnvelope(redemption, recipientPublicKey))

        assertThrows(IllegalArgumentException::class.java) {
            decoder.verifyCandidate(ServerIssuedInvitation.toLink(response()), serverKey, 1_700_001_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            decoder.verifyCandidate(ServerIssuedInvitation.toLink(response()), "invalid-server-key", 1_700_000_010)
        }
        val changed = response().copy(profileId = "another-profile")
        assertThrows(IllegalArgumentException::class.java) {
            decoder.verifyCandidate(ServerIssuedInvitation.toLink(changed), serverKey, 1_700_000_010)
        }
        assertThrows(Exception::class.java) {
            ServerIssuedInvitation(RecipientInvitationCrypto(MemoryStore()))
                .verifyCandidate(ServerIssuedInvitation.toLink(response()), serverKey, 1_700_000_010)
        }
    }

    @Test fun activationRequiresMatchingSignedRedemptionReceipt() {
        val decoder = ServerIssuedInvitation(recipient())
        val candidate = decoder.verifyCandidate(
            ServerIssuedInvitation.toLink(response()), fixture.getValue("server_pub").jsonPrimitive.content, 1_700_000_010
        )
        val request = decoder.createRedemption(candidate, 1_700_000_010)
        val serverPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val serverPub = Base64.getEncoder().encodeToString(serverPair.public.encoded)
        fun signedReceipt(status: String, revision: Long): AdminEnvelope {
            val body = """{"op_id":"${request.opId}","status":"$status","data":{"invitation_id":"${candidate.invitationId}","profile_id":"${candidate.profileId}","revision":$revision}}"""
            val unsigned = AdminEnvelope(
                subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                profileId = candidate.profileId,
                rev = request.rev + 1L,
                sender = "server",
                exp = 1_700_000_300,
                payload = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toByteArray(Charsets.UTF_8))
            )
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(serverPair.private)
            signer.update(unsigned.canonicalSigningString().toByteArray(Charsets.UTF_8))
            return unsigned.copy(signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()))
        }
        assertEquals(true, decoder.verifyRedemptionReceipt(request, signedReceipt("verified", 1), candidate, serverPub, 1_700_000_010))
        assertEquals(false, decoder.verifyRedemptionReceipt(request, signedReceipt("applied", 1), candidate, serverPub, 1_700_000_010))
        assertEquals(false, decoder.verifyRedemptionReceipt(request, signedReceipt("verified", 2), candidate, serverPub, 1_700_000_010))
        assertEquals(false, decoder.verifyRedemptionReceipt(request, signedReceipt("verified", 1), candidate, "wrong-key", 1_700_000_010))
        assertEquals(false, decoder.verifyRedemptionReceipt(request.copy(payload = "e30"), signedReceipt("verified", 1), candidate, serverPub, 1_700_000_010))
    }

    @Test fun decryptsMultiRouteInvitationAndPopulatesCandidateRoutes() {
        val crypto = recipient()
        val serverKey = fixture.getValue("server_pub").jsonPrimitive.content
        val candidate = ServerIssuedInvitation(crypto).verifyCandidate(
            ServerIssuedInvitation.toLink(response()), serverKey, 1_700_000_010
        )
        assertEquals(1, candidate.routes.size)
        assertEquals(candidate.profileId, candidate.routes[0].profile_instance_id)
        assertEquals(candidate.documentUrl, candidate.routes[0].url)
    }
}
