package io.github.libreroute.admin

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class CoreUpdateStreamSelectionTest {
    @Test fun advertisedStreamRecoversSameOperationWithoutChunkFallback() {
        val bytes = ByteArray(8192 + 17) { (it % 251).toByte() }
        val file = File.createTempFile("stream-selection", ".bin").apply { writeBytes(bytes) }
        val plan = buildJsonObject { put("manifest", buildJsonObject {
            put("size", bytes.size); put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).hex())
        }) }
        val actions = mutableListOf<String>()
        var opens = 0
        fun state(stage: String, received: Int = 0) = buildJsonObject {
            put("stage", stage); put("stream_available", true); put("received_bytes", received)
            put("next_offset", received); put("size", bytes.size); put("chunk_bytes", 8192)
        }
        val client = CoreUpdateClient(call = { action, _ ->
            actions += action
            when (action) {
                "begin" -> state("receiving")
                "upload_status" -> state("receiving", 8192)
                "verify" -> state("verified", bytes.size)
                "prepare" -> state("prepared", bytes.size)
                "apply" -> state("committed", bytes.size)
                else -> error("Unexpected chunk fallback: $action")
            }
        }, checkpoint = {}, useReliableBatches = true,
            recoverableUploadFailure = { it is UnknownAdminOperation }, pause = {},
            binaryUpload = { artifact, approved, _ ->
                assertEquals(file, artifact); assertEquals(plan, approved)
                if (++opens == 1) throw UnknownAdminOperation("ACK lost")
                state("receiving", bytes.size)
            })
        try {
            assertEquals("committed", client.submit(plan, file).string("stage"))
            assertEquals(2, opens)
            assertEquals(listOf("begin", "upload_status", "verify", "prepare", "apply"), actions)
        } finally { file.delete() }
    }

    @Test fun serverRejectionNeverReopensOrSubmitsVerify() {
        val bytes = ByteArray(17)
        val file = File.createTempFile("stream-denied", ".bin").apply { writeBytes(bytes) }
        val plan = buildJsonObject { put("manifest", buildJsonObject {
            put("size", bytes.size); put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).hex())
        }) }
        val actions = mutableListOf<String>()
        var opens = 0
        val client = CoreUpdateClient(call = { action, _ ->
            actions += action
            buildJsonObject { put("stage", "receiving"); put("stream_available", true) }
        }, checkpoint = {}, useReliableBatches = true,
            recoverableUploadFailure = { it is UnknownAdminOperation }, pause = {},
            binaryUpload = { _, _, _ -> opens++; throw ConfirmedAdminRejection("revoked") })
        try {
            assertTrue(runCatching { client.submit(plan, file) }.exceptionOrNull() is ConfirmedAdminRejection)
            assertEquals(1, opens); assertEquals(listOf("begin"), actions)
        } finally { file.delete() }
    }
}
