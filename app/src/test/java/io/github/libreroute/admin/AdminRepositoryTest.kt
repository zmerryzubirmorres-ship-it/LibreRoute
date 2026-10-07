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
import io.github.libreroute.data.Tunnel
import io.github.libreroute.data.TunnelStore

class AdminRepositoryTest {
    @Test fun defaultDocumentUrlDoesNotInferOwnershipFromVpnNameOrCount() {
        val url = "https://docs.yandex.ru/edit/d/vds89-control"
        val tunnelStore = object : TunnelStore {
            private val tunnels = listOf(Tunnel(1L, "VDS89 document Dev", "vyandex", listOf("--url", url)))
            override fun load() = tunnels
            override fun save(tunnels: List<Tunnel>) = Unit
            override fun getSelectedId(): Long? = 1L
            override fun setSelectedId(id: Long) = Unit
        }
        val repo = AdminRepository(storage = InMemoryAdminStorage(), customTunnelStore = tunnelStore)
        repo.addServerNode(ServerNode(id = "vds89", name = "VDS89", host = "192.0.2.10"))
        assertNull(repo.defaultDocumentUrl("vds89"))
    }

    @Test fun migratesSshCredentialsAndExcludesThemFromNodeJson() {
        val state = InMemoryAdminStorage()
        state.putString("admin_server_nodes", """[{"id":"isolated","name":"Test","host":"127.0.0.1","password":"test-password","passphrase":"test-passphrase"}]""")
        val repo = AdminRepository(storage = state)
        assertEquals("test-password", repo.getServerNode("isolated")?.password)
        val serialized = state.getString("admin_server_nodes", null)!!
        assertFalse(serialized.contains("test-password"))
        assertFalse(serialized.contains("test-passphrase"))
        val reload = AdminRepository(storage = state)
        assertEquals("test-passphrase", reload.getServerNode("isolated")?.passphrase)
    }
    private lateinit var keys: AdminKeyManager
    private lateinit var storage: InMemoryAdminStorage
    private lateinit var repository: AdminRepository
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Before fun setUp() {
        AdminKeyManager.clearFallbackKey()
        keys = AdminKeyManager()
        storage = InMemoryAdminStorage()
        repository = newRepository()
    }

    private fun newRepository(clock: () -> Long = { System.currentTimeMillis() / 1000L }) = AdminRepository(
        context = null,
        keyManager = keys,
        sshClient = AdminSshClient(
            context = AdminRepository.createDummyContext(),
            networkChecker = { true },
            commandExecutor = { _, _ -> Pair(0, "{}") }
        ),
        storage = storage,
        socketProbe = { _, _, _ -> true },
        currentTimeSeconds = clock
    )

