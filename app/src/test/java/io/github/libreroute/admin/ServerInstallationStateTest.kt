package io.github.libreroute.admin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Base64

class ServerInstallationStateTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private lateinit var keys: AdminKeyManager
    private lateinit var state: InMemoryAdminStorage
    private val node = ServerNode(id = "isolated", name = "Isolated", host = "192.0.2.2",
        privateKeyPath = "/test/ssh.key", hostPublicKey = "ssh-ed25519 pinned", password = "keep")
    private val inventory = ServerInventory(arch = "x86_64", isCompatible = true)

    @Before fun before() { AdminKeyManager.clearFallbackKey(); keys = AdminKeyManager(); state = InMemoryAdminStorage() }

    private inner class Backend : ServerInstallationBackend {
        var installs = 0
        var failAfterCommit = false
        var receipt: JsonObject? = null
        var tamperNonce = false
        var documentPending = false
        var updateMode = false
        var controlProfileId = ""
        override fun plan(node: ServerNode, inventory: ServerInventory, operationId: String) = buildJsonObject {
            put("namespace", "node-isolated"); put("plan_hash", "test-plan-hash"); put("operation_id", operationId)
            if (updateMode) {
                put("mode", "update"); put("server_id", node.id); put("server_public_key", keys.getPublicKeyBase64())
                put("control_profile_id", controlProfileId)
            }
        }
        override fun install(node: ServerNode, inventory: ServerInventory, operationId: String, documentUrl: String,
                             join: AdminEnvelope, pairingCode: String, expectedPlan: JsonObject, ownerRecovery: Boolean): JsonObject {
            installs++
            controlProfileId = join.profileId
            assertEquals("test-plan-hash", expectedPlan["plan_hash"]?.jsonPrimitive?.content)
            val grant = AdminGrant(AdminRole.OWNER, join.profileId, keys.getFingerprint(), keys.getPublicKeyBase64(),
                System.currentTimeMillis(), System.currentTimeMillis() / 1000L + 3600, nonce = if (tamperNonce) "other" else join.nonce,
                serverId = node.id)
            val env = keys.signEnvelope(AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_GRANT, profileId = join.profileId,
                sender = "server", nonce = grant.nonce, exp = grant.expiresAt, payload = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.encodeToString(grant).toByteArray())))
            receipt = buildJsonObject {
                put("operation_id", operationId); put("namespace", "node-isolated"); put("plan_hash", "test-plan-hash")
                put("status", "committed"); put("agent_ready", true); put("ssh_management_ready", true)
                put("control_profile_ready", true); put("document_ready", false)
                put("document_url", if (documentPending) "" else documentUrl)
                if (documentPending) { put("document_status", "pending"); put("requested_document_url", documentUrl) }
                put("server_public_key", grant.serverPublicKey); put("server_id", node.id); put("grant", Json.parseToJsonElement(env.toJson()))
            }
            if (failAfterCommit) error("Connection lost after commit")
            return receipt!!
        }
        override fun status(node: ServerNode, operationId: String) = receipt ?: error("No receipt")
        override fun rollback(node: ServerNode, operationId: String) = buildJsonObject {
            put("operation_id", operationId); put("namespace", "node-isolated"); put("plan_hash", "test-plan-hash"); put("status", "rolled_back")
        }
    }

    private fun repo(backend: Backend, storage: AdminStorage = state) = AdminRepository(keyManager = keys, storage = storage,
        installationBackend = backend, serverInventoryProbe = { inventory })

    @Test fun newOwnerCanInstallBySshWithoutOldGrantOrDocument() {
        val backend = Backend(); val repo = repo(backend)
        repo.addServerNode(node)
        assertEquals(AdminRole.NONE, repo.getRole())
        repo.planServerInstallation(node, "operation-one")
        assertTrue(repo.installServer(node, "operation-one") is AdminOpResult.Success)
        assertEquals(AdminRole.OWNER, repo.getRole(node.id))
        val profile = repo.managedProfilesFlow.value.single()
        assertEquals(ManagedProfile.PROFILE_TYPE_CONTROL, profile.profileType)
        assertEquals("ssh", profile.transportType)
        assertTrue(profile.allowedProtocols.isEmpty())
        assertNull(repo.defaultDocumentUrl(node.id))
        assertEquals(ServerInstallOperation.READY, repo.loadServerInstallation("operation-one")?.stage)
        assertTrue(repo.installServer(node, "operation-one") is AdminOpResult.Failure)
        assertEquals(1, backend.installs)
        assertEquals("keep", repo(backend).getServerNode(node.id)?.password)
    }

    @Test fun missingReceiptKeepsUnknownAndSshWithoutReinstallation() {
        val backend = Backend(); val repo = repo(backend)
        repo.addServerNode(node); repo.planServerInstallation(node, "missing-receipt")
        backend.receipt = buildJsonObject { put("status", "not_found") }
        val result = repo.checkServerInstallation(node, "missing-receipt") as AdminOpResult.Failure
        assertTrue(result.error.contains("нет квитанции"))
        assertEquals(ServerInstallOperation.UNKNOWN, repo.loadServerInstallation("missing-receipt")?.stage)
        assertEquals("keep", repo.getServerNode(node.id)?.password)
        assertEquals(0, backend.installs)
    }

    @Test fun coreUpdateRequiresOwnerAndKeepsTheExistingControlIdentityAndSsh() {
        val backend = Backend(); val repo = repo(backend)
        repo.addServerNode(node)
        assertThrows(IllegalArgumentException::class.java) { repo.planCoreUpdate(node, "unauthorized-update") }
        repo.planServerInstallation(node, "first-install")
        assertTrue(repo.installServer(node, "first-install") is AdminOpResult.Success)
        val original = repo.managedProfilesFlow.value.single()
        backend.updateMode = true
        repo.planCoreUpdate(repo.getServerNode(node.id)!!, "core-update")
        assertEquals(ServerInstallOperation.KIND_CORE_UPDATE, repo.loadServerInstallation("core-update")?.kind)
        assertTrue(repo.installServer(node, "core-update") is AdminOpResult.Success)
        assertEquals(original.id, repo.managedProfilesFlow.value.single().id)
        assertEquals(AdminRole.OWNER, repo.getRole(node.id))
        assertEquals("keep", repo.getServerNode(node.id)?.password)
        assertEquals(keys.getPublicKeyBase64(), repo.getPinnedServerPublicKey(node.id))
    }

    @Test fun lossAfterRemoteCommitReconcilesSameOperationWithoutReinstall() {
        val backend = Backend().apply { failAfterCommit = true }; val repo = repo(backend)
        repo.addServerNode(node); repo.planServerInstallation(node, "operation-two")
        assertTrue(repo.installServer(node, "operation-two") is AdminOpResult.Failure)
        assertEquals(ServerInstallOperation.UNKNOWN, repo.loadServerInstallation("operation-two")?.stage)
        val restarted = repo(backend)
        assertTrue(restarted.checkServerInstallation(node, "operation-two") is AdminOpResult.Success)
        assertTrue(restarted.checkServerInstallation(node, "operation-two") is AdminOpResult.Success)
        assertEquals(1, backend.installs)
        assertEquals(1, restarted.managedProfilesFlow.value.count { it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL })
        assertEquals("keep", restarted.getServerNode(node.id)?.password)
    }

    @Test fun interruptedLocalCommitReplaysDurableReceiptAtStartup() {
        val backend = Backend()
        val failing = object : AdminStorage by state {
            var fail = true
            override fun putString(key: String, value: String?) {
                if (key == "admin_active_grant_isolated" && fail) { fail = false; error("disk write interrupted") }
                state.putString(key, value)
            }
        }
        val repo = repo(backend, failing); repo.addServerNode(node); repo.planServerInstallation(node, "operation-three")
        assertTrue(repo.installServer(node, "operation-three") is AdminOpResult.Failure)
        assertEquals(ServerInstallOperation.SERVER_CONFIRMED, repo.loadServerInstallation("operation-three")?.stage)
        val restarted = repo(backend)
        assertEquals(ServerInstallOperation.READY, restarted.loadServerInstallation("operation-three")?.stage)
        assertEquals(AdminRole.OWNER, restarted.getRole(node.id))
        assertEquals(1, backend.installs)
    }

    @Test fun foreignNonceCannotCreateOwnerAndRollbackPreservesSsh() {
        val backend = Backend().apply { tamperNonce = true }; val repo = repo(backend)
        repo.addServerNode(node); repo.planServerInstallation(node, "operation-four")
        assertTrue(repo.installServer(node, "operation-four") is AdminOpResult.Failure)
        assertFalse(repo.hasAdminAccess())
        assertTrue(repo.rollbackServerInstallation(node, "operation-four") is AdminOpResult.Success)
        assertEquals(ServerInstallOperation.ROLLED_BACK, repo.loadServerInstallation("operation-four")?.stage)
        assertEquals("keep", repo(backend).getServerNode(node.id)?.password)
    }

    @Test fun optionalUnverifiedDocumentCannotBlockConfirmedSshOwnerBootstrap() {
        val backend = Backend().apply { documentPending = true }; val repository = repo(backend)
        repository.addServerNode(node); repository.planServerInstallation(node, "pending-document")
        val document = "https://docs.yandex.ru/edit/d/future-control"
        val result = repository.installServer(node, "pending-document", document)
        assertTrue((result as? AdminOpResult.Failure)?.error, result is AdminOpResult.Success)
        assertEquals(AdminRole.OWNER, repository.getRole(node.id))
        assertEquals(document, repository.loadServerInstallation("pending-document")?.documentUrl)
        assertNull(repository.defaultDocumentUrl(node.id))
        assertEquals("ssh", repository.managedProfilesFlow.value.single().transportType)
    }

    @Test fun rollingBackConfirmedFreshInstallRemovesItsGrantAndKeepsSshAndHistory() {
        val backend = Backend(); val repository = repo(backend)
        repository.addServerNode(node); repository.planServerInstallation(node, "confirmed-rollback")
        assertTrue(repository.installServer(node, "confirmed-rollback") is AdminOpResult.Success)
        assertTrue(repository.rollbackServerInstallation(node, "confirmed-rollback") is AdminOpResult.Success)
        val restarted = repo(backend)
        assertFalse(restarted.hasAdminReadAccess())
        assertEquals("keep", restarted.getServerNode(node.id)?.password)
        assertEquals(ServerInstallOperation.ROLLED_BACK, restarted.loadServerInstallation("confirmed-rollback")?.stage)
        assertEquals(1, restarted.managedProfilesFlow.value.size)
    }
}
