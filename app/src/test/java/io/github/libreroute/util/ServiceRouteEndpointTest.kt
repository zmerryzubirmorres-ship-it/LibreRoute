package io.github.libreroute.util

import io.github.libreroute.data.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ServiceRouteEndpointTest {
    @Test fun extractsJitsiJwtOnlyFromRoomHash() {
        val token = "aaa.bbb.ccc"
        assertTrue(JitsiTokenStore.fromFragment("jwt=\"$token\"&x=1") == token)
        assertFalse(JitsiTokenStore.fromFragment("jwt=not-a-jwt") != null)
    }

    @Test fun detectsExpiredJitsiJwtAndKeepsNonExpiringJwtUsable() {
        fun token(payload: String) = "aaa.${Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray())}.ccc"
        assertTrue(JitsiTokenStore.isExpired(token("{\"exp\":99}"), nowSeconds = 100))
        assertFalse(JitsiTokenStore.isExpired(token("{\"exp\":101}"), nowSeconds = 100))
        assertFalse(JitsiTokenStore.isExpired("aaa.bbb.ccc", nowSeconds = 100))
    }

    @Test fun acceptsOnlySharedJitsiRoomLinksWithJwt() {
        val token = "aaa.bbb.ccc"
        val link = JitsiBrowserHandoff.parseSharedText(
            "Jitsi room\nhttps://meet.jit.si/phone-123#jwt=\"$token\""
        )
        assertEquals("https://meet.jit.si/phone-123", link?.roomUrl)
        assertEquals(token, link?.token)
        assertTrue(JitsiBrowserHandoff.sameRoom(link!!.roomUrl, "https://MEET.JIT.SI/phone-123/"))
        assertFalse(JitsiBrowserHandoff.parse("https://meet.jit.si/phone-123?x=1#jwt=$token") != null)

        val jaasLink = JitsiBrowserHandoff.parseSharedText(
            "JaaS room\nhttps://8x8.vc/tenant-1/room-1?jwt=$token"
        )
        assertEquals("https://8x8.vc/tenant-1/room-1", jaasLink?.roomUrl)
        assertEquals(token, jaasLink?.token)
    }
    @Test fun validatesDedicatedChannelsAndRejectsCredentials() {
        assertTrue(ServiceRouteEndpoint.isValid(TransportType.mqtt, "wss://broker.example:8884/mqtt#phone"))
        assertTrue(ServiceRouteEndpoint.isValid(TransportType.mqtt, "wss://broker.example/mqtt?topic=phone"))
        assertTrue(ServiceRouteEndpoint.isValid(TransportType.jitsi, "https://meet.jit.si/phone-123"))
        assertTrue(ServiceRouteEndpoint.isValid(TransportType.jitsi, "https://8x8.vc/tenant-1/phone-123"))
        for (url in listOf("ws://broker.example/mqtt#phone", "wss://user:password@broker.example/mqtt#phone", "wss://broker.example/mqtt?topic=a&topic=b", "wss://broker.example/mqtt?topic=a#b", "wss://broker.example:70000/mqtt#phone", "wss://broker.example/mqtt#all/+")) {
            assertFalse(url, ServiceRouteEndpoint.isValid(TransportType.mqtt, url))
        }
        for (url in listOf("https://meet.jit.si/", "https://meet.jit.si/a/b", "https://8x8.vc/just-one", "https://8x8.vc/a/b/c", "https://meet.jit.si/phone?jwt=secret", "https://meet.jit.si/phone#token=secret", "http://meet.jit.si/phone")) {
            assertFalse(url, ServiceRouteEndpoint.isValid(TransportType.jitsi, url))
        }
    }

    @Test fun mqttDefaultsToEmqxAndCanBeConfiguredSeparately() {
        assertEquals(
            "wss://broker.emqx.io:8084/mqtt#home-gateway",
            ServiceRouteEndpoint.buildMqttUrl("", "home-gateway")
        )
        val parsed = ServiceRouteEndpoint.parseMqttUrl(
            "wss://broker.emqx.io:8084/mqtt#backup-gateway"
        )
        assertEquals("wss://broker.emqx.io:8084/mqtt", parsed?.broker)
        assertEquals("backup-gateway", parsed?.topic)
    }
}
