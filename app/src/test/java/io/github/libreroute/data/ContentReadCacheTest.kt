package io.github.libreroute.data

import org.junit.Assert.*
import org.junit.Test

class ContentReadCacheTest {
    @Test fun reusesUnchangedFileButReloadsReplacementOfSameSize() {
        val cache = ContentReadCache<String>()
        var decryptions = 0
        fun load(bytes: ByteArray) = cache.read("profiles", bytes) { decryptions++; bytes.decodeToString() }
        assertEquals("old", load("old".toByteArray()))
        assertEquals("old", load("old".toByteArray()))
        assertEquals(1, decryptions)
        assertEquals("new", load("new".toByteArray()))
        assertEquals(2, decryptions)
        cache.invalidate("profiles")
        assertEquals("new", load("new".toByteArray()))
        assertEquals(3, decryptions)
    }

    @Test fun failureDoesNotReplaceLastGoodSnapshotOrPoisonRetry() {
        val cache = ContentReadCache<String>()
        assertEquals("good", cache.read("profiles", byteArrayOf(1)) { "good" })
        try { cache.read("profiles", byteArrayOf(2)) { error("Decryption failed") }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals("good", cache.read("profiles", byteArrayOf(1)) { error("Must be cached") })
        assertEquals("recovered", cache.read("profiles", byteArrayOf(2)) { "recovered" })
    }

    @Test fun isolatesFilesAndDoesNotRetainMutableInputBytes() {
        val cache = ContentReadCache<String>()
        val bytes = byteArrayOf(1)
        cache.read("first", bytes) { "first" }
        bytes[0] = 2
        assertEquals("updated", cache.read("first", bytes) { "updated" })
        assertEquals("second", cache.read("second", bytes) { "second" })
        assertEquals("updated", cache.read("first", bytes) { error("Must be cached") })
    }
}
