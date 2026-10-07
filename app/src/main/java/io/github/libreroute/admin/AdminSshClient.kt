package io.github.libreroute.admin

import android.content.Context
import io.github.libreroute.util.Logx
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class AdminSSHRequest(
    val host: String,
    val port: Int,
    val user: String,
    val host_public_key: String,
    val auth: String,
    val private_key_path: String? = null,
    val passphrase: String? = null,
    val remote_namespace: String? = null,
    val password: String? = null,
    val envelope: AdminEnvelope
)

@Serializable
internal data class AdminSSHProbeRequest(
    val host: String,
    val port: Int,
    val user: String,
    val host_public_key: String,
    val auth: String,
    val private_key_path: String? = null,
    val passphrase: String? = null,
    val remote_namespace: String? = null,
    val password: String? = null
)

/**
 * Fallback SSH client for administrative control plane.
 * Enforces host policies:
 * - Netcraze: accessible strictly from home LAN (10.10.1.1:222) when device IP is in 10.10.1.0/24.
 * - VDS: key authentication by default (password allowed only if explicitly enabled by host policy).
 * - Idempotency: preserves identical OpID from the administrative envelope.
 */
class AdminSshClient(
    private val context: Context,
    private val networkChecker: (() -> Boolean)? = null,
    private val commandExecutor: ((List<String>, String) -> Pair<Int, String>)? = null
) {

    companion object {
        private const val TAG = "AdminSshClient"
        const val NETCRAZE_DEFAULT_HOST = "10.10.1.1"
        const val NETCRAZE_DEFAULT_PORT = 222
        const val TIMEOUT_SECONDS = 240L
        private const val PROBE_TIMEOUT_SECONDS = 30L
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    /**
     * Checks whether an IPv4 address belongs to Netcraze subnet 10.10.1.0/24.
     */
    fun isNetcrazeSubnetIp(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        val p0 = parts[0]
        val p1 = parts[1]
        val p2 = parts[2]
        val p3 = parts[3].toIntOrNull() ?: return false
        return p0 == "10" && p1 == "10" && p2 == "1" && p3 in 1..254
    }

    /**
     * Checks if the device has an active Wi-Fi / Ethernet interface in 10.10.1.0/24.
     */
    fun isDeviceInNetcrazeLan(): Boolean {
        if (networkChecker != null) {
            return networkChecker.invoke()
        }
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            for (iface in interfaces) {
                if (iface.isLoopback || !iface.isUp) continue
                val name = iface.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("pdp") ||
                    name.startsWith("tun") || name.startsWith("ppp")) {
                    continue
                }
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (isNetcrazeSubnetIp(host)) return true
                    }
                }
            }
            false
        } catch (e: Exception) {
            Logx.w(TAG, "Failed to inspect network interfaces: ${e.message}")
            false
        }
    }

    /**
     * Safely probes a ServerNode over SSH to collect hardware/OS inventory and compatibility.
     */
    fun probeServer(serverNode: ServerNode): ServerInventory =
        probeServer(serverNode.toSshHostConfig())

    /**
     * Probes target host over SSH with pinned host key.
     */
    fun probeServer(hostConfig: SshHostConfig): ServerInventory {
        val isNetcraze = hostConfig.host == NETCRAZE_DEFAULT_HOST
        if (isNetcraze && !isDeviceInNetcrazeLan()) {
            return ServerInventory(
                probeError = "Netcraze доступен строго из домашней LAN (10.10.1.0/24)",
                probedAt = System.currentTimeMillis()
            )
        }

        if (hostConfig.authType == SshAuthType.PASSWORD && !hostConfig.allowPasswordAuth) {
            return ServerInventory(
                probeError = "Политика VDS запрещает аутентификацию по паролю",
                probedAt = System.currentTimeMillis()
            )
        }

        if (hostConfig.hostPublicKey.isNullOrBlank()) {
            return ServerInventory(
                probeError = "A verified SSH host public key is required",
                probedAt = System.currentTimeMillis()
            )
        }
        if (hostConfig.authType == SshAuthType.KEY && hostConfig.privateKeyPath.isNullOrBlank()) {
            return ServerInventory(
                probeError = "SSH private key file is required",
                probedAt = System.currentTimeMillis()
            )
        }
        if (hostConfig.authType == SshAuthType.PASSWORD && hostConfig.password.isNullOrBlank()) {
            return ServerInventory(
                probeError = "SSH password is required",
                probedAt = System.currentTimeMillis()
            )
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binaryPath = "$nativeLibDir/liblibreroute_client.so"

        val cmd = listOf(
            binaryPath,
            "admin",
            "ssh-probe"
        )
        val request = json.encodeToString(AdminSSHProbeRequest(
            host = hostConfig.host,
            port = hostConfig.port,
            user = hostConfig.username,
            host_public_key = hostConfig.hostPublicKey,
            auth = if (hostConfig.authType == SshAuthType.KEY) "key" else "password",
            private_key_path = hostConfig.privateKeyPath,
            passphrase = hostConfig.passphrase,
            remote_namespace = hostConfig.managementNamespace,
            password = hostConfig.password
        ))

        Logx.i(TAG, "Probing host via SSH: target=${hostConfig.host}:${hostConfig.port}")

        return try {
            val (exitCode, output) = if (commandExecutor != null) {
                commandExecutor.invoke(cmd, request)
            } else {
                  runNativeProcess(cmd, request, PROBE_TIMEOUT_SECONDS)
            }

            if (exitCode == 0) {
                json.decodeFromString<ServerInventory>(output)
            } else {
                ServerInventory(
                    probeError = "SSH probe failed (exit code $exitCode): $output",
                    probedAt = System.currentTimeMillis()
                )
            }
        } catch (e: Exception) {
            Logx.e(TAG, "SSH probe exception: ${e.message}")
            ServerInventory(
                probeError = "SSH probe failed: ${e.message}",
                probedAt = System.currentTimeMillis()
            )
        }
    }

    /**
     * Executes an administrative envelope directly to a target ServerNode via SSH.
     */
    fun executeAdminEnvelope(
        envelope: AdminEnvelope,
        serverNode: ServerNode
    ): AdminOpResult = executeAdminEnvelope(envelope, serverNode.toSshHostConfig(), AdminChannel.DIRECT_SSH)

    /**
     * Executes an administrative envelope via SSH.
     * Preserves envelope.opId for command idempotency.
     */
    fun executeAdminEnvelope(
        envelope: AdminEnvelope,
        hostConfig: SshHostConfig,
        channel: AdminChannel
    ): AdminOpResult {
        // 1. Host Policy Verification
        val isNetcraze = channel == AdminChannel.SSH_NETCRAZE ||
                hostConfig.host == NETCRAZE_DEFAULT_HOST

        if (isNetcraze) {
            if (!isDeviceInNetcrazeLan()) {
                val errorMsg = "Netcraze доступен строго из домашней LAN (10.10.1.0/24). Подключитесь к домашней сети Wi-Fi."
                Logx.w(TAG, errorMsg)
                return AdminOpResult.Failure(errorMsg)
            }
        }

        if (channel == AdminChannel.SSH_VDS || channel == AdminChannel.DIRECT_SSH) {
            if (hostConfig.authType == SshAuthType.PASSWORD && !hostConfig.allowPasswordAuth) {
                val errorMsg = "Политика VDS запрещает аутентификацию по паролю (действующие VDS принимают только SSH-ключи)."
                Logx.w(TAG, errorMsg)
                return AdminOpResult.Failure(errorMsg)
            }
        }

        if (hostConfig.hostPublicKey.isNullOrBlank()) {
            return AdminOpResult.Failure("A verified SSH host public key is required")
        }
        if (hostConfig.authType == SshAuthType.KEY && hostConfig.privateKeyPath.isNullOrBlank()) {
            return AdminOpResult.Failure("SSH private key file is required")
        }
        if (hostConfig.authType == SshAuthType.PASSWORD && hostConfig.password.isNullOrBlank()) {
            return AdminOpResult.Failure("SSH password is required")
        }

        // 2. Send credentials and envelope through stdin, never through argv.
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binaryPath = "$nativeLibDir/liblibreroute_client.so"

        val cmd = listOf(
            binaryPath,
            "admin",
            "ssh-exec"
        )
        val request = json.encodeToString(AdminSSHRequest(
            host = hostConfig.host,
            port = hostConfig.port,
            user = hostConfig.username,
            host_public_key = hostConfig.hostPublicKey,
            auth = if (hostConfig.authType == SshAuthType.KEY) "key" else "password",
            private_key_path = hostConfig.privateKeyPath,
            passphrase = hostConfig.passphrase,
            remote_namespace = hostConfig.managementNamespace,
            password = hostConfig.password,
            envelope = envelope
        ))

        Logx.i(TAG, "Executing SSH admin command opId=${envelope.opId} target=${hostConfig.host}:${hostConfig.port}")

        // 3. Execution (via custom executor or ProcessBuilder)
        return try {
            val (exitCode, output) = if (commandExecutor != null) {
                commandExecutor.invoke(cmd, request)
            } else {
                runNativeProcess(cmd, request)
            }

            if (exitCode == 0) {
                Logx.i(TAG, "SSH command returned for opId=${envelope.opId}")
                val responseEnvelope = AdminEnvelope.fromJson(output)
                    ?: return AdminOpResult.Failure("SSH did not return a valid admin envelope")
                AdminOpResult.Success(
                    message = "Команда успешно выполнена через SSH (${hostConfig.host})",
                    envelope = responseEnvelope
                )
            } else {
                Logx.e(TAG, "SSH command failed with exit code $exitCode")
                val message = if (exitCode == -1) {
                    "Сервер не ответил за ${TIMEOUT_SECONDS} с. Результат установки пока неизвестен; повторите проверку."
                } else "SSH-команда не завершена (код $exitCode). Повторите проверку результата на сервере."
                AdminOpResult.Failure(message, UnknownAdminOperation(message))
            }
        } catch (e: Exception) {
            Logx.e(TAG, "SSH execution exception for opId=${envelope.opId}: ${e.javaClass.simpleName}")
            AdminOpResult.Failure("SSH connection failed", e)
        }
    }

    private fun runNativeProcess(cmd: List<String>, request: String, timeoutSeconds: Long = TIMEOUT_SECONDS): Pair<Int, String> {
        val binaryFile = File(cmd.first())
        if (!binaryFile.exists() || !binaryFile.canExecute()) {
            return Pair(1, "Нативное ядро liblibreroute_client.so не найдено или недоступно для исполнения")
        }

        return NativeAdminProcess.run(cmd, context.filesDir, request, timeoutSeconds)
    }
}
