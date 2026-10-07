package io.github.libreroute.admin

import android.content.Context
import io.github.libreroute.util.Logx
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit

@Serializable
internal data class GenSshKeyResult(
    val private_key_pem: String = "",
    val public_key_openssh: String = "",
    val fingerprint: String = "",
    val key_type: String = "ed25519"
)

/**
 * Manages SSH keys used for administrative connection to servers (VDS, Keenetic, etc.).
 * Supports:
 * - Native OpenSSH Ed25519 generation via liblibreroute_client.so
 * - Pure JVM RSA-2048 fallback if native binary is unavailable
 * - Manual import of OpenSSH / PEM private keys
 * - OpenSSH public key extraction for ~/.ssh/authorized_keys
 */
class AdminSshKeyManager(
    private val context: Context? = null,
    private val commandExecutor: ((List<String>) -> Pair<Int, String>)? = null
) {
    companion object {
        private const val TAG = "AdminSshKeyManager"
        const val KEY_FILE_NAME = "libreroute_admin_key"
        const val PUBKEY_FILE_NAME = "libreroute_admin_key.pub"
        private val json = Json { ignoreUnknownKeys = true }
    }

    fun getAdminSshKeyFile(): File? {
        val filesDir = context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        return File(filesDir, KEY_FILE_NAME)
    }

    fun hasAdminSshKey(): Boolean {
        val file = getAdminSshKeyFile() ?: return false
        return file.exists() && file.length() > 0
    }

    fun getEffectiveAdminSshKeyPath(): String? {
        val file = getAdminSshKeyFile() ?: return null
        return if (file.exists() && file.length() > 0) file.absolutePath else null
    }

    fun getAdminSshPublicKey(): String? {
        val filesDir = context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val pubFile = File(filesDir, PUBKEY_FILE_NAME)
        if (pubFile.exists() && pubFile.length() > 0) {
            return pubFile.readText().trim()
        }
        return null
    }

    /**
     * Generates a new SSH key pair.
     * Primary method: runs liblibreroute_client.so admin gen-ssh-key (OpenSSH Ed25519).
     * Fallback method: generates standard PKCS#8 RSA key.
     * Saves private key to filesDir/libreroute_admin_key (0600) and public key to filesDir/libreroute_admin_key.pub.
     * Returns Pair(privateKeyPath, publicKeyOpenSsh).
     */
    fun generateSshKeyPair(): Pair<String, String> {
        val filesDir = context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val privFile = File(filesDir, KEY_FILE_NAME)
        val pubFile = File(filesDir, PUBKEY_FILE_NAME)

        // 1. Try native OpenSSH Ed25519 generation via Go core
        val nativeLibDir = context?.applicationInfo?.nativeLibraryDir
        val binaryPath = if (nativeLibDir != null) "$nativeLibDir/liblibreroute_client.so" else null

        if ((binaryPath != null && File(binaryPath).exists()) || commandExecutor != null) {
            val cmd = listOf(binaryPath ?: "libreroute", "admin", "gen-ssh-key")
            try {
                val (code, output) = commandExecutor?.invoke(cmd) ?: runNativeGenKey(cmd)
                if (code == 0 && output.isNotBlank()) {
                    val result = json.decodeFromString<GenSshKeyResult>(output)
                    if (result.private_key_pem.isNotBlank() && result.public_key_openssh.isNotBlank()) {
                        privFile.writeText(result.private_key_pem)
                        privFile.setReadable(false, false)
                        privFile.setReadable(true, true) // owner only
                        privFile.setWritable(false, false)
                        privFile.setWritable(true, true) // owner only

                        val fullPub = "${result.public_key_openssh} libreroute-admin\n"
                        pubFile.writeText(fullPub)

                        Logx.i(TAG, "Generated native OpenSSH Ed25519 key (fingerprint: ${result.fingerprint})")
                        return Pair(privFile.absolutePath, result.public_key_openssh)
                    }
                }
            } catch (e: Exception) {
                Logx.w(TAG, "Native gen-ssh-key failed, falling back to JVM RSA generator: ${e.message}")
            }
        }

        // 2. Pure JVM RSA 2048 fallback (standard PKCS#8 PEM accepted by Go ssh.ParsePrivateKey)
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val privBase64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded)
        val privPem = "-----BEGIN PRIVATE KEY-----\n$privBase64\n-----END PRIVATE KEY-----\n"

        privFile.writeText(privPem)
        privFile.setReadable(false, false)
        privFile.setReadable(true, true)
        privFile.setWritable(false, false)
        privFile.setWritable(true, true)

        val pubOpenSsh = encodeOpenSshRsaPublicKey(kp.public as RSAPublicKey) + " libreroute-admin"
        pubFile.writeText(pubOpenSsh + "\n")

        Logx.i(TAG, "Generated JVM RSA 2048 key")
        return Pair(privFile.absolutePath, pubOpenSsh)
    }

    /**
     * Imports an existing SSH private key in PEM / OpenSSH format.
     */
    fun importSshPrivateKey(pemContent: String, passphrase: String? = null): Boolean {
        val trimmed = pemContent.trim()
        if (!trimmed.contains("BEGIN") || !trimmed.contains("PRIVATE KEY")) {
            return false
        }
        val filesDir = context?.filesDir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        val privFile = File(filesDir, KEY_FILE_NAME)
        privFile.writeText(trimmed + "\n")
        privFile.setReadable(false, false)
        privFile.setReadable(true, true)
        privFile.setWritable(false, false)
        privFile.setWritable(true, true)

        val pubFile = File(filesDir, PUBKEY_FILE_NAME)
        // Try extracting public key if it's a standard PKCS#8 RSA key
        try {
            val clean = trimmed.lines()
                .filterNot { it.startsWith("-----") }
                .joinToString("")
            val keyBytes = Base64.getDecoder().decode(clean)
            val kf = KeyFactory.getInstance("RSA")
            val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            if (privKey is RSAPrivateCrtKey) {
                val pubSpec = RSAPublicKeySpec(privKey.modulus, privKey.publicExponent)
                val pubKey = kf.generatePublic(pubSpec) as RSAPublicKey
                val pubOpenSsh = encodeOpenSshRsaPublicKey(pubKey) + " imported-admin\n"
                pubFile.writeText(pubOpenSsh)
            } else {
                if (pubFile.exists()) pubFile.delete()
            }
        } catch (e: Exception) {
            if (pubFile.exists()) pubFile.delete()
        }
        return true
    }

    private fun encodeOpenSshRsaPublicKey(publicKey: RSAPublicKey): String {
        val byteStream = ByteArrayOutputStream()
        val dos = DataOutputStream(byteStream)
        val type = "ssh-rsa".toByteArray(Charsets.US_ASCII)
        dos.writeInt(type.size)
        dos.write(type)
        val e = publicKey.publicExponent.toByteArray()
        dos.writeInt(e.size)
        dos.write(e)
        val n = publicKey.modulus.toByteArray()
        dos.writeInt(n.size)
        dos.write(n)
        val b64 = Base64.getEncoder().encodeToString(byteStream.toByteArray())
        return "ssh-rsa $b64"
    }

    private fun runNativeGenKey(cmd: List<String>): Pair<Int, String> {
        val pb = ProcessBuilder(cmd)
            .directory(context?.filesDir)
            .redirectErrorStream(true)
        val proc = pb.start()
        val finished = proc.waitFor(10, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return Pair(-1, "Timeout generating SSH key")
        }
        val stdout = proc.inputStream.bufferedReader().use { it.readText() }
        return Pair(proc.exitValue(), stdout.trim())
    }
}
