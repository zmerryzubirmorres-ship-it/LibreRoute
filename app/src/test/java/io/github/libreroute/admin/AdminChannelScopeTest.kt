package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test

class AdminChannelScopeTest {
    private val server = ServerNode(id = "vds", name = "VDS", host = "192.0.2.1", nodeType = ServerNodeType.VDS)
    private val router = ServerNode(id = "router", name = "Router", host = "192.0.2.2", nodeType = ServerNodeType.ROUTER_OPENWRT)

    @Test fun documentAndLiveRouteKeepSelectedServerWithMultipleNodes() {
        for (channel in listOf(AdminChannel.AUTO, AdminChannel.DOCUMENT, AdminChannel.ACTIVE_TRANSPORT, AdminChannel.DIRECT_SSH)) {
            assertEquals(server, serverForAdminChannel(listOf(router, server), "vds", channel))
            assertEquals(router, serverForAdminChannel(listOf(router, server), "router", channel))
            assertNull(serverForAdminChannel(listOf(router, server), null, channel))
        }
    }

    @Test fun unavailableCarrierDoesNotSilentlySelectAnotherServer() {
        assertNull(serverForAdminChannel(listOf(router, server), "router", AdminChannel.SSH_VDS))
        assertNull(serverForAdminChannel(listOf(server), "removed", AdminChannel.DOCUMENT))
        assertEquals(server, serverForAdminChannel(listOf(server), null, AdminChannel.DOCUMENT))
    }

    @Test fun automaticUsesOnlyReadyCarrierOfAuthorizedSelectedServer() {
        assertEquals(AdminChannel.ACTIVE_TRANSPORT, automaticAdminChannel("vds", true, "vds", true, true))
        assertEquals(AdminChannel.DIRECT_SSH, automaticAdminChannel("vds", true, "router", true, true))
        assertEquals(AdminChannel.DIRECT_SSH, automaticAdminChannel("vds", true, "vds", false, true))
        assertEquals(AdminChannel.DIRECT_SSH, automaticAdminChannel("vds", false, "vds", true, true))
        assertEquals(AdminChannel.DOCUMENT, automaticAdminChannel("vds", true, null, true, false))
        assertEquals(AdminChannel.DOCUMENT, automaticAdminChannel(null, true, null, true, false))
        assertEquals(AdminChannel.DOCUMENT, automaticAdminChannel("vds", true, "router", true, true, true))
        assertEquals(AdminChannel.ACTIVE_TRANSPORT, automaticAdminChannel("vds", true, "vds", true, true, true))
    }
}
