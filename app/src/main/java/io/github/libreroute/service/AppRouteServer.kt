package io.github.libreroute.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.Build
import android.os.Process
import io.github.libreroute.data.AppRoutingState
import io.github.libreroute.data.appRulesByUid
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Per-flow UID lookup. This socket and its immutable rules belong to one Core launch. */
class AppRouteServer(context: Context, state: AppRoutingState) : AutoCloseable {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val rules = appRulesByUid(state, state.rules.mapNotNull { rule ->
        runCatching { rule.packageName to context.packageManager.getApplicationInfo(rule.packageName, 0).uid }.getOrNull()
    }.toMap())
    private val closed = AtomicBoolean(false)
    private val sockets = ConcurrentHashMap.newKeySet<LocalSocket>()
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val workers = ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS, ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "AppRouteLookup").apply { isDaemon = true } })
    private val name = "libreroute_app_${UUID.randomUUID()}"
    val address = "@$name"
    private val server: LocalServerSocket

    init {
        require(Build.VERSION.SDK_INT >= 29) { "Правила приложений требуют Android 10 или новее" }
        server = LocalServerSocket(name)
        Thread({
            while (!closed.get()) {
                val socket = try { server.accept() } catch (_: Exception) { break }
                sockets.add(socket)
                try { workers.execute { handle(socket) } }
                catch (_: Exception) { sockets.remove(socket); runCatching { socket.close() } }
            }
        }, "AppRouteAccept").apply { isDaemon = true; start() }
    }

    private fun handle(socket: LocalSocket) {
        try {
            socket.soTimeout = 1000
            if (socket.peerCredentials.uid != Process.myUid()) return
            val bytes = java.io.ByteArrayOutputStream()
            while (bytes.size() <= 1024) {
                val value = socket.inputStream.read()
                if (value < 0) return
                if (value == 10) break
                bytes.write(value)
            }
            if (bytes.size() > 1024) return
            val query = JSONObject(bytes.toString("UTF-8"))
            val protocol = query.getInt("protocol")
            require(protocol == 6 || protocol == 17)
            // Only flows originating in our IPv4 VPN may be queried.
            require(query.getString("src_ip") == "26.26.26.1")
            val destination = query.getString("dst_ip")
            require(destination.length <= 15 && Regex("[0-9.]+").matches(destination))
            val srcPort = query.getInt("src_port"); val dstPort = query.getInt("dst_port")
            require(srcPort in 1..65535 && dstPort in 1..65535)
            val uid = manager.getConnectionOwnerUid(protocol,
                InetSocketAddress(InetAddress.getByName("26.26.26.1"), srcPort),
                InetSocketAddress(InetAddress.getByName(destination), dstPort))
            val rule = rules[uid]
            // Unknown ownership stays in the tunnel, rather than using a site bypass.
            val response = JSONObject().put("action", if (uid < 0) "tunnel" else rule?.action ?: "")
                .put("profile", rule?.profile ?: "")
            val decision = if (uid < 0) "unknown-owner:tunnel" else rule?.action ?: "site-rules"
            // Log each decision category only once per launch, without app/host data.
            if (reported.add(decision)) io.github.libreroute.util.Logx.i("AppRouting", "[APP_ROUTE] $decision")
            socket.outputStream.write((response.toString() + "\n").toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
        } catch (_: Exception) {
            // EOF/timeout is handled by Core as a tunnel-only decision.
            if (reported.add("lookup-error")) io.github.libreroute.util.Logx.w("AppRouting", "[APP_ROUTE] lookup unavailable; tunnel")
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        sockets.forEach { runCatching { it.close() } }
        workers.shutdownNow()
    }
}
