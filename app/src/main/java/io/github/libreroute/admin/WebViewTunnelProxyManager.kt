package io.github.libreroute.admin

import android.content.Context
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import io.github.libreroute.util.Logx
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Serializable
internal data class AdminSshProxyRequest(
    val host: String,
    val port: Int,
    val user: String,
    val host_public_key: String,
    val auth: String,
    val private_key_path: String? = null,
    val passphrase: String? = null,
    val password: String? = null
)

/**
 * Manages an isolated SSH-tunnel HTTP CONNECT loopback proxy for Android WebView.
 * Enforces strict fail-closed routing through the selected target server node (e.g. VDS89)
 * with no direct internet breakout.
 */
class WebViewTunnelProxyManager(
    private val context: Context,
    private val proxyExecutor: Executor = Executors.newSingleThreadExecutor()
) {
    companion object {
        private const val TAG = "WebViewTunnelProxy"
        private const val TIMEOUT_SECONDS = 15L
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }

    @Volatile
    private var proxyProcess: Process? = null

    @Volatile
    private var stdinWriter: java.io.BufferedWriter? = null

    @Volatile
    private var activeProxyPort: Int? = null

    fun isProxyOverrideSupported(): Boolean {
        return WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)
    }

    /**
     * Starts the native ssh-proxy daemon connected to [serverNode] and configures WebView's ProxyController.
     * Guaranteed fail-closed: if proxy override is unsupported or fails, onReady is NEVER invoked.
     */
    fun startProxy(
        serverNode: ServerNode,
        onReady: (port: Int) -> Unit,
        onError: (error: String) -> Unit,
        onTerminated: ((exitCode: Int) -> Unit)? = null
    ) {
        if (!isProxyOverrideSupported()) {
            onError("WebViewFeature.PROXY_OVERRIDE не поддерживается на данном устройстве. Прямой выход без туннеля запрещён политикой безопасности.")
            return
        }

        val hostConfig = serverNode.toSshHostConfig()
        if (hostConfig.hostPublicKey.isNullOrBlank()) {
            onError("Для туннелирования требуется верифицированный SSH host public key")
            return
        }
        if (hostConfig.authType == SshAuthType.KEY && hostConfig.privateKeyPath.isNullOrBlank()) {
            onError("Требуется закрытый SSH-ключ для узла ${serverNode.name}")
            return
        }

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val binaryPath = "$nativeLibDir/liblibreroute_client.so"
        val binaryFile = File(binaryPath)
        if (!binaryFile.exists() || !binaryFile.canExecute()) {
            onError("Нативный модуль $binaryPath не найден или не исполняем")
            return
        }

        val reqJson = json.encodeToString(
            AdminSshProxyRequest(
                host = hostConfig.host,
                port = hostConfig.port,
                user = hostConfig.username,
                host_public_key = hostConfig.hostPublicKey,
                auth = if (hostConfig.authType == SshAuthType.KEY) "key" else "password",
                private_key_path = hostConfig.privateKeyPath,
                passphrase = hostConfig.passphrase,
                password = hostConfig.password
            )
        )

        proxyExecutor.execute {
            try {
                stopProxyInternal()

                val cmd = listOf(binaryPath, "admin", "ssh-proxy")
                val pb = ProcessBuilder(cmd)
                    .directory(context.filesDir)
                    .redirectErrorStream(false)

                val proc = pb.start()
                proxyProcess = proc

                // Send config via stdin and KEEP stdin open for process lifetime!
                val writer = proc.outputStream.bufferedWriter(Charsets.UTF_8)
                writer.write(reqJson)
                writer.newLine()
                writer.flush()
                stdinWriter = writer

                // Read port notification from stdout
                val reader = BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8))
                val firstLine = reader.readLine()
                if (firstLine.isNullOrBlank()) {
                    val errReader = BufferedReader(InputStreamReader(proc.errorStream, Charsets.UTF_8))
                    val stderr = errReader.readText()
                    onError("Не удалось запустить ssh-proxy: $stderr")
                    stopProxyInternal()
                    return@execute
                }

                val parsed = json.parseToJsonElement(firstLine).jsonObject
                val port = parsed["proxy_port"]?.jsonPrimitive?.content?.toIntOrNull()
                if (port == null || port <= 0) {
                    onError("Некорректный порт ssh-proxy: $firstLine")
                    stopProxyInternal()
                    return@execute
                }

                // Verify loopback connectivity to proxy before advertising readiness
                try {
                    java.net.Socket().use { s ->
                        s.connect(java.net.InetSocketAddress("127.0.0.1", port), 1000)
                    }
                } catch (e: Exception) {
                    onError("Прокси на 127.0.0.1:$port не отвечает на подключение: ${e.message}")
                    stopProxyInternal()
                    return@execute
                }

                activeProxyPort = port
                Logx.i(TAG, "ssh-proxy started and verified on 127.0.0.1:$port via ${hostConfig.host}")

                // Watchdog thread to detect unexpected termination
                Thread({
                    val exitVal = try { proc.waitFor() } catch (_: InterruptedException) { -1 }
                    if (activeProxyPort != null) {
                        Logx.w(TAG, "ssh-proxy process exited unexpectedly with code $exitVal")
                        activeProxyPort = null
                        onTerminated?.invoke(exitVal)
                    }
                }, "ssh-proxy-watchdog").start()

                // Apply proxy override to WebView
                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("http://127.0.0.1:$port")
                    .removeImplicitRules()
                    .build()

                ProxyController.getInstance().setProxyOverride(
                    proxyConfig,
                    proxyExecutor,
                    Runnable {
                        Logx.i(TAG, "WebView proxy override successfully set to 127.0.0.1:$port")
                        if (activeProxyPort == port && proc.isAlive) onReady(port)
                    }
                )

            } catch (e: Exception) {
                Logx.e(TAG, "Failed to start WebView tunnel proxy: ${e.message}", e)
                onError("Ошибка запуска прокси: ${e.message}")
                stopProxyInternal()
            }
        }
    }

    /**
     * Stops the proxy process and clears WebView proxy settings.
     */
    fun stopProxy(onComplete: (() -> Unit)? = null) {
        proxyExecutor.execute {
            stopProxyInternal()
            onComplete?.invoke()
        }
    }

    private fun stopProxyInternal() {
        val writer = stdinWriter
        if (writer != null) {
            try {
                writer.write("q\n")
                writer.flush()
                writer.close()
            } catch (_: Exception) {}
            stdinWriter = null
        }

        val proc = proxyProcess
        if (proc != null) {
            try {
                proc.destroyForcibly()
                proc.waitFor(2, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Logx.w(TAG, "Error stopping ssh-proxy process: ${e.message}")
            }
            proxyProcess = null
        }
        activeProxyPort = null

        if (isProxyOverrideSupported()) {
            try {
                ProxyController.getInstance().clearProxyOverride(proxyExecutor, Runnable {
                    Logx.i(TAG, "WebView proxy override cleared")
                })
            } catch (e: Exception) {
                Logx.w(TAG, "Error clearing WebView proxy: ${e.message}")
            }
        }
    }
}
