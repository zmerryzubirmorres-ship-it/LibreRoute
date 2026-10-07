package io.github.libreroute.admin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AdminRepositoryV2Test {
    private lateinit var keys: AdminKeyManager
    private lateinit var storage: InMemoryAdminStorage
    private lateinit var repository: AdminRepository
    private var lastExecutedEnvelope: AdminEnvelope? = null
    private var nextResponseData: String = "{}"
    private var coreInfo: CoreCompatibility? = CoreCompatibility("1.2.5", "a".repeat(64), 1,
        CoreCompatibility.REQUIRED_CAPABILITIES.toList())

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before fun setUp() {
        AdminKeyManager.clearFallbackKey()
        keys = AdminKeyManager()
        storage = InMemoryAdminStorage()
        repository = createRepo()
    }

    @Test fun serviceRouteRetainsSignedFramingAndRequiresKey() {
        val route = RouteCandidate(route_id = "mqtt-phone", transport = "mqtt",
            url = "wss://broker.example/mqtt#phone", secret_key = "distinct encryption secret 12345",
            codec = "legacy", encryption_layout = "batch",
            parameters = mapOf("session_batch_bytes" to "8192"))
        val tunnel = repository.routeCandidateToTunnel(route)
        assertEquals("mqtt", tunnel.transportType)
        assertTrue(tunnel.transportConnPayload.contains("--codec=legacy"))
        assertTrue(tunnel.transportConnPayload.contains("--encryption-layout=batch"))
        assertTrue(tunnel.transportConnPayload.contains("--peer-health"))
        assertTrue(tunnel.transportConnPayload.contains("--session-batch-bytes=8192"))
        assertEquals(route.secret_key, tunnel.encryptionKey)
        assertTrue(runCatching { repository.routeCandidateToTunnel(route.copy(secret_key = "")) }.isFailure)
        assertTrue(runCatching { repository.routeCandidateToTunnel(route.copy(url = "ws://broker.example/mqtt#phone")) }.isFailure)
    }

    @Test fun protocolWorkerNamesAcceptAnIpOrRussianServerDisplayName() {
        grantRole("srv-1", AdminRole.OWNER)
        val node = repository.getServerNode("srv-1")!!.copy(name = "Москва — 89.167.49.64")
        repository.updateServerNode(node)
        for ((transport, endpoint) in listOf("vyandex" to "https://docs.yandex.ru/edit/d/test", "mqtt" to "", "jitsi" to "")) {
            val result = repository.installProtocol(node, transport, endpoint)
            assertTrue((result as? AdminOpResult.Failure)?.error, result is AdminOpResult.Success)
            val command = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(lastExecutedEnvelope!!.payload), Charsets.UTF_8)).jsonObject
            val workerName = command.getValue("name").jsonPrimitive.content
            assertTrue(Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}").matches(workerName))
            assertEquals(command.getValue("profile_id").jsonPrimitive.content, workerName)
            val operation = repository.latestProtocolInstallation(node.id, transport)!!
            assertEquals(ProtocolInstallationOperation.READY, operation.stage)
            assertEquals("${transport.uppercase()} — ${node.name}", repository.managedProfilesFlow.value.first { it.id == operation.profileId }.name)
        }
    }

    @Test fun provisionedYandexTunnelRetainsSignedNegotiationAndEncryptionSettings() {
        val profile = ManagedProfile(id = "new-yandex", name = "Yandex — Москва", transportType = "vyandex")
        val body = buildJsonObject {
            put("data", buildJsonObject {
                put("profile_id", profile.id)
                put("transport", "vyandex")
                put("endpoint", "https://docs.yandex.ru/edit/d/new-yandex")
                put("secret_key", "test encryption secret 12345")
                put("parameters", buildJsonObject {
                    put("codec", "batched")
                    put("encryption_layout", "batch")
                    put("session_negotiate", "true")
                    put("session_batch_bytes", "8192")
                })
            })
        }
        val response = AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_RESPONSE, profileId = profile.id, sender = "server", payload = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().toByteArray()))
        val tunnel = repository.provisionedTunnel(profile, response)
        assertEquals(profile.name, tunnel.name)
        assertTrue(tunnel.transportConnPayload.contains("--encryption-layout=batch"))
        assertTrue(tunnel.transportConnPayload.contains("--session-negotiate"))
        assertTrue(tunnel.transportConnPayload.contains("--session-batch-bytes=8192"))
        assertEquals("test encryption secret 12345", tunnel.encryptionKey)
    }

    @Test fun oldCoreBlocksProtocolAndAuthBeforeMutation() {
        grantRole("srv-1", AdminRole.OWNER)
        coreInfo = null
        val node = repository.getServerNode("srv-1")!!
        val result = repository.installProtocol(node, "vyandex", "https://docs.yandex.ru/edit/d/test")
        assertTrue(result is AdminOpResult.Failure)
        assertTrue((result as AdminOpResult.Failure).error.contains("Core несовместим"))
        assertNull(repository.latestProtocolInstallation(node.id, "vyandex"))
        assertTrue(repository.beginAuthSession(node.id, "profile-1", serverNode = node).isFailure)
        val command = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(lastExecutedEnvelope!!.payload))).jsonObject
        assertEquals("get-cluster-snapshot", command.getValue("op").jsonPrimitive.content)
    }

    @Test fun signedCoreVersionPersistsPerServerAndFailedRefreshKeepsLastVerifiedVersion() {
        grantRole("srv-1", AdminRole.OWNER)
        val node = repository.getServerNode("srv-1")!!
        assertTrue(repository.refreshServerCore(node).isSuccess)
        val observed = repository.getServerNode(node.id)!!.coreObservation
        assertEquals(coreInfo, observed.info)
        assertTrue(observed.checkedAt > 0L)
        assertEquals(observed, createRepo().getServerNode(node.id)!!.coreObservation)
        val corrupted = AdminClusterSnapshot(authorityId = keys.getPublicKeyBase64(), snapshotRevision = 5L,
            coreInfo = coreInfo?.copy(version = "99.0.0"), snapshotHash = "invalid")
        nextResponseData = json.encodeToString(corrupted)
        assertTrue(repository.refreshServerCore(node).isFailure)
        val retained = repository.getServerNode(node.id)!!.coreObservation
        assertEquals(observed.info, retained.info)
        assertEquals(observed.checkedAt, retained.checkedAt)
        assertNotNull(retained.error)
    }

    private fun createRepo(): AdminRepository {
        return AdminRepository(
            context = null,
            keyManager = keys,
            sshClient = AdminSshClient(
                context = AdminRepository.createDummyContext(),
                networkChecker = { true },
                commandExecutor = { _, request ->
                    val command = AdminEnvelope.fromJson(json.parseToJsonElement(request).jsonObject["envelope"].toString())!!
                    lastExecutedEnvelope = command
                    val requestOp = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(command.payload), Charsets.UTF_8)).jsonObject["op"]?.jsonPrimitive?.content
                    val autoSnapshot = requestOp == "get-cluster-snapshot" && !json.parseToJsonElement(nextResponseData).let { it is kotlinx.serialization.json.JsonObject && it.containsKey("snapshot_revision") }
                    val snapshot = AdminClusterSnapshot(authorityId = keys.getPublicKeyBase64(), snapshotRevision = 5L,
                        coreInfo = coreInfo)
                    val data = if (autoSnapshot) json.encodeToString(snapshot.copy(snapshotHash = computeClusterSnapshotHash(snapshot))) else nextResponseData
                    val body = buildJsonObject {
                        put("op_id", command.opId)
                        put("status", "applied")
                        put("timestamp", System.currentTimeMillis() / 1000L)
                        put("data", json.parseToJsonElement(data))
                    }
                    val payloadB64 = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(body.toString().toByteArray(Charsets.UTF_8))
                    val respEnv = keys.signEnvelope(
                        AdminEnvelope(
                            subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                            profileId = command.profileId,
                            opId = command.opId,
                            rev = command.rev + 1L,
                            sender = "server",
                            payload = payloadB64
                        )
                    )
                    Pair(0, respEnv.toJson())
                }
            ),
            storage = storage,
            socketProbe = { _, _, _ -> true }
        )
    }

    private fun grantOwner() {
        val request = repository.createJoinRequest("profile-1", AdminRole.OWNER).first
        val serverKey = keys.getPublicKeyBase64()
        repository.setPinnedServerPublicKey(serverKey)
        val expiry = System.currentTimeMillis() / 1000L + 3600L
        val grant = AdminGrant(
            role = AdminRole.OWNER,
            profileId = request.profileId,
            adminFingerprint = keys.getFingerprint(),
            serverPublicKey = serverKey,
            grantedAt = System.currentTimeMillis(),
            expiresAt = expiry,
            revision = 1L,
            nonce = request.nonce
        )
        val envelope = AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_GRANT,
            profileId = request.profileId,
            rev = 1L,
            exp = expiry,
            sender = "server",
            nonce = request.nonce,
            payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(grant).toByteArray(Charsets.UTF_8))
        )
        val signedGrant = keys.signEnvelope(envelope)
        val applyResult = repository.applyAdminGrant(signedGrant, serverKey)
        assertTrue(applyResult is AdminOpResult.Success)
        repository.addServerNode(ServerNode(
            id = "srv-1",
            name = "TestServer",
            host = "10.0.0.1",
            hostPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestKey",
            privateKeyPath = "/path/to/key"
        ))
        assertEquals(AdminRole.OWNER, repository.getRole())
    }

    private fun grantRole(serverId: String, role: AdminRole, scope: List<String> = listOf(serverId)) {
        repository.addServerNode(ServerNode(id = serverId, name = serverId, host = "192.0.2.4",
            hostPublicKey = "ssh-ed25519 pinned", privateKeyPath = "/test/key"))
        val request = repository.createJoinRequest("control-$serverId", role).first
        val expiry = System.currentTimeMillis() / 1000 + 3600
        val grant = AdminGrant(role, request.profileId, keys.getFingerprint(), keys.getPublicKeyBase64(),
            System.currentTimeMillis(), expiry, scope, nonce = request.nonce, serverId = serverId)
        repository.setPinnedServerPublicKey(keys.getPublicKeyBase64(), serverId)
        val envelope = keys.signEnvelope(AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_GRANT, profileId = request.profileId,
            exp = expiry, nonce = request.nonce, sender = "server", payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(grant).toByteArray())))
        assertTrue(repository.applyAdminGrant(envelope, grant.serverPublicKey) is AdminOpResult.Success)
    }

    @Test fun viewerCanReadItsServerButCannotMutateOrUseAnotherServerOwner() {
        grantRole("owned", AdminRole.OWNER)
        grantRole("observed", AdminRole.VIEWER)
        assertTrue(repository.hasAdminReadAccess())
        assertTrue(repository.canPerform("owned", "create-user"))
        assertTrue(repository.canPerform("observed", "list-users"))
        assertFalse(repository.canPerform("observed", "create-user"))
        assertFalse(repository.canPerform("missing", "list-users"))
        assertFalse(repository.canPerform(null, "create-user"))
        lastExecutedEnvelope = null
        assertTrue(repository.createUser("Blocked", AdminChannel.DIRECT_SSH, repository.getServerNode("observed")).isFailure)
        assertNull(lastExecutedEnvelope)
        nextResponseData = "[]"
        assertTrue(repository.listUsers(AdminChannel.DIRECT_SSH, repository.getServerNode("observed")).isSuccess)
        assertNotNull(lastExecutedEnvelope)
        val viewerProfile = repository.managedProfilesFlow.value.first { it.serverId == "observed" }
        assertEquals(AdminRole.VIEWER, viewerProfile.role)
    }

    @Test fun removingOneScopedGrantPreservesOtherServerAndSshContexts() {
        grantRole("owned", AdminRole.OWNER)
        grantRole("operated", AdminRole.OPERATOR)
        repository.removeLocalAdminGrant("operated")
        val restored = createRepo()
        assertEquals(AdminAccessState.REVOKED, restored.getServerAccess("operated").state)
        assertFalse(restored.canPerform("operated", "create-user"))
        assertTrue(restored.canPerform("owned", "create-user"))
        assertNotNull(restored.getServerNode("operated")?.hostPublicKey)
        assertEquals("/test/key", restored.getServerNode("operated")?.privateKeyPath)
    }

    @Test fun confirmedSnapshotsSurviveRestartWithoutBeingCurrentCapabilities() {
        grantOwner()
        val node = repository.getServerNode("srv-1")!!
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isSuccess)
        val restored = createRepo()
        assertEquals(5L, restored.cachedClusterSnapshot("srv-1")?.snapshotRevision)
        restored.revokeAdminRights()
        assertFalse(restored.hasAdminAccess())
        assertEquals(5L, restored.cachedClusterSnapshot("srv-1")?.snapshotRevision)
    }

    @Test fun bootstrapIssuerReceivesTheValidatedServerEnvelopeForQr() {
        grantOwner()
        nextResponseData = buildJsonObject {
            put("enrollment_id", "enrollment-one"); put("token", "a".repeat(43)); put("control_profile_id", "profile-1")
            put("document_url", "https://docs.yandex.ru/edit/d/dedicated-control"); put("server_public_key", keys.getPublicKeyBase64())
            put("expires_at", System.currentTimeMillis() / 1000 + 600)
        }.toString()
        val issued = repository.issueBootstrapInvitation("user-one", listOf(EnrollmentRoute("srv-1")),
            AdminChannel.DIRECT_SSH, repository.getServerNode("srv-1"))
        assertTrue(issued.exceptionOrNull()?.message, issued.isSuccess)
        assertEquals("enrollment-one", BootstrapInvitation.parse(issued.getOrThrow(), keys).enrollmentId)
    }

    @Test fun testV2ModelsSerialization() {
        val user = AdminUser(id = "u-1", name = "Alice", status = "active")
        assertEquals(true, user.isActive)
        assertEquals(false, user.isSuspended)

        val dev = AdminDevice(id = "d-1", userId = "u-1", publicKey = "pub123", fingerprint = "fp123", status = "active")
        assertEquals(true, dev.isActive)
        assertEquals(false, dev.isRevoked)

        val assign = AdminAssignment(
            id = "a-1",
            userId = "u-1",
            userName = "Alice",
            deviceId = "d-1",
            profileInstanceId = "prof-1",
            serverId = "srv-1",
            transport = "vyandex",
            status = "ready",
            revision = 1L
        )
        assertEquals(false, assign.isActive)
        val activeAssign = assign.copy(status = "active")
        assertEquals(true, activeAssign.isActive)

        val inv = AdminInvitation(
            id = "inv-1",
            assignmentId = "a-1",
            userId = "u-1",
            deviceId = "d-1",
            status = "issued",
            revision = 1L
        )
        assertEquals(true, inv.isIssued)
        assertEquals(false, inv.isRedeemed)
    }

    @Test fun testDeviceAndDocumentFieldsMatchGoCommandPayload() {
        val device = json.parseToJsonElement(json.encodeToString(
            RegisterDevicePayload(userId = "u-1", publicKey = "device-key")
        )).jsonObject
        assertEquals("device-key", device["device_pub"]?.toString()?.trim('"'))
        assertFalse(device.containsKey("public_key"))

        val assignment = json.parseToJsonElement(json.encodeToString(
            AssignProfilePayload(userId = "u-1", deviceId = "d-1", serverId = "vds89-test",
                documentUrl = "https://disk.yandex.ru/i/example")
        )).jsonObject
        assertEquals("https://disk.yandex.ru/i/example", assignment["url"]?.toString()?.trim('"'))
        assertFalse(assignment.containsKey("document_url"))
    }

    @Test fun testUnauthorizedRejection() {
        // Without grant, repository has Role.NONE
        val res = repository.createUser("Alice", AdminChannel.DIRECT_SSH)
        assertTrue(res.isFailure)
        assertTrue(res.exceptionOrNull()?.message?.contains("target_server_id") == true)
    }

    @Test fun testCreateUserSuccess() {
        grantOwner()
        val testServer = repository.serverNodesFlow.value.first()
        nextResponseData = """{"id":"user-101","name":"Alice","status":"active"}"""
        val result = repository.createUser("Alice", AdminChannel.DIRECT_SSH, serverNode = testServer)
        assertTrue(result.isSuccess)
        val user = result.getOrThrow()
        assertEquals("user-101", user.id)
        assertEquals("Alice", user.name)
        assertEquals(true, user.isActive)
    }

    @Test fun testSuspendUserSuccess() {
        grantOwner()
        val testServer = repository.serverNodesFlow.value.first()
        nextResponseData = """{"user_id":"user-101","status":"suspended"}"""
        val result = repository.suspendUser("user-101", AdminChannel.DIRECT_SSH, serverNode = testServer)
        assertTrue(result.isSuccess)
    }

    @Test fun testRegisterAndRevokeDeviceSuccess() {
        grantOwner()
        val testServer = repository.serverNodesFlow.value.first()
        nextResponseData = """{"id":"dev-505","user_id":"user-101","public_key":"base64pub","fingerprint":"fp505","status":"active"}"""
        val devResult = repository.registerDevice("user-101", "base64pub", AdminChannel.DIRECT_SSH, serverNode = testServer)
        assertTrue(devResult.isSuccess)
        assertEquals("dev-505", devResult.getOrThrow().id)

        nextResponseData = """{"device_id":"dev-505","status":"revoked"}"""
        val revokeResult = repository.revokeDevice("dev-505", AdminChannel.DIRECT_SSH, serverNode = testServer)
        assertTrue(revokeResult.isSuccess)
    }

    @Test fun testAssignProfileAndRevokeSuccess() {
        grantOwner()
        val testServer = repository.serverNodesFlow.value.first()
        nextResponseData = """{
            "id": "assign-1",
            "user_id": "user-101",
            "device_id": "dev-505",
            "profile_instance_id": "prof-1",
            "server_id": "srv-1",
            "transport": "vyandex",
            "status": "ready",
            "revision": 1
        }"""
        val assignResult = repository.assignProfile(
            userId = "user-101",
            deviceId = "dev-505",
            serverId = "srv-1",
            transport = "vyandex",
            channel = AdminChannel.DIRECT_SSH,
            serverNode = testServer
        )
        assertTrue(assignResult.isSuccess)
        assertEquals("assign-1", assignResult.getOrThrow().id)

        nextResponseData = """{"assignment_id":"assign-1","status":"revoked"}"""
        val revokeResult = repository.revokeAssignment("assign-1", AdminChannel.DIRECT_SSH, serverNode = testServer)
        assertTrue(revokeResult.isSuccess)
    }

    @Test fun testIssueAssignmentInvitationSuccess() {
        grantOwner()
        val testServer = repository.serverNodesFlow.value.first()
        nextResponseData = """{
            "invitation_id": "inv-999",
            "assignment_id": "assign-1",
            "profile_id": "prof-1",
            "expires_at": 1700000900,
            "revision": 1,
            "recipient_pub": "pubkey",
            "ephemeral_pub": "ephpub",
            "nonce": "nonce12",
            "encrypted_payload": "ciphertext"
        }"""

        val (res, link) = repository.issueAssignmentInvitation("assign-1", AdminChannel.DIRECT_SSH, serverNode = testServer)
        assertTrue(res is AdminOpResult.Success)
        assertTrue(link.startsWith("libreroute://invitation?data="))
    }

    @Test fun testDeviceKeyGenerationAndFingerprint() {
        val pubKey = repository.getRecipientDevicePublicKey()
        assertNotNull(pubKey)
        assertTrue(pubKey.isNotBlank())

        val fp = repository.getRecipientDeviceFingerprint()
        assertEquals(64, fp.length) // SHA-256 hex is 64 characters
    }

    @Test fun testRedeemInvitationWithoutPinnedKeyFails() {
        // Pinned key is NOT set
        val fakeLink = "libreroute://invitation?data=eyJwYXlsb2FkIjoiIn0="
        val (res, tunnel) = repository.redeemInvitation(fakeLink)
        assertTrue(res is AdminOpResult.Failure)
        assertNull(tunnel)
        assertTrue((res as AdminOpResult.Failure).error.contains("не закреплён"))
    }

    @Test fun testRedeemCandidateDecryptionAndSignatureVerification() {
        val recipientCrypto = RecipientInvitationCrypto(InMemoryInvitationStateStore())
        val recipientPub = recipientCrypto.getOrCreatePublicKeyBase64()

        val serverKey = keys.getPublicKeyBase64()
        repository.setPinnedServerPublicKey(serverKey)

        val invitationHandler = ServerIssuedInvitation(recipientCrypto, keys)

        val nowSec = System.currentTimeMillis() / 1000L
        val expiresAt = nowSec + 900L

        // 1. Prepare invite payload and encrypt for recipient
        val invitePayload = """{
            "invitation_id": "inv-test-1",
            "assignment_id": "assign-test-1",
            "user_id": "user-test-1",
            "device_id": "device-test-1",
            "profile_id": "prof-test-1",
            "name": "TestProfile",
            "url": "https://disk.yandex.ru/i/test-doc",
            "secret_key": "secretKeyForTestProfile123",
            "transport": "vyandex",
            "server_id": "vds89-test",
            "parameters": {"transport": "vyandex"},
            "revision": 1,
            "issued_time": $nowSec,
            "expires_at": $expiresAt,
            "recipient_pub_sha256": "${recipientCrypto.publicKeyHashHex()}"
        }"""

        val (ephPub, nonce, ct) = RecipientInvitationCrypto.encryptForDevice(
            recipientPub,
            invitePayload.toByteArray(Charsets.UTF_8)
        )

        // 2. Build signed server response envelope
        val responseBody = buildJsonObject {
            put("op_id", "inv-test-1")
            put("status", "applied")
            put("data", buildJsonObject {
                put("invitation_id", "inv-test-1")
                put("assignment_id", "assign-test-1")
                put("profile_id", "prof-test-1")
                put("expires_at", expiresAt)
                put("revision", 1L)
                put("recipient_pub", recipientPub)
                put("ephemeral_pub", ephPub)
                put("nonce", nonce)
                put("encrypted_payload", ct)
            })
            put("timestamp", nowSec)
        }

        val respEnv = keys.signEnvelope(AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_RESPONSE,
            profileId = "prof-test-1",
            opId = "inv-test-1",
            rev = 1L,
            exp = expiresAt,
            sender = "server",
            payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(responseBody.toString().toByteArray(Charsets.UTF_8))
        ))

        val link = ServerIssuedInvitation.toLink(respEnv)

        // 3. Verify candidate decryption succeeds with correct recipient crypto
        val candidate = invitationHandler.verifyCandidate(link, serverKey)
        assertEquals("inv-test-1", candidate.invitationId)
        assertEquals("prof-test-1", candidate.profileId)
        assertEquals("TestProfile", candidate.name)
        assertEquals("secretKeyForTestProfile123", candidate.secretKey)

        // 4. Verify candidate decryption fails with different recipient crypto (wrong key)
        val otherCrypto = RecipientInvitationCrypto(InMemoryInvitationStateStore())
        val otherHandler = ServerIssuedInvitation(otherCrypto, keys)
        try {
            otherHandler.verifyCandidate(link, serverKey)
            fail("Expected decryption failure with wrong recipient key")
        } catch (e: Exception) {
            // expected
        }

        // 5. Verify signature verification fails if tampered
        val tamperedEnv = respEnv.copy(payload = respEnv.payload + "bad")
        val tamperedLink = ServerIssuedInvitation.toLink(tamperedEnv)
        try {
            invitationHandler.verifyCandidate(tamperedLink, serverKey)
            fail("Expected signature failure on tampered invitation")
        } catch (e: Exception) {
            // expected
        }
    }

    @Test
    fun testSyncRoutesUpdatedWithEmptyRoutesStopsActiveManagedTunnelAndPreservesManual() {
        var vpnStopped = false
        val manualTunnel = io.github.libreroute.data.Tunnel(
            id = 100L,
            name = "ManualHome",
            transportType = "vyandex",
            transportConnPayload = listOf("--url", "https://docs.yandex.ru/edit/d/manual"),
            encryptionKey = "secretManual12345"
        )
        val managedTunnel1 = io.github.libreroute.data.Tunnel(
            id = 201L,
            name = "ManagedVds",
            transportType = "vyandex",
            transportConnPayload = listOf("--url", "https://docs.yandex.ru/edit/d/vds"),
            encryptionKey = "secretVds1234567"
        )
        val managedTunnel2 = io.github.libreroute.data.Tunnel(
            id = 202L,
            name = "ManagedRouter",
            transportType = "vyandex",
            transportConnPayload = listOf("--url", "https://docs.yandex.ru/edit/d/router"),
            encryptionKey = "secretRouter1234"
        )

        val store = InMemoryTestTunnelStore(listOf(manualTunnel, managedTunnel1, managedTunnel2))
        store.setSelectedId(201L) // Managed tunnel 1 is active

        storage.putString("sync_device_id", "d_test_123")
        storage.putString("sync_user_id", "u_test_123")
        storage.putString("sync_profile_id", "prof-test-1")
        storage.putString("sync_doc_url", "https://docs.yandex.ru/edit/d/vds")
        storage.putString("sync_secret_key", "secretVds1234567")
        storage.putLong("sync_revision", 5L)
        val serverKey = keys.getPublicKeyBase64()
        storage.putString("sync_trusted_server_key", serverKey)
        storage.putString("sync_managed_tunnel_ids", "201,202")

        val repo = AdminRepository(
            context = null,
            keyManager = keys,
            storage = storage,
            customTunnelStore = store,
            vpnStopper = { vpnStopped = true },
            documentSyncRunner = { _, profileId, _, env, _ ->
                val body = buildJsonObject {
                    put("op_id", env.opId)
                    put("status", "applied")
                    put("timestamp", System.currentTimeMillis() / 1000L)
                    put("data", buildJsonObject {
                        put("status", "updated")
                        put("revision", 6L)
                        put("device_id", "d_test_123")
                        put("routes", kotlinx.serialization.json.JsonArray(emptyList()))
                    })
                }
                val payloadB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(body.toString().toByteArray(Charsets.UTF_8))
                keys.signEnvelope(AdminEnvelope(
                    subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                    profileId = profileId,
                    opId = env.opId,
                    rev = env.rev + 1L,
                    sender = "server",
                    payload = payloadB64
                ))
            }
        )

        val result = repo.syncRoutes(force = true)
        assertTrue("Expected Updated result, got $result", result is RouteSyncResult.Updated)
        assertEquals(6L, (result as RouteSyncResult.Updated).newRevision)
        assertTrue("Active managed tunnel MUST be stopped when routes become empty", vpnStopped)

        val remainingTunnels = store.load()
        assertEquals(1, remainingTunnels.size)
        assertEquals("Manual tunnel must be preserved", 100L, remainingTunnels.first().id)
        assertEquals("ManualHome", remainingTunnels.first().name)
        assertEquals(6L, storage.getLong("sync_revision", 0L))
        assertEquals("", storage.getString("sync_managed_tunnel_ids", null))
    }

    @Test
    fun testSyncRoutesRevokedStopsActiveManagedTunnelRemovesManagedTunnelsAndClearsEnrolledState() {
        var vpnStopped = false
        val manualTunnel = io.github.libreroute.data.Tunnel(
            id = 100L,
            name = "ManualHome",
            transportType = "vyandex",
            transportConnPayload = listOf("--url", "https://docs.yandex.ru/edit/d/manual"),
            encryptionKey = "secretManual12345"
        )
        val managedTunnel = io.github.libreroute.data.Tunnel(
            id = 201L,
            name = "ManagedVds",
            transportType = "vyandex",
            transportConnPayload = listOf("--url", "https://docs.yandex.ru/edit/d/vds"),
            encryptionKey = "secretVds1234567"
        )

        val store = InMemoryTestTunnelStore(listOf(manualTunnel, managedTunnel))
        store.setSelectedId(201L)

        storage.putString("sync_device_id", "d_test_123")
        storage.putString("sync_user_id", "u_test_123")
        storage.putString("sync_profile_id", "prof-test-1")
        storage.putString("sync_doc_url", "https://docs.yandex.ru/edit/d/vds")
        storage.putString("sync_secret_key", "secretVds1234567")
        storage.putLong("sync_revision", 5L)
        val serverKey = keys.getPublicKeyBase64()
        storage.putString("sync_trusted_server_key", serverKey)
        storage.putString("sync_managed_tunnel_ids", "201")

        val repo = AdminRepository(
            context = null,
            keyManager = keys,
            storage = storage,
            customTunnelStore = store,
            vpnStopper = { vpnStopped = true },
            documentSyncRunner = { _, profileId, _, env, _ ->
                val body = buildJsonObject {
                    put("op_id", env.opId)
                    put("status", "applied")
                    put("timestamp", System.currentTimeMillis() / 1000L)
                    put("data", buildJsonObject {
                        put("status", "revoked")
                        put("device_id", "d_test_123")
                    })
                }
                val payloadB64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(body.toString().toByteArray(Charsets.UTF_8))
                keys.signEnvelope(AdminEnvelope(
                    subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                    profileId = profileId,
                    opId = env.opId,
                    rev = env.rev + 1L,
                    sender = "server",
                    payload = payloadB64
                ))
            }
        )

        val result = repo.syncRoutes(force = true)
        assertEquals(RouteSyncResult.Revoked, result)
        assertTrue("Active managed tunnel must be stopped on revocation", vpnStopped)

        val remainingTunnels = store.load()
        assertEquals(1, remainingTunnels.size)
        assertEquals(100L, remainingTunnels.first().id)
        assertFalse("Enrollment state must be cleared", repo.isEnrolledInRouteSync())

        // Repeat call is safe and fails gracefully
        val repeatResult = repo.syncRoutes(force = true)
        assertTrue(repeatResult is RouteSyncResult.Failed)
    }

    @Test fun testSshCommandWithoutTargetServerFails() {
        grantOwner()
        val result = repository.createUser("Alice", AdminChannel.DIRECT_SSH)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("target_server_id") == true)

        val (invResult, link) = repository.issueAssignmentInvitation("assign-1", AdminChannel.DIRECT_SSH)
        assertTrue(invResult is AdminOpResult.Failure)
        assertTrue((invResult as AdminOpResult.Failure).error.contains("target_server_id"))
        assertTrue(link.isEmpty())
    }

    @Test fun testTwoServersWithDifferentKeys() {
        val serverKey1 = "serverKey1_base64_ed25519"
        val serverKey2 = "serverKey2_base64_ed25519"

        repository.setPinnedServerPublicKey(serverKey1, "srv-1")
        repository.setPinnedServerPublicKey(serverKey2, "srv-2")

        assertEquals(serverKey1, repository.getPinnedServerPublicKey("srv-1"))
        assertEquals(serverKey2, repository.getPinnedServerPublicKey("srv-2"))
        assertNotEquals(repository.getPinnedServerPublicKey("srv-1"), repository.getPinnedServerPublicKey("srv-2"))

        val grant1 = AdminGrant(
            role = AdminRole.OWNER,
            profileId = "prof-srv1",
            adminFingerprint = keys.getFingerprint(),
            serverPublicKey = serverKey1,
            grantedAt = 1000L,
            expiresAt = 5000L,
            revision = 1L,
            nonce = "n1",
            serverId = "srv-1"
        )
        val grant2 = AdminGrant(
            role = AdminRole.OPERATOR,
            profileId = "prof-srv2",
            adminFingerprint = keys.getFingerprint(),
            serverPublicKey = serverKey2,
            grantedAt = 2000L,
            expiresAt = 6000L,
            revision = 2L,
            nonce = "n2",
            serverId = "srv-2"
        )

        storage.putString("admin_active_grant_srv-1", json.encodeToString(grant1))
        storage.putString("admin_active_grant_srv-2", json.encodeToString(grant2))

        val loadedGrant1 = repository.getActiveGrant("srv-1")
        val loadedGrant2 = repository.getActiveGrant("srv-2")

        assertNotNull(loadedGrant1)
        assertNotNull(loadedGrant2)
        assertEquals("srv-1", loadedGrant1?.serverId)
        assertEquals("srv-2", loadedGrant2?.serverId)
        assertEquals(AdminRole.OWNER, loadedGrant1?.role)
        assertEquals(AdminRole.OPERATOR, loadedGrant2?.role)
    }

    @Test fun testCommandResponseUsesSelectedServersPin() {
        grantOwner()
        val secondKeyPair = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val secondPublicKey = Base64.getEncoder().encodeToString(secondKeyPair.public.encoded)
        val secondNode = ServerNode(id = "srv-2", name = "Second", host = "10.0.0.2",
            hostPublicKey = "ssh-ed25519 second-test-key", privateKeyPath = "/path/to/key")
        repository.addServerNode(secondNode)
        val expiry = System.currentTimeMillis() / 1000L + 3600L
        val secondGrant = AdminGrant(
            role = AdminRole.OPERATOR, profileId = "profile-2",
            adminFingerprint = keys.getFingerprint(), serverPublicKey = secondPublicKey,
            grantedAt = System.currentTimeMillis(), expiresAt = expiry,
            revision = 1L, nonce = "server-2-nonce", serverId = secondNode.id
        )
        fun signWithSecondKey(envelope: AdminEnvelope): AdminEnvelope {
            val signer = java.security.Signature.getInstance("SHA256withECDSA")
            signer.initSign(secondKeyPair.private)
            signer.update(envelope.canonicalSigningString().toByteArray(Charsets.UTF_8))
            return envelope.copy(signature = Base64.getEncoder().encodeToString(signer.sign()))
        }
        val grantEnvelope = signWithSecondKey(AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_GRANT, profileId = secondGrant.profileId,
            rev = secondGrant.revision, exp = expiry, sender = "server", nonce = secondGrant.nonce,
            payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(secondGrant).toByteArray(Charsets.UTF_8))
        ))
        storage.putString("admin_active_grant_srv-2", json.encodeToString(secondGrant))
        storage.putString("admin_active_grant_envelope_srv-2", json.encodeToString(grantEnvelope))
        storage.putString("admin_pinned_server_key_srv-2", secondPublicKey)

        val client = AdminSshClient(
            context = AdminRepository.createDummyContext(), networkChecker = { true },
            commandExecutor = { _, request ->
                val command = AdminEnvelope.fromJson(json.parseToJsonElement(request).jsonObject["envelope"].toString())!!
                val body = buildJsonObject {
                    put("op_id", command.opId)
                    put("status", "applied")
                    put("data", json.parseToJsonElement(json.encodeToString(
                        AdminUser(id = "user-2", name = "Bob", status = "active")
                    )))
                }
                val response = signWithSecondKey(AdminEnvelope(
                    subtype = AdminEnvelope.SUBTYPE_RESPONSE, profileId = command.profileId,
                    opId = command.opId, rev = command.rev + 1L, sender = "server",
                    payload = policySnapshotPayload(command, secondPublicKey) ?: Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(body.toString().toByteArray(Charsets.UTF_8))
                ))
                Pair(0, response.toJson())
            }
        )
        val scopedRepo = AdminRepository(context = null, keyManager = keys, sshClient = client,
            storage = storage, socketProbe = { _, _, _ -> true })
        assertTrue(scopedRepo.createUser("Bob", AdminChannel.DIRECT_SSH, secondNode).isSuccess)
        val firstNode = scopedRepo.getServerNode("srv-1")!!
        assertTrue(scopedRepo.createUser("Bob", AdminChannel.DIRECT_SSH, firstNode).isFailure)
    }

    @Test fun testOwnerPreservedAfterUpdate() {
        val serverKey = keys.getPublicKeyBase64()
        val expiry = System.currentTimeMillis() / 1000L + 7200L
        val legacyGrant = AdminGrant(
            role = AdminRole.OWNER,
            profileId = "prof-legacy",
            adminFingerprint = keys.getFingerprint(),
            serverPublicKey = serverKey,
            grantedAt = System.currentTimeMillis(),
            expiresAt = expiry,
            revision = 10L,
            nonce = "legacy-nonce"
        )
        val envelope = AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_GRANT,
            profileId = "prof-legacy",
            rev = 10L,
            exp = expiry,
            sender = "server",
            nonce = "legacy-nonce",
            payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(legacyGrant).toByteArray(Charsets.UTF_8))
        )
        val signedEnvelope = keys.signEnvelope(envelope)

        // Store only in legacy keys without server prefix
        storage.putString("admin_active_grant", json.encodeToString(legacyGrant))
        storage.putString("admin_active_grant_envelope", json.encodeToString(signedEnvelope))
        storage.putString("admin_role", "OWNER")
        storage.putString("admin_pinned_server_key", serverKey)
        storage.putString("admin_server_nodes", json.encodeToString(listOf(ServerNode(
            id = "legacy-server", name = "Legacy", host = "10.0.0.1"
        ))))

        // Create new repository instance simulating app restart after update
        val newRepo = createRepo()
        assertEquals(AdminRole.OWNER, newRepo.getRole())
        assertEquals(serverKey, newRepo.getPinnedServerPublicKey())
        assertEquals(serverKey, newRepo.getPinnedServerPublicKey("legacy-server"))
        assertNull(newRepo.getPinnedServerPublicKey("any-server-id"))
        val grant = newRepo.getActiveGrant("legacy-server")
        assertNotNull(grant)
        assertEquals(AdminRole.OWNER, grant?.role)
        assertEquals("prof-legacy", grant?.profileId)
        assertNull(newRepo.getActiveGrant("any-server-id"))
    }

    @Test fun testClusterSnapshotTwoProfilesOnSameServer() {
        grantOwner()
        val testServer = repository.serverNodesFlow.value.first()
        val prof1 = ServerManagedProfile(id = "prof-1", profileId = "prof-1", name = "P1", serverId = "srv-1")
        val prof2 = ServerManagedProfile(id = "prof-2", profileId = "prof-2", name = "P2", serverId = "srv-1")
        val w1 = AdminWorkerStatus(serverId = "srv-1", profileId = "prof-1", readiness = true)
        val w2 = AdminWorkerStatus(serverId = "srv-1", profileId = "prof-2", readiness = true)

        val snapshotPayload = AdminClusterSnapshot(
            snapshotRevision = 5L,
            authorityId = keys.getPublicKeyBase64(),
            timestamp = 1700000000L,
            roleScope = "OWNER",
            isComplete = true,
            servers = listOf("srv-1"),
            profiles = listOf(prof1, prof2),
            workerStatuses = listOf(w1, w2)
        )
        val computedHash = computeClusterSnapshotHash(snapshotPayload)
        val fullSnapshot = snapshotPayload.copy(snapshotHash = computedHash)

        assertEquals(1, fullSnapshot.distinctServerIds.size)
        assertEquals("srv-1", fullSnapshot.distinctServerIds.first())
        assertEquals(2, fullSnapshot.profiles.size)
        assertEquals(2, fullSnapshot.workerStatuses.size)

        nextResponseData = json.encodeToString(fullSnapshot)
        val result = repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, serverNode = testServer)
        assertTrue(result.isSuccess)
        val received = result.getOrThrow()
        assertEquals(5L, received.snapshotRevision)
        assertEquals(1, received.servers.size)
        assertEquals("srv-1", received.servers.first())
        assertEquals(2, received.profiles.size)
    }

    @Test fun testClusterSnapshotMonotonicityAndHashVerification() {
        grantOwner()
        val testServer = repository.serverNodesFlow.value.first()
        val validSnapshot = AdminClusterSnapshot(
            snapshotRevision = 10L,
            authorityId = keys.getPublicKeyBase64(),
            timestamp = 1700000000L,
            roleScope = "OWNER",
            isComplete = true,
            servers = listOf("srv-1")
        )
        val validHash = computeClusterSnapshotHash(validSnapshot)
        nextResponseData = json.encodeToString(validSnapshot.copy(snapshotHash = validHash))
        val res1 = repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, serverNode = testServer)
        assertTrue(res1.isSuccess)

        // Stale revision downgrade (rev 9 < 10) must be rejected
        val staleSnapshot = AdminClusterSnapshot(
            snapshotRevision = 9L,
            authorityId = keys.getPublicKeyBase64(),
            timestamp = 1700000001L,
            roleScope = "OWNER",
            isComplete = true,
            servers = listOf("srv-1")
        )
        nextResponseData = json.encodeToString(staleSnapshot.copy(snapshotHash = computeClusterSnapshotHash(staleSnapshot)))
        val res2 = repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, serverNode = testServer)
        assertTrue(res2.isFailure)
        assertTrue(res2.exceptionOrNull()?.message?.contains("устаревший снимок") == true)

        // Tampered hash must be rejected
        val tamperedSnapshot = validSnapshot.copy(snapshotRevision = 11L, snapshotHash = "tampered-hash")
        nextResponseData = json.encodeToString(tamperedSnapshot)
        val res3 = repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, serverNode = testServer)
        assertTrue(res3.isFailure)
        assertTrue(res3.exceptionOrNull()?.message?.contains("Контрольная сумма") == true)
    }

    @Test fun testSaveFailureHandling() {
        var failOnWrite = false
        val failingStorage = object : AdminStorage {
            private val map = mutableMapOf<String, String>()
            override fun getString(key: String, default: String?): String? = map[key] ?: default
            override fun putString(key: String, value: String?) {
                if (failOnWrite) throw RuntimeException("Disk full / I/O error")
                if (value == null) map.remove(key) else map[key] = value
            }
            override fun remove(key: String) { map.remove(key) }
        }

        val testRepo = AdminRepository(
            context = null,
            keyManager = keys,
            storage = failingStorage
        )
        // With no grants, operations fail
        assertEquals(AdminRole.NONE, testRepo.getRole())
        val userRes = testRepo.createUser("Alice")
        assertTrue(userRes.isFailure)

        // Applying grant with failing storage returns failure and does not elevate in-memory role
        val request = testRepo.createJoinRequest("profile-1", AdminRole.OWNER).first
        val serverKey = keys.getPublicKeyBase64()
        testRepo.setPinnedServerPublicKey(serverKey)
        val expiry = System.currentTimeMillis() / 1000L + 3600L
        val grant = AdminGrant(
            role = AdminRole.OWNER,
            profileId = request.profileId,
            adminFingerprint = keys.getFingerprint(),
            serverPublicKey = serverKey,
            grantedAt = System.currentTimeMillis(),
            expiresAt = expiry,
            revision = 1L,
            nonce = request.nonce
        )
        val envelope = AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_GRANT,
            profileId = request.profileId,
            rev = 1L,
            exp = expiry,
            sender = "server",
            nonce = request.nonce,
            payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(grant).toByteArray(Charsets.UTF_8))
        )
        val signedGrant = keys.signEnvelope(envelope)

        // Trigger disk/storage failure
        failOnWrite = true
        val applyRes = testRepo.applyAdminGrant(signedGrant, serverKey)
        assertTrue(applyRes is AdminOpResult.Failure)
        assertNotEquals(AdminRole.OWNER, testRepo.getRole())
        assertNull(testRepo.getActiveGrant())
    }

    private class InMemoryTestTunnelStore(initial: List<io.github.libreroute.data.Tunnel>) : io.github.libreroute.data.TunnelStore {
        private val list = initial.toMutableList()
        private var selected: Long? = null
        override fun load(): List<io.github.libreroute.data.Tunnel> = list.toList()
        override fun save(tunnels: List<io.github.libreroute.data.Tunnel>) {
            list.clear()
            list.addAll(tunnels)
        }
        override fun getSelectedId(): Long? = selected
        override fun setSelectedId(id: Long) { selected = id }
    }
}
