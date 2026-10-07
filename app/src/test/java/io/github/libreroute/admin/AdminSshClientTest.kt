package io.github.libreroute.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlinx.serialization.json.jsonObject

class AdminSshClientTest {

    @Test
    fun testNetcrazeSubnetIpValidation() {
        val client = AdminSshClient(context = createMockContext())

        // Valid IPs in 10.10.1.0/24
        assertTrue(client.isNetcrazeSubnetIp("10.10.1.1"))
        assertTrue(client.isNetcrazeSubnetIp("10.10.1.55"))
        assertTrue(client.isNetcrazeSubnetIp("10.10.1.254"))

        // Invalid IPs (outside 10.10.1.0/24)
        assertFalse(client.isNetcrazeSubnetIp("10.10.1.0")) // Network addr
        assertFalse(client.isNetcrazeSubnetIp("10.10.1.255")) // Broadcast addr
        assertFalse(client.isNetcrazeSubnetIp("10.10.2.1"))
        assertFalse(client.isNetcrazeSubnetIp("192.168.1.1"))
        assertFalse(client.isNetcrazeSubnetIp("172.16.1.1"))
        assertFalse(client.isNetcrazeSubnetIp("invalid"))
    }

    @Test
    fun testNetcrazeLanPolicyEnforcement() {
        val context = createMockContext()

        // 1. Device is NOT in Netcraze LAN -> Must fail
        val clientOutsideLan = AdminSshClient(
            context = context,
            networkChecker = { false }
        )
        val envelope = AdminEnvelope(
            ver = 1,
            type = "ADMIN",
            subtype = "create",
            profileId = "prof-1",
            opId = UUID.randomUUID().toString(),
            sender = "SHA256:test"
        )
        val netcrazeConfig = SshHostConfig(
            host = "10.10.1.1",
            port = 222,
            username = "root",
            authType = SshAuthType.KEY,
            privateKeyPath = "/path/to/key",
            hostPublicKey = "ssh-ed25519 test-pinned-host-key"
        )

        val resultBlocked = clientOutsideLan.executeAdminEnvelope(
            envelope = envelope,
            hostConfig = netcrazeConfig,
            channel = AdminChannel.SSH_NETCRAZE
        )
        assertTrue("Execution must fail when device is outside Netcraze LAN", resultBlocked is AdminOpResult.Failure)
        val failureMsg = (resultBlocked as AdminOpResult.Failure).error
        assertTrue("Error message must mention LAN / 10.10.1.0/24", failureMsg.contains("10.10.1.0/24"))

        // 2. Device IS in Netcraze LAN -> Allowed to execute
        var capturedCmd: List<String>? = null
        var capturedRequest: String? = null
        val clientInsideLan = AdminSshClient(
            context = context,
            networkChecker = { true },
            commandExecutor = { cmd, request ->
                capturedCmd = cmd
                capturedRequest = request
                Pair(0, AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_RESPONSE, profileId = envelope.profileId, opId = envelope.opId, sender = "server").toJson())
            }
        )

