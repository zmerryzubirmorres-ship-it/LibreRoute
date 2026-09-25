package io.github.p1neapplexpress.openflux.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.p1neapplexpress.openflux.util.Constants
import io.github.p1neapplexpress.openflux.util.Logx
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class EncryptedProfileRepository(private val context: Context) {

    companion object {
        private const val TAG = "EncryptedProfileRepo"
        private const val KEY_ALIAS = "fluxon_profile_master_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val CIPHER_ALGO = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
        private const val FILE_NAME = "profiles_secure.bin"
        private val lock = Any()
    }

    private val file = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))
    private val json = Json { ignoreUnknownKeys = true }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

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

    @Synchronized
    fun load(): List<Tunnel> = synchronized(lock) {
        val backupFile = File(file.baseFile.path + ".bak")
        if (!file.baseFile.exists() && !backupFile.exists()) {
            val migrated = migrateFromSharedPreferences()
            if (migrated != null) {
                return migrated
            }
            return emptyList()
        }

        return try {
            val bytes = file.readFully()
            if (bytes.size < GCM_IV_LENGTH + 16) return emptyList()
            val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
            val cipher = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            val decrypted = cipher.doFinal(bytes, GCM_IV_LENGTH, bytes.size - GCM_IV_LENGTH)
            json.decodeFromString<List<Tunnel>>(String(decrypted, Charsets.UTF_8))
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to decrypt profiles: ${e.message}", e)
            emptyList()
        }
    }

    @Synchronized
    fun save(tunnels: List<Tunnel>): Unit = synchronized(lock) {
        try {
            val cipher = Cipher.getInstance(CIPHER_ALGO)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val plaintext = json.encodeToString(tunnels).toByteArray(Charsets.UTF_8)
            val ciphertext = cipher.doFinal(plaintext)
            val iv = cipher.iv
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)

            val dir = file.baseFile.parentFile
            if (dir != null && !dir.exists()) {
                dir.mkdirs()
            }

            val out = file.startWrite()
            try {
                out.write(combined)
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to encrypt and save profiles: ${e.message}", e)
            throw e
        }
    }

    private fun migrateFromSharedPreferences(): List<Tunnel>? {
        val prefs = context.applicationContext.getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)
        val raw = prefs.getString(Constants.PREF_TUNNELS_KEY, null) ?: return null
        return try {
            val tunnels = json.decodeFromString<List<Tunnel>>(raw)
            save(tunnels)
            prefs.edit().remove(Constants.PREF_TUNNELS_KEY).apply()
            Logx.i(TAG, "Migrated ${tunnels.size} profiles to EncryptedProfileRepository")
            tunnels
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to migrate profiles from SharedPreferences: ${e.message}", e)
            null
        }
    }
}
