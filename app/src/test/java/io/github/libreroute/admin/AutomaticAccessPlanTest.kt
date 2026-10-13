package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test

class AutomaticAccessPlanTest {
    @Test fun jazzV2UsesServerAllocationWithoutRoomCredentials() {
        val snapshot = AdminClusterSnapshot(servers = listOf("node"), accessMethods = mapOf("node" to listOf(
            AdminAccessMethod("jazz", true, automaticAllocation = true, requiredClientSchema = 2, placementModes = listOf("dedicated", "shared_pool"))
        )))
        val route = AutomaticAccessPlan.build(snapshot, setOf("node"), emptyMap(), protocols("jazz")).single()
        assertEquals("automatic", route.allocation_mode)
        assertEquals(2, route.client_channel_schema)
        assertEquals("", route.url)
    }
    @Test fun jazzRequiresItsOwnRoomAndIsIncludedInSignedAccessPlan() {
        val jazz = AdminClusterSnapshot(servers = listOf("node"), accessMethods = mapOf("node" to listOf(AdminAccessMethod("jazz", true))))
        assertThrows(IllegalStateException::class.java) { AutomaticAccessPlan.build(jazz, setOf("node"), emptyMap(), protocols("jazz")) }
        val link = "https://salutejazz.ru/call/room/password"
        assertEquals(listOf(EnrollmentRoute("node", "jazz", link)), AutomaticAccessPlan.build(jazz, setOf("node"), mapOf(("node" to "jazz") to link), protocols("jazz")))
    }
    private fun snapshot() = AdminClusterSnapshot(servers = listOf("node"), accessMethods = mapOf("node" to listOf(
        AdminAccessMethod("mqtt", false), AdminAccessMethod("vyandex"), AdminAccessMethod("jitsi")
    )))
    private fun protocols(vararg names: String) = names.map { "node" to it }.toSet()

    @Test fun excludedMqttIsNotGrantedEvenWhenServerAdvertisesIt() {
        assertThrows(IllegalArgumentException::class.java) {
            AutomaticAccessPlan.build(snapshot(), setOf("node"), emptyMap(), protocols("mqtt"))
        }
    }

    @Test fun suppliedLinksAddExternalMethodsToAutomaticSet() {
        val routes = AutomaticAccessPlan.build(snapshot(), setOf("node"), mapOf(
            ("node" to "vyandex") to "https://docs.yandex.ru/edit/d/dedicated",
            ("node" to "jitsi") to "https://meet.jit.si/dedicated-room"
        ), protocols("vyandex", "jitsi"))
        assertEquals(setOf("vyandex", "jitsi"), routes.map { it.transport }.toSet())
    }

    @Test fun unknownServerOrMethodsAreNotGranted() {
        assertThrows(IllegalArgumentException::class.java) { AutomaticAccessPlan.build(snapshot(), setOf("other"), emptyMap(), protocols("mqtt")) }
        assertThrows(IllegalArgumentException::class.java) { AutomaticAccessPlan.build(AdminClusterSnapshot(servers = listOf("node")), setOf("node"), emptyMap(), protocols("mqtt")) }
    }

    @Test fun oldSnapshotDoesNotClaimAutomaticAllocation() {
        val old = AdminClusterSnapshot(servers = listOf("node"), profiles = listOf(
            ServerManagedProfile(serverId = "node", transport = "jitsi")))
        assertTrue(old.methodsFor("node").single().endpointRequired)
        assertThrows(IllegalStateException::class.java) { AutomaticAccessPlan.build(old, setOf("node"), emptyMap(), protocols("jitsi")) }
    }

    @Test fun malformedLinksAreRejectedAndUnknownProtocolsAreFiltered() {
        assertFalse(AutomaticAccessPlan.validLink("vyandex", "https://example.org/document"))
        assertFalse(AutomaticAccessPlan.validLink("jitsi", "https://meet.jit.si/room#jwt=secret"))
        assertThrows(IllegalArgumentException::class.java) {
            AutomaticAccessPlan.build(snapshot(), setOf("node"), mapOf(("node" to "jitsi") to "http://meet.jit.si/room"), protocols("jitsi"))
        }
        val unknown = snapshot().copy(accessMethods = mapOf("node" to listOf(AdminAccessMethod("future-method", false))))
        assertThrows(IllegalArgumentException::class.java) { AutomaticAccessPlan.build(unknown, setOf("node"), emptyMap(), protocols("future-method")) }
    }

    @Test fun jazzOnlyInvitationDoesNotAllocateMqttOrValidateUncheckedLinks() {
        val snapshot = snapshot().copy(accessMethods = mapOf("node" to listOf(
            AdminAccessMethod("jazz", true, automaticAllocation = true, requiredClientSchema = 2),
            AdminAccessMethod("mqtt", false), AdminAccessMethod("jitsi")
        )))
        val routes = AutomaticAccessPlan.build(snapshot, setOf("node"),
            mapOf(("node" to "jitsi") to "invalid unchecked link"), protocols("jazz"))
        assertEquals(listOf(EnrollmentRoute("node", "jazz", allocation_mode = "automatic", client_channel_schema = 2)), routes)
    }

    @Test fun selectedExternalMethodDoesNotImplicitlyAddAutomaticMethods() {
        val routes = AutomaticAccessPlan.build(snapshot(), setOf("node"),
            mapOf(("node" to "jitsi") to "https://meet.jit.si/room"), protocols("jitsi"))
        assertEquals(listOf(EnrollmentRoute("node", "jitsi", "https://meet.jit.si/room")), routes)
    }

    @Test fun eachSelectedServerRequiresItsOwnAvailableProtocolSelection() {
        val snapshot = snapshot().copy(servers = listOf("node", "second"), accessMethods = snapshot().accessMethods +
            ("second" to listOf(AdminAccessMethod("jitsi", true))))
        assertThrows(IllegalArgumentException::class.java) {
            AutomaticAccessPlan.build(snapshot, setOf("node", "second"),
                mapOf(("node" to "jitsi") to "https://meet.jit.si/first-room"), protocols("jitsi"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutomaticAccessPlan.build(snapshot, setOf("node"), emptyMap(), emptySet())
        }
        assertThrows(IllegalArgumentException::class.java) {
            AutomaticAccessPlan.build(snapshot, setOf("second"), emptyMap(), setOf("second" to "jazz"))
        }
        val link = "https://meet.jit.si/second-room"
        assertEquals(listOf(EnrollmentRoute("second", "jitsi", link)),
            AutomaticAccessPlan.build(snapshot, setOf("second"), mapOf(("second" to "jitsi") to link),
                protocols("jitsi") + ("second" to "jitsi")))
    }
}
