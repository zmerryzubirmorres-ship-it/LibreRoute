package io.github.libreroute.util

import org.junit.Assert.*
import org.junit.Test

class BackupEncryptionTest {
    @Test fun encryptedBackupIsPortableAndHidesKeys() {
        val raw = "{\"encryptionKey\":\"private-key\",\"name\":\"Маршрут\"}"
        val encrypted = BackupEncryption.encrypt(raw, "test-password")
        assertFalse(encrypted.contains("private-key")); assertTrue(BackupEncryption.isEncrypted(encrypted))
        assertEquals(raw, BackupEncryption.decrypt(encrypted, "test-password"))
        assertNotEquals(encrypted, BackupEncryption.encrypt(raw, "test-password"))
    }
    @Test fun wrongPasswordOrModifiedCiphertextNeverRestoresData() {
        val encrypted = BackupEncryption.encrypt("private", "test-password")
        assertTrue(runCatching { BackupEncryption.decrypt(encrypted, "wrong-password") }.isFailure)
        assertTrue(runCatching { BackupEncryption.decrypt(encrypted.replace("\"version\":3", "\"version\":9"), "test-password") }.isFailure)
    }
}
