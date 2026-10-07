package io.github.libreroute.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID
import java.security.interfaces.ECPublicKey
import java.util.Base64

class AdminKeyManagerTest {

    @Test
    fun testGoRawP256ServerPublicKeyVerification() {
        AdminKeyManager.clearFallbackKey()
        val signer = AdminKeyManager()
        val publicKey = signer.getPublicKey() as ECPublicKey
        fun fixed32(value: java.math.BigInteger): ByteArray {
            val raw = value.toByteArray()
            return ByteArray(32).also { raw.copyInto(it, 32 - raw.size.coerceAtMost(32), (raw.size - 32).coerceAtLeast(0)) }
        }
        val rawPoint = byteArrayOf(4) + fixed32(publicKey.w.affineX) + fixed32(publicKey.w.affineY)
        val goKey = Base64.getUrlEncoder().withoutPadding().encodeToString(rawPoint)
        val env = signer.signEnvelope(AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_GRANT,
            profileId = "profile-1",
            sender = "server",
            payload = "dGVzdA"
        ))
        assertTrue(AdminKeyManager().verifyServerResponse(env, goKey, goKey))
    }

    private lateinit var keyManager: AdminKeyManager

    @Before
    fun setUp() {
        AdminKeyManager.clearFallbackKey()
        keyManager = AdminKeyManager()
    }

    @Test
    fun testKeyPairGenerationAndFingerprint() {
        val kp = keyManager.getOrCreateKeyPair()
        assertNotNull(kp)
        assertNotNull(kp.public)
        assertNotNull(kp.private)

        val fingerprint = keyManager.getFingerprint()
        assertTrue("Fingerprint should start with SHA256:", fingerprint.startsWith("SHA256:"))
        assertEquals(71, fingerprint.length) // "SHA256:" (7) + 64 hex chars = 71
    }

    @Test
    fun testOneTimeCodeGeneration() {
        val nonce = UUID.randomUUID().toString()
        val code1 = keyManager.generateOneTimeCode(nonce)
        val code2 = keyManager.generateOneTimeCode(nonce)

        assertEquals("Same nonce should produce deterministic code", code1, code2)
        assertEquals("Code must be 6 digits", 6, code1.length)
        assertTrue("Code must contain only digits", code1.all { it.isDigit() })
    }

    @Test
    fun testCanonicalStringFormat() {
        val envelope = AdminEnvelope(
            ver = 1,
            type = "ADMIN",
            subtype = "request",
            profileId = "profile-123",
            opId = "op-456",
            rev = 2L,
            exp = 1700000000L,
            sender = "SHA256:abcd",
            nonce = "nonce-789",
            payload = "test-payload"
        )
        val expected = "1|ADMIN|request|profile-123|op-456|2|1700000000|SHA256:abcd|nonce-789|test-payload"
        assertEquals(expected, envelope.canonicalSigningString())
    }

    @Test
    fun testEnvelopeSigningAndVerification() {
        val envelope = AdminEnvelope(
            ver = 1,
            type = "ADMIN",
            subtype = "create",
            profileId = "profile-libreroute-1",
            opId = UUID.randomUUID().toString(),
            rev = 1L,
            exp = System.currentTimeMillis() / 1000L + 300L,
            sender = keyManager.getFingerprint(),
            nonce = UUID.randomUUID().toString(),
            payload = "{\"name\":\"test-profile\"}"
        )

        val signed = keyManager.signEnvelope(envelope)
        assertTrue("Signature must not be empty", signed.signature.isNotBlank())

        val isValid = keyManager.verifyEnvelope(signed, keyManager.getPublicKey())
        assertTrue("Original signed envelope should verify successfully", isValid)

        // Tampering with payload must fail verification
        val tamperedPayload = signed.copy(payload = "{\"name\":\"malicious-profile\"}")
        val isTamperedValid = keyManager.verifyEnvelope(tamperedPayload, keyManager.getPublicKey())
        assertFalse("Tampered payload must fail verification", isTamperedValid)

        // Tampering with revision must fail verification
        val tamperedRev = signed.copy(rev = 999L)
        assertFalse("Tampered revision must fail verification", keyManager.verifyEnvelope(tamperedRev, keyManager.getPublicKey()))
    }

    @Test
    fun testServerResponseVerificationAndPinning() {
        val serverKeyManager = AdminKeyManager()
        val serverPublicKeyBase64 = serverKeyManager.getPublicKeyBase64()

        val envelope = AdminEnvelope(
            ver = 1,
            type = "ADMIN",
            subtype = "grant",
            profileId = "admin-prof",
            opId = UUID.randomUUID().toString(),
            rev = 1L,
            sender = serverKeyManager.getFingerprint(),
            payload = "{\"role\":\"OWNER\"}"
        )
        val signedByServer = serverKeyManager.signEnvelope(envelope)

        // 1. Initial verification with no prior pinned key (first trust)
        val firstCheck = keyManager.verifyServerResponse(
            envelope = signedByServer,
            serverPublicKeyBase64 = serverPublicKeyBase64,
            pinnedServerPublicKeyBase64 = null
        )
        assertFalse("Unpinned server key must not be trusted", firstCheck)

        // 2. Verification with matching pinned key
        val pinnedCheck = keyManager.verifyServerResponse(
            envelope = signedByServer,
            serverPublicKeyBase64 = serverPublicKeyBase64,
            pinnedServerPublicKeyBase64 = serverPublicKeyBase64
        )
        assertTrue("Matching pinned key must verify", pinnedCheck)

        // 3. Verification with different pinned key (MITM scenario)
        AdminKeyManager.clearFallbackKey()
        val attackerKeyManager = AdminKeyManager()
        val attackerKeyBase64 = attackerKeyManager.getPublicKeyBase64()
        val mitmCheck = keyManager.verifyServerResponse(
            envelope = signedByServer,
            serverPublicKeyBase64 = serverPublicKeyBase64,
            pinnedServerPublicKeyBase64 = attackerKeyBase64
        )
        assertFalse("Mismatched pinned key must be rejected", mitmCheck)
    }
}
