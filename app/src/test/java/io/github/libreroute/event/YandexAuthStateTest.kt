package io.github.libreroute.event

import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class YandexAuthStateTest {
    @Before fun clear() { EventBus.clearPendingYandexAuthIssue() }
    @After fun cleanup() { EventBus.clearPendingYandexAuthIssue() }
    @Test fun successForAnotherDocumentDoesNotEraseAnOutstandingChallenge() {
        val issue = AppEvent.YandexAuthIssue("https://docs.yandex.ru/edit/d/one", YandexAuthReason.CAPTCHA)
        EventBus.dispatch(issue)
        EventBus.clearPendingYandexAuthIssue("https://docs.yandex.ru/edit/d/two")
        assertEquals(issue, EventBus.pendingYandexAuthIssue())
        EventBus.clearPendingYandexAuthIssue(issue.documentUrl)
        assertNull(EventBus.pendingYandexAuthIssue())
    }
}
