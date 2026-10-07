package io.github.libreroute.service

import io.github.libreroute.event.YandexAuthReason
import java.util.Locale

/** Classifies only errors where a human browser check may help. Network/relay conflicts are excluded. */
internal object YandexAuthIssueDetector {
    fun classify(line: String): YandexAuthReason? {
        val value = line.lowercase(Locale.ROOT)
        if (Regex("(?:http|status)\\s*[=:]?\\s*(429|5[0-9]{2})\\b").containsMatchIn(value) ||
            "deadline exceeded" in value || "network is unreachable" in value || "i/o timeout" in value) return null
        if ("yandex captcha challenge" in value) return YandexAuthReason.CAPTCHA

        val authorizationFailed = "authorization refresh failed:" in value ||
            "failed to start transport: auth:" in value
        if (!authorizationFailed) return null

        val needsBrowser = "auth/initial status 401" in value ||
              "auth/initial status 403" in value ||
              "auth document status 401" in value ||
              "auth document status 403" in value ||
            "client-config not found" in value ||
            "access_token missing" in value ||
            "auth/initial returned /document/error/" in value ||
            "incomplete auth:" in value ||
            "cannot read private cookie file" in value ||
            "invalid private cookie file" in value ||
            "authorization is already expired" in value
        return if (needsBrowser) YandexAuthReason.SESSION else null
    }
}