        val resultAllowed = clientInsideLan.executeAdminEnvelope(
            envelope = envelope,
            hostConfig = netcrazeConfig,
            channel = AdminChannel.SSH_NETCRAZE
        )
        assertTrue("Execution should succeed inside LAN", resultAllowed is AdminOpResult.Success)
        assertNotNull(capturedCmd)
        assertFalse("Sensitive envelope must not appear in process arguments", capturedCmd!!.any { it.contains(envelope.opId) })
        assertTrue("Operation ID must be in stdin request", capturedRequest!!.contains(envelope.opId))
    }

    @Test
    fun testVdsHostPolicyEnforcement() {
        val context = createMockContext()
        val envelope = AdminEnvelope(
            ver = 1,
            type = "ADMIN",
            subtype = "suspend",
            profileId = "prof-2",
            opId = UUID.randomUUID().toString(),
            sender = "SHA256:test"
        )

        // 1. VDS with Password auth when allowPasswordAuth = false -> Must fail
        val vdsPasswordForbidden = SshHostConfig(
            host = "185.22.67.89",
            port = 22,
            username = "root",
            authType = SshAuthType.PASSWORD,
            password = "secret-password",
            allowPasswordAuth = false
        )
        val client = AdminSshClient(
            context = context,
            commandExecutor = { _, _ -> Pair(0, "") }
        )
        val resultPasswordBlocked = client.executeAdminEnvelope(
            envelope = envelope,
            hostConfig = vdsPasswordForbidden,
            channel = AdminChannel.SSH_VDS
        )
        assertTrue("Password auth on VDS must fail when policy forbids password", resultPasswordBlocked is AdminOpResult.Failure)
        assertTrue((resultPasswordBlocked as AdminOpResult.Failure).error.contains("VDS запрещает"))

        // 2. VDS with Key auth -> Must succeed
        var capturedCmd: List<String>? = null
        val vdsKeyAllowed = SshHostConfig(
            host = "185.22.67.89",
            port = 22,
            username = "root",
            authType = SshAuthType.KEY,
            privateKeyPath = "/path/to/key",
            hostPublicKey = "ssh-ed25519 test-pinned-host-key",
            allowPasswordAuth = false
        )
        val clientKey = AdminSshClient(
            context = context,
            commandExecutor = { cmd, _ ->
                capturedCmd = cmd
                Pair(0, AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_RESPONSE, profileId = envelope.profileId, opId = envelope.opId, sender = "server").toJson())
            }
        )
        val resultKeyOk = clientKey.executeAdminEnvelope(
            envelope = envelope,
            hostConfig = vdsKeyAllowed,
            channel = AdminChannel.SSH_VDS
        )
        assertTrue("Key auth on VDS must succeed", resultKeyOk is AdminOpResult.Success)
        assertNotNull(capturedCmd)
        assertEquals(listOf("/data/app/lib/liblibreroute_client.so", "admin", "ssh-exec"), capturedCmd)
    }

    @Test
    fun testOpIdPreservationForIdempotency() {
        val context = createMockContext()
        val testOpId = "550e8400-e29b-41d4-a716-446655440000"
        val envelope = AdminEnvelope(
            ver = 1,
            type = "ADMIN",
            subtype = "create",
            profileId = "prof-idempotency",
            opId = testOpId,
            sender = "SHA256:test"
        )
        val hostConfig = SshHostConfig(
            host = "10.10.1.1",
            port = 222,
            username = "root",
            authType = SshAuthType.KEY,
            privateKeyPath = "/path/to/key",
            hostPublicKey = "ssh-ed25519 test-pinned-host-key"
        )

        var executedOpId: String? = null
        val client = AdminSshClient(
            context = context,
            networkChecker = { true },
            commandExecutor = { _, request ->
                executedOpId = kotlinx.serialization.json.Json.parseToJsonElement(request)
                    .jsonObject["envelope"]?.toString()?.let(AdminEnvelope::fromJson)?.opId
                Pair(0, AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_RESPONSE, profileId = envelope.profileId, opId = testOpId, sender = "server").toJson())
            }
        )

        val result = client.executeAdminEnvelope(envelope, hostConfig, AdminChannel.SSH_NETCRAZE)
        assertTrue(result is AdminOpResult.Success)
        assertEquals("OpID passed via SSH command line must match the envelope OpID exactly", testOpId, executedOpId)
    }

    @Test
    fun testProbeServerSuccessful() {
        val context = createMockContext()
        val hostConfig = SshHostConfig(
            host = "89.167.49.64",
            port = 2202,
            username = "root",
            authType = SshAuthType.KEY,
            privateKeyPath = "/path/to/key",
            hostPublicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAI-test"
        )

        var capturedCmd: List<String>? = null
        val mockInventoryJson = """
            {
                "os_name": "Ubuntu",
                "os_version": "22.04.4 LTS",
                "arch": "x86_64",
                "init_system": "systemd",
                "package_manager": "apt",
                "ram_total_mb": 1980,
                "ram_free_mb": 1420,
                "disk_total_mb": 40960,
                "disk_free_mb": 31200,
                "has_docker": true,
                "has_podman": false,
                "installed_libreroute_version": "libreroute v1.2.0",
                "is_compatible": true,
                "probed_at": 1711890000
            }
        """.trimIndent()

        val client = AdminSshClient(
            context = context,
            commandExecutor = { cmd, _ ->
                capturedCmd = cmd
                Pair(0, mockInventoryJson)
            }
        )

        val inv = client.probeServer(hostConfig)
        assertNotNull(capturedCmd)
        assertEquals(listOf("/data/app/lib/liblibreroute_client.so", "admin", "ssh-probe"), capturedCmd)
        assertEquals("Ubuntu", inv.osName)
        assertEquals("22.04.4 LTS", inv.osVersion)
        assertEquals("x86_64", inv.arch)
        assertEquals("systemd", inv.initSystem)
        assertEquals("apt", inv.packageManager)
        assertEquals(1980L, inv.ramTotalMb)
        assertEquals(1420L, inv.ramFreeMb)
        assertEquals(40960L, inv.diskTotalMb)
        assertEquals(31200L, inv.diskFreeMb)
        assertTrue(inv.hasDocker)
        assertFalse(inv.hasPodman)
        assertEquals("libreroute v1.2.0", inv.installedLibreRouteVersion)
        assertTrue(inv.isCompatible)
        assertEquals(null, inv.probeError)
    }

    @Test
    fun testProbeServerPolicyEnforcement() {
        val context = createMockContext()
        // Missing pinned host key -> error
        val badConfig = SshHostConfig(
            host = "89.167.49.64",
            port = 2202,
            username = "root",
            authType = SshAuthType.KEY,
            privateKeyPath = "/path/to/key",
            hostPublicKey = null
        )
        val client = AdminSshClient(context = context)
        val inv = client.probeServer(badConfig)
        assertFalse(inv.isCompatible)
        assertNotNull(inv.probeError)
        assertTrue(inv.probeError!!.contains("host public key"))
    }

    private fun createMockContext(): android.content.Context {
        // Minimal proxy/mock context providing applicationInfo.nativeLibraryDir
        val appInfo = android.content.pm.ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/lib"
        }
        return object : android.content.ContextWrapper(null) {
            override fun getApplicationInfo(): android.content.pm.ApplicationInfo = appInfo
            override fun getFilesDir(): java.io.File = java.io.File(System.getProperty("java.io.tmpdir") ?: ".")
        }
    }

    private fun assertNotNull(obj: Any?) {
        assertTrue(obj != null)
    }
}
