package io.github.libreroute.service

import io.github.libreroute.admin.ChannelGroupBinding
import io.github.libreroute.admin.ChannelGroupLane
import io.github.libreroute.admin.PairChannelBinding
import io.github.libreroute.event.YandexAuthReason
import org.junit.Assert.*
import org.junit.Test

class ChannelGroupAuthEventTest {
    @Test fun mapsEachLaneToItsOwnDocumentAndIgnoresUnknownLanes() {
        val b = PairChannelBinding(2, "a".repeat(32), 1, "lease", "resource", "room", 1, 1, 1, 1, "server", "device", "b".repeat(64), "public-key")
        val g = ChannelGroupBinding(1, "ordered_striping", 1, listOf(
            ChannelGroupLane("jazz", "https://salutejazz.ru/call/room/password", b),
            ChannelGroupLane("vyandex", "https://disk.yandex.ru/i/first", b.copy(lane_id = 2)),
            ChannelGroupLane("vyandex", "https://disk.yandex.ru/i/second", b.copy(lane_id = 3))
        ))
        val captcha = ChannelGroupAuthEvent.parse(g, "[GROUP_AUTH] lane=3 reason=captcha")!!
        assertEquals(g.lanes[2].url, captcha.url)
        assertEquals(YandexAuthReason.CAPTCHA, captcha.reason)
        assertNull(ChannelGroupAuthEvent.parse(g, "[GROUP_AUTH] lane=2 reason=ready")!!.reason)
        assertNull(ChannelGroupAuthEvent.parse(g, "[GROUP_AUTH] lane=1 reason=session"))
        assertNull(ChannelGroupAuthEvent.parse(g, "[GROUP_AUTH] lane=9 reason=captcha"))
        assertNull(ChannelGroupAuthEvent.parse(g, "[VOLGA] authorization established"))
    }
}
