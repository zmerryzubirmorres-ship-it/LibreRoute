package io.github.libreroute.admin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ChannelGroupEnrollmentPlanTest {
    @Test fun preparedMixedLinksProduceOneTypedRouteWithoutRecipientOrSecrets() {
        val plan = ChannelGroupPlan.fromLinks(" https://salutejazz.ru/call/room/password \n\nhttps://disk.yandex.ru/i/document\n")
        val route = EnrollmentRoute("server", transport = "channel-group", channel_group_plan = plan)
        route.validateGroupTemplate()
        assertEquals(listOf("jazz", "vyandex"), plan.lanes.map { it.provider })
        val encoded = Json.encodeToString(route)
        assertEquals(route, Json.decodeFromString<EnrollmentRoute>(encoded))
        assertFalse(encoded.contains("device_public_key")); assertFalse(encoded.contains("secret_key"))
        assertThrows(IllegalArgumentException::class.java) { route.copy(transport = "jazz").validateGroupTemplate() }
        assertThrows(IllegalArgumentException::class.java) { route.copy(allocation_mode = "automatic").validateGroupTemplate() }
    }

    @Test fun roomAliasesAndChannelLimitsAreCheckedBeforeIssuance() {
        assertThrows(IllegalArgumentException::class.java) {
            ChannelGroupPlan.fromLinks("https://salutejazz.ru/call/same/password\nhttps://jazz.sber.ru/call/same/another-password")
        }
        assertThrows(IllegalArgumentException::class.java) { ChannelGroupPlan.fromLinks("https://salutejazz.ru/call/one/password") }
        assertThrows(IllegalArgumentException::class.java) {
            ChannelGroupPlan.fromLinks((1..5).joinToString("\n") { "https://salutejazz.ru/call/room-$it/password" })
        }
        val jazz = ChannelGroupPlan.fromLinks("https://salutejazz.ru/call/one/password\nhttps://salutejazz.ru/call/two/password")
        assertTrue(jazz.lanes.all { it.provider == "jazz" })
    }
}
