package io.github.libreroute.data

import org.junit.Assert.*
import org.junit.Test

class RestoredTunnelMergeTest {
    private val route = Tunnel(100, "Server MQTT", "mqtt", listOf("--url", "wss://broker/mqtt#one"),
        "secret encryption key", "server", "profile")

    @Test fun restoresRenamedLegacyAndRemovesExistingDuplicatePreservingSelectedId() {
        val legacy = route.copy(id = 1, name = "My connection", managedProfileId = null, serverId = null)
        val duplicate = route.copy(id = 2)
        val other = route.copy(id = 3, serverId = "other-server", encryptionKey = "different server secret")
        val merged = RestoredTunnelMerge.upsert(listOf(legacy, duplicate, other), listOf(route), 1)
        assertEquals(listOf(legacy.copy(managedProfileId = "profile", serverId = "server"), other), merged)
        assertEquals(merged, RestoredTunnelMerge.upsert(merged, listOf(route), 1))
    }

    @Test fun neverMergesDifferentIdentitiesEndpointsSecretsOrEnrollmentScopes() {
        val unrelated = listOf(route.copy(id = 1, managedProfileId = "other-profile"),
            route.copy(id = 2, serverId = null, managedProfileId = null, encryptionKey = "unknown-server key"),
            route.copy(id = 3, managedProfileId = null, encryptionKey = "different secret"),
            route.copy(id = 4, managedProfileId = null, transportConnPayload = listOf("--url", "wss://other/mqtt#one")))
        assertEquals(unrelated + route, RestoredTunnelMerge.upsert(unrelated, listOf(route), null))
        val scoped = listOf(route.copy(id = 5), route.copy(id = 6))
        assertEquals(scoped, RestoredTunnelMerge.upsert(scoped, listOf(route), 5,
            mapOf(5L to setOf("first"), 6L to setOf("second"))))
    }

    @Test fun unknownServerLegacyIsKeptWhenTwoSignedProfilesClaimSameRoute() {
        val legacy = route.copy(id = 1, managedProfileId = null, serverId = null)
        val otherServer = route.copy(id = 2, serverId = "another-server")
        assertEquals(listOf(legacy, otherServer, route),
            RestoredTunnelMerge.upsert(listOf(legacy, otherServer), listOf(route), 1))
    }

    @Test fun authenticatedProfileRefreshesChangedSettingsWhileKeepingActiveIdAndName() {
        val active = route.copy(id = 7, name = "Active route", encryptionKey = "old encryption key")
        val updated = route.copy(transportConnPayload = listOf("--url", "wss://new/mqtt#two"))
        assertEquals(listOf(updated.copy(id = 7, name = active.name)),
            RestoredTunnelMerge.upsert(listOf(route.copy(id = 8), active), listOf(updated), 7))
    }
}
