package io.github.libreroute.admin

import android.content.Context
import io.github.libreroute.util.SecurePreferences
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Storage for the recipient's ECDH key. Production strings are encrypted by Android Keystore. */
interface InvitationStateStore {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

class SecureInvitationStateStore(context: Context) : InvitationStateStore {
    private val prefs = SecurePreferences(context, "recipient_invitations")
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) = prefs.putString(key, value)
}

class InMemoryInvitationStateStore : InvitationStateStore {
    private val map = mutableMapOf<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
}

/** P-256 ECDH compatible with LibreRoute docrecords.EncryptForDevice. */
class RecipientInvitationCrypto(private val store: InvitationStateStore) {
    companion object {
        private const val PRIVATE_KEY = "recipient_p256_private_v1"
        private const val PUBLIC_KEY = "recipient_p256_public_v1"
        private val SALT = "LibreRoute-DeviceInvitation-v1".toByteArray(Charsets.UTF_8)
        private val LEGACY_SALT = Base64.getDecoder().decode("T3BlbkZsdXgtRGV2aWNlSW52aXRhdGlvbi12MQ==")
        private val INFO = "invite-payload".toByteArray(Charsets.UTF_8)

        private fun decode(value: String): ByteArray =
            runCatching { Base64.getUrlDecoder().decode(value) }
                .getOrElse { Base64.getDecoder().decode(value) }

        fun encryptForDevice(recipientPublicKeyBase64: String, plaintext: ByteArray): Triple<String, String, String> {
            val recipientKeyBytes = decode(recipientPublicKeyBase64)
            val factory = KeyFactory.getInstance("EC")
            val recipientPubKey = if (recipientKeyBytes.size == 65 && recipientKeyBytes[0] == 4.toByte()) {
                val parameters = AlgorithmParameters.getInstance("EC").apply {
                    init(ECGenParameterSpec("secp256r1"))
                }.getParameterSpec(ECParameterSpec::class.java)
                val point = ECPoint(BigInteger(1, recipientKeyBytes.copyOfRange(1, 33)),
                    BigInteger(1, recipientKeyBytes.copyOfRange(33, 65)))
                factory.generatePublic(ECPublicKeySpec(point, parameters))
            } else {
                factory.generatePublic(X509EncodedKeySpec(recipientKeyBytes))
            }
            val ephemeralPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()

            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(ephemeralPair.private)
            agreement.doPhase(recipientPubKey, true)
            val sharedSecret = agreement.generateSecret()

            val extract = Mac.getInstance("HmacSHA256").apply {
                init(SecretKeySpec(SALT, "HmacSHA256"))
            }.doFinal(sharedSecret)
            val derivedKey = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(extract, "HmacSHA256"))
                update(INFO)
                update(1.toByte())
                doFinal().copyOf(32)
            }

            val ecPub = ephemeralPair.public as java.security.interfaces.ECPublicKey
            val x = ecPub.w.affineX.toByteArray()
            val y = ecPub.w.affineY.toByteArray()
            val raw65 = ByteArray(65)
            raw65[0] = 0x04
            fun pad32(src: ByteArray, dest: ByteArray, offset: Int) {
                val clean = if (src.size == 33 && src[0] == 0.toByte()) src.copyOfRange(1, 33) else src
                System.arraycopy(clean, 0, dest, offset + (32 - clean.size), clean.size)
            }
            pad32(x, raw65, 1)
            pad32(y, raw65, 33)

            val nonce = ByteArray(12).apply { java.security.SecureRandom().nextBytes(this) }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(raw65)
            }
            val ciphertext = cipher.doFinal(plaintext)
            return Triple(
                Base64.getUrlEncoder().withoutPadding().encodeToString(raw65),
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce),
                Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext)
            )
        }
    }

    @Synchronized
    fun getOrCreatePublicKeyBase64(): String = Base64.getEncoder().encodeToString(keyPair().public.encoded)

    @Synchronized
    fun decrypt(ephemeralPublicKey: String, nonce: String, encryptedPayload: String): ByteArray {
        val ephemeral = decode(ephemeralPublicKey)
        require(ephemeral.size == 65 && ephemeral[0] == 4.toByte()) { "Invalid P-256 ephemeral public key" }
        val nonceBytes = decode(nonce)
        require(nonceBytes.size == 12) { "Invalid invitation nonce" }
        val ciphertext = decode(encryptedPayload)
        require(ciphertext.size >= 16) { "Invalid invitation ciphertext" }

        val parameters = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
        val point = ECPoint(
            BigInteger(1, ephemeral.copyOfRange(1, 33)),
            BigInteger(1, ephemeral.copyOfRange(33, 65))
        )
        val publicKey = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, parameters))
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(keyPair().private)
        agreement.doPhase(publicKey, true)
        val sharedSecret = agreement.generateSecret()
        val derivedKey = hkdfSha256(sharedSecret, SALT)
        val plain = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(128, nonceBytes))
                updateAAD(ephemeral)
                doFinal(ciphertext)
            }
        } catch (_: Exception) {
            val legacyKey = hkdfSha256(sharedSecret, LEGACY_SALT)
            try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(legacyKey, "AES"), GCMParameterSpec(128, nonceBytes))
                    updateAAD(ephemeral)
                    doFinal(ciphertext)
                }
            } finally {
                legacyKey.fill(0)
            }
        } finally {
            sharedSecret.fill(0)
            derivedKey.fill(0)
        }
        return plain
    }

    fun publicKeyHashHex(): String {
        val publicDER = keyPair().public.encoded
        return MessageDigest.getInstance("SHA-256").digest(publicDER)
            .joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun signRedeemEnvelope(envelope: AdminEnvelope): AdminEnvelope {
        require(envelope.sender == publicKeyHashHex()) { "Recipient identity mismatch" }
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair().private)
        signer.update(envelope.canonicalSigningString().toByteArray(Charsets.UTF_8))
        return envelope.copy(signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign()))
    }

    private fun hkdfSha256(secret: ByteArray, salt: ByteArray = SALT): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }.doFinal(secret)
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(extract, "HmacSHA256"))
                update(INFO)
                update(1.toByte())
                doFinal().copyOf(32)
            }
        } finally {
            extract.fill(0)
        }
    }

    private fun keyPair(): KeyPair {
        val privateEncoded = store.get(PRIVATE_KEY)
        val publicEncoded = store.get(PUBLIC_KEY)
        if (privateEncoded != null || publicEncoded != null) {
            require(privateEncoded != null && publicEncoded != null) { "Recipient key storage is incomplete" }
            val factory = KeyFactory.getInstance("EC")
            val privateKey: PrivateKey = factory.generatePrivate(PKCS8EncodedKeySpec(decode(privateEncoded)))
            val publicKey: PublicKey = factory.generatePublic(X509EncodedKeySpec(decode(publicEncoded)))
            return KeyPair(publicKey, privateKey)
        }
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        store.put(PRIVATE_KEY, Base64.getEncoder().encodeToString(pair.private.encoded))
        store.put(PUBLIC_KEY, Base64.getEncoder().encodeToString(pair.public.encoded))
        return pair
    }
}
