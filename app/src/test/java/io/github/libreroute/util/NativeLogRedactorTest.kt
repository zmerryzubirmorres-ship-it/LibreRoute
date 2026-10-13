package io.github.libreroute.util

import org.junit.Assert.assertEquals
import org.junit.Test

class NativeLogRedactorTest {
    @Test fun pairBindingAndPrivateKeyNeverAppearInLogs() {
        val redactor = NativeLogRedactor(listOf("--pair-channel-config", "public-binding", "--pair-device-private-key=private-device-key"))
        assertEquals("config=[скрыто] key=[скрыто]", redactor.redact("config=public-binding key=private-device-key"))
    }
    @Test fun credentialsAndPrivateUrlsAreHiddenButDiagnosticsRemain() {
        val redactor = NativeLogRedactor(listOf("--url", "https://host/room?token=abc", "--mqtt-password=a secret", "--jitsi-token", "abc"))
        assertEquals("connect [скрыто]; password=[скрыто]; token=[скрыто]; timeout", redactor.redact("connect https://host/room?token=abc; password=a secret; token=abc; timeout"))
    }
}
