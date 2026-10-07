package io.github.libreroute.admin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class RemoteAuthSyncTest {

    @Test
    fun browserAcceptsWebsitesAndRejectsLocalSchemes() {
        assertEquals("https://example.org/path", RemoteAuthActivity.browserUrl("example.org/path"))
        assertEquals("http://localhost:8080/", RemoteAuthActivity.browserUrl("http://localhost:8080/"))
        assertEquals("https://example.org:8443/", RemoteAuthActivity.browserUrl("example.org:8443/"))
        for (url in listOf("javascript:alert(1)", "file:///etc/passwd", "content://local", "https://user:secret@example.org", "https://example.org:99999", "")) {
            assertEquals(null, RemoteAuthActivity.browserUrl(url))
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun authEncryptionAcceptsServerRawP256Key() {
        val recipient = RecipientInvitationCrypto(InMemoryInvitationStateStore())
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(
            Base64.getDecoder().decode(recipient.getOrCreatePublicKeyBase64()))) as java.security.interfaces.ECPublicKey
        val raw = ByteArray(65).apply {
            this[0] = 4
            val x = key.w.affineX.toByteArray().takeLast(32).toByteArray()
            val y = key.w.affineY.toByteArray().takeLast(32).toByteArray()
            x.copyInto(this, 33 - x.size)
            y.copyInto(this, 65 - y.size)
        }
        val plain = "server authentication payload".toByteArray()
        val (ephemeral, nonce, encrypted) = RecipientInvitationCrypto.encryptForDevice(
            Base64.getUrlEncoder().withoutPadding().encodeToString(raw), plain)
        org.junit.Assert.assertArrayEquals(plain, recipient.decrypt(ephemeral, nonce, encrypted))
    }

    @Test
    fun testOriginFiltering() {
        // Allowed origins
        assertTrue(RemoteAuthActivity.isAllowedHost("yandex.ru"))
        assertTrue(RemoteAuthActivity.isAllowedHost("disk.yandex.ru"))
        assertTrue(RemoteAuthActivity.isAllowedHost("docs.yandex.ru"))
        assertTrue(RemoteAuthActivity.isAllowedHost("passport.yandex.ru"))
        assertTrue(RemoteAuthActivity.isAllowedHost("volga.yandex.ru"))
        assertTrue(RemoteAuthActivity.isAllowedHost("sub.disk.yandex.ru"))
        assertTrue(RemoteAuthActivity.isAllowedHost("127.0.0.1"))
        assertTrue(RemoteAuthActivity.isAllowedHost("localhost"))

        // Disallowed / untrusted origins
        assertFalse(RemoteAuthActivity.isAllowedHost("evil.com"))
        assertFalse(RemoteAuthActivity.isAllowedHost("notyandex.ru"))
        assertFalse(RemoteAuthActivity.isAllowedHost("attacker-yandex.ru"))
        assertFalse(RemoteAuthActivity.isAllowedHost("google.com"))
        assertFalse(RemoteAuthActivity.isAllowedHost(null))
        assertFalse(RemoteAuthActivity.isAllowedHost(""))
    }

    @Test
    fun testAuthBundleHashDeterminism() {
        val bundle1 = AuthBundlePlaintext(
            version = 1,
            targetId = "vds89",
            profileId = "prof_01",
            authSessionId = "sess_01",
            generation = 1,
            createdAt = 1000L,
            nonce = "nonce_123",
            userAgent = "AndroidTestBrowser",
            origins = mapOf(
                "https://disk.yandex.ru/" to "Session_id=sid1; uid=u1",
                "https://docs.yandex.ru/" to "Session_id=sid1"
            )
        )

        // Same data with different map insertion order must produce identical hash
        val bundle2 = AuthBundlePlaintext(
            version = 1,
            targetId = "vds89",
            profileId = "prof_01",
            authSessionId = "sess_01",
            generation = 1,
            createdAt = 1000L,
            nonce = "nonce_123",
            userAgent = "AndroidTestBrowser",
            origins = mapOf(
                "https://docs.yandex.ru/" to "Session_id=sid1",
                "https://disk.yandex.ru/" to "Session_id=sid1; uid=u1"
            )
        )

        val hash1 = bundle1.computeContentHash()
        val hash2 = bundle2.computeContentHash()

        assertNotNull(hash1)
        assertEquals(64, hash1.length) // SHA-256 hex string
        assertEquals(hash1, hash2)

        // Altering any parameter must change the hash
        val bundleModified = bundle1.copy(nonce = "nonce_changed")
        assertFalse(hash1 == bundleModified.computeContentHash())
    }

    @Test
    fun testAuthBundleEncryptionAndDecryptionRoundTrip() {
        // Generate Server P-256 EC KeyPair
        val kpg = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }
        val serverPair = kpg.generateKeyPair()
        val serverPubDER = serverPair.public.encoded
        val serverPubBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(serverPubDER)

        val bundle = AuthBundlePlaintext(
            version = 1,
            targetId = "vds89",
            profileId = "prof_test",
            authSessionId = "sess_abc",
            generation = 2,
            createdAt = 1700000000L,
            nonce = "nonce_xyz",
            userAgent = "Mozilla/5.0 Android",
            origins = mapOf(
                "https://disk.yandex.ru/" to "Session_id=secret_session_token_123; HttpOnly"
            )
        )
        val plaintext = json.encodeToString(bundle).toByteArray(Charsets.UTF_8)

        // Client encrypts for server
        val (ephemeralPub, nonce, ciphertext) = RecipientInvitationCrypto.encryptForDevice(serverPubBase64, plaintext)
        assertNotNull(ephemeralPub)
        assertNotNull(nonce)
        assertNotNull(ciphertext)

        // Server decrypts using server private key
        val ephPubBytes = Base64.getUrlDecoder().decode(ephemeralPub)
        val nonceBytes = Base64.getUrlDecoder().decode(nonce)
        val ctBytes = Base64.getUrlDecoder().decode(ciphertext)

        // Reconstruct ephemeral EC public key
        val kf = KeyFactory.getInstance("EC")
        // Since ephemeralPub is raw 65 bytes uncompressed point (0x04 || X || Y),
        // we can decode X and Y to ECPublicKeySpec
        assertEquals(65, ephPubBytes.size)
        assertEquals(0x04.toByte(), ephPubBytes[0])
        val xBytes = ephPubBytes.copyOfRange(1, 33)
        val yBytes = ephPubBytes.copyOfRange(33, 65)
        val point = java.security.spec.ECPoint(java.math.BigInteger(1, xBytes), java.math.BigInteger(1, yBytes))
        val ecParams = (serverPair.public as java.security.interfaces.ECPublicKey).params
        val ephPubKey = kf.generatePublic(java.security.spec.ECPublicKeySpec(point, ecParams))

        // ECDH with Server Private Key
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(serverPair.private)
        agreement.doPhase(ephPubKey, true)
        val sharedSecret = agreement.generateSecret()

        // HKDF with Salt and Info
        val salt = "LibreRoute-DeviceInvitation-v1".toByteArray(Charsets.UTF_8)
        val info = "invite-payload".toByteArray(Charsets.UTF_8)
        val extract = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(salt, "HmacSHA256"))
        }.doFinal(sharedSecret)
        val derivedKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(extract, "HmacSHA256"))
            update(info)
            update(1.toByte())
            doFinal().copyOf(32)
        }

        // AES-256-GCM Decrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedKey, "AES"), GCMParameterSpec(128, nonceBytes))
            updateAAD(ephPubBytes)
        }
        val decryptedBytes = cipher.doFinal(ctBytes)
        val decryptedJson = String(decryptedBytes, Charsets.UTF_8)

        val decryptedBundle = json.decodeFromString<AuthBundlePlaintext>(decryptedJson)
        assertEquals(bundle.version, decryptedBundle.version)
        assertEquals(bundle.targetId, decryptedBundle.targetId)
        assertEquals(bundle.profileId, decryptedBundle.profileId)
        assertEquals(bundle.authSessionId, decryptedBundle.authSessionId)
        assertEquals(bundle.nonce, decryptedBundle.nonce)
        assertEquals(bundle.origins["https://disk.yandex.ru/"], decryptedBundle.origins["https://disk.yandex.ru/"])
    }

    @Test
    fun testAuthIncidentsModel() {
        val inc = AuthIncident(
            incidentId = "inc_001",
            targetId = "vds89",
            profileId = "prof_yandex",
            code = "CAPTCHA_REQUIRED",
            severity = "critical",
            repeatCount = 3,
            authGeneration = 1,
            status = "open",
            description = "Captcha challenged by Yandex endpoint",
            actionRequired = "Log in via browser and pass captcha"
        )
        assertTrue(inc.isOpen)
        assertFalse(inc.isResolved)
        assertFalse(inc.isAcknowledged)

        val resolved = inc.copy(status = "resolved")
        assertTrue(resolved.isResolved)
        assertFalse(resolved.isOpen)
    }
}
