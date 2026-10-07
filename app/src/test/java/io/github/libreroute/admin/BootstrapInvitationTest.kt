package io.github.libreroute.admin

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class BootstrapInvitationTest {
    private fun invite(keys: AdminKeyManager, expiry: Long): AdminEnvelope {
        val envelope = AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_RESPONSE, sender = "server", profileId = "control", exp = expiry)
        val body = buildJsonObject {
            put("op_id", envelope.opId); put("status", "applied")
            put("data", buildJsonObject {
                put("enrollment_id", "isolated-test"); put("token", "a".repeat(43))
                put("control_profile_id", "control"); put("document_url", "https://docs.yandex.ru/edit/d/isolated-test")
                put("server_public_key", keys.getPublicKeyBase64()); put("expires_at", expiry)
            })
        }
        return keys.signEnvelope(envelope.copy(payload = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().toByteArray())))
    }

    @Test fun verifiesInvitationAndNewDeviceProof() {
        val keys = AdminKeyManager()
        val envelope = invite(keys, System.currentTimeMillis() / 1000 + 600)
        val invitation = BootstrapInvitation.parse(BootstrapInvitation.toLink(envelope), keys)
        val state = object : InvitationStateStore {
            val values = mutableMapOf<String, String>()
            override fun get(key: String) = values[key]
            override fun put(key: String, value: String) { values[key] = value }
        }
        val recipient = RecipientInvitationCrypto(state)
        val request = invitation.createRequest(recipient)
        assertEquals("ADMIN_ENROLLMENT_REDEEM", request.subtype)
        assertEquals(recipient.publicKeyHashHex(), request.sender)
        assertTrue(keys.verifyEnvelope(request, keys.parsePublicKey(recipient.getOrCreatePublicKeyBase64())!!))
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapInvitation.parse(BootstrapInvitation.toLink(envelope.copy(profileId = "changed")), keys)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BootstrapInvitation.parse(BootstrapInvitation.toLink(invite(keys, System.currentTimeMillis() / 1000 - 1)), keys)
        }
    }
}
