package io.github.libreroute.admin

import io.github.libreroute.data.Tunnel
import io.github.libreroute.data.TunnelStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class ClientEnrollmentRecoveryTest {
    private val json = Json { encodeDefaults = true }
    private lateinit var keys: AdminKeyManager
    private lateinit var storage: InMemoryAdminStorage
    private var calls = 0
    private var loseNextResponse = false
    private val committed = mutableMapOf<String, AdminEnvelope>()
    private val requests = mutableListOf<String>()
    private val store = object : TunnelStore {
        var tunnels = emptyList<Tunnel>()
        var failSave = false
        override fun load() = tunnels
        override fun save(tunnels: List<Tunnel>) { if (failSave) { failSave = false; error("Interrupted local save") }; this.tunnels = tunnels }
        override fun getSelectedId(): Long? = tunnels.firstOrNull()?.id
        override fun setSelectedId(id: Long) = Unit
    }

    @Before fun before() { AdminKeyManager.clearFallbackKey(); keys = AdminKeyManager(); storage = InMemoryAdminStorage(); calls = 0; store.tunnels = emptyList(); committed.clear(); requests.clear(); loseNextResponse = false }

    private fun signedResponse(profile: String, operation: String, data: JsonObject): AdminEnvelope {
        val payload = buildJsonObject { put("op_id", operation); put("status", "applied"); put("data", data) }
        return keys.signEnvelope(AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_RESPONSE, profileId = profile,
            opId = operation, sender = "server", exp = System.currentTimeMillis() / 1000 + 600,
            payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toString().toByteArray())))
    }

    private fun invitation(): String {
        val data = buildJsonObject {
            put("enrollment_id", "one-time"); put("token", "x".repeat(43)); put("control_profile_id", "control-one")
            put("document_url", "https://docs.yandex.ru/edit/d/enrollment-control"); put("server_public_key", keys.getPublicKeyBase64())
            put("expires_at", System.currentTimeMillis() / 1000 + 600)
        }
        return BootstrapInvitation.toLink(signedResponse("control-one", "issued", data))
    }

    private fun repo(state: AdminStorage = storage) = AdminRepository(keyManager = keys, storage = state, customTunnelStore = store,
        documentSyncRunner = { _, _, _, request, _ ->
            calls++
            requests += request.toJson()
            committed[request.opId] ?: run {
            val requestBody = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(request.payload))).jsonObject
            val recipient = requestBody.getValue("public_key").jsonPrimitive.content
            val crypto = RecipientInvitationCrypto(object : InvitationStateStore {
                override fun get(key: String) = storage.getString("recipient_identity_$key", null)
                override fun put(key: String, value: String) = storage.putString("recipient_identity_$key", value)
            })
            val now = System.currentTimeMillis() / 1000
            val plaintext = buildJsonObject {
                put("invitation_id", request.opId); put("profile_id", "package-one"); put("name", "My access")
                put("url", "https://docs.yandex.ru/edit/d/enrollment-control"); put("secret_key", "server-secret-material-123456")
                put("revision", 1); put("issued_time", now); put("expires_at", now + 600)
                put("recipient_pub_sha256", crypto.publicKeyHashHex()); put("device_id", crypto.publicKeyHashHex()); put("user_id", "user-one")
                put("routes", json.encodeToJsonElement(listOf(RouteCandidate(route_id = "mqtt-one", server_id = "server-one",
                    transport = "mqtt", name = "MQTT access", url = "wss://broker.example/mqtt#isolated",
                    secret_key = "server-secret-material-123456"))))
            }.toString()
            val (ephemeral, nonce, encrypted) = RecipientInvitationCrypto.encryptForDevice(recipient, plaintext.toByteArray())
            val response = signedResponse(request.profileId, request.opId, buildJsonObject {
                put("profile_id", "package-one"); put("invitation_id", request.opId); put("expires_at", now + 600)
                put("revision", 1); put("ephemeral_pub", ephemeral); put("nonce", nonce); put("encrypted_payload", encrypted)
            })
            committed[request.opId] = response
            if (loseNextResponse) { loseNextResponse = false; error("Connection lost after server commit") }
            response
            }
        })

    @Test fun ordinaryCleanDeviceEnrollsThroughDocumentWithoutSshOrAdminRoleAndReplayIsLocal() {
        val link = invitation(); val repository = repo()
        assertEquals(AdminRole.NONE, repository.getRole()); assertTrue(repository.getServerNodes().isEmpty())
        val first = repository.redeemInvitation(link)
        assertTrue((first.first as? AdminOpResult.Failure)?.error, first.first is AdminOpResult.Success)
        val ids = store.tunnels.map { it.id }; assertEquals(1, ids.size); assertEquals("mqtt", store.tunnels.single().transportType)
        assertFalse(repository.hasAdminAccess()); assertTrue(repository.getServerNodes().isEmpty())
        val replay = repo().redeemInvitation(link)
        assertTrue(replay.first is AdminOpResult.Success); assertEquals(ids, store.tunnels.map { it.id }); assertEquals(1, calls)
    }

    @Test fun restartBetweenReceiptAndLocalProfileSaveReusesReceiptAndStableIds() {
        val link = invitation(); store.failSave = true
        assertTrue(repo().redeemInvitation(link).first is AdminOpResult.Failure)
        assertTrue(store.tunnels.isEmpty())
        assertTrue(repo().redeemInvitation(link).first is AdminOpResult.Success)
        val ids = store.tunnels.map { it.id }
        assertTrue(repo().redeemInvitation(link).first is AdminOpResult.Success)
        assertEquals(ids, store.tunnels.map { it.id }); assertEquals(1, calls)
    }

    @Test fun completedInviteCannotResurrectRouteRemovedByLaterSynchronization() {
        val link = invitation(); assertTrue(repo().redeemInvitation(link).first is AdminOpResult.Success)
        store.tunnels = emptyList()
        val replay = repo().redeemInvitation(link)
        assertTrue(replay.first is AdminOpResult.Success); assertNull(replay.second); assertTrue(store.tunnels.isEmpty()); assertEquals(1, calls)
    }

    @Test fun lostNetworkResponseAfterServerCommitReplaysExactlySameSignedRequest() {
        val link = invitation(); loseNextResponse = true
        assertTrue(repo().redeemInvitation(link).first is AdminOpResult.Failure)
        assertTrue(store.tunnels.isEmpty()); assertEquals(1, committed.size)
        val restarted = repo()
        assertTrue(restarted.redeemInvitation(link).first is AdminOpResult.Success)
        assertEquals(2, calls); assertEquals(requests.first(), requests.last())
        val stableIds = store.tunnels.map { it.id }
        assertTrue(repo().redeemInvitation(link).first is AdminOpResult.Success)
        assertEquals(stableIds, store.tunnels.map { it.id }); assertEquals(1, stableIds.size); assertEquals(2, calls)
    }

    @Test fun restartAfterProfileSaveBeforeOwnershipMarkerDoesNotDuplicateTheRoute() {
        val link = invitation()
        val failing = object : AdminStorage by storage {
            var fail = true
            override fun putString(key: String, value: String?) {
                if (key.endsWith("sync_managed_tunnel_ids") && fail) { fail = false; error("Ownership write interrupted") }
                storage.putString(key, value)
            }
        }
        assertTrue(repo(failing).redeemInvitation(link).first is AdminOpResult.Failure)
        val savedIds = store.tunnels.map { it.id }; assertEquals(1, savedIds.size)
        assertTrue(repo().redeemInvitation(link).first is AdminOpResult.Success)
        assertEquals(savedIds, store.tunnels.map { it.id }); assertEquals(1, calls)
    }
}
