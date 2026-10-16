package io.github.libreroute.admin

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class AdminCommandControlTest {
    @Test fun workflowKeepsProgressButCommandsOwnTheirSubmissionAndCancellation() {
        val control = AdminCommandControl()
        val cancellations = mutableListOf<String>()
        control.run("server", "issue-portable-jazz", true, { _, _ -> fail() }, workflow = true) {
            control.stage("Preparing device")
            val actionId = control.actions.value.single().id
            control.run("server", "register-device", true, { _, _ -> fail() }) {
                control.phase(AdminCommandControl.Phase.SENDING)
                assertFalse(control.beforeSubmission())
            }
            assertEquals(actionId, control.actions.value.single().id)
            control.stage("Starting Jazz")
            runCatching {
                control.run("server", "assign-profile", true, { _, _ -> fail("Already submitted") }) {
                    control.phase(AdminCommandControl.Phase.SENDING)
                    error("reply lost")
                }
            }
            runCatching {
                control.run("server", "issue-invitation", true, { _, _ -> cancellations += "invitation" }) {
                    control.run("server", "get-cluster-snapshot", false, { _, _ -> fail() }) {
                        control.phase(AdminCommandControl.Phase.SENDING)
                        assertTrue(control.beforeSubmission())
                        error("access probe offline")
                    }
                }
            }
            assertEquals(listOf("invitation"), cancellations)
            assertEquals("Starting Jazz", control.actions.value.single().detail)
            assertEquals(actionId, control.actions.value.single().id)
        }
        assertTrue(control.actions.value.isEmpty())
    }

    @Test fun failedAccessNeverFormsCommandAndCancelsLocally() {
        val control = AdminCommandControl()
        var formed = false
        var cancelled = 0
        val result = runCatching {
            control.run("server", "create-user", true, { _, _ -> cancelled++ }) {
                check(control.actions.value.single().phase == AdminCommandControl.Phase.CHECKING)
                error("administrator status is revoked")
                @Suppress("UNREACHABLE_CODE")
                formed = true
            }
        }
        assertTrue(result.isFailure)
        assertFalse(formed)
        assertEquals(1, cancelled)
        assertTrue(control.actions.value.isEmpty())
    }

    @Test fun signedProbeCannotMarkParentAsSubmitted() {
        val control = AdminCommandControl()
        var cancelled = 0
        runCatching {
            control.run("server", "issue-invitation", true, { _, _ -> cancelled++ }) {
                control.run("server", "get-cluster-snapshot", false, { _, _ -> fail() }) {
                    control.phase(AdminCommandControl.Phase.SENDING)
                    assertEquals(AdminCommandControl.Phase.CHECKING, control.actions.value.single().phase)
                    error("network unavailable")
                }
            }
        }
        assertEquals(1, cancelled)
        assertTrue(control.actions.value.isEmpty())
    }

    @Test fun lostResponseAfterSubmissionIsNeverLocalCancellation() {
        val control = AdminCommandControl()
        var cancelled = false
        runCatching {
            control.run("server", "create-user", true, { _, _ -> cancelled = true }) {
                control.phase(AdminCommandControl.Phase.FORMING)
                control.phase(AdminCommandControl.Phase.SENDING)
                error("response lost")
            }
        }
        assertFalse(cancelled)
        assertTrue(control.actions.value.isEmpty())
        control.run("server", "create-user", true, { _, _ -> fail() }) { Unit }
    }

    @Test fun concurrentMutationIsRejectedWithoutRunningItsBody() {
        val control = AdminCommandControl()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val worker = thread {
            control.run("server", "create-user", true, { _, _ -> fail() }) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val secondExecuted = AtomicBoolean()
            var cancelled = false
            val second = runCatching {
                control.run("server", "suspend-user", true, { _, _ -> cancelled = true }) {
                    secondExecuted.set(true)
                }
            }
            assertTrue(second.isFailure)
            assertTrue(cancelled)
            assertFalse(secondExecuted.get())
            assertEquals(1, control.actions.value.size)
        } finally {
            release.countDown()
            worker.join(5000)
        }
        assertFalse(worker.isAlive)
        assertTrue(control.actions.value.isEmpty())
    }
}
