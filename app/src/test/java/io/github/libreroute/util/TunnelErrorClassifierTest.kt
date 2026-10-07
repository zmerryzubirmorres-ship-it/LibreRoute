package io.github.libreroute.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TunnelErrorClassifierTest {
    @Test fun mqttCausesRemainDistinctFromGenericAuthAndTimeout() {
        assertEquals(ErrorCategory.MQTT_BROKER_UNAVAILABLE,
            TunnelErrorClassifier.classify("mqtt: connection refused by broker").category)
        assertEquals(ErrorCategory.MQTT_TLS_FAILED,
            TunnelErrorClassifier.classify("mqtt websocket TLS handshake failed").category)
        assertEquals(ErrorCategory.MQTT_AUTH_FAILED,
            TunnelErrorClassifier.classify("mqtt: ACL not authorized").category)
        assertEquals(ErrorCategory.MQTT_PEER_NOT_READY,
            TunnelErrorClassifier.classify("mqtt: peer not ready").category)
    }

    @Test fun jitsiTokenAndModeratorFailuresHaveDifferentRecoveryReasons() {
        assertEquals(ErrorCategory.JITSI_TOKEN_EXPIRED,
            TunnelErrorClassifier.classify("[JITSI_AUTH] token-expired").category)
        assertEquals(ErrorCategory.JITSI_MODERATOR_REQUIRED,
            TunnelErrorClassifier.classify("[JITSI_AUTH] moderator-required").category)
    }
}
