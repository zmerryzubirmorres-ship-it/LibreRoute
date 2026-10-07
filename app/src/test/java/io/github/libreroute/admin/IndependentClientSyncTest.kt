package io.github.libreroute.admin

import io.github.libreroute.data.Tunnel
import io.github.libreroute.data.TunnelStore
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class IndependentClientSyncTest {
    @Test fun secondAuthorityEnrollmentAndFirstAuthorityRevocationPreserveOtherRoutes() {
        AdminKeyManager.clearFallbackKey()
        val firstKeys = AdminKeyManager()
        val firstPub = firstKeys.getPublicKeyBase64()
        val firstPair = firstKeys.getOrCreateKeyPair()
        AdminKeyManager.clearFallbackKey()
        val secondKeys = AdminKeyManager()
        val secondPub = secondKeys.getPublicKeyBase64()
        val secondPair = secondKeys.getOrCreateKeyPair()
        assertNotEquals(firstPub, secondPub)
        val state = InMemoryAdminStorage()
        val manual = Tunnel(10, "Manual", "mqtt", listOf("--url", "wss://manual.example/mqtt#manual"))
        val first = Tunnel(11, "First", "mqtt", listOf("--url", "wss://one.example/mqtt#first"))
        val second = Tunnel(12, "Second", "mqtt", listOf("--url", "wss://two.example/mqtt#second"))
        val store = object : TunnelStore {
            var tunnels = listOf(manual)
            override fun load() = tunnels
            override fun save(tunnels: List<Tunnel>) { this.tunnels = tunnels }
            override fun getSelectedId() = second.id
            override fun setSelectedId(id: Long) = Unit
        }
        var stopped = false
        val contacted = mutableListOf<String>()
        fun repo() = AdminRepository(keyManager = secondKeys, storage = state, customTunnelStore = store,
            vpnStopper = { stopped = true }, documentSyncRunner = { _, profile, _, request, pinned ->
                contacted += profile
                val signer = if (profile == "control-first") firstPair else secondPair
                assertEquals(Base64.getEncoder().encodeToString(signer.public.encoded), pinned)
                val payload = buildJsonObject { put("op_id", request.opId); put("status", "applied")
                    put("data", buildJsonObject { put("status", if (profile == "control-first") "revoked" else "unchanged") }) }
                val envelope = AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_RESPONSE, sender = "server",
                    profileId = profile, opId = request.opId, payload = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(payload.toString().toByteArray()))
                val signature = java.security.Signature.getInstance("SHA256withECDSA").apply {
                    initSign(signer.private); update(envelope.canonicalSigningString().toByteArray())
                }.sign()
                envelope.copy(signature = Base64.getEncoder().encodeToString(signature))
            })
        val repository = repo()
        repository.saveEnrolledSyncState("first-device", "first-user", "control-first",
            "https://docs.yandex.ru/edit/d/first", "first-secret", 1, firstPub, emptyList())
        repository.updateManagedTunnels(listOf(first))
        repository.saveEnrolledSyncState("second-device", "second-user", "control-second",
            "https://docs.yandex.ru/edit/d/second", "second-secret", 1, secondPub, emptyList())
        repository.updateManagedTunnels(listOf(second))
        assertEquals(listOf(manual, first, second), store.tunnels)
        assertEquals(RouteSyncResult.Revoked, repo().syncRoutes(force = true))
        assertEquals(setOf("control-first", "control-second"), contacted.toSet())
        assertEquals(listOf(manual, second), store.tunnels); assertFalse(stopped)
        assertTrue(repo().isEnrolledInRouteSync())
        contacted.clear(); assertTrue(repo().syncRoutes(force = true) is RouteSyncResult.Unchanged)
        assertEquals(listOf("control-second"), contacted)
    }
}
