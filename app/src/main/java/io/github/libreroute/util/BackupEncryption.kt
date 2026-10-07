package io.github.libreroute.util

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Portable authenticated backups; Keystore identities and SSH credentials are excluded. */
object BackupEncryption {
    private const val ITERATIONS = 210_000
    private const val LIMIT = 16 * 1024 * 1024
    private val aad = "LibreRoute backup v3".toByteArray(Charsets.UTF_8)
    private val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }
    @Serializable private data class Envelope(val format: String = "libreroute-encrypted-backup", val version: Int = 3,
        val iterations: Int = ITERATIONS, val salt: String, val nonce: String, val ciphertext: String)

    fun encrypt(plaintext: String, password: String): String {
        require(password.length in 8..1024) { "Пароль резервной копии должен содержать не менее 8 символов" }
        val raw = plaintext.toByteArray(Charsets.UTF_8)
        require(raw.size <= LIMIT) { "Резервная копия слишком большая" }
        val random = SecureRandom(); val salt = ByteArray(16).also(random::nextBytes); val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, derive(password, salt, ITERATIONS), GCMParameterSpec(128, nonce)); cipher.updateAAD(aad)
        val b64 = Base64.getUrlEncoder().withoutPadding()
        return json.encodeToString(Envelope(salt = b64.encodeToString(salt), nonce = b64.encodeToString(nonce), ciphertext = b64.encodeToString(cipher.doFinal(raw))))
    }

    fun isEncrypted(content: String): Boolean = content.contains("\"libreroute-encrypted-backup\"")

    fun decrypt(content: String, password: String): String {
        require(password.isNotEmpty() && password.length <= 1024) { "Введите пароль резервной копии" }
        require(content.length <= LIMIT * 2) { "Резервная копия слишком большая" }
        val envelope = json.decodeFromString<Envelope>(content)
        require(envelope.format == "libreroute-encrypted-backup" && envelope.version == 3 && envelope.iterations == ITERATIONS) { "Неподдерживаемый формат резервной копии" }
        val b64 = Base64.getUrlDecoder(); val salt = b64.decode(envelope.salt); val nonce = b64.decode(envelope.nonce)
        require(salt.size == 16 && nonce.size == 12) { "Резервная копия повреждена" }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, derive(password, salt, envelope.iterations), GCMParameterSpec(128, nonce)); cipher.updateAAD(aad)
            String(cipher.doFinal(b64.decode(envelope.ciphertext)), Charsets.UTF_8)
        } catch (_: javax.crypto.AEADBadTagException) { throw IllegalArgumentException("Неверный пароль или повреждённая резервная копия") }
    }

    private fun derive(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val chars = password.toCharArray(); val spec = PBEKeySpec(chars, salt, iterations, 256)
        return try { SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") }
        finally { spec.clearPassword(); chars.fill('\u0000') }
    }
}
