package io.github.libreroute.service

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/** Runs the bundled zapret tpws in its supported no-root SOCKS mode. */
class TpwsEngine(private val context: Context) {
    private var process: Process? = null
    private var drainThread: Thread? = null
    var port: Int = 0
        private set

    fun start(): Int {
        stop()
        val binary = File(context.applicationInfo.nativeLibraryDir, "libtpws.so")
        require(binary.isFile && binary.canExecute()) { "tpws binary is unavailable" }
        port = ServerSocket(0, 16, InetAddress.getLoopbackAddress()).use { it.localPort }
        val command = command(binary.absolutePath, port)
        try {
            val p = ProcessBuilder(command).redirectErrorStream(true).start()
            process = p
            val cancelled = AtomicBoolean(false)
            drainThread = Thread {
                runCatching {
                    p.inputStream.bufferedReader().use { reader ->
                        while (!cancelled.get()) {
                            val line = reader.readLine() ?: break
                            val safeLine = line.take(240)
                            Log.w("TpwsEngine", safeLine)
                            io.github.libreroute.util.Logx.w("TpwsEngine", safeLine)
                        }
                    }
                }
            }.apply { name = "TpwsOutput"; isDaemon = true; start() }
            check(waitForSocks(port, p)) { "tpws SOCKS endpoint did not become ready" }
            return port
        } catch (e: Exception) {
            stop()
            throw e
        }
    }

    fun isAlive(): Boolean = process?.isAlive == true

    fun stop() {
        process?.let { p ->
            runCatching { p.destroy() }
            if (p.isAlive) runCatching { p.destroyForcibly() }
        }
        process = null
        drainThread?.interrupt()
        drainThread = null
        port = 0
    }

    internal fun command(binary: String, port: Int): List<String> = listOf(
        binary, "--socks", "--bind-addr=127.0.0.1", "--port=$port",
        "--maxconn=1024", "--filter-tcp=80,443", "--split-pos=1,midsld",
        "--tlsrec=sni", "--methodeol"
    )

    private fun waitForSocks(port: Int, p: Process): Boolean {
        repeat(100) {
            if (!p.isAlive) return false
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 100)
                    // A successful loopback connect is enough here. The native
                    // core performs and validates the SOCKS5 handshake itself.
                    return true
                }
            } catch (_: Exception) { Thread.sleep(50) }
        }
        return false
    }
}
