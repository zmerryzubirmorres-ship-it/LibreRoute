package io.github.libreroute.admin

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.libreroute.util.Logx
import java.security.KeyFactory
import java.security.AlgorithmParameters
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.math.BigInteger

/**
 * Manages ECDSA P-256 keys, fingerprints, envelope signing, and server key verification.
 * Backed by Android Keystore on devices. JVM tests use an in-memory key.
 */
class AdminKeyManager(private val context: Context? = null) {

    companion object {
        private const val TAG = "AdminKeyManager"
        const val KEY_ALIAS = "libreroute_admin_identity_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val EC_CURVE = "secp256r1"
        private const val SIGN_ALGORITHM = "SHA256withECDSA"

        // In-memory fallback keypair for JVM tests or emulators lacking Keystore provider
        @Volatile
        private var fallbackKeyPair: KeyPair? = null

        fun clearFallbackKey() {
            fallbackKeyPair = null
        }
    }

    private val lock = Any()

    /**
     * Retrieves or generates the device's ECDSA P-256 key pair.
     */
    fun getOrCreateKeyPair(): KeyPair = synchronized(lock) {
        try {
            // Attempt Android Keystore
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                val priv = ks.getKey(KEY_ALIAS, null) as? PrivateKey
                val pub = ks.getCertificate(KEY_ALIAS)?.publicKey
                if (priv != null && pub != null) {
                    return KeyPair(pub, priv)
                }
            }

            // Generate new ECDSA key pair in Android Keystore
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
            val parameterSpec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()

            kpg.initialize(parameterSpec)
            return kpg.generateKeyPair()
        } catch (e: Exception) {
            if (context != null) {
                Logx.e(TAG, "Android Keystore unavailable for administrator identity")
                throw IllegalStateException("Administrator identity requires Android Keystore", e)
            }
            Logx.w(TAG, "Android Keystore unavailable; using JVM test key")
            fallbackKeyPair?.let { return it }

            // Software EC fallback (e.g. JVM unit tests or test environments)
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec(EC_CURVE))
            val kp = kpg.generateKeyPair()
            fallbackKeyPair = kp
            return kp
        }
    }

    /**
     * Returns device public key as PublicKey.
     */
    fun getPublicKey(): PublicKey {
        return getOrCreateKeyPair().public
    }

    /**
     * Returns standard Base64-encoded X.509 representation of device public key.
     */
    fun getPublicKeyBase64(): String {
        val pub = getPublicKey()
        return Base64.getEncoder().encodeToString(pub.encoded)
    }

    /**
     * Computes SHA-256 fingerprint of device public key in "SHA256:<hex>" format.
     */
    fun getFingerprint(): String {
        val pub = getPublicKey()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(pub.encoded)
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "SHA256:$hex"
    }

    /**
     * User-friendly short fingerprint (e.g. "AA:BB:CC:DD...").
     */
    fun getFormattedFingerprint(): String {
        val pub = getPublicKey()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(pub.encoded)
        return digest.take(8).joinToString(":") { "%02X".format(it) }
    }

    /**
     * Generates a 6-digit confirmation code for join request out-of-band verification.
     */
    fun generateOneTimeCode(nonce: String): String {
        val pub = getPublicKey()
        val md = MessageDigest.getInstance("SHA-256")
        md.update(pub.encoded)
        md.update(nonce.toByteArray(Charsets.UTF_8))
        val digest = md.digest()
        val num = ((digest[0].toInt() and 0x7F) shl 24) or
                ((digest[1].toInt() and 0xFF) shl 16) or
                ((digest[2].toInt() and 0xFF) shl 8) or
                (digest[3].toInt() and 0xFF)
        val code = (num % 1_000_000).toString().padStart(6, '0')
        return code
    }

    /**
     * Signs the canonical envelope string and returns a copy of envelope with signature populated.
     * Canonical string: "ver:type:subtype:profile_id:op_id:rev:exp:sender:nonce:payload"
     */
    fun signEnvelope(envelope: AdminEnvelope): AdminEnvelope {
        val canonical = envelope.canonicalSigningString()
        val sig = signCanonical(canonical)
        return envelope.copy(signature = sig)
    }

    /**
     * Low-level ECDSA SHA-256 signing of a canonical string.
     */
    fun signCanonical(canonical: String): String {
        val kp = getOrCreateKeyPair()
        val s = Signature.getInstance(SIGN_ALGORITHM)
        s.initSign(kp.private)
        s.update(canonical.toByteArray(Charsets.UTF_8))
        val sigBytes = s.sign()
        return Base64.getEncoder().encodeToString(sigBytes)
    }

    /**
     * Verifies signature of an envelope using the given public key.
     */
    fun verifyEnvelope(envelope: AdminEnvelope, publicKey: PublicKey): Boolean {
        if (envelope.signature.isBlank()) return false
        val canonical = envelope.canonicalSigningString()
        return verifyCanonical(canonical, envelope.signature, publicKey)
    }

    /**
     * Low-level ECDSA SHA-256 verification of a canonical string.
     */
    fun verifyCanonical(canonical: String, signatureBase64: String, publicKey: PublicKey): Boolean {
        return try {
            val s = Signature.getInstance(SIGN_ALGORITHM)
            s.initVerify(publicKey)
            s.update(canonical.toByteArray(Charsets.UTF_8))
            val sigBytes = decodeBase64(signatureBase64)
            s.verify(sigBytes)
        } catch (e: Exception) {
            Logx.e(TAG, "Signature verification failed: ${e.message}", e)
            false
        }
    }

    /**
     * Verifies server signature on an ADMIN_GRANT or ADMIN_RESPONSE envelope,
     * comparing the server's public key with the pinned key.
     */
    fun verifyServerResponse(
        envelope: AdminEnvelope,
        serverPublicKeyBase64: String,
        pinnedServerPublicKeyBase64: String?
    ): Boolean {
        // Enforce server key pinning: if already pinned, keys MUST match
        if (pinnedServerPublicKeyBase64.isNullOrBlank() || serverPublicKeyBase64 != pinnedServerPublicKeyBase64) {
            Logx.e(TAG, "Server public key does not match pinned server key! Possible MITM.")
            return false
        }

        val serverPubKey = parsePublicKey(serverPublicKeyBase64) ?: run {
            Logx.e(TAG, "Failed to decode server public key")
            return false
        }

        return verifyEnvelope(envelope, serverPubKey)
    }

    /**
     * Decodes Base64 X.509 SubjectPublicKeyInfo into a PublicKey object.
     */
    fun parsePublicKey(base64Key: String): PublicKey? {
        return try {
            val bytes = decodeBase64(base64Key)
            val kf = KeyFactory.getInstance("EC")
            if (bytes.size == 65 && bytes[0] == 4.toByte()) {
                val params = AlgorithmParameters.getInstance("EC")
                params.init(ECGenParameterSpec(EC_CURVE))
                val curve = params.getParameterSpec(ECParameterSpec::class.java)
                val point = ECPoint(
                    BigInteger(1, bytes.copyOfRange(1, 33)),
                    BigInteger(1, bytes.copyOfRange(33, 65))
                )
                kf.generatePublic(ECPublicKeySpec(point, curve))
            } else {
                kf.generatePublic(X509EncodedKeySpec(bytes))
            }
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to parse EC public key: ${e.message}", e)
            null
        }
    }

    /**
     * Computes SHA-256 fingerprint from a Base64-encoded public key.
     */
    fun computeFingerprint(base64Key: String): String? {
        return try {
            val bytes = decodeBase64(base64Key)
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(bytes)
            val hex = digest.joinToString("") { "%02x".format(it) }
            "SHA256:$hex"
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeBase64(value: String): ByteArray =
        runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { Base64.getDecoder().decode(value) }
}
