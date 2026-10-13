package io.github.libreroute.admin

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SshUpdateArtifactTest {
    private val signed = buildJsonObject { put("payload", "approved"); put("signature", "trusted") }
    private fun plan(adapter: String = "systemd") = buildJsonObject {
        put("adapter", adapter); put("signed_manifest", signed); put("plan_hash", "unchanged")
    }

    @Test fun usesExactApprovedManifestForSshReceiving() {
        for (channel in listOf(AdminChannel.DIRECT_SSH, AdminChannel.SSH_VDS, AdminChannel.SSH_NETCRAZE)) {
            assertEquals(signed, sshUpdateArtifactManifest(channel, "begin", "root", plan()))
        }
    }

    @Test fun otherCarriersAndStagesKeepExistingCoordinator() {
        for (channel in listOf(AdminChannel.DOCUMENT, AdminChannel.ACTIVE_TRANSPORT, AdminChannel.AUTO)) {
            assertNull(sshUpdateArtifactManifest(channel, "begin", "root", plan()))
        }
        for (action in listOf("plan", "status", "put_chunk", "verify", "prepare", "apply", "confirm", "rollback")) {
            assertNull(sshUpdateArtifactManifest(AdminChannel.DIRECT_SSH, action, "root", plan()))
        }
    }

    @Test fun helperIsNotSelectedForUnsupportedUserOrInitSystem() {
        assertNull(sshUpdateArtifactManifest(AdminChannel.DIRECT_SSH, "begin", "admin", plan()))
        for (adapter in listOf("procd", "sysvinit", "")) {
            assertNull(sshUpdateArtifactManifest(AdminChannel.DIRECT_SSH, "begin", "root", plan(adapter)))
        }
        assertNull(sshUpdateArtifactManifest(AdminChannel.DIRECT_SSH, "begin", "root", null))
    }
}
