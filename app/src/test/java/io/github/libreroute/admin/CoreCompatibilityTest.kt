package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test

class CoreCompatibilityTest {
    @Test fun versionDisplayDistinguishesFreshSavedAndUnsupportedResults() {
        val time = 1_000_000L
        val info = CoreCompatibility(CoreCompatibility.REQUIRED_VERSION, "a".repeat(64), 1,
            CoreCompatibility.REQUIRED_CAPABILITIES.toList())
        val checked = CoreObservation(info, time)
        assertTrue(coreVersionSummary(checked, time).contains("Core ${CoreCompatibility.REQUIRED_VERSION} · Совместим"))
        assertTrue(coreVersionSummary(checked, time + 300_001L).contains("Сохранённые сведения"))
        assertTrue(coreVersionSummary(checked.copy(error = "offline"), time).contains("Не удалось обновить"))
        assertTrue(coreVersionSummary(CoreObservation(null, time), time).contains("Требуется обновление"))
        assertTrue(coreVersionSummary(CoreObservation(), time).contains("Совместимость не проверена"))
    }

    @Test fun compatibilityRequiresTheRunningBuildAndExplicitCapabilities() {
        val required = setOf("setup-auth-document-v1", "negotiated-batch-v1")
        val core = CoreCompatibility("1.2.2", "a".repeat(64), 1, required.toList())
        assertTrue(core.supports(required))
        assertFalse(core.copy(capabilities = listOf("setup-auth-document-v1")).supports(required))
        assertFalse(core.copy(buildId = "").supports(required))
        assertFalse(core.copy(apiRevision = 0).supports(required))
        assertFalse(CoreCompatibility(version = "1.2.2").supports(required))
    }

    @Test fun oldReleaseRequiresPhoneUpdateEvenWhenItsOldCapabilitiesArePresent() {
        val current = CoreCompatibility(CoreCompatibility.REQUIRED_VERSION, "a".repeat(64), 1, CoreCompatibility.REQUIRED_CAPABILITIES.toList())
        assertFalse(current.needsUpdate)
        assertTrue(current.copy(version = "1.2.2").needsUpdate)
        assertTrue(current.copy(version = "1.2.3").needsUpdate)
        assertTrue(current.copy(version = "1.2.4").needsUpdate)
        assertTrue(current.copy(version = "1.2.10").needsUpdate)
        assertTrue(current.copy(version = "1.2.18").needsUpdate)
        assertTrue(current.copy(capabilities = current.capabilities - "mqtt-wss-readiness-v1").needsUpdate)
        assertTrue(current.copy(capabilities = current.capabilities - "setup-auth-worker-cookies-v1").needsUpdate)
        assertTrue(current.copy(version = "1.2.20").needsUpdate)
        assertFalse(current.copy(version = "99.0.0").needsUpdate)
        assertTrue(current.copy(version = "unknown").needsUpdate)
    }

    @Test fun transportConfigurationErrorsDoNotRequestBrowserAuthorization() {
        assertFalse(requiresYandexBrowserAuth("EXECUTION_FAILED: session negotiation requires authenticated encrypted transport"))
        assertFalse(requiresYandexBrowserAuth("AUTH_PROBE_FAILED: empty document URL for candidate probe"))
        assertTrue(requiresYandexBrowserAuth("AUTH_PROBE_FAILED: CAPTCHA challenge required"))
        assertTrue(requiresYandexBrowserAuth("authentication cookies expired"))
    }
}
