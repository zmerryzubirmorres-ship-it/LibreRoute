package io.github.libreroute.admin

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class JazzBindingTest {
    @Test fun bindingIsBoundToDeviceServerAndSupportedLane() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
        val publicKey = Base64.getEncoder().encodeToString(key)
        val binding = PairChannelBinding(2, "a".repeat(32), 1, "lease", "resource", "room", 1, 1, 1, 1, "server", "device", fingerprint, publicKey)
        binding.validate("server", fingerprint, publicKey)
        assertThrows(IllegalArgumentException::class.java) { binding.validate("other-server", fingerprint, publicKey) }
        assertThrows(IllegalArgumentException::class.java) { binding.validate("server", "b".repeat(64), publicKey) }
        assertThrows(IllegalArgumentException::class.java) { binding.copy(lane_id=2).validate("server", fingerprint, publicKey) }
        assertThrows(IllegalArgumentException::class.java) { binding.copy(schema=1).validate("server", fingerprint, publicKey) }
    }
}
