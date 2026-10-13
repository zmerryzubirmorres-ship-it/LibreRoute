package io.github.libreroute.service

import io.github.libreroute.admin.ChannelGroupBinding
import io.github.libreroute.event.YandexAuthReason

/** Maps credential-free native lane IDs back to the signed document URL. */
internal data class ChannelGroupAuthEvent(val url: String, val reason: YandexAuthReason?) {
    companion object {
        fun parse(group: ChannelGroupBinding, line: String): ChannelGroupAuthEvent? {
            val match = Regex("\\[GROUP_AUTH\\] lane=(\\d+) reason=(ready|captcha|session)\\b").find(line) ?: return null
            val id = match.groupValues[1].toIntOrNull() ?: return null
            val lane = group.lanes.singleOrNull { it.binding.lane_id == id && it.provider == "vyandex" } ?: return null
            val reason = when (match.groupValues[2]) {
                "captcha" -> YandexAuthReason.CAPTCHA
                "session" -> YandexAuthReason.SESSION
                else -> null
            }
            return ChannelGroupAuthEvent(lane.url, reason)
        }
    }
}
