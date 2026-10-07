package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test

class AutomaticAccessPlanTest {
    private fun snapshot() = AdminClusterSnapshot(servers = listOf("node"), accessMethods = mapOf("node" to listOf(
        AdminAccessMethod("mqtt", false), AdminAccessMethod("vyandex"), AdminAccessMethod("jitsi")
    )))

    @Test fun mqttNeedsNoManuallyConfiguredEndpoint() {
        val routes = AutomaticAccessPlan.build(snapshot(), setOf("node"), emptyMap())
        assertEquals(listOf(EnrollmentRoute("node", "mqtt", "")), routes)
    }

    @Test fun suppliedLinksAddExternalMethodsToAutomaticSet() {
        val routes = AutomaticAccessPlan.build(snapshot(), setOf("node"), mapOf(
            ("node" to "vyandex") to "https://docs.yandex.ru/edit/d/dedicated",
            ("node" to "jitsi") to "https://meet.jit.si/dedicated-room"
        ))
        assertEquals(setOf("mqtt", "vyandex", "jitsi"), routes.map { it.transport }.toSet())
    }

    @Test fun unknownServerOrMethodsAreNotGranted() {
        assertThrows(IllegalArgumentException::class.java) { AutomaticAccessPlan.build(snapshot(), setOf("other"), emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { AutomaticAccessPlan.build(AdminClusterSnapshot(servers = listOf("node")), setOf("node"), emptyMap()) }
    }

    @Test fun oldSnapshotDoesNotClaimAutomaticAllocation() {
        val old = AdminClusterSnapshot(servers = listOf("node"), profiles = listOf(
            ServerManagedProfile(serverId = "node", transport = "mqtt")))
        assertTrue(old.methodsFor("node").single().endpointRequired)
        assertThrows(IllegalArgumentException::class.java) { AutomaticAccessPlan.build(old, setOf("node"), emptyMap()) }
    }

    @Test fun malformedLinksAreRejectedAndUnknownProtocolsAreFiltered() {
        assertFalse(AutomaticAccessPlan.validLink("vyandex", "https://example.org/document"))
        assertFalse(AutomaticAccessPlan.validLink("jitsi", "https://meet.jit.si/room#jwt=secret"))
        assertThrows(IllegalArgumentException::class.java) {
            AutomaticAccessPlan.build(snapshot(), setOf("node"), mapOf(("node" to "jitsi") to "http://meet.jit.si/room"))
        }
        val unknown = snapshot().copy(accessMethods = mapOf("node" to listOf(AdminAccessMethod("future-method", false))))
        assertThrows(IllegalArgumentException::class.java) { AutomaticAccessPlan.build(unknown, setOf("node"), emptyMap()) }
    }
}
