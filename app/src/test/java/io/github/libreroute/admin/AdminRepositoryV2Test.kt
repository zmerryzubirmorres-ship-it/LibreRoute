package io.github.libreroute.admin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
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
    private var jazzReplyStatus = "applied"
    private var jazzRoomCreator: () -> String = { error("Unexpected room creation") }
    private var coreInfo: CoreCompatibility? = CoreCompatibility("1.2.5", "a".repeat(64), 1,
        CoreCompatibility.REQUIRED_CAPABILITIES.toList())
    private var lastUpdateBootstrap: kotlinx.serialization.json.JsonElement? = null
    private var loseEnrollmentResponse = false
    private var rejectAccessProbe = false
    private var accessProbeError = "administrator status is revoked"
    private var accessProbeOffline = false
    private var liveRoleScope = ""
    private val executedOperations = mutableListOf<String>()
    private val enrollmentRequests = mutableListOf<AdminEnvelope>()
    private var apiResponse: ((AdminEnvelope) -> String)? = null

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

    private fun checkUpdateBootstrapSelection(configurationBaselineSupported: Boolean) {
        grantRole("update-node", AdminRole.OWNER)
        val oldGrant = repository.getActiveGrant("update-node")
        val caps = CoreCompatibility.REQUIRED_CAPABILITIES + CoreUpdateClient.CAPABILITY +
            if (configurationBaselineSupported) setOf(CoreUpdateClient.CONFIGURATION_BASELINE) else emptySet()
        coreInfo = CoreCompatibility("1.2.30", "a".repeat(64), 1, caps.toList(), arch = "amd64")
        val artifact = java.io.File.createTempFile("core-bootstrap", ".bin").apply { writeText("target") }
        try {
            val target = buildJsonObject { put("arch", "amd64"); put("version", CoreCompatibility.REQUIRED_VERSION); put("size", artifact.length()) }
            val manifest = buildJsonObject { put("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(target.toString().toByteArray())); put("signature", "fixture") }
            nextResponseData = buildJsonObject {
                put("server_id", "update-node"); put("server_public_key", keys.getPublicKeyBase64())
                put("control_profile_id", oldGrant!!.profileId); put("operation_id", "update-plan")
                put("namespace", "node-updatenode"); put("operation_kind", "core-update"); put("plan_hash", "hash")
                put("manifest", target); put("baseline_format", "configuration-v1")
            }.toString()
            val repo = createRepo(artifactProvider = { artifact to manifest })
            val node = repo.getServerNode("update-node")!!
            repo.planCoreUpdate(node, "update-plan", AdminChannel.DIRECT_SSH)
            val operation = repo.loadServerInstallation("update-plan")!!
            if (configurationBaselineSupported) {
                assertNull(operation.bootstrapManifest); assertNull(lastUpdateBootstrap)
            } else {
                assertEquals(manifest.toString(), operation.bootstrapManifest)
                assertEquals(manifest, lastUpdateBootstrap!!.jsonObject.getValue("manifest"))
                // Switching this helper attempt to a live carrier is rejected
                // before a mutation or an update-plan identity change.
                val result = repo.continueCoreUpdate(node, operation.operationId, AdminChannel.ACTIVE_TRANSPORT)
                assertTrue(result is AdminOpResult.Failure)
                assertEquals(AdminChannel.DIRECT_SSH, repo.loadServerInstallation(operation.operationId)!!.channel)
            }
            assertEquals(oldGrant, repo.getActiveGrant(node.id)); assertNull(operation.join)
        } finally { artifact.delete() }
    }

    @Test fun brokenBaselineUsesSignedHelperDespiteTransactionCapability() = checkUpdateBootstrapSelection(false)
    @Test fun fixedBaselineUsesExistingServerCoordinatorOverSsh() = checkUpdateBootstrapSelection(true)

    @Test fun resumedSshUpdateStagesApprovedArtifactWithoutPerChunkCommandsOrPlanChanges() {
        grantRole("update-node", AdminRole.OWNER)
        val grant = repository.getActiveGrant("update-node")!!
        val node = repository.getServerNode("update-node")!!
        val artifact = java.io.File.createTempFile("ssh-artifact", ".bin").apply { writeText("target") }
        try {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(artifact.readBytes()).hex()
            val target = buildJsonObject {
                put("arch", "amd64"); put("version", CoreCompatibility.REQUIRED_VERSION)
                put("size", artifact.length()); put("sha256", hash)
            }
            val signed = buildJsonObject {
                put("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(target.toString().toByteArray()))
                put("signature", "fixture")
            }
            val plan = buildJsonObject {
                put("server_id", node.id); put("server_public_key", keys.getPublicKeyBase64())
                put("control_profile_id", grant.profileId); put("owner_device_id", keys.getFingerprint())
                put("operation_id", "resumed"); put("namespace", "node-updatenode")
                put("operation_kind", "core-update"); put("plan_hash", "approved-hash")
                put("manifest", target); put("signed_manifest", signed); put("adapter", "systemd")
            }
            val actions = mutableListOf<String>()
            storage.putString("admin_install_operations", json.encodeToString(listOf(ServerInstallOperation(
                "resumed", node.id, "node-updatenode", node, plan.toString(), "approved-hash", grant.profileId,
                keys.getFingerprint(), kind = ServerInstallOperation.KIND_CORE_UPDATE, coordinator = true,
                channel = AdminChannel.DIRECT_SSH, stage = ServerInstallOperation.UNKNOWN,
                serverStage = "receiving", receivedBytes = 2))))
            val repo = createRepo(artifactProvider = { artifact to signed }, updateResponse = { payload, bootstrap ->
                val request = payload.getValue("core_update").jsonObject
                val action = request.getValue("action").jsonPrimitive.content
                actions += action
                assertEquals("resumed", request.getValue("operation_id").jsonPrimitive.content)
                assertEquals("approved-hash", request.getValue("plan_hash").jsonPrimitive.content)
                if (action == "begin") {
                    assertEquals(plan, request.getValue("plan"))
                    assertEquals(signed, bootstrap!!.jsonObject.getValue("manifest"))
                    assertEquals(artifact.absolutePath, bootstrap.jsonObject.getValue("artifact_path").jsonPrimitive.content)
                } else assertNull(bootstrap)
                buildJsonObject {
                    put("server_id", node.id); put("server_public_key", keys.getPublicKeyBase64())
                    put("control_profile_id", grant.profileId); put("operation_id", "resumed"); put("plan_hash", "approved-hash")
                    put("stage", when (action) { "begin" -> "verified"; "prepare" -> "prepared"; "apply" -> "committed"; else -> error("Unexpected action: $action") })
                    put("next_offset", artifact.length()); put("received_bytes", artifact.length()); put("size", artifact.length())
                    if (action == "apply") put("runtime", json.encodeToJsonElement(CoreCompatibility.serializer(), CoreCompatibility(
                        CoreCompatibility.REQUIRED_VERSION, hash, 1, CoreCompatibility.REQUIRED_CAPABILITIES.toList())))
                }.toString()
            })
            assertTrue(repo.continueCoreUpdate(node, "resumed", AdminChannel.DIRECT_SSH) is AdminOpResult.Success)
            assertEquals(listOf("begin", "prepare", "apply"), actions)
            val saved = repo.loadServerInstallation("resumed")!!
            assertEquals(plan.toString(), saved.expectedPlan)
            assertEquals("approved-hash", saved.planHash)
            assertNull(saved.bootstrapManifest)
            assertEquals(grant, repo.getActiveGrant(node.id))
            assertEquals(ServerInstallOperation.READY, saved.stage)
        } finally { artifact.delete() }
    }

    @Test fun jazzCreationChecksCapabilityAndDoesNotMutateOnProviderFailure() {
        grantRole("srv-1", AdminRole.OWNER)
        val node = repository.getServerNode("srv-1")!!
        var calls = 0
        jazzRoomCreator = { calls++; throw java.io.IOException("secret provider response") }
        assertTrue(repository.createAndInstallJazz(node) is AdminOpResult.Failure)
        assertEquals(0, calls)
        coreInfo = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "jazz-datachannel-v1")
        val result = repository.createAndInstallJazz(node) as AdminOpResult.Failure
        assertEquals(1, calls)
        assertFalse(result.error.contains("secret"))
        assertNull(result.throwable)
        assertNull(repository.latestProtocolInstallation(node.id, "jazz"))
        val command = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(lastExecutedEnvelope!!.payload))).jsonObject
        assertEquals("get-cluster-snapshot", command.getValue("op").jsonPrimitive.content)
    }

    @Test fun jazzRoomIsCommittedBeforeInstallationAndReusedAfterRecreation() {
        grantRole("srv-1", AdminRole.OWNER)
        val node = repository.getServerNode("srv-1")!!
        val supported = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "jazz-datachannel-v1")
        coreInfo = supported
        val endpoint = "https://salutejazz.ru/call/new-room/secret-password"
        var calls = 0
        jazzRoomCreator = {
            calls++
            // The second snapshot fails, after guest creation but before the server command.
            coreInfo = null
            endpoint
        }
        assertTrue(repository.createAndInstallJazz(node) is AdminOpResult.Failure)
        val saved = repository.latestProtocolInstallation(node.id, "jazz")!!
        assertEquals(endpoint, saved.endpoint)
        assertEquals(ProtocolInstallationOperation.PLANNED, saved.stage)
        val restored = createRepo()
        assertEquals(saved, restored.latestProtocolInstallation(node.id, "jazz"))
        coreInfo = supported
        assertTrue(restored.createAndInstallJazz(node) is AdminOpResult.Success)
        assertEquals(1, calls)
        val completed = restored.latestProtocolInstallation(node.id, "jazz")!!
        assertEquals(saved.operationId, completed.operationId)
        assertEquals(saved.profileId, completed.profileId)
        assertEquals(ProtocolInstallationOperation.READY, completed.stage)
        assertTrue(restored.createAndInstallJazz(node) is AdminOpResult.Success)
        assertEquals(1, calls)
    }

    @Test fun jazzUnknownOperationIsReconciledWithoutCreatingAnotherRoom() {
        grantRole("srv-1", AdminRole.OWNER)
        val node = repository.getServerNode("srv-1")!!
        coreInfo = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "jazz-datachannel-v1")
        val pending = ProtocolInstallationOperation("jazz-unknown", node.id, "jazz", "pending-jazz",
            "https://salutejazz.ru/call/pending/password", ProtocolInstallationOperation.UNKNOWN)
        storage.putString("admin_protocol_install_operations", json.encodeToString(listOf(pending)))
        val restored = createRepo()
        restored.createAndInstallJazz(node)
        assertEquals(pending.operationId, restored.latestProtocolInstallation(node.id, "jazz")!!.operationId)
        val command = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(lastExecutedEnvelope!!.payload))).jsonObject
        assertNotEquals("create-profile", command.getValue("op").jsonPrimitive.content)
    }

    @Test fun concurrentJazzSetupUsesOneGuestRoom() {
        grantRole("srv-1", AdminRole.OWNER)
        val node = repository.getServerNode("srv-1")!!
        coreInfo = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "jazz-datachannel-v1")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        jazzRoomCreator = {
            calls.incrementAndGet(); entered.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
            "https://salutejazz.ru/call/concurrent/password"
        }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<AdminOpResult> { repository.createAndInstallJazz(node) }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = executor.submit<AdminOpResult> { repository.createAndInstallJazz(node) }
            release.countDown()
            assertTrue(first.get(10, java.util.concurrent.TimeUnit.SECONDS) is AdminOpResult.Success)
            assertTrue(second.get(10, java.util.concurrent.TimeUnit.SECONDS) is AdminOpResult.Success)
            assertEquals(1, calls.get())
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun jazzDoesNotMutateServerWhenRoomCannotBeCommitted() {
        grantRole("srv-1", AdminRole.OWNER)
        val node = repository.getServerNode("srv-1")!!
        coreInfo = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "jazz-datachannel-v1")
        jazzRoomCreator = { "https://salutejazz.ru/call/new-room/password" }
        val failingStorage = object : AdminStorage by storage {
            override fun putStringCommitted(key: String, value: String?) {
                throw java.io.IOException("disk full")
            }
        }
        val restored = createRepo(failingStorage)
        val result = restored.createAndInstallJazz(node) as AdminOpResult.Failure
        assertTrue(result.error.contains("сохранить"))
        assertNull(restored.latestProtocolInstallation(node.id, "jazz"))
        val command = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(lastExecutedEnvelope!!.payload))).jsonObject
        assertEquals("get-cluster-snapshot", command.getValue("op").jsonPrimitive.content)
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
        for ((transport, endpoint) in listOf("vyandex" to "https://docs.yandex.ru/edit/d/test", "jitsi" to "")) {
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

    @Test fun jazzRequiresCapabilityBeforeMutationAndRestoresDurableOperation() {
        grantRole("srv-1", AdminRole.OWNER)
        val node = repository.getServerNode("srv-1")!!
        val endpoint = "https://salutejazz.ru/call/dedicated/password"
        assertTrue(repository.installProtocol(node, "jazz", endpoint) is AdminOpResult.Failure)
        assertNull(repository.latestProtocolInstallation(node.id, "jazz"))
        coreInfo = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "jazz-datachannel-v1")
        assertTrue(repository.installProtocol(node, "jazz", endpoint) is AdminOpResult.Success)
        val operation = repository.latestProtocolInstallation(node.id, "jazz")!!
        assertEquals(endpoint, operation.endpoint)
        assertEquals(ProtocolInstallationOperation.READY, operation.stage)
        val restored = createRepo()
        assertTrue(restored.installProtocol(node, "jazz", endpoint) is AdminOpResult.Success)
        assertEquals(operation.operationId, restored.latestProtocolInstallation(node.id, "jazz")!!.operationId)
        val route = RouteCandidate(route_id = operation.profileId, transport = "jazz", url = endpoint, secret_key = "distinct Jazz encryption key 12345")
        val tunnel = restored.routeCandidateToTunnel(route)
        assertEquals("jazz", tunnel.transportType)
        assertTrue(tunnel.transportConnPayload.contains("--peer-health"))
        assertEquals(endpoint, tunnel.transportConnPayload[tunnel.transportConnPayload.indexOf("--url") + 1])
        assertTrue(runCatching { restored.routeCandidateToTunnel(route.copy(secret_key = "")) }.isFailure)
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

    @Test fun mixedGroupMaterializesOneRouteAndRestoresSameIdentityWithoutPrivateKey() {
        val publicKey = repository.getRecipientDevicePublicKey()
        val fp = java.security.MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(publicKey))
            .joinToString("") { "%02x".format(it) }
        val binding = PairChannelBinding(2, "12345678123456781234567812345678", 1,
            "lease1", "resource1", "room", 1, 1, 1, 1, "group-node", "device", fp, publicKey)
        val group = ChannelGroupBinding(1, "ordered_striping", 1, listOf(
            ChannelGroupLane("jazz", "https://salutejazz.ru/call/room/password", binding),
            ChannelGroupLane("vyandex", "https://disk.yandex.ru/i/document", binding.copy(
                lane_id = 2, lease_id = "lease2", resource_id = "resource2", resource_identity = "disk.yandex.ru/i/document"))
        ))
        val profile = ManagedProfile(id = "new-group", name = "Mixed group", transportType = "channel-group", serverId = "group-node")
        val body = buildJsonObject { put("data", buildJsonObject {
            put("profile_id", profile.id); put("transport", "channel-group"); put("endpoint", group.endpoint)
            put("secret_key", "a".repeat(64)); put("channel_group", json.encodeToJsonElement(group))
            put("parameters", buildJsonObject { put("codec", "batched"); put("encryption_layout", "batch"); put("session_negotiate", "false") })
        }) }
        val response = AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_RESPONSE, profileId = profile.id, sender = "server",
            payload = Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().toByteArray()))
        val tunnel = repository.provisionedTunnel(profile, response)
        val route = RouteCandidate(route_id = profile.id, server_id = "group-node", name = profile.name,
            transport = "channel-group", url = group.endpoint, secret_key = "a".repeat(64), codec = "batched", encryption_layout = "batch", channel_group = group)
        val restored = createRepo().routeCandidateToTunnel(route)
        assertEquals(tunnel.transportConnPayload, restored.transportConnPayload)
        assertEquals("channelGroup", tunnel.transportType)
        assertTrue(tunnel.transportConnPayload.contains("--channel-group-config"))
        assertFalse(tunnel.transportConnPayload.any { "private-key" in it || it == "--session-negotiate" })
        assertTrue(runCatching { repository.routeCandidateToTunnel(route.copy(server_id = "another-node")) }.isFailure)
        assertTrue(runCatching { repository.routeCandidateToTunnel(route.copy(channel_group = null)) }.isFailure)
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

    private fun createRepo(repositoryStorage: AdminStorage = storage,
        artifactProvider: ((String) -> Pair<java.io.File, kotlinx.serialization.json.JsonObject>)? = null,
        tunnelStore: io.github.libreroute.data.TunnelStore? = null,
        updateResponse: ((kotlinx.serialization.json.JsonObject, kotlinx.serialization.json.JsonElement?) -> String)? = null): AdminRepository {
        return AdminRepository(
            context = null,
            keyManager = keys,
            sshClient = AdminSshClient(
                context = AdminRepository.createDummyContext(),
                networkChecker = { true },
                  commandExecutor = { _, request ->
                      lastUpdateBootstrap = json.parseToJsonElement(request).jsonObject["update_bootstrap"]
                          ?.takeUnless { it is kotlinx.serialization.json.JsonNull }
                    val command = AdminEnvelope.fromJson(json.parseToJsonElement(request).jsonObject["envelope"].toString())!!
                    lastExecutedEnvelope = command
                    val requestOp = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(command.payload), Charsets.UTF_8)).jsonObject["op"]?.jsonPrimitive?.content
                    executedOperations += requestOp.orEmpty()
                    if (requestOp == "get-cluster-snapshot" && accessProbeOffline) throw java.io.IOException("Network unavailable")
                    if (requestOp == "issue-enrollment") {
                        enrollmentRequests.add(command)
                        if (loseEnrollmentResponse) { loseEnrollmentResponse = false; throw java.io.IOException("Lost response after issuance") }
                    }
                    val autoSnapshot = requestOp == "get-cluster-snapshot" && !json.parseToJsonElement(nextResponseData).let {
                        val objectData = if (it is kotlinx.serialization.json.JsonArray) it.singleOrNull() else it
                        objectData is kotlinx.serialization.json.JsonObject && objectData.containsKey("snapshot_revision")
                    }
                    val snapshot = AdminClusterSnapshot(authorityId = keys.getPublicKeyBase64(), snapshotRevision = 5L,
                        coreInfo = coreInfo, roleScope = liveRoleScope)
                    val data = if (requestOp == "core-update" && updateResponse != null) {
                        updateResponse(json.parseToJsonElement(String(Base64.getUrlDecoder().decode(command.payload))).jsonObject, lastUpdateBootstrap)
                    } else if (autoSnapshot) json.encodeToString(snapshot.copy(snapshotHash = computeClusterSnapshotHash(snapshot)))
                    else apiResponse?.invoke(command) ?: nextResponseData
                    val body = buildJsonObject {
                        put("op_id", command.opId)
                        put("status", if (requestOp == "get-cluster-snapshot" && rejectAccessProbe) "rejected"
                            else if (requestOp == "assign-profile") jazzReplyStatus else "applied")
                        if (requestOp == "get-cluster-snapshot" && rejectAccessProbe) put("error", accessProbeError)
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
            storage = repositoryStorage,
            customTunnelStore = tunnelStore,
            socketProbe = { _, _, _ -> true },
              jazzRoomCreator = { jazzRoomCreator() },
              coreArtifactProvider = artifactProvider
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

    @Test fun jazzRoomPermissionsFollowSignedRolesAndScope() {
        grantRole("owner", AdminRole.OWNER)
        grantRole("viewer", AdminRole.VIEWER)
        grantRole("operator", AdminRole.OPERATOR)
        assertTrue(repository.canPerform("owner", "set-jazz-room-policy"))
        assertTrue(repository.canPerform("operator", "replace-jazz-room"))
        assertFalse(repository.canPerform("operator", "set-jazz-room-policy"))
        assertTrue(repository.canPerform("viewer", "get-jazz-room-pool"))
        assertFalse(repository.canPerform("viewer", "drain-jazz-room"))
        assertFalse(repository.canPerform("missing", "get-jazz-room-pool"))
    }

    @Test fun unknownJazzAssignmentKeepsOperationAcrossRepositoryRecreation() {
        grantRole("srv-1", AdminRole.OWNER)
        val node = repository.getServerNode("srv-1")!!
        nextResponseData = """{"id":"pair-a","user_id":"user","device_id":"device","profile_instance_id":"profile","server_id":"srv-1","transport":"jazz","status":"ready","revision":1}"""
        jazzReplyStatus = "unknown"
        val first = repository.assignProfile("user", "device", "srv-1", "jazz", channel=AdminChannel.DIRECT_SSH, serverNode=node)
        assertTrue(first.isFailure)
        assertNull(repository.serverMutationBlockReason(node.id, AdminChannel.DIRECT_SSH))
        assertTrue(repository.canUseManagementUi(node.id, "create-user"))
        assertEquals(AdminRole.OWNER, repository.getRole(node.id))
        val original = lastExecutedEnvelope!!
        jazzReplyStatus = "applied"
        val restored = createRepo()
        val retry = restored.assignProfile("user", "device", "srv-1", "jazz", channel=AdminChannel.DIRECT_SSH, serverNode=node)
        assertTrue(retry.isSuccess)
        assertEquals(original.opId, lastExecutedEnvelope!!.opId)
        assertEquals(original.payload, lastExecutedEnvelope!!.payload)
        assertEquals("pair-a", retry.getOrThrow().id)
    }

    @Test fun portableJazzWorkflowRetainsReservationAndIdentityAfterUnknownResult() {
        grantRole("srv-1", AdminRole.OWNER)
        coreInfo = coreInfo!!.copy(version = "1.2.48", capabilities = coreInfo!!.capabilities + "compact-route-v2")
        val node = repository.getServerNode("srv-1")!!
        val requests = mutableListOf<AdminEnvelope>()
        val stages = mutableListOf<String?>()
        var device: kotlinx.serialization.json.JsonObject? = null
        var repo = repository
        apiResponse = { envelope ->
            val command = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(envelope.payload))).jsonObject
            stages += repo.commandControl.actions.value.single().detail
            assertEquals("issue-portable-jazz", repo.commandControl.actions.value.single().operation)
            when (command.getValue("op").jsonPrimitive.content) {
                "list-devices" -> device?.let { "[$it]" } ?: "[]"
                "register-device" -> buildJsonObject {
                    put("id", "device"); put("user_id", "user"); put("status", "active")
                    put("public_key", command.getValue("device_pub")); put("fingerprint", "fingerprint")
                }.also { device = it }.toString()
                "list-assignments" -> "[]"
                "assign-profile" -> {
                    requests += envelope
                    """{"id":"pair-a","user_id":"user","device_id":"device","profile_instance_id":"profile","server_id":"srv-1","transport":"jazz","status":"ready","revision":1}"""
                }
                else -> error("Unexpected command")
            }
        }
        jazzReplyStatus = "unknown"
        val first = repo.issuePortableJazzRoute("user", node.id, AdminChannel.DIRECT_SSH, node)
        assertTrue(first.toString(), first.exceptionOrNull() is UnknownAdminOperation)
        assertTrue(repo.commandControl.actions.value.isEmpty())
        assertTrue(repo.commandHistoryFlow.value.none { it.status == AdminCommandRecord.STATUS_CANCELLED_LOCAL })
        repo = createRepo()
        assertTrue(repo.issuePortableJazzRoute("user", node.id, AdminChannel.DIRECT_SSH, node).exceptionOrNull() is UnknownAdminOperation)
        assertEquals(2, requests.size)
        assertEquals(requests.first().toJson(), requests.last().toJson())
        assertEquals(1, executedOperations.count { it == "register-device" })
        assertFalse(executedOperations.contains("issue-invitation"))
        assertTrue(stages.contains("Подготавливаем устройство…"))
        assertTrue(stages.contains("Подключаем Jazz на сервере…"))
        assertTrue(repo.commandControl.actions.value.isEmpty())
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

    @Test fun revokedAdministratorNeverSubmitsMutationAndRecordsLocalCancellation() {
        grantRole("revoked-owner", AdminRole.OWNER)
        executedOperations.clear()
        rejectAccessProbe = true
        val result = repository.createUser("Blocked", AdminChannel.DIRECT_SSH, repository.getServerNode("revoked-owner"))
        assertTrue(result.isFailure)
        assertEquals(listOf("get-cluster-snapshot"), executedOperations)
        assertTrue(repository.commandHistoryFlow.value.any {
            it.commandType == "create-user" && it.status == AdminCommandRecord.STATUS_CANCELLED_LOCAL
        })
        assertTrue(repository.commandControl.actions.value.isEmpty())
    }

    @Test fun downgradedLiveRoleNeverSubmitsMutation() {
        grantRole("downgraded-owner", AdminRole.OWNER)
        executedOperations.clear()
        liveRoleScope = "viewer"
        val result = repository.createUser("Blocked", AdminChannel.DIRECT_SSH, repository.getServerNode("downgraded-owner"))
        assertTrue(result.isFailure)
        assertEquals(listOf("get-cluster-snapshot"), executedOperations)
        assertEquals(AdminCommandRecord.STATUS_CANCELLED_LOCAL, repository.commandHistoryFlow.value.first().status)
        assertFalse(repository.canUseManagementUi("downgraded-owner", "create-user"))
        assertTrue(repository.canUseManagementUi("downgraded-owner", "list-users"))
    }

    @Test fun managementUiRequiresLiveAccessAndStaysBlockedAfterRejection() {
        grantRole("live-gate", AdminRole.OWNER)
        val node = repository.getServerNode("live-gate")!!
        assertFalse(repository.canUseManagementUi(node.id, "create-user"))
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isSuccess)
        assertTrue(repository.canUseManagementUi(node.id, "create-user"))
        rejectAccessProbe = true
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isFailure)
        assertFalse(repository.canUseManagementUi(node.id, "create-user"))
        assertFalse(repository.canUseManagementUi(node.id, "list-users"))
        assertEquals(AdminLiveAccess.State.DENIED, repository.liveAccessFlow.value[node.id]?.state)
        rejectAccessProbe = false
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isSuccess)
        assertTrue(repository.canUseManagementUi(node.id, "create-user"))
    }

    @Test fun networkFailureBlocksManagementWithoutRevokingTheGrant() {
        grantRole("offline-owner", AdminRole.OWNER)
        val node = repository.getServerNode("offline-owner")!!
        val original = repository.getActiveGrant(node.id)
        accessProbeOffline = true
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isFailure)
        assertEquals(AdminLiveAccess.State.UNAVAILABLE, repository.liveAccessFlow.value[node.id]?.state)
        assertEquals(original, repository.getActiveGrant(node.id))
        assertFalse(repository.canUseManagementUi(node.id, "create-user"))
        assertTrue(repository.canUseManagementUi(node.id, "list-users"))
    }

    @Test fun administrationSessionReusesSnapshotUntilExplicitRefresh() {
        grantRole("session-owner", AdminRole.OWNER)
        val node = repository.getServerNode("session-owner")!!
        executedOperations.clear()
        assertTrue(repository.loadAdminSession(AdminChannel.DIRECT_SSH, node).isSuccess)
        assertTrue(repository.loadAdminSession(AdminChannel.DIRECT_SSH, node).isSuccess)
        assertEquals(listOf("get-cluster-snapshot"), executedOperations)
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isSuccess)
        assertTrue(repository.loadAdminSession(AdminChannel.DIRECT_SSH, node).isSuccess)
        assertEquals(2, executedOperations.size)
        nextResponseData = """{"id":"session-user","name":"Alice","status":"active"}"""
        assertTrue(repository.createUser("Alice", AdminChannel.DIRECT_SSH, node).isSuccess)
        assertEquals(listOf("get-cluster-snapshot", "get-cluster-snapshot",
            "get-cluster-snapshot", "create-user"), executedOperations)
        assertTrue(repository.loadAdminSession(AdminChannel.DIRECT_SSH, node).isSuccess)
        assertEquals("get-cluster-snapshot", executedOperations.last())
        assertEquals(5, executedOperations.size)
    }

    @Test fun cachedGrantValidationRejectsChangedPinSignatureAndRevocation() {
        grantRole("cached-validation", AdminRole.OWNER)
        val id = "cached-validation"
        assertTrue(repository.canPerform(id, "create-user"))
        val pin = repository.getPinnedServerPublicKey(id)!!
        repository.setPinnedServerPublicKey("invalid-key", id)
        assertFalse(repository.canPerform(id, "create-user"))
        repository.setPinnedServerPublicKey(pin, id)
        assertTrue(repository.canPerform(id, "create-user"))
        val envelope = repository.getActiveGrantEnvelope(id)!!
        storage.putString("admin_active_grant_envelope_$id", envelope.copy(signature = "invalid").toJson())
        assertFalse(repository.canPerform(id, "create-user"))
        storage.putString("admin_active_grant_envelope_$id", envelope.toJson())
        assertTrue(repository.canPerform(id, "create-user"))
        val grant = repository.getActiveGrant(id)!!
        storage.putString("admin_active_grant_$id", json.encodeToString(grant.copy(scope = listOf("other"))))
        assertFalse(repository.canPerform(id, "create-user"))
        storage.putString("admin_active_grant_$id", json.encodeToString(grant))
        assertTrue(repository.canPerform(id, "create-user"))
        storage.putString("admin_locally_revoked_servers", """["cached-validation"]""")
        assertFalse(repository.canPerform(id, "create-user"))
    }

    @Test fun cachedGrantValidationStillExpiresWithoutRestart() {
        grantRole("cached-expiry", AdminRole.OWNER)
        var now = System.currentTimeMillis() / 1000L
        val repo = AdminRepository(keyManager = keys, storage = storage, currentTimeSeconds = { now })
        assertTrue(repo.canPerform("cached-expiry", "create-user"))
        now = repository.getActiveGrant("cached-expiry")!!.expiresAt
        assertFalse(repo.canPerform("cached-expiry", "create-user"))
    }

    @Test fun signedOperationalSnapshotFailureDoesNotRevokeAdministrator() {
        grantRole("operation-failed", AdminRole.OWNER)
        val node = repository.getServerNode("operation-failed")!!
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isSuccess)
        rejectAccessProbe = true
        accessProbeError = "EXECUTION_FAILED: worker state unavailable"
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isFailure)
        assertEquals(AdminLiveAccess.State.UNAVAILABLE, repository.liveAccessFlow.value[node.id]?.state)
        assertEquals(AdminRole.OWNER, repository.getRole(node.id))
        assertTrue(repository.canUseManagementUi(node.id, "list-users"))
        assertNull(repository.serverMutationBlockReason(node.id, AdminChannel.DIRECT_SSH))
    }

    @Test fun deleteUserPinsAuthorityAndExplicitTargetServer() {
        grantRole("authority", AdminRole.OWNER)
        grantRole("other", AdminRole.OWNER)
        coreInfo = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "user-server-delete-v1")
        val node = repository.getServerNode("authority")!!
        executedOperations.clear()
        assertTrue(repository.deleteUserFromServer("same-user-id", "worker-b", node, AdminChannel.DIRECT_SSH).isSuccess)
        assertEquals(listOf("get-cluster-snapshot", "delete-user"), executedOperations)
        val command = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(lastExecutedEnvelope!!.payload))).jsonObject
        assertEquals("worker-b", command["server_id"]?.jsonPrimitive?.content)
        assertEquals("same-user-id", command["user_id"]?.jsonPrimitive?.content)
        assertEquals("control-authority", lastExecutedEnvelope!!.profileId)
        assertEquals(AdminRole.OWNER, repository.getRole("other"))
    }

    @Test fun oldServerCannotReceiveUserDeletionAndOtherServerUserStaysVisible() {
        grantRole("old", AdminRole.OWNER)
        executedOperations.clear()
        assertTrue(repository.deleteUserFromServer("u", "old", repository.getServerNode("old")!!, AdminChannel.DIRECT_SSH).isFailure)
        assertEquals(listOf("get-cluster-snapshot"), executedOperations)
        val user = AdminUser("u", "TePoLe", removedServerIds = listOf("first"))
        assertFalse(user.visibleOn(setOf("first")))
        assertTrue(user.visibleOn(setOf("second")))
        assertTrue(user.visibleOn(setOf("first", "second")))
    }

    @Test fun offlineSessionDoesNotRetryOnEveryEntryButExplicitRefreshRecovers() {
        grantRole("session-offline", AdminRole.OWNER)
        val node = repository.getServerNode("session-offline")!!
        executedOperations.clear()
        accessProbeOffline = true
        assertTrue(repository.loadAdminSession(AdminChannel.DIRECT_SSH, node).isFailure)
        accessProbeOffline = false
        assertTrue(repository.loadAdminSession(AdminChannel.DIRECT_SSH, node).isFailure)
        assertEquals(1, executedOperations.size)
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isSuccess)
        assertTrue(repository.loadAdminSession(AdminChannel.DIRECT_SSH, node).isSuccess)
        assertEquals(2, executedOperations.size)
        assertTrue(repository.canUseManagementUi(node.id, "create-user"))
    }

    @Test fun offlineRetryDoesNotReopenPreviouslyDeniedUsers() {
        grantRole("session-denied", AdminRole.OWNER)
        val node = repository.getServerNode("session-denied")!!
        rejectAccessProbe = true
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isFailure)
        rejectAccessProbe = false
        accessProbeOffline = true
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isFailure)
        assertFalse(repository.canUseManagementUi(node.id, "list-users"))
    }

    @Test fun confirmedAccessPrecedesFormationAndSubmission() {
        grantRole("active-owner", AdminRole.OWNER)
        executedOperations.clear()
        nextResponseData = """{"id":"user-live","name":"Alice","status":"active"}"""
        val result = repository.createUser("Alice", AdminChannel.DIRECT_SSH, repository.getServerNode("active-owner"))
        assertTrue(result.toString(), result.isSuccess)
        assertEquals(listOf("get-cluster-snapshot", "create-user"), executedOperations)
        assertTrue(repository.commandControl.actions.value.isEmpty())
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

    @Test fun wrappedClusterSnapshotRetainsVerifiedCacheAndRejectsRevisionRollback() {
        grantOwner()
        val node = repository.serverNodesFlow.value.first()
        val snapshot = AdminClusterSnapshot(authorityId = keys.getPublicKeyBase64(), snapshotRevision = 10L,
            coreInfo = coreInfo)
        val signedData = json.encodeToString(snapshot.copy(snapshotHash = computeClusterSnapshotHash(snapshot)))
        nextResponseData = "[$signedData]"
        assertEquals(10L, repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).getOrThrow().snapshotRevision)
        assertEquals(10L, createRepo().cachedClusterSnapshot(node.id)?.snapshotRevision)

        val stale = snapshot.copy(snapshotRevision = 9L)
        nextResponseData = json.encodeToString(stale.copy(snapshotHash = computeClusterSnapshotHash(stale)))
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isFailure)
        assertEquals(10L, repository.cachedClusterSnapshot(node.id)?.snapshotRevision)
    }

    @Test fun wrappedClusterSnapshotRejectsTamperedHash() {
        grantOwner()
        val node = repository.serverNodesFlow.value.first()
        val tampered = AdminClusterSnapshot(authorityId = keys.getPublicKeyBase64(), snapshotRevision = 10L,
            snapshotHash = "invalid")
        nextResponseData = "[${json.encodeToString(tampered)}]"
        assertTrue(repository.getClusterSnapshot(AdminChannel.DIRECT_SSH, node).isFailure)
        assertNull(repository.cachedClusterSnapshot(node.id))
        assertNotNull(repository.getServerNode(node.id)?.coreObservation?.error)
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

    private fun restorationReply(serverId: String = "restore-node") = """{"server_id":"$serverId","routes":[{
        "profile_instance_id":"jitsi-existing","route_id":"jitsi-existing","server_id":"$serverId",
        "name":"Jitsi Meet","transport":"jitsi","url":"https://meet.jit.si/restored-room",
        "secret_key":"distinct encryption secret 12345","revision":7,"status":"ready",
        "codec":"batched","encryption_layout":"packet","parameters":{"session_negotiate":"false","session_batch_bytes":"8192"}
    }]}"""

    @Test fun restorationRecoversMissingRouteAndPreservesSelectionOnRetry() {
        grantRole("restore-node", AdminRole.OWNER)
        coreInfo = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "protocol-config-restore-v1")
        val manual = io.github.libreroute.data.Tunnel(42, "Manual", "jitsi", listOf("--url", "https://meet.jit.si/manual-room"))
        val store = InMemoryTestTunnelStore(listOf(manual))
        val repo = createRepo(tunnelStore = store)
        val node = repo.getServerNode("restore-node")!!
        nextResponseData = restorationReply()
        assertTrue(repo.restoreServerProtocols(node).isSuccess)
        assertEquals(2, store.load().size)
        val restored = store.load().single { it.managedProfileId == "jitsi-existing" }
        store.setSelectedId(restored.id)
        assertFalse(restored.transportConnPayload.contains("--session-negotiate"))
        assertTrue(restored.transportConnPayload.contains("--session-batch-bytes=8192"))
        assertTrue(repo.isProtocolReady(node.id, "jitsi"))
        val originalGrant = repo.getActiveGrant(node.id)
        assertTrue(repo.restoreServerProtocols(node).isSuccess)
        assertEquals(2, store.load().size)
        assertEquals(restored.id, store.load().single { it.managedProfileId == "jitsi-existing" }.id)
        assertEquals(restored.id, store.getSelectedId())
        assertEquals(originalGrant, repo.getActiveGrant(node.id))
        assertEquals(manual, store.load().single { it.id == 42L })
        assertTrue(repo.installProtocol(node, "jitsi", "", AdminChannel.DIRECT_SSH) is AdminOpResult.Success)
        val command = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(lastExecutedEnvelope!!.payload))).jsonObject
        assertEquals("get-protocol-configurations", command.getValue("op").jsonPrimitive.content)
    }

    @Test fun restorationRejectsWrongServerAndViewerWithoutChangingConnections() {
        grantRole("restore-node", AdminRole.OWNER)
        coreInfo = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "protocol-config-restore-v1")
        val store = InMemoryTestTunnelStore(emptyList())
        val repo = createRepo(tunnelStore = store)
        nextResponseData = restorationReply("another-server")
        assertTrue(repo.restoreServerProtocols(repo.getServerNode("restore-node")!!).isFailure)
        assertTrue(store.load().isEmpty())
        grantRole("viewer-node", AdminRole.VIEWER)
        val viewerRepo = createRepo(tunnelStore = store)
        lastExecutedEnvelope = null
        assertTrue(viewerRepo.restoreServerProtocols(viewerRepo.getServerNode("viewer-node")!!).isFailure)
        assertNull(lastExecutedEnvelope)
    }

    @Test fun restorationRepairsRenamedLegacyDuplicatesAndReadySetupUsesNoNetwork() {
        grantRole("restore-node", AdminRole.OWNER)
        coreInfo = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "protocol-config-restore-v1")
        val legacy = io.github.libreroute.data.Tunnel(41, "My Jitsi", "jitsi",
            listOf("--url", "https://meet.jit.si/restored-room"),
            "distinct encryption secret 12345", "restore-node")
        val store = InMemoryTestTunnelStore(listOf(legacy, legacy.copy(id = 42, name = "Restored Jitsi",
            managedProfileId = "jitsi-existing")))
        store.setSelectedId(41)
        val repo = createRepo(tunnelStore = store)
        val node = repo.getServerNode("restore-node")!!
        nextResponseData = restorationReply()
        assertTrue(repo.restoreServerProtocols(node).isSuccess)
        assertEquals(1, store.load().size)
        assertEquals(41L, store.load().single().id)
        assertEquals("My Jitsi", store.load().single().name)
        assertEquals(41L, store.getSelectedId())
        lastExecutedEnvelope = null
        assertTrue(repo.restoreServerProtocolsAutomatically(node).isSuccess)
        assertNull(lastExecutedEnvelope)
        assertTrue(repo.installProtocol(node, "jitsi") is AdminOpResult.Success)
        assertNull(lastExecutedEnvelope)
    }

    @Test fun excludedMqttIsRemovedAndNotRestoredOrInstalled() {
        grantRole("restore-node", AdminRole.OWNER)
        coreInfo = coreInfo!!.copy(capabilities = coreInfo!!.capabilities + "protocol-config-restore-v1")
        val legacyMqtt = io.github.libreroute.data.Tunnel(50, "Old MQTT", "mqtt",
            listOf("--url", "wss://broker.example/mqtt#phone"), "distinct encryption secret 12345")
        val manual = io.github.libreroute.data.Tunnel(42, "My Jazz", "jazz",
            listOf("--url", "https://salutejazz.ru/call/manual/password"))
        val store = InMemoryTestTunnelStore(listOf(legacyMqtt, manual))
        store.setSelectedId(50)
        val repo = createRepo(tunnelStore = store)
        val node = repo.getServerNode("restore-node")!!
        val reply = json.parseToJsonElement(restorationReply()).jsonObject
        val readyRoute = reply.getValue("routes").jsonArray.single().jsonObject
        val mqttRoute = buildJsonObject {
            readyRoute.forEach { (key, value) -> put(key, value) }
            put("profile_instance_id", "mqtt-existing"); put("route_id", "mqtt-existing")
            put("transport", "mqtt"); put("url", "wss://broker.example/mqtt#phone")
        }
        nextResponseData = buildJsonObject {
            put("server_id", "restore-node")
            put("routes", buildJsonArray { add(mqttRoute); add(readyRoute) })
        }.toString()
        repeat(2) {
            assertTrue(repo.restoreServerProtocols(node).isSuccess)
            assertEquals(setOf("jazz", "jitsi"), store.load().map { it.transportType }.toSet())
            assertEquals(manual, store.load().single { it.id == 42L })
            assertEquals(42L, store.getSelectedId())
            assertFalse(repo.isProtocolReady(node.id, "mqtt"))
            assertTrue(repo.managedProfilesFlow.value.none { it.transportType == "mqtt" })
        }
        lastExecutedEnvelope = null
        assertTrue(repo.installProtocol(node, "mqtt") is AdminOpResult.Failure)
        assertNull(lastExecutedEnvelope)
        repo.updateManagedTunnels(listOf(legacyMqtt))
        assertTrue(store.load().none { it.transportType == "mqtt" })
    }

    @Test fun groupEnrollmentIssuanceRetainsOneSignedOperationOnLossAndRejectsChangedPendingLinks() {
        grantOwner()
        nextResponseData = buildJsonObject {
            put("enrollment_id", "group-enrollment"); put("token", "a".repeat(43)); put("control_profile_id", "profile-1")
            put("document_url", "https://docs.yandex.ru/edit/d/dedicated-control"); put("server_public_key", keys.getPublicKeyBase64())
            put("expires_at", System.currentTimeMillis() / 1000 + 600)
        }.toString()
        val plan = ChannelGroupPlan.fromLinks("https://salutejazz.ru/call/one/password\nhttps://salutejazz.ru/call/two/password")
        val routes = listOf(EnrollmentRoute("srv-1", transport = "channel-group", channel_group_plan = plan))
        loseEnrollmentResponse = true
        val first = repository.issueBootstrapInvitation("user-one", routes, AdminChannel.DIRECT_SSH, repository.getServerNode("srv-1"))
        assertTrue(first.isFailure); assertEquals(1, enrollmentRequests.size)
        val changed = routes.map { it.copy(channel_group_plan = plan.copy(min_ready = 2)) }
        assertTrue(repository.issueBootstrapInvitation("user-one", changed, AdminChannel.DIRECT_SSH, repository.getServerNode("srv-1")).isFailure)
        assertEquals(1, enrollmentRequests.size)
        val restarted = createRepo()
        val retry = restarted.issueBootstrapInvitation("user-one", routes, AdminChannel.DIRECT_SSH, restarted.getServerNode("srv-1"))
        assertTrue(retry.exceptionOrNull()?.message, retry.isSuccess)
        assertEquals("group-enrollment", BootstrapInvitation.parse(retry.getOrThrow(), keys).enrollmentId)
        assertEquals(2, enrollmentRequests.size)
        assertEquals(enrollmentRequests.first().toJson(), enrollmentRequests.last().toJson())
        val command = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(enrollmentRequests.last().payload))).jsonObject
        val decoded = json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(EnrollmentRoute.serializer()), command.getValue("enrollment_routes"))
        assertEquals(routes, decoded)
        assertNull(storage.getString("group_enrollment_request_srv-1_user-one", null))
    }

    @Test fun failureOfPreviousCarrierDoesNotDisableNewOwnerRoute() {
        grantRole("carrier-node", AdminRole.OWNER)
        val node = repository.getServerNode("carrier-node")!!
        val grant = repository.getActiveGrant(node.id)
        val old = io.github.libreroute.service.NativeProcessSupervisor.AdminCarrier("/private/old.sock", "vyandex", node.id, ready = true)
        try {
            io.github.libreroute.service.NativeProcessSupervisor.activeAdminCarrier = old
            assertEquals(AdminChannel.ACTIVE_TRANSPORT, repository.resolveAdminChannel(AdminChannel.AUTO, node))
            // This JVM has no native carrier; the read records a real connection failure.
            assertTrue(repository.getClusterSnapshot(AdminChannel.ACTIVE_TRANSPORT, node).isFailure)
            assertEquals("Нет связи с сервером", repository.serverMutationBlockReason(node.id, AdminChannel.ACTIVE_TRANSPORT))
            assertEquals(AdminChannel.DIRECT_SSH, repository.resolveAdminChannel(AdminChannel.AUTO, node))
            val next = old.copy(path = "/private/new.sock", transport = "jazz", ready = false)
            io.github.libreroute.service.NativeProcessSupervisor.activeAdminCarrier = next
            assertEquals(AdminChannel.DIRECT_SSH, repository.resolveAdminChannel(AdminChannel.AUTO, node))
            io.github.libreroute.service.NativeProcessSupervisor.activeAdminCarrier = next.copy(ready = true)
            assertEquals(AdminChannel.ACTIVE_TRANSPORT, repository.resolveAdminChannel(AdminChannel.AUTO, node))
            assertNull(repository.serverMutationBlockReason(node.id, AdminChannel.ACTIVE_TRANSPORT))
            assertEquals(grant, repository.getActiveGrant(node.id))
        } finally { io.github.libreroute.service.NativeProcessSupervisor.activeAdminCarrier = null }
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
