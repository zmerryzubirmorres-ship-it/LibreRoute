package io.github.libreroute.service

import io.github.libreroute.event.YandexAuthReason
import org.junit.Assert.*
import org.junit.Test

class YandexAuthIssueDetectorTest {
    @Test fun browserChecksExcludeTransientServerAndNetworkFailures() {
        assertEquals(YandexAuthReason.CAPTCHA, YandexAuthIssueDetector.classify("Failed to start transport: auth: Yandex CAPTCHA challenge (HTTP 200)"))
        assertEquals(YandexAuthReason.SESSION, YandexAuthIssueDetector.classify("authorization refresh failed: auth/initial status 401"))
        assertEquals(YandexAuthReason.SESSION, YandexAuthIssueDetector.classify("Failed to start transport: auth: auth document status 403"))
        assertNull(YandexAuthIssueDetector.classify("authorization refresh failed: cached session validation: poll status status 429"))
        for (status in listOf(429, 500, 502, 503)) {
            assertNull(YandexAuthIssueDetector.classify("Failed to start transport: auth: client-config not found (HTTP $status)"))
        }
        assertNull(YandexAuthIssueDetector.classify("Failed to start transport: auth: GET document: i/o timeout"))
        assertNull(YandexAuthIssueDetector.classify("[HEALTH] peer round trip timed out"))
    }
}