    private fun signedGrant(): Pair<AdminEnvelope, String> {
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
            payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(grant).toByteArray(Charsets.UTF_8))
        )
        return keys.signEnvelope(envelope) to serverKey
    }

    @Test fun startsWithoutAdminRights() {
        assertEquals(AdminRole.NONE, repository.getRole())
        assertTrue(repository.managedProfilesFlow.value.isEmpty())
    }

    @Test fun grantExpiryPreservesSshConnectionsProfilesAndSignedReceipt() {
        var now = System.currentTimeMillis() / 1000L
        repository = newRepository { now }
        val (envelope, serverKey) = signedGrant()
        assertTrue(repository.applyAdminGrant(envelope, serverKey) is AdminOpResult.Success)
        repository.addServerNode(ServerNode(id = "saved", name = "Saved", host = "192.0.2.20",
            privateKeyPath = "/test/saved.key", password = "saved-password",
            hostPublicKey = "ssh-ed25519 saved-host-key"))
        repository = newRepository { now }
        val profiles = repository.managedProfilesFlow.value
        assertTrue(profiles.isNotEmpty())

        now = envelope.exp
        assertFalse(repository.hasAdminAccess())
        assertEquals(AdminRole.NONE, repository.getRole())
        assertNull(repository.activeGrantFlow.value)
        assertEquals(profiles, repository.managedProfilesFlow.value)
        assertNotNull(storage.getString("admin_active_grant_envelope", null))
        val reloaded = newRepository { now }
        assertFalse(reloaded.hasAdminAccess())
        val connection = reloaded.getServerNode("saved")!!.toSshHostConfig()
        assertEquals("/test/saved.key", connection.privateKeyPath)
        assertEquals("saved-password", connection.password)
        assertEquals("ssh-ed25519 saved-host-key", connection.hostPublicKey)
        assertEquals(profiles, reloaded.managedProfilesFlow.value)
        assertEquals(serverKey, reloaded.getPinnedServerPublicKey())
    }

    @Test fun explicitLocalRevocationDoesNotRestoreScopedRightsOnRestart() {
        val (original, serverKey) = signedGrant()
        val body = json.decodeFromString<AdminGrant>(String(Base64.getUrlDecoder().decode(original.payload)))
            .copy(serverId = "scoped")
        val scoped = keys.signEnvelope(original.copy(payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.encodeToString(body).toByteArray(Charsets.UTF_8))))
        repository.addServerNode(ServerNode(id = "scoped", name = "Scoped", host = "192.0.2.21",
            password = "keep-ssh", privateKeyPath = "/test/scoped.key"))
        repository.setPinnedServerPublicKey(serverKey, "scoped")
        assertTrue(repository.applyAdminGrant(scoped, serverKey) is AdminOpResult.Success)
        repository = newRepository()
        val profiles = repository.managedProfilesFlow.value

        repository.revokeAdminRights()
        val reloaded = newRepository()
        assertEquals(AdminRole.NONE, reloaded.getRole())
        assertFalse(reloaded.hasAdminAccess())
        assertNull(reloaded.getActiveGrant("scoped"))
        assertEquals("keep-ssh", reloaded.getServerNode("scoped")?.password)
        assertEquals(profiles, reloaded.managedProfilesFlow.value)
        assertEquals(serverKey, reloaded.getPinnedServerPublicKey("scoped"))
    }

    @Test fun rejectedInventoryCommandDoesNotMarkReachableServerOffline() {
        val repo = AdminRepository(
            context = null,
            keyManager = keys,
            sshClient = AdminSshClient(
                context = AdminRepository.createDummyContext(),
                networkChecker = { true },
                commandExecutor = { _, _ -> Pair(1, "LIBREROUTE_ADMIN_STATE_DIR is required") }
            ),
            storage = storage
        )
        val node = ServerNode(
            id = "vds-test",
            name = "VDS test",
            host = "192.0.2.1",
            privateKeyPath = "/test/admin.key",
            hostPublicKey = "ssh-ed25519 pinned-host-key",
            lastSeenAt = 123L
        )
        repo.addServerNode(node)

        val inventory = repo.probeServer(node)

        assertNotNull(inventory.probeError)
        val updated = repo.getServerNode(node.id)!!
        assertEquals(ServerNodeStatus.UNKNOWN, updated.status)
        assertEquals(123L, updated.lastSeenAt)
        assertEquals(inventory.probeError, updated.inventory?.probeError)
    }

    @Test fun joinRequestDoesNotGrantRights() {
        val (request, code) = repository.createJoinRequest("profile-1")
        assertEquals(AdminRole.PENDING, repository.getRole())
        assertEquals(6, code.length)
        assertTrue(keys.verifyEnvelope(request, keys.getPublicKey()))
    }

    @Test fun signedPinnedGrantRestoresOnlyWithIntactEnvelope() {
        val (grant, serverKey) = signedGrant()
        assertTrue(repository.applyAdminGrant(grant, serverKey) is AdminOpResult.Success)
        assertEquals(AdminRole.OWNER, newRepository().getRole())

        val changed = json.decodeFromString<AdminGrant>(storage.getString("admin_active_grant", null)!!)
            .copy(profileId = "other-profile")
        storage.putString("admin_active_grant", json.encodeToString(changed))
        assertEquals(AdminRole.NONE, newRepository().getRole())
    }

    @Test fun ownerGrantWithConnectedNodeRepairsMissingControlProfile() {
        val (grant, serverKey) = signedGrant()
        assertTrue(repository.applyAdminGrant(grant, serverKey) is AdminOpResult.Success)
        repository.addServerNode(ServerNode(id = "connected", name = "Connected", host = "192.0.2.10"))

        val reloaded = newRepository()
        val control = reloaded.managedProfilesFlow.value.singleOrNull()
        assertNotNull(control)
        assertEquals(ManagedProfile.PROFILE_TYPE_CONTROL, control?.profileType)
        assertEquals(AdminRole.OWNER, control?.role)
        assertEquals("connected", control?.serverId)
    }

    @Test fun rejectsUnpinnedAndMalformedGrant() {
        val (grant, serverKey) = signedGrant()
        storage.remove("admin_pinned_server_key")
        assertTrue(repository.applyAdminGrant(grant, serverKey) is AdminOpResult.Failure)
        assertEquals(AdminRole.PENDING, repository.getRole())

        repository.setPinnedServerPublicKey(serverKey)
        val malformed = keys.signEnvelope(grant.copy(payload = "{}"))
        assertTrue(repository.applyAdminGrant(malformed, serverKey) is AdminOpResult.Failure)
        assertEquals(AdminRole.PENDING, repository.getRole())
    }

    @Test fun manualBootstrapRequiresMatchingIndependentFingerprint() {
        val (grant, serverKey) = signedGrant()
        storage.remove("admin_pinned_server_key")
        assertTrue(repository.importTrustedBootstrapGrant(grant.toJson(), "SHA256:wrong") is AdminOpResult.Failure)
        assertEquals(AdminRole.PENDING, repository.getRole())
        assertNull(repository.getPinnedServerPublicKey())

        val fingerprint = keys.computeFingerprint(serverKey)!!
        assertTrue(repository.importTrustedBootstrapGrant(grant.toJson(), fingerprint) is AdminOpResult.Success)
        assertEquals(AdminRole.OWNER, repository.getRole())
    }

    @Test fun unavailableChannelsNeverReportApplied() {
        val (grant, serverKey) = signedGrant()
        assertTrue(repository.applyAdminGrant(grant, serverKey) is AdminOpResult.Success)
        assertTrue(repository.createManagedProfile("test") is AdminOpResult.Failure)
        assertTrue(repository.managedProfilesFlow.value.isEmpty())
        assertTrue(repository.issueInvitation("missing").first is AdminOpResult.Failure)
        assertTrue(repository.submitProblemReport("profile-1", "video_stall") is AdminOpResult.Failure)

        val ssh = repository.createManagedProfile(
            "test", channel = AdminChannel.SSH_NETCRAZE,
            hostConfig = SshHostConfig(host = "10.10.1.1", port = 222)
        )
        assertTrue(ssh is AdminOpResult.Failure)
        assertTrue(repository.managedProfilesFlow.value.isEmpty())
    }

    @Test fun sshCreateUsesGoCommandEnvelopeAndTrustedResponse() {
        val (grant, serverKey) = signedGrant()
        assertTrue(repository.applyAdminGrant(grant, serverKey) is AdminOpResult.Success)
        var capturedCommand: kotlinx.serialization.json.JsonObject? = null
        var controlProfileId: String? = null
        val client = AdminSshClient(
            context = AdminRepository.createDummyContext(),
            networkChecker = { true },
            commandExecutor = { args, request ->
                assertEquals(listOf("/data/app/lib/liblibreroute_client.so", "admin", "ssh-exec"), args)
                val envelope = AdminEnvelope.fromJson(json.parseToJsonElement(request).jsonObject["envelope"].toString())!!
                controlProfileId = envelope.profileId
                capturedCommand = json.parseToJsonElement(
                    String(java.util.Base64.getUrlDecoder().decode(envelope.payload), Charsets.UTF_8)
                ).jsonObject
                assertEquals(AdminEnvelope.SUBTYPE_COMMAND, envelope.subtype)
                assertTrue(keys.verifyEnvelope(envelope, keys.getPublicKey()))
                val responsePayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    "{\"op_id\":\"${envelope.opId}\",\"status\":\"applied\"}".toByteArray(Charsets.UTF_8)
                )
                val response = keys.signEnvelope(AdminEnvelope(
                    subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                    profileId = envelope.profileId,
                    rev = envelope.rev + 1L,
                    sender = "server",
                    payload = policySnapshotPayload(envelope, serverKey) ?: responsePayload
                ))
                Pair(0, response.toJson())
            }
        )
        val sendingRepo = AdminRepository(context = null, keyManager = keys, sshClient = client, storage = storage)
        val result = sendingRepo.createManagedProfile(
            name = "phone-test",
            documentUrl = "https://docs.yandex.ru/docs/view/test",
            channel = AdminChannel.SSH_NETCRAZE,
            hostConfig = SshHostConfig(host = "10.10.1.1", port = 222,
                privateKeyPath = "/path/to/key", hostPublicKey = "ssh-ed25519 test")
        )
        assertTrue(result is AdminOpResult.Success)
        assertEquals("profile-1", controlProfileId)
        assertEquals("create", capturedCommand?.get("op")?.jsonPrimitive?.content)
        assertEquals("https://docs.yandex.ru/docs/view/test", capturedCommand?.get("url")?.jsonPrimitive?.content)
        val createdId = sendingRepo.managedProfilesFlow.value.single().id
        val suspend = sendingRepo.suspendProfile(
            profileId = createdId,
            channel = AdminChannel.SSH_NETCRAZE,
            hostConfig = SshHostConfig(host = "10.10.1.1", port = 222,
                privateKeyPath = "/path/to/key", hostPublicKey = "ssh-ed25519 test")
        )
        assertTrue(suspend is AdminOpResult.Success)
        assertEquals("profile-1", controlProfileId)
        assertEquals("suspend", capturedCommand?.get("op")?.jsonPrimitive?.content)
        assertEquals(createdId, capturedCommand?.get("profile_id")?.jsonPrimitive?.content)
        assertEquals(ManagedProfile.STATUS_SUSPENDED, sendingRepo.managedProfilesFlow.value.single().status)
    }

    @Test fun signedResponseForAnotherProfileCannotConfirmCreate() {
        val (grant, serverKey) = signedGrant()
        assertTrue(repository.applyAdminGrant(grant, serverKey) is AdminOpResult.Success)
        val client = AdminSshClient(
            context = AdminRepository.createDummyContext(),
            networkChecker = { true },
            commandExecutor = { _, request ->
                val command = AdminEnvelope.fromJson(json.parseToJsonElement(request).jsonObject["envelope"].toString())!!
                val body = """{"op_id":"${command.opId}","status":"applied"}"""
                val response = keys.signEnvelope(AdminEnvelope(
                    subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                    profileId = "another-profile",
                    rev = command.rev + 1L,
                    sender = "server",
                    payload = java.util.Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(body.toByteArray(Charsets.UTF_8))
                ))
                Pair(0, response.toJson())
            }
        )
        val sendingRepo = AdminRepository(context = null, keyManager = keys, sshClient = client, storage = storage)
        val result = sendingRepo.createManagedProfile(
            name = "test",
            documentUrl = "https://docs.yandex.ru/docs/view/test",
            channel = AdminChannel.SSH_NETCRAZE,
            hostConfig = SshHostConfig(host = "10.10.1.1", port = 222,
                privateKeyPath = "/path/to/key", hostPublicKey = "ssh-ed25519 test")
        )
        assertTrue(result is AdminOpResult.Failure)
        assertTrue(sendingRepo.managedProfilesFlow.value.isEmpty())
    }

    @Test
    fun testServerNodeClusterLifecycle() {
        val repo = newRepository()
        assertEquals(0, repo.getServerNodes().size)

        // 1. Add VDS ServerNode
        val vdsNode = ServerNode(
            id = "vds-frankfurt",
            name = "VDS Frankfurt",
            nodeType = ServerNodeType.VDS,
            host = "194.87.1.50",
            port = 22,
            sshUser = "root",
            authType = SshAuthType.KEY,
            privateKeyPath = "/data/keys/vds.key",
            hostPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5...",
            supportedProtocols = listOf("vyandex", "udp-ipv4", "socks5"),
            status = ServerNodeStatus.ONLINE
        )
        repo.addServerNode(vdsNode)

        // 2. Add Keenetic Router ServerNode
        val routerNode = ServerNode(
            id = "router-home",
            name = "Домашний роутер Keenetic",
            nodeType = ServerNodeType.ROUTER_KEENETIC,
            host = "10.10.1.1",
            port = 222,
            sshUser = "root",
            authType = SshAuthType.KEY,
            privateKeyPath = "/data/keys/router.key",
            hostPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5router...",
            allowPasswordAuth = true,
            supportedProtocols = listOf("vyandex"),
            status = ServerNodeStatus.ONLINE
        )
        repo.addServerNode(routerNode)

        assertEquals(2, repo.getServerNodes().size)
        assertEquals("VDS Frankfurt", repo.getServerNode("vds-frankfurt")?.name)
        assertEquals("Домашний роутер Keenetic", repo.getServerNode("router-home")?.name)

        // 3. Test conversion to SshHostConfig
        val sshConfig = vdsNode.toSshHostConfig()
        assertEquals("194.87.1.50", sshConfig.host)
        assertEquals(22, sshConfig.port)
        assertEquals(SshAuthType.KEY, sshConfig.authType)
        assertFalse(sshConfig.allowPasswordAuth)

        // 4. Update ServerNode status
        val updatedVds = vdsNode.copy(status = ServerNodeStatus.DEGRADED, lastSeenAt = 1234567L)
        repo.updateServerNode(updatedVds)
        assertEquals(ServerNodeStatus.DEGRADED, repo.getServerNode("vds-frankfurt")?.status)
        assertEquals(1234567L, repo.getServerNode("vds-frankfurt")?.lastSeenAt)

        // 5. Test persistence across repository reload
        val reloadedRepo = newRepository()
        assertEquals(2, reloadedRepo.getServerNodes().size)
        assertEquals(ServerNodeStatus.DEGRADED, reloadedRepo.getServerNode("vds-frankfurt")?.status)
        assertEquals("router-home", reloadedRepo.getServerNode("router-home")?.id)

        // 6. Remove ServerNode
        repo.removeServerNode("vds-frankfurt")
        assertEquals(1, repo.getServerNodes().size)
        assertNull(repo.getServerNode("vds-frankfurt"))
        assertNotNull(repo.getServerNode("router-home"))

        // 7. Service capabilities and independent SSH connections have separate lifetimes.
        repo.revokeAdminRights()
        assertEquals(1, repo.getServerNodes().size)
        assertEquals("/data/keys/router.key", newRepository().getServerNode("router-home")?.privateKeyPath)
    }

    @Test
    fun testServerClaimInfoParsing() {
        // Valid with all params
        val uri1 = "libreroute://claim?server=194.87.1.50&port=2222&token=libreroute-setup-abcdef123456&name=VDS+Frankfurt"
        val info1 = ServerClaimInfo.parse(uri1)
        assertNotNull(info1)
        assertEquals("194.87.1.50", info1!!.host)
        assertEquals(2222, info1.port)
        assertEquals("libreroute-setup-abcdef123456", info1.token)
        assertEquals("VDS Frankfurt", info1.name)

        // Valid with default port
        val uri2 = "libreroute://claim?server=vds.example.com&token=libreroute-setup-9999"
        val info2 = ServerClaimInfo.parse(uri2)
        assertNotNull(info2)
        assertEquals("vds.example.com", info2!!.host)
        assertEquals(22, info2.port)
        assertEquals("libreroute-setup-9999", info2.token)
        assertNull(info2.name)

        // Invalid schemes or missing params
        assertNull(ServerClaimInfo.parse("https://claim?server=1.1.1.1&token=xyz"))
        assertNull(ServerClaimInfo.parse("libreroute://other?server=1.1.1.1&token=xyz"))
        assertNull(ServerClaimInfo.parse("libreroute://claim?port=22&token=xyz"))
        assertNull(ServerClaimInfo.parse("libreroute://claim?server=1.1.1.1"))
    }

    @Test
    fun testClaimServerWithSetupKeySuccess() {
        assertClaimSurvivesRestart(password = null)
    }

    @Test fun passwordClaimPreservesCredentialsOnRestart() {
        assertClaimSurvivesRestart(password = "claim-test-password")
    }

    private fun assertClaimSurvivesRestart(password: String?) {
        val serverKey = keys.getPublicKeyBase64()
        val claimUri = "libreroute://claim?server=194.87.1.50&port=22&token=libreroute-setup-secret123&name=Frankfurt-Node"
        val claimInfo = ServerClaimInfo.parse(claimUri)!!

        val mockSshClient = AdminSshClient(
            context = AdminRepository.createDummyContext(),
            networkChecker = { true },
            commandExecutor = { _, requestJson ->
                val clientEnvelope = json.decodeFromString<AdminEnvelope>(
                    json.parseToJsonElement(requestJson).jsonObject["envelope"].toString()
                )

                val grant = AdminGrant(
                    role = AdminRole.OWNER,
                    profileId = clientEnvelope.profileId,
                    adminFingerprint = clientEnvelope.sender,
                    serverPublicKey = serverKey,
                    grantedAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() / 1000L + 86400L,
                    revision = 1L,
                    nonce = clientEnvelope.nonce
                )
                val grantPayload = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.encodeToString(grant).toByteArray(Charsets.UTF_8))

                val grantEnvelope = keys.signEnvelope(AdminEnvelope(
                    subtype = AdminEnvelope.SUBTYPE_GRANT,
                    profileId = clientEnvelope.profileId,
                    rev = 1L,
                    exp = System.currentTimeMillis() / 1000L + 86400L,
                    sender = "server",
                    nonce = clientEnvelope.nonce,
                    payload = grantPayload
                ))

                val grantJson = json.decodeFromString<kotlinx.serialization.json.JsonObject>(json.encodeToString(grantEnvelope))
                val respPayloadStr = java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("{\"op_id\":\"${clientEnvelope.opId}\",\"status\":\"applied\",\"data\":$grantJson}".toByteArray(Charsets.UTF_8))

                val serverResponseEnvelope = keys.signEnvelope(AdminEnvelope(
                    subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                    profileId = clientEnvelope.profileId,
                    rev = 2L,
                    exp = System.currentTimeMillis() / 1000L + 600L,
                    sender = "server",
                    payload = respPayloadStr
                ))

                Pair(0, serverResponseEnvelope.toJson())
            }
        )

        val repo = AdminRepository(
            context = null,
            keyManager = keys,
            sshClient = mockSshClient,
            storage = storage
        )

        assertEquals(AdminRole.NONE, repo.getRole())
        assertEquals(0, repo.getServerNodes().size)

        val result = repo.claimServerWithSetupKey(
            claimInfo = claimInfo,
            hostPublicKey = "ssh-ed25519 AAA...",
            privateKeyPath = "/dummy/test_admin_key",
            password = password
        )
        assertTrue("Expected Success but got: $result", result is AdminOpResult.Success)
        assertEquals(AdminRole.OWNER, repo.getRole())
        assertEquals(serverKey, repo.getPinnedServerPublicKey())

        val nodes = repo.getServerNodes()
        assertEquals(1, nodes.size)
        val claimedNode = nodes[0]
        assertEquals("Frankfurt-Node", claimedNode.name)
        assertEquals("194.87.1.50", claimedNode.host)
        assertEquals(22, claimedNode.port)
        assertEquals(ServerNodeStatus.ONLINE, claimedNode.status)
        val reloaded = newRepository()
        val savedHost = reloaded.getServerNode(claimedNode.id)!!.toSshHostConfig()
        assertEquals("/dummy/test_admin_key", savedHost.privateKeyPath)
        assertEquals(if (password == null) SshAuthType.KEY else SshAuthType.PASSWORD, savedHost.authType)
        assertEquals(password, savedHost.password)
        assertTrue(savedHost.allowPasswordAuth)
        assertEquals("ssh-ed25519 AAA...", savedHost.hostPublicKey)
        assertEquals(ManagedProfile.PROFILE_TYPE_CONTROL, reloaded.managedProfilesFlow.value.single().profileType)
        if (password != null) assertFalse(storage.getString("admin_server_nodes", null)!!.contains(password))
    }

    @Test fun claimWithoutVerifiedHostKeyDoesNotStartPairing() {
        val claim = ServerClaimInfo.parse("libreroute://claim?server=194.87.1.50&token=setup-test")!!
        val result = repository.claimServerWithSetupKey(claim)
        assertTrue(result is AdminOpResult.Failure)
        assertEquals(AdminRole.NONE, repository.getRole())
        assertNull(repository.pendingRequestFlow.value)
    }

    @Test
    fun legacyBundleCannotBeExportedAsInvitation() {
        val entry1 = UserBundleServerEntry(
            serverId = "srv-1",
            serverName = "VDS Москва",
            host = "194.87.1.50",
            port = 22,
            transportType = "vyandex",
            documentUrl = "https://docs.yandex.ru/docs/view?id=test-doc-1",
            sessionNegotiated = true,
            udpEnabled = true
        )
        val entry2 = UserBundleServerEntry(
            serverId = "srv-2",
            serverName = "Домашний роутер Keenetic",
            host = "10.10.1.1",
            port = 222,
            transportType = "vyandex",
            documentUrl = "https://docs.yandex.ru/docs/view?id=test-doc-2",
            sessionNegotiated = true,
            udpEnabled = false
        )

        val bundle = UserBundle(
            profileId = "profile-alice",
            userName = "Алиса",
            serverEntries = listOf(entry1, entry2),
            signature = "SHA256:dummy_test_sig"
        )

        assertThrows(IllegalStateException::class.java) { bundle.toLink() }
    }

    @Test
    fun legacyBundleImportIsRejected() {
        val entry1 = UserBundleServerEntry(
            serverId = "srv-1",
            serverName = "VDS Москва",
            host = "194.87.1.50",
            port = 22,
            transportType = "vyandex",
            documentUrl = "https://docs.yandex.ru/docs/view?id=test-doc-1",
            sessionNegotiated = true,
            udpEnabled = true
        )
        val entry2 = UserBundleServerEntry(
            serverId = "srv-2",
            serverName = "Роутер Keenetic",
            host = "10.10.1.1",
            port = 222,
            transportType = "vyandex",
            documentUrl = "https://docs.yandex.ru/docs/view?id=test-doc-2",
            sessionNegotiated = false,
            udpEnabled = false
        )
        val bundle = UserBundle(
            profileId = "user-bob",
            userName = "Боб",
            serverEntries = listOf(entry1, entry2)
        )
        val raw = json.encodeToString(bundle)
        val encoded = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(raw.toByteArray(Charsets.UTF_8))
        val link = "libreroute://import?bundle=$encoded"
        val parser = io.github.libreroute.util.TunnelLinkParser
        assertNull(UserBundle.fromLink(link))
        assertNull(parser.parse(link))
        assertTrue(parser.parseTunnels(link).isEmpty())
        assertNull(parser.parse(raw))
        assertTrue(parser.parseTunnels(raw).isEmpty())
        assertNull(parser.parse(encoded))
        assertTrue(parser.parseTunnels(encoded).isEmpty())
        assertNull(parser.parse("libreroute://import?bundle=invalid&url=https://example.com"))
        assertEquals(1, parser.parseTunnels("https://docs.yandex.ru/docs/view/manual-profile").size)
    }

    @Test
    fun socketProbeCannotAuthorizeBundleIssuance() {
        val (grant, serverKey) = signedGrant()
        assertTrue(repository.applyAdminGrant(grant, serverKey) is AdminOpResult.Success)

        val node1 = ServerNode(
            id = "node-moscow",
            name = "VDS Москва",
            nodeType = ServerNodeType.VDS,
            host = "127.0.0.1",
            port = 22
        )
        val node2 = ServerNode(
            id = "node-router",
            name = "Роутер Keenetic",
            nodeType = ServerNodeType.ROUTER_KEENETIC,
            host = "10.10.1.1",
            port = 222
        )
        repository.addServerNode(node1)
        repository.addServerNode(node2)

        val profile = ManagedProfile(
            id = "user-charlie",
            name = "Чарли",
            transportType = "vyandex",
            allowedServerIds = listOf("node-moscow", "node-router"),
            allowedProtocols = listOf("vyandex", "udp-ipv4"),
            serverConfigs = mapOf(
                "node-moscow" to ProfileServerConfig(serverId = "node-moscow", documentUrl = "https://docs.yandex.ru/docs/view?id=123"),
                "node-router" to ProfileServerConfig(serverId = "node-router", documentUrl = "https://docs.yandex.ru/docs/view?id=456")
            )
        )

        // Pre-issuance test combinations
        val probes = repository.testCombinations(profile)
        // 2 servers x 2 protocols = 4 probe results
        assertEquals(4, probes.size)
        assertTrue(probes.any { it.serverId == "node-moscow" && it.protocol == "vyandex" })
        assertTrue(probes.any { it.serverId == "node-moscow" && it.protocol == "udp-ipv4" })
        assertTrue(probes.none { it.success })

        // Save profile and issue bundle
        val repoList = repository.managedProfilesFlow.value.toMutableList()
        repoList.add(profile)
        val saveMethod = AdminRepository::class.java.getDeclaredMethod("saveProfiles", List::class.java).apply { isAccessible = true }
        saveMethod.invoke(repository, repoList)

        val (missingKey, missingLink) = repository.issueInvitation("user-charlie")
        assertTrue(missingKey is AdminOpResult.Failure)
        assertTrue(missingLink.isEmpty())
        val (issueResult, bundleLink) = repository.issueInvitation("user-charlie", "recipient-key")
        assertTrue(issueResult is AdminOpResult.Failure)
        assertTrue(bundleLink.isEmpty())
        assertNull(repository.managedProfilesFlow.value.single().issuedTo)
    }

    @Test
    fun testSubmitProblemReportAndLocalPersistence() {
        val node = ServerNode(
            id = "node-alpha",
            name = "VDS Тест",
            nodeType = ServerNodeType.VDS,
            host = "127.0.0.1",
            port = 22,
            status = ServerNodeStatus.ONLINE
        )
        repository.addServerNode(node)

        val result = repository.submitProblemReport(
            profileId = "prof-test",
            code = ProblemReport.CODE_HIGH_RTT,
            details = mapOf("rtt_ms" to "650", "note" to "Lagging stream")
        )

        assertTrue(result is AdminOpResult.Failure)
        val reports = repository.problemReportsFlow.value
        assertEquals(1, reports.size)
        assertEquals(ProblemReport.CODE_HIGH_RTT, reports[0].code)
        assertEquals("prof-test", reports[0].profileId)
        assertEquals("650", reports[0].details["rtt_ms"])

        // Test persistence across repository reloads
        val reloadedRepo = AdminRepository(
            context = null,
            keyManager = keys,
            sshClient = AdminSshClient(
                context = AdminRepository.createDummyContext(),
                networkChecker = { true },
                commandExecutor = { _, _ -> Pair(0, "{}") }
            ),
            storage = storage,
            socketProbe = { _, _, _ -> true }
        )

        val reloadedReports = reloadedRepo.problemReportsFlow.value
        assertEquals(1, reloadedReports.size)
        assertEquals(ProblemReport.CODE_HIGH_RTT, reloadedReports[0].code)
        assertEquals("prof-test", reloadedReports[0].profileId)
    }

    @Test fun testAdminRoleStrictEnforcement() {
        // Initial state: NONE
        assertEquals(AdminRole.NONE, repository.getRole())
        assertFalse(repository.hasAdminAccess())

        // Pending join request: PENDING
        repository.createJoinRequest("prof-gate")
        assertEquals(AdminRole.PENDING, repository.getRole())
        assertFalse("PENDING role must not have admin access", repository.hasAdminAccess())

        // VIEWER role: must NOT have admin access
        val (grantViewer, serverKey) = signedGrantWithRole(AdminRole.VIEWER, "prof-gate")
        assertTrue(repository.applyAdminGrant(grantViewer, serverKey) is AdminOpResult.Success)
        assertEquals(AdminRole.VIEWER, repository.getRole())
        assertFalse("VIEWER role must NOT have admin access", repository.hasAdminAccess())

        // OPERATOR role: has admin access
        repository.createJoinRequest("prof-gate")
        val (grantOp, _) = signedGrantWithRole(AdminRole.OPERATOR, "prof-gate")
        assertTrue(repository.applyAdminGrant(grantOp, serverKey) is AdminOpResult.Success)
        assertEquals(AdminRole.OPERATOR, repository.getRole())
        assertTrue("OPERATOR role must have admin access", repository.hasAdminAccess())

        // OWNER role: has admin access
        repository.createJoinRequest("prof-gate")
        val (grantOwner, _) = signedGrantWithRole(AdminRole.OWNER, "prof-gate")
        assertTrue(repository.applyAdminGrant(grantOwner, serverKey) is AdminOpResult.Success)
        assertEquals(AdminRole.OWNER, repository.getRole())
        assertTrue("OWNER role must have admin access", repository.hasAdminAccess())

        // Revoke: drops back to NONE
        repository.revokeAdminRights()
        assertEquals(AdminRole.NONE, repository.getRole())
        assertFalse(repository.hasAdminAccess())
    }

    private fun signedGrantForRepo(repo: AdminRepository, role: AdminRole, profileId: String): AdminEnvelope {
        val serverKey = keys.getPublicKeyBase64()
        repo.setPinnedServerPublicKey(serverKey)
        val expiry = System.currentTimeMillis() / 1000L + 3600L
        val pendingNonce = repo.pendingRequestFlow.value?.nonce ?: java.util.UUID.randomUUID().toString()
        val grant = AdminGrant(
            role = role,
            profileId = profileId,
            adminFingerprint = keys.getFingerprint(),
            serverPublicKey = serverKey,
            grantedAt = System.currentTimeMillis(),
            expiresAt = expiry,
            revision = 1L,
            nonce = pendingNonce
        )
        val envelope = AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_GRANT,
            profileId = profileId,
            rev = 1L,
            exp = expiry,
            sender = "server",
            nonce = pendingNonce,
            payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(grant).toByteArray(Charsets.UTF_8))
        )
        return keys.signEnvelope(envelope)
    }

    private fun signedGrantWithRole(role: AdminRole, profileId: String): Pair<AdminEnvelope, String> {
        val serverKey = keys.getPublicKeyBase64()
        repository.setPinnedServerPublicKey(serverKey)
        val expiry = System.currentTimeMillis() / 1000L + 3600L
        val pendingNonce = repository.pendingRequestFlow.value?.nonce ?: java.util.UUID.randomUUID().toString()
        val grant = AdminGrant(
            role = role,
            profileId = profileId,
            adminFingerprint = keys.getFingerprint(),
            serverPublicKey = serverKey,
            grantedAt = System.currentTimeMillis(),
            expiresAt = expiry,
            revision = 1L,
            nonce = pendingNonce
        )
        val envelope = AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_GRANT,
            profileId = profileId,
            rev = 1L,
            exp = expiry,
            sender = "server",
            nonce = pendingNonce,
            payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.encodeToString(grant).toByteArray(Charsets.UTF_8))
        )
        return keys.signEnvelope(envelope) to serverKey
    }

    @Test
    fun testIssueInvitationSuccessAndRedeemPipeline() {
        val serverKey = keys.getPublicKeyBase64()
        repository.setPinnedServerPublicKey(serverKey)

        // Give repo OWNER role
        val (grantOwner, _) = signedGrantWithRole(AdminRole.OWNER, "prof-charlie")
        repository.applyAdminGrant(grantOwner, serverKey)

        val serverNode = ServerNode(
            id = "vds-1",
            name = "VDS Тест",
            nodeType = ServerNodeType.VDS,
            host = "10.0.0.1",
            port = 22,
            hostPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleHostKey",
            privateKeyPath = "/test/path/admin.key",
            status = ServerNodeStatus.ONLINE
        )
        repository.addServerNode(serverNode)

        val profile = ManagedProfile(
            id = "prof-charlie",
            name = "Чарли",
            transportType = "vyandex",
            allowedServerIds = listOf("vds-1"),
            allowedProtocols = listOf("vyandex", "udp-ipv4"),
            serverConfigs = mapOf(
                "vds-1" to ProfileServerConfig(serverId = "vds-1", documentUrl = "https://docs.yandex.ru/docs/view?id=test-doc")
            )
        )
        val repoList = repository.managedProfilesFlow.value.toMutableList()
        repoList.add(profile)
        val saveMethod = AdminRepository::class.java.getDeclaredMethod("saveProfiles", List::class.java).apply { isAccessible = true }
        saveMethod.invoke(repository, repoList)

        val recipientCrypto = RecipientInvitationCrypto(InMemoryInvitationStateStore())
        val recipientPub = recipientCrypto.getOrCreatePublicKeyBase64()

        var lastRedeemOpId: String? = null

        // Custom executor that simulates server behavior for issue and redeem
        val customExecutor: (List<String>, String) -> Pair<Int, String> = { _, requestJson ->
            val req = json.decodeFromString<AdminSSHRequest>(requestJson)
            val env = req.envelope

            if (env.subtype == AdminEnvelope.SUBTYPE_COMMAND) {
                val cmdJson = String(Base64.getUrlDecoder().decode(env.payload), Charsets.UTF_8)
                val cmdObj = json.parseToJsonElement(cmdJson).jsonObject
                val op = cmdObj["op"]?.jsonPrimitive?.content
                val snapshotPayload = policySnapshotPayload(env, serverKey)
                if (snapshotPayload != null) {
                    Pair(0, keys.signEnvelope(AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                        profileId = env.profileId, opId = env.opId, rev = env.rev + 1, sender = "server", payload = snapshotPayload)).toJson())
                } else if (op == "issue") {
                    val devicePub = cmdObj["device_pub"]!!.jsonPrimitive.content
                    val nowSec = System.currentTimeMillis() / 1000L
                    val candidateExpSec = nowSec + 900L
                    val envelopeExpSec = nowSec + 300L
                    val plain = buildJsonObject {
                        put("invitation_id", env.opId)
                        put("profile_id", "prof-charlie")
                        put("name", "Чарли")
                        put("server_id", "vds-1")
                        put("url", "https://docs.yandex.ru/docs/view?id=test-doc")
                        put("secret_key", "0123456789abcdef0123456789abcdef")
                        put("parameters", buildJsonObject {
                            put("transport", "vyandex")
                            put("session_negotiate", "true")
                            put("udp_enabled", "true")
                            put("allowed_server_ids", "vds-1")
                        })
                        put("revision", 1L)
                        put("issued_time", nowSec)
                        put("expires_at", candidateExpSec)
                        put("recipient_pub_sha256", recipientCrypto.publicKeyHashHex())
                    }
                    val (ephemPub, nonce, cipher) = RecipientInvitationCrypto.encryptForDevice(
                        devicePub,
                        plain.toString().toByteArray(Charsets.UTF_8)
                    )
                    val issueResp = buildJsonObject {
                        put("op_id", env.opId)
                        put("status", "applied")
                        put("data", buildJsonObject {
                            put("profile_id", "prof-charlie")
                            put("invitation_id", env.opId)
                            put("expires_at", candidateExpSec)
                            put("revision", 1L)
                            put("ephemeral_pub", ephemPub)
                            put("nonce", nonce)
                            put("encrypted_payload", cipher)
                        })
                        put("timestamp", nowSec)
                    }
                    val respEnv = AdminEnvelope(
                        subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                        profileId = "prof-charlie",
                        opId = env.opId,
                        rev = env.rev + 1L,
                        exp = envelopeExpSec,
                        sender = "server",
                        payload = Base64.getUrlEncoder().withoutPadding().encodeToString(issueResp.toString().toByteArray(Charsets.UTF_8))
                    )
                    Pair(0, keys.signEnvelope(respEnv).toJson())
                } else {
                    Pair(1, "unknown op")
                }
            } else if (env.subtype == AdminEnvelope.SUBTYPE_INVITATION_REDEEM) {
                lastRedeemOpId = env.opId
                val redeemReqJson = String(Base64.getUrlDecoder().decode(env.payload), Charsets.UTF_8)
                val redeemObj = json.parseToJsonElement(redeemReqJson).jsonObject
                val invId = redeemObj["invitation_id"]!!.jsonPrimitive.content
                val nowSec = System.currentTimeMillis() / 1000L
                val redeemResp = buildJsonObject {
                    put("op_id", env.opId)
                    put("status", "verified")
                    put("data", buildJsonObject {
                        put("invitation_id", invId)
                        put("profile_id", "prof-charlie")
                        put("revision", env.rev)
                    })
                }
                val receiptEnv = AdminEnvelope(
                    subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                    profileId = "prof-charlie",
                    opId = env.opId,
                    rev = env.rev + 1L,
                    exp = nowSec + 300L,
                    sender = "server",
                    payload = Base64.getUrlEncoder().withoutPadding().encodeToString(redeemResp.toString().toByteArray(Charsets.UTF_8))
                )
                Pair(0, keys.signEnvelope(receiptEnv).toJson())
            } else {
                Pair(1, "unknown subtype")
            }
        }

        val testRepo = AdminRepository(
            context = null,
            keyManager = keys,
            sshClient = AdminSshClient(
                context = AdminRepository.createDummyContext(),
                networkChecker = { true },
                commandExecutor = customExecutor
            ),
            storage = storage,
            socketProbe = { _, _, _ -> true },
            combinationProber = { s, p, d, _ ->
                ProbeResult(serverId = s.id, serverName = s.name, protocol = p, success = true, rttMs = 15L, message = "OK")
            }
        )
        testRepo.setPinnedServerPublicKey(serverKey)
        testRepo.createJoinRequest("prof-charlie")
        val grantOwnerTest = signedGrantForRepo(testRepo, AdminRole.OWNER, "prof-charlie")
        val grantRes = testRepo.applyAdminGrant(grantOwnerTest, serverKey)
        assertTrue("Grant should succeed: ${(grantRes as? AdminOpResult.Failure)?.error}", grantRes is AdminOpResult.Success)
        testRepo.addServerNode(serverNode)

        val repoListTest = testRepo.managedProfilesFlow.value.toMutableList()
        repoListTest.add(profile)
        saveMethod.invoke(testRepo, repoListTest)

        // 1. Issue invitation
        val (issueResult, inviteLink) = testRepo.issueInvitation("prof-charlie", recipientPub, serverNode)
        assertTrue("Issue should succeed: ${(issueResult as? AdminOpResult.Failure)?.error}", issueResult is AdminOpResult.Success)
        assertTrue(inviteLink.startsWith("libreroute://invitation?data="))

        // A signed invitation must not be redeemed against another selected server.
        val wrongServer = serverNode.copy(id = "vds-2")
        val (wrongResult, wrongTunnel) = testRepo.redeemInvitation(
            invitationLink = inviteLink,
            serverNode = wrongServer,
            trustedServerPublicKeyBase64 = serverKey,
            recipientCrypto = recipientCrypto
        )
        assertTrue(wrongResult is AdminOpResult.Failure)
        assertNull(wrongTunnel)
        assertNull(lastRedeemOpId)

        // 2. Redeem invitation on client
        val (redeemResult, tunnel) = testRepo.redeemInvitation(
            invitationLink = inviteLink,
            serverNode = serverNode,
            trustedServerPublicKeyBase64 = serverKey,
            recipientCrypto = recipientCrypto
        )
        assertTrue("Redeem should succeed: ${(redeemResult as? AdminOpResult.Failure)?.error}", redeemResult is AdminOpResult.Success)
        assertNotNull(tunnel)
        assertEquals("Чарли", tunnel!!.name)
        assertEquals("0123456789abcdef0123456789abcdef", tunnel.encryptionKey)
        assertTrue(tunnel.transportConnPayload.contains("--session-negotiate"))
        assertTrue(tunnel.transportConnPayload.contains("--udp-ipv4"))
        assertNotNull(lastRedeemOpId)
    }

    @Test
    fun testRedeemInvitationRejectsForgedServerSignature() {
        val untrustedKeys = AdminKeyManager()
        val recipientCrypto = RecipientInvitationCrypto(InMemoryInvitationStateStore())
        val recipientPub = recipientCrypto.getOrCreatePublicKeyBase64()
        val nowSec = System.currentTimeMillis() / 1000L

        val plain = buildJsonObject {
            put("invitation_id", "inv-1")
            put("profile_id", "prof-forged")
            put("name", "Подделка")
            put("url", "https://docs.yandex.ru/docs/view?id=test-doc")
            put("secret_key", "0123456789abcdef0123456789abcdef")
            put("parameters", buildJsonObject { put("transport", "vyandex") })
            put("revision", 1L)
            put("issued_time", nowSec)
            put("expires_at", nowSec + 900L)
            put("recipient_pub_sha256", recipientCrypto.publicKeyHashHex())
        }
        val (ephemPub, nonce, cipher) = RecipientInvitationCrypto.encryptForDevice(
            recipientPub,
            plain.toString().toByteArray(Charsets.UTF_8)
        )
        val issueResp = buildJsonObject {
            put("op_id", "inv-1")
            put("status", "applied")
            put("data", buildJsonObject {
                put("profile_id", "prof-forged")
                put("invitation_id", "inv-1")
                put("expires_at", nowSec + 900L)
                put("revision", 1L)
                put("ephemeral_pub", ephemPub)
                put("nonce", nonce)
                put("encrypted_payload", cipher)
            })
            put("timestamp", nowSec)
        }
        val forgedEnv = AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_RESPONSE,
            profileId = "prof-forged",
            opId = "inv-1",
            rev = 1L,
            exp = nowSec + 900L,
            sender = "server",
            payload = Base64.getUrlEncoder().withoutPadding().encodeToString(issueResp.toString().toByteArray(Charsets.UTF_8))
        )
        val ecGen = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
        }
        val forgedPair = ecGen.generateKeyPair()
        val signer = java.security.Signature.getInstance("SHA256withECDSA").apply {
            initSign(forgedPair.private)
            update(forgedEnv.canonicalSigningString().toByteArray(Charsets.UTF_8))
        }
        val forgedSig = Base64.getEncoder().encodeToString(signer.sign())
        val signedForged = forgedEnv.copy(signature = forgedSig)
        val forgedLink = ServerIssuedInvitation.toLink(signedForged)

        val trustedServerKey = keys.getPublicKeyBase64()
        val (result, tunnel) = repository.redeemInvitation(
            invitationLink = forgedLink,
            trustedServerPublicKeyBase64 = trustedServerKey,
            recipientCrypto = recipientCrypto
        )
        assertTrue(result is AdminOpResult.Failure)
        assertNull(tunnel)
        assertTrue((result as AdminOpResult.Failure).error.contains("Server signature does not match the trusted key"))
    }

    @Test
    fun testRedeemInvitationRejectsExpiredLink() {
        val recipientCrypto = RecipientInvitationCrypto(InMemoryInvitationStateStore())
        val recipientPub = recipientCrypto.getOrCreatePublicKeyBase64()
        val nowSec = System.currentTimeMillis() / 1000L
        val pastSec = nowSec - 200L

        val plain = buildJsonObject {
            put("invitation_id", "inv-exp")
            put("profile_id", "prof-exp")
            put("name", "Истёкший")
            put("url", "https://docs.yandex.ru/docs/view?id=test-doc")
            put("secret_key", "0123456789abcdef0123456789abcdef")
            put("parameters", buildJsonObject { put("transport", "vyandex") })
            put("revision", 1L)
            put("issued_time", pastSec - 300L)
            put("expires_at", pastSec)
            put("recipient_pub_sha256", recipientCrypto.publicKeyHashHex())
        }
        val (ephemPub, nonce, cipher) = RecipientInvitationCrypto.encryptForDevice(
            recipientPub,
            plain.toString().toByteArray(Charsets.UTF_8)
        )
        val issueResp = buildJsonObject {
            put("op_id", "inv-exp")
            put("status", "applied")
            put("data", buildJsonObject {
                put("profile_id", "prof-exp")
                put("invitation_id", "inv-exp")
                put("expires_at", pastSec)
                put("revision", 1L)
                put("ephemeral_pub", ephemPub)
                put("nonce", nonce)
                put("encrypted_payload", cipher)
            })
            put("timestamp", pastSec - 300L)
        }
        val expiredEnv = AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_RESPONSE,
            profileId = "prof-exp",
            opId = "inv-exp",
            rev = 1L,
            exp = pastSec,
            sender = "server",
            payload = Base64.getUrlEncoder().withoutPadding().encodeToString(issueResp.toString().toByteArray(Charsets.UTF_8))
        )
        val signedExpired = keys.signEnvelope(expiredEnv)
        val expiredLink = ServerIssuedInvitation.toLink(signedExpired)

        val (result, tunnel) = repository.redeemInvitation(
            invitationLink = expiredLink,
            trustedServerPublicKeyBase64 = keys.getPublicKeyBase64(),
            recipientCrypto = recipientCrypto
        )
        assertTrue(result is AdminOpResult.Failure)
        assertNull(tunnel)
        assertTrue((result as AdminOpResult.Failure).error.contains("Server response has expired"))
    }

    @Test
    fun testRedeemInvitationRejectsMismatchedRecipient() {
        val cryptoA = RecipientInvitationCrypto(InMemoryInvitationStateStore())
        val cryptoB = RecipientInvitationCrypto(InMemoryInvitationStateStore())
        val nowSec = System.currentTimeMillis() / 1000L

        // Encrypted for Device A
        val plain = buildJsonObject {
            put("invitation_id", "inv-diff")
            put("profile_id", "prof-diff")
            put("name", "Чужой")
            put("url", "https://docs.yandex.ru/docs/view?id=test-doc")
            put("secret_key", "0123456789abcdef0123456789abcdef")
            put("parameters", buildJsonObject { put("transport", "vyandex") })
            put("revision", 1L)
            put("issued_time", nowSec)
            put("expires_at", nowSec + 900L)
            put("recipient_pub_sha256", cryptoA.publicKeyHashHex())
        }
        val (ephemPub, nonce, cipher) = RecipientInvitationCrypto.encryptForDevice(
            cryptoA.getOrCreatePublicKeyBase64(),
            plain.toString().toByteArray(Charsets.UTF_8)
        )
        val issueResp = buildJsonObject {
            put("op_id", "inv-diff")
            put("status", "applied")
            put("data", buildJsonObject {
                put("profile_id", "prof-diff")
                put("invitation_id", "inv-diff")
                put("expires_at", nowSec + 900L)
                put("revision", 1L)
                put("ephemeral_pub", ephemPub)
                put("nonce", nonce)
                put("encrypted_payload", cipher)
            })
            put("timestamp", nowSec)
        }
        val env = AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_RESPONSE,
            profileId = "prof-diff",
            opId = "inv-diff",
            rev = 1L,
            exp = nowSec + 900L,
            sender = "server",
            payload = Base64.getUrlEncoder().withoutPadding().encodeToString(issueResp.toString().toByteArray(Charsets.UTF_8))
        )
        val link = ServerIssuedInvitation.toLink(keys.signEnvelope(env))

        // Device B attempts to redeem
        val (result, tunnel) = repository.redeemInvitation(
            invitationLink = link,
            trustedServerPublicKeyBase64 = keys.getPublicKeyBase64(),
            recipientCrypto = cryptoB
        )
        assertTrue(result is AdminOpResult.Failure)
        assertNull(tunnel)
    }

    @Test
    fun testRedeemInvitationFailsClosedWhenSocketProbeFails() {
        val serverKey = keys.getPublicKeyBase64()
        repository.setPinnedServerPublicKey(serverKey)

        val serverNode = ServerNode(
            id = "vds-probe-fail",
            name = "VDS Unreachable",
            nodeType = ServerNodeType.VDS,
            host = "192.0.2.1",
            port = 22,
            hostPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExampleHostKey",
            privateKeyPath = "/test/path/admin.key",
            status = ServerNodeStatus.ONLINE
        )
        repository.addServerNode(serverNode)

        val recipientCrypto = RecipientInvitationCrypto(InMemoryInvitationStateStore())
        val recipientPub = recipientCrypto.getOrCreatePublicKeyBase64()
        val nowSec = System.currentTimeMillis() / 1000L

        val plain = buildJsonObject {
            put("invitation_id", "inv-reach")
            put("profile_id", "prof-reach")
            put("name", "Недостижимый")
            put("url", "https://docs.yandex.ru/docs/view?id=test-doc")
            put("secret_key", "0123456789abcdef0123456789abcdef")
            put("parameters", buildJsonObject {
                put("transport", "vyandex")
                put("allowed_server_ids", "vds-probe-fail")
            })
            put("revision", 1L)
            put("issued_time", nowSec)
            put("expires_at", nowSec + 900L)
            put("recipient_pub_sha256", recipientCrypto.publicKeyHashHex())
        }
        val (ephemPub, nonce, cipher) = RecipientInvitationCrypto.encryptForDevice(
            recipientPub,
            plain.toString().toByteArray(Charsets.UTF_8)
        )
        val issueResp = buildJsonObject {
            put("op_id", "inv-reach")
            put("status", "applied")
            put("data", buildJsonObject {
                put("profile_id", "prof-reach")
                put("invitation_id", "inv-reach")
                put("expires_at", nowSec + 900L)
                put("revision", 1L)
                put("ephemeral_pub", ephemPub)
                put("nonce", nonce)
                put("encrypted_payload", cipher)
            })
            put("timestamp", nowSec)
        }
        val respEnv = AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_RESPONSE,
            profileId = "prof-reach",
            opId = "inv-reach",
            rev = 1L,
            exp = nowSec + 900L,
            sender = "server",
            payload = Base64.getUrlEncoder().withoutPadding().encodeToString(issueResp.toString().toByteArray(Charsets.UTF_8))
        )
        val link = ServerIssuedInvitation.toLink(keys.signEnvelope(respEnv))

        // Custom executor that succeeds on redeem receipt, BUT socket probe fails!
        val customExecutor: (List<String>, String) -> Pair<Int, String> = { _, requestJson ->
            val req = json.decodeFromString<AdminSSHRequest>(requestJson)
            val env = req.envelope
            val redeemResp = buildJsonObject {
                put("op_id", env.opId)
                put("status", "verified")
                put("data", buildJsonObject {
                    put("invitation_id", "inv-reach")
                    put("profile_id", "prof-reach")
                    put("revision", env.rev)
                })
            }
            val receiptEnv = AdminEnvelope(
                subtype = AdminEnvelope.SUBTYPE_RESPONSE,
                profileId = "prof-reach",
                opId = env.opId,
                rev = env.rev + 1L,
                exp = nowSec + 300L,
                sender = "server",
                payload = Base64.getUrlEncoder().withoutPadding().encodeToString(redeemResp.toString().toByteArray(Charsets.UTF_8))
            )
            Pair(0, keys.signEnvelope(receiptEnv).toJson())
        }

        val testRepo = AdminRepository(
            context = null,
            keyManager = keys,
            sshClient = AdminSshClient(
                context = AdminRepository.createDummyContext(),
                networkChecker = { true },
                commandExecutor = customExecutor
            ),
            storage = storage,
            // Socket probe FAILS!
            socketProbe = { _, _, _ -> false }
        )

        val (result, tunnel) = testRepo.redeemInvitation(
            invitationLink = link,
            serverNode = serverNode,
            trustedServerPublicKeyBase64 = serverKey,
            recipientCrypto = recipientCrypto
        )
        assertTrue("Expected Failure, got: $result", result is AdminOpResult.Failure)
        assertNull(tunnel)
        val errorMsg = (result as AdminOpResult.Failure).error
        assertTrue("Expected Pre-flight error, got: $errorMsg", errorMsg.contains("Pre-flight проверка"))
    }

    @Test
    fun sshKeyManagement_jvmFallbackGeneration() {
        val testRepo = newRepository()
        // Ensure clean state
        val keyFile = testRepo.sshKeyManager.getAdminSshKeyFile()
        keyFile?.delete()
        java.io.File(keyFile?.parentFile, AdminSshKeyManager.PUBKEY_FILE_NAME).delete()

        val (privPath, pubKey) = testRepo.generateAdminSshKey()
        assertTrue("Private key path should not be blank", privPath.isNotBlank())
        assertTrue("Private key file should exist", java.io.File(privPath).exists())
        assertTrue("Public key should start with ssh-rsa or ssh-ed25519", pubKey.startsWith("ssh-"))
        assertTrue("Repository should report hasAdminSshKey", testRepo.hasAdminSshKey())
        assertEquals(pubKey, testRepo.getAdminSshPublicKey()?.trim())
        assertEquals(privPath, testRepo.getEffectiveAdminSshKeyPath())
    }

    @Test
    fun sshKeyManagement_nativeCommandExecution() {
        val mockEd25519Priv = "-----BEGIN OPENSSH PRIVATE KEY-----\nbW9ja1ByaXZhdGVLZXk=\n-----END OPENSSH PRIVATE KEY-----"
        val mockEd25519Pub = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGtV+testMockPublicKeyForLibreRoute"
        val mockResponse = """
            {
                "key_type": "ed25519",
                "private_key_pem": "$mockEd25519Priv",
                "public_key_openssh": "$mockEd25519Pub",
                "fingerprint": "SHA256:mockFingerprint123456"
            }
        """.trimIndent()

        val keyMgr = AdminSshKeyManager(
            context = null,
            commandExecutor = { cmd ->
                if (cmd.contains("gen-ssh-key")) {
                    Pair(0, mockResponse)
                } else {
                    Pair(1, "unknown command")
                }
            }
        )

        val (privPath, pubKey) = keyMgr.generateSshKeyPair()
        assertEquals(mockEd25519Pub, pubKey)
        val file = java.io.File(privPath)
        assertTrue(file.exists())
        assertEquals(mockEd25519Priv, file.readText())
        val pubFile = java.io.File(file.parentFile, AdminSshKeyManager.PUBKEY_FILE_NAME)
        assertTrue(pubFile.exists())
        assertTrue(pubFile.readText().startsWith(mockEd25519Pub))
    }

    @Test
    fun sshKeyManagement_importPrivateKey() {
        val testRepo = newRepository()
        // Generate a valid RSA key first to get standard PKCS#8 PEM
        val kpg = java.security.KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val privPem = "-----BEGIN PRIVATE KEY-----\n" +
            java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded) +
            "\n-----END PRIVATE KEY-----"

        val success = testRepo.importAdminSshPrivateKey(privPem)
        assertTrue("Import should succeed for valid PKCS#8 PEM", success)
        assertTrue(testRepo.hasAdminSshKey())
        val pub = testRepo.getAdminSshPublicKey()
        assertNotNull(pub)
        assertTrue("Public key extracted from imported key should start with ssh-rsa", pub!!.startsWith("ssh-rsa"))
    }
}
