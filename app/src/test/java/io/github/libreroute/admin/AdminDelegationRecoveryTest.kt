package io.github.libreroute.admin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class AdminDelegationRecoveryTest {
    private val json = Json { encodeDefaults = true }
    private lateinit var keys: AdminKeyManager
    private lateinit var state: InMemoryAdminStorage

    @Before fun before() { AdminKeyManager.clearFallbackKey(); keys = AdminKeyManager(); state = InMemoryAdminStorage() }

    private fun response(data: JsonObject, profile: String = "control-delegated"): AdminEnvelope {
        val envelope = AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_RESPONSE, sender = "server", profileId = profile,
            exp = System.currentTimeMillis() / 1000 + 3600)
        return keys.signEnvelope(envelope.copy(payload = Base64.getUrlEncoder().withoutPadding().encodeToString(buildJsonObject {
            put("op_id", envelope.opId); put("status", "applied"); put("data", data)
        }.toString().toByteArray())))
    }

    private fun invitation(): String = AdminDelegationLink.encodeInvitation(response(buildJsonObject {
        put("profile_id", "control-delegated"); put("server_id", "delegated"); put("role", "VIEWER")
        put("server_public_key", keys.getPublicKeyBase64()); put("expires_at", System.currentTimeMillis() / 1000 + 600)
        put("document_url", "https://docs.yandex.ru/edit/d/dedicated")
    }))

    private fun grantLink(request: AdminEnvelope, nonce: String = request.nonce): String {
        val grant = AdminGrant(AdminRole.VIEWER, request.profileId, keys.getFingerprint(), keys.getPublicKeyBase64(),
            System.currentTimeMillis(), System.currentTimeMillis() / 1000 + 3600, scope = listOf("delegated"),
            nonce = nonce, serverId = "delegated")
        val envelope = keys.signEnvelope(AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_GRANT, profileId = request.profileId,
            sender = "server", nonce = nonce, exp = grant.expiresAt, payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(grant).toByteArray())))
        return AdminDelegationLink.encodeGrant(response(buildJsonObject {
            put("grant", json.parseToJsonElement(envelope.toJson())); put("server_id", "delegated")
            put("server_public_key", keys.getPublicKeyBase64()); put("document_url", "https://docs.yandex.ru/edit/d/dedicated")
            put("client_key", "client-session-secret"); put("server_key", "server-session-secret")
        }))
    }

    @Test fun invitationNeedsIndependentTrustAndViewerImportCreatesOwnControlWithoutSsh() {
        val repo = AdminRepository(keyManager = keys, storage = state)
        val link = invitation()
        assertTrue(repo.acceptAdminInvitationTemplate(link).first is AdminOpResult.Failure)
        val accepted = repo.acceptAdminInvitationTemplate(link, keys.computeFingerprint(keys.getPublicKeyBase64())!!)
        assertTrue(accepted.first is AdminOpResult.Success)
        assertFalse(repo.hasAdminReadAccess())
        val request = AdminDelegationLink.parseRequest(accepted.second!!)!!
        val grant = grantLink(request)
        assertTrue(repo.importAdminGrantLink(grant) is AdminOpResult.Success)
        assertTrue(repo.hasAdminReadAccess()); assertFalse(repo.hasAdminAccess())
        assertEquals(AdminRole.VIEWER, repo.getRole("delegated"))
        assertEquals(AdminRole.VIEWER, repo.managedProfilesFlow.value.single().role)
        assertTrue(repo.canPerform("delegated", "list-users")); assertFalse(repo.canPerform("delegated", "create-user"))
        assertEquals("", repo.getServerNode("delegated")?.host)
        val reload = AdminRepository(keyManager = keys, storage = state)
        assertEquals("https://docs.yandex.ru/edit/d/dedicated", reload.defaultDocumentUrl("delegated"))
        assertEquals(AdminRole.VIEWER, reload.getRole("delegated"))
        assertTrue(reload.importAdminGrantLink(grant) is AdminOpResult.Failure)
    }

    @Test fun foreignNonceDoesNotElevateRoleOrOverwritePendingContext() {
        val repo = AdminRepository(keyManager = keys, storage = state)
        val accepted = repo.acceptAdminInvitationTemplate(invitation(), keys.computeFingerprint(keys.getPublicKeyBase64())!!)
        val request = AdminDelegationLink.parseRequest(accepted.second!!)!!
        assertTrue(repo.importAdminGrantLink(grantLink(request, "foreign-request")) is AdminOpResult.Failure)
        assertEquals(request.nonce, repo.pendingRequestFlow.value?.nonce)
        assertFalse(repo.hasAdminReadAccess()); assertTrue(repo.managedProfilesFlow.value.isEmpty())
    }
}
