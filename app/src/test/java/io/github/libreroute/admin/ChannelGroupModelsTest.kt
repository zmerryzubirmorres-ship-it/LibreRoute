package io.github.libreroute.admin

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import io.github.libreroute.util.NativeLogRedactor

class ChannelGroupModelsTest {
    private fun fixture(): ChannelGroupBinding {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair().public.encoded
        val pub = Base64.getEncoder().encodeToString(key)
        val fp = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
        val b = PairChannelBinding(2, "12345678123456781234567812345678", 1, "lease1", "resource1", "room", 1, 1, 1, 1, "server", "device", fp, pub)
        return ChannelGroupBinding(1, "ordered_striping", 1, listOf(
            ChannelGroupLane("jazz", "https://salutejazz.ru/call/room/secret-password", b),
            ChannelGroupLane("vyandex", "https://disk.yandex.ru/i/document", b.copy(lane_id = 2, lease_id = "lease2", resource_id = "resource2", resource_identity = "disk.yandex.ru/i/document"))
        ))
    }

    @Test fun signedMixedGroupRequiresOneServerAndRecipient() {
        val g = fixture(); val b = g.lanes.first().binding
        g.validate("server", b.device_fingerprint, b.device_public_key)
        assertTrue(g.hasYandex)
        assertThrows(IllegalArgumentException::class.java) { g.validate("other", b.device_fingerprint) }
        assertThrows(IllegalArgumentException::class.java) { g.validate("server", "f".repeat(64)) }
        val foreign = g.copy(lanes = g.lanes.mapIndexed { i, l -> if (i == 1) l.copy(binding = l.binding.copy(pair_id = "f".repeat(32))) else l })
        assertThrows(IllegalArgumentException::class.java) { foreign.validate("server", b.device_fingerprint) }
        assertThrows(IllegalArgumentException::class.java) { g.copy(min_ready = 3).validate("server", b.device_fingerprint) }
    }

    @Test fun duplicateResourcesAndModifiedEndpointsCannotEnterGroup() {
        val plan = ChannelGroupPlan(lanes = listOf(GroupLaneRequest("vyandex", "https://disk.yandex.ru/i/same"), GroupLaneRequest("vyandex", "https://disk.yandex.ru/i/same/")))
        assertThrows(IllegalArgumentException::class.java) { plan.validate() }
        val g = fixture(); val b = g.lanes.first().binding
        val modified = g.copy(lanes = g.lanes.mapIndexed { i,l -> if (i==1) l.copy(url="https://disk.yandex.ru/i/other") else l })
        assertThrows(IllegalArgumentException::class.java) { modified.validate("server", b.device_fingerprint) }
    }

    @Test fun nestedProviderPasswordsNeverEnterExportedLogs() {
        val g = fixture(); val raw = Json.encodeToString(g)
        val redactor = NativeLogRedactor(listOf("--channel-group-config",raw))
        val output = redactor.redact("config=$raw url=${g.lanes[0].url} password=secret-password")
        assertFalse(output.contains("secret-password"));assertFalse(output.contains(g.lanes[0].url));assertFalse(output.contains(raw))
    }
}
