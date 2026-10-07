package io.github.libreroute.data

enum class TransportType(val cliName: String) {
    yandex("yandex"),
    vyandex("vyandex"),
    max("oneme"),
    cups("cupsonline"),
    mailru("mailru"),
    mqtt("mqtt"),
    jitsi("jitsi"),
    directDpi("direct-dpi");

    companion object {
        fun from(raw: String): TransportType =
            fromOrNull(raw) ?: throw IllegalArgumentException("Unsupported or unknown transport type: '$raw'")

        fun fromOrNull(raw: String?): TransportType? {
            if (raw.isNullOrBlank()) return null
            return entries.firstOrNull {
                it.name.equals(raw, ignoreCase = true) ||
                it.cliName.equals(raw, ignoreCase = true) ||
                (it == cups && (raw.equals("cupsonline", ignoreCase = true) || raw.equals("cups.online", ignoreCase = true))) ||
                (it == max && raw.equals("oneme", ignoreCase = true)) ||
                (it == mailru && (raw.equals("mail", ignoreCase = true) || raw.equals("mail.ru", ignoreCase = true) || raw.equals("mail_ru", ignoreCase = true)))
            }
        }
    }
}
