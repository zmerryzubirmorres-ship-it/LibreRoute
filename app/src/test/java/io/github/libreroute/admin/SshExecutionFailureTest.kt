package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test

class SshExecutionFailureTest {
    @Test fun dialTimeoutProvesRequestWasNotSent() {
        val failure = sshExecutionFailure(1, "SSH administrative request failed: ssh dial 89.167.49.64:2202: dial tcp 89.167.49.64:2202: i/o timeout", 30)
        assertTrue(failure.beforeSend)
        assertEquals("connect_timeout", failure.code)
        assertFalse(failure.message.contains("89.167"))
    }

    @Test fun timeoutAfterSendingCannotBeRetried() {
        assertFalse(sshExecutionFailure(1, "SSH administrative request failed: remote administrative command failed: i/o timeout", 30).beforeSend)
        val timeout = sshExecutionFailure(-1, "i/o timeout", 30)
        assertFalse(timeout.beforeSend)
        assertTrue(timeout.message.contains("30 с"))
        assertFalse(timeout.message.contains("240"))
    }

    @Test fun authenticationAndHostKeyErrorsArePermanent() {
        for (error in listOf("ssh: unable to authenticate, no supported methods remain", "ssh: handshake failed: host key mismatch")) {
            assertFalse(sshExecutionFailure(1, "SSH administrative request failed: ssh dial example:2202: $error", 30).beforeSend)
        }
    }

    @Test fun unrecognizedRemoteOutputDoesNotLeakSecrets() {
        val failure = sshExecutionFailure(1, "password=private-value token=secret-value payload=sensitive remote output", 30)
        assertEquals("unknown_result", failure.code)
        assertFalse(failure.beforeSend)
        assertFalse(failure.message.contains("private-value"))
        assertFalse(failure.message.contains("secret-value"))
        assertFalse(failure.message.contains("sensitive"))
    }
}
