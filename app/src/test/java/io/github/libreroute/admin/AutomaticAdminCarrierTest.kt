package io.github.libreroute.admin

import io.github.libreroute.data.Tunnel
import io.github.libreroute.data.TunnelStore
import io.github.libreroute.service.NativeProcessSupervisor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AutomaticAdminCarrierTest {
    private val storage = InMemoryAdminStorage()
    private val route = Tunnel(1, "A display name", "jitsi", listOf("--url", "https://meet.example/room"),
        "private route secret", serverId = "srv")
    private var tunnels = listOf(route)
    private val store = object : TunnelStore {
        override fun load() = tunnels
        override fun save(value: List<Tunnel>) { tunnels = value }
        override fun getSelectedId(): Long? = null
        override fun setSelectedId(id: Long) {}
    }
    private val repository = AdminRepository(storage = storage, customTunnelStore = store)

    @After fun cleanup() { NativeProcessSupervisor.activeAdminCarrier = null }

    @Test fun newRouteKeepsServerHintWithoutGrantingAdministratorRights() {
        val tunnel = repository.routeCandidateToTunnel(RouteCandidate(route_id = "new", server_id = "srv",
            transport = "jitsi", url = "https://meet.example/new", secret_key = "private route secret"))
        assertEquals("srv", tunnel.serverId)
        assertEquals("srv", Json.decodeFromString<Tunnel>(Json.encodeToString(tunnel)).serverId)
        assertEquals(AdminRole.NONE, repository.getRole("srv"))
        NativeProcessSupervisor.activeAdminCarrier = NativeProcessSupervisor.AdminCarrier("/private/socket", "jitsi", "srv", ready = true)
        assertEquals(AdminChannel.DIRECT_SSH, repository.resolveAdminChannel(AdminChannel.AUTO,
            ServerNode(id = "srv", name = "Server", host = "192.0.2.1")))
    }

    @Test fun legacyBindingSurvivesRenameButNotChangedEndpointOrEncryptionKey() {
        tunnels = listOf(route.copy(serverId = null))
        val initial = repository.adminCarrierBinding(1)
        assertNull(initial.first)
        storage.putString("admin_carrier_server_" + initial.second, "srv")
        tunnels = listOf(tunnels.single().copy(name = "Renamed"))
        assertEquals("srv", repository.adminCarrierBinding(1).first)
        tunnels = listOf(tunnels.single().copy(encryptionKey = "different private secret"))
        assertNull(repository.adminCarrierBinding(1).first)
        tunnels = listOf(route.copy(serverId = null, transportConnPayload = listOf("--url", "https://meet.example/other")))
        assertNull(repository.adminCarrierBinding(1).first)
        assertEquals(null to null, repository.adminCarrierBinding(99))
    }

    @Test fun delayedReplyCannotBindReplacedConnection() {
        val previous = NativeProcessSupervisor.AdminCarrier("/private/old", "jitsi", ready = true)
        val current = NativeProcessSupervisor.AdminCarrier("/private/new", "jazz", ready = false)
        NativeProcessSupervisor.activeAdminCarrier = current
        NativeProcessSupervisor.bindAdminCarrier(previous, "srv")
        assertEquals(current, NativeProcessSupervisor.activeAdminCarrier)
        NativeProcessSupervisor.bindAdminCarrier(current, "srv")
        assertEquals("srv", NativeProcessSupervisor.activeAdminCarrier?.serverId)
        assertFalse(NativeProcessSupervisor.activeAdminCarrier!!.ready)
    }
}
