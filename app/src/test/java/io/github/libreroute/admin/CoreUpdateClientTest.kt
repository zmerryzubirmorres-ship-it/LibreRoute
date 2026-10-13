package io.github.libreroute.admin

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class CoreUpdateClientTest {
    private val target = "a".repeat(64)
    private fun plan(size: Long = 0) = buildJsonObject {
        put("manifest", buildJsonObject { put("size", size); put("sha256", target); put("version", "1.2.28") })
    }
    private fun state(stage: String, hash: String = target, version: String = "1.2.28") = buildJsonObject {
        put("stage", stage); put("runtime", buildJsonObject { put("build_id", hash); put("version", version) })
    }

    @Test fun rollingBackIsObservedToTerminalStateWithoutResendingRollback() {
        val actions = mutableListOf<String>()
        val client = CoreUpdateClient({ action, _ ->
            actions += action
            state(if (actions.size == 1) "rolling_back" else "rolled_back")
        }, {})
        assertEquals("rolled_back", client.reconcile(plan()).string("stage"))
        assertEquals(listOf("status", "status"), actions)
    }

    @Test fun oldCompatibleProcessCannotConfirmUpdate() {
        val actions = mutableListOf<String>()
        val client = CoreUpdateClient({ action, _ -> actions += action; state("awaiting_confirmation", "b".repeat(64)) }, {})
        assertTrue(runCatching { client.reconcile(plan()) }.isFailure)
        assertEquals(listOf("status"), actions)
    }

    @Test fun lostCommitAckReconcilesWithoutSecondMutation() {
        val actions = mutableListOf<String>()
        val client = CoreUpdateClient({ action, _ ->
            actions += action
            if (action == "status") state("awaiting_confirmation") else error("ACK lost")
        }, {})
        assertTrue(runCatching { client.reconcile(plan()) }.isFailure)
        val restarted = CoreUpdateClient({ action, _ -> actions += action; state("committed") }, {})
        assertEquals("committed", restarted.reconcile(plan()).string("stage"))
        assertEquals(listOf("status", "confirm", "status"), actions)
    }

    @Test fun receivingReconcileDoesNotAutomaticallySubmitDeferredCommand() {
        val actions = mutableListOf<String>()
        val client = CoreUpdateClient({ action, _ -> actions += action; state("receiving") }, {})
        assertEquals("receiving", client.reconcile(plan()).string("stage"))
        assertEquals(listOf("status"), actions)
    }

    @Test fun cancellationBeforeSubmitNeverSendsBegin() {
        val file = File.createTempFile("core-update", ".bin")
        try {
            file.writeText("payload")
            val manifest = buildJsonObject {
                put("size", file.length()); put("sha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex())
            }
            val actions = mutableListOf<String>()
            val client = CoreUpdateClient({ action, _ -> actions += action; state("receiving") }, {}, cancelled = { true })
            assertTrue(runCatching { client.submit(buildJsonObject { put("manifest", manifest) }, file) }.isFailure)
            assertTrue(actions.isEmpty())
        } finally { file.delete() }
    }

    @Test fun resumedUploadStartsAtDurableOffsetAndChecksFullTarget() {
        val file = File.createTempFile("core-update", ".bin")
        try {
            val bytes = ByteArray(8 * 1024 + 17) { (it % 251).toByte() }; file.writeBytes(bytes)
            val actions = mutableListOf<String>(); var received = 8 * 1024L
            fun receiving() = buildJsonObject {
                put("stage", "receiving"); put("next_offset", received); put("received_bytes", received)
                put("size", bytes.size); put("chunk_bytes", 8 * 1024)
            }
            val client = CoreUpdateClient({ action, fields ->
                actions += action
                when (action) {
                    "begin" -> receiving()
                    "put_chunk" -> {
                        assertEquals(8 * 1024L, fields.getValue("offset").jsonPrimitive.long)
                        val chunk = java.util.Base64.getUrlDecoder().decode(fields.getValue("data").jsonPrimitive.content)
                        assertArrayEquals(bytes.copyOfRange(8 * 1024, bytes.size), chunk)
                        received += chunk.size; receiving()
                    }
                    "verify" -> state("verified")
                    "prepare" -> state("prepared")
                    "apply" -> state("committed")
                    else -> error("unexpected $action")
                }
            }, {})
            val manifest = buildJsonObject { put("size", bytes.size); put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).hex()) }
            assertEquals("committed", client.submit(buildJsonObject { put("manifest", manifest) }, file).string("stage"))
            assertEquals(listOf("begin", "put_chunk", "verify", "prepare", "apply"), actions)
        } finally { file.delete() }
    }

    @Test fun cancellationAfterPrepareDoesNotApplyWhenConnectionReturns() {
        val file = File.createTempFile("core-update", ".bin")
        try {
            file.writeText("payload")
            var cancelled = false
            val actions = mutableListOf<String>()
            val client = CoreUpdateClient({ action, _ ->
                actions += action
                when (action) {
                    "begin" -> state("verified")
                    "prepare" -> { cancelled = true; state("prepared") }
                    else -> error("cancelled operation sent $action")
                }
            }, {}, cancelled = { cancelled })
            val manifest = buildJsonObject {
                put("size", file.length()); put("sha256", MessageDigest.getInstance("SHA-256").digest(file.readBytes()).hex())
            }
            assertTrue(runCatching { client.submit(buildJsonObject { put("manifest", manifest) }, file) }.isFailure)
            assertEquals(listOf("begin", "prepare"), actions)
        } finally { file.delete() }
    }
}
