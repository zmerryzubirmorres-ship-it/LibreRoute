package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore-backed SharedPreferences wrapper.
 *
 * String values are encrypted with AES-256-GCM before writing to disk.
 * Non-String values (Int, Boolean, Long) are stored plain — they carry no
 * user-identifiable secrets (only flags and numeric settings).
 *
 * Encryption key is generated once per device and stored in the hardware-backed
 * AndroidKeyStore (alias: [KEY_ALIAS]). The key never leaves the secure enclave.
 *
 * Storage format on disk (Base64):  [12-byte IV] || [ciphertext + 16-byte GCM tag]
 */
class SecurePreferences(context: Context, prefsName: String) {

    companion object {
        private const val TAG = "SecurePreferences"
        private const val KEY_ALIAS = "fluxon.secure_prefs.v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER_ALGO = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        // Prefix to distinguish encrypted values from plain ones
        private const val ENC_PREFIX = "ENC:"
        private val lock = Any()
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    // ── Keystore helpers ──────────────────────────────────────────────────────

    private fun getOrCreateKey(): SecretKey = synchronized(lock) {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        try {
            (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        } catch (e: Exception) {
            when (e) {
                is KeyPermanentlyInvalidatedException, is UnrecoverableKeyException -> {
                    Logx.w(TAG, "Key invalidated, regenerating: ${e.message}")
                    runCatching { ks.deleteEntry(KEY_ALIAS) }
                }
                else -> throw e
            }
        }

        val kgen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kgen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return kgen.generateKey()
    }

    private fun encrypt(plaintext: String): String = synchronized(lock) {
        return try {
            val cipher = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val iv = cipher.iv
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            ENC_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Logx.e(TAG, "Encryption failed: ${e.message}", e)
            throw SecurityException("SecurePreferences encryption failure: ${e.message}", e)
        }
    }

    private fun decrypt(stored: String): String? = synchronized(lock) {
        if (!stored.startsWith(ENC_PREFIX)) return stored // legacy plain value
        return try {
            val combined = Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP)
            if (combined.size < GCM_IV_LENGTH + 16) return null
            val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
            val cipher = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val plainBytes = cipher.doFinal(combined, GCM_IV_LENGTH, combined.size - GCM_IV_LENGTH)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            Logx.e(TAG, "Decryption failed: ${e.message}", e)
            null
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Read encrypted string; returns [default] if not set or decryption fails. */
    fun getString(key: String, default: String?): String? {
        val raw = prefs.getString(key, null) ?: return default
        return decrypt(raw) ?: default
    }

    /** Write string encrypted with AES-256-GCM. */
    fun putString(key: String, value: String?) {
        if (value == null) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = encrypt(value)
        prefs.edit().putString(key, encrypted).apply()
    }

    /** Read plain boolean (not sensitive). */
    fun getBoolean(key: String, default: Boolean): Boolean =
        prefs.getBoolean(key, default)

    /** Write plain boolean. */
    fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }

    /** Read plain int. */
    fun getInt(key: String, default: Int): Int =
        prefs.getInt(key, default)

    /** Write plain int. */
    fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }

    /** Remove a key. */
    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    /** Migrate a plain-text value that was previously stored without encryption. */
    fun migrateIfNeeded(key: String) {
        val raw = prefs.getString(key, null) ?: return
        if (raw.startsWith(ENC_PREFIX)) return // already encrypted
        runCatching {
            putString(key, raw)
            Logx.i(TAG, "Migrated key='$key' to encrypted storage")
        }.onFailure { e ->
            Logx.e(TAG, "Failed to migrate key='$key': ${e.message}")
        }
    }
}
