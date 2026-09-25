package io.github.p1neapplexpress.openflux.service

import io.github.p1neapplexpress.openflux.util.Logx
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Local TCP bridge for LAN/Hotspot Proxy Sharing.
 * Listens on 0.0.0.0:lanPort, handles RFC 1929 SOCKS5 user/pass authentication if enabled,
 * and transparently forwards traffic to the internal 127.0.0.1:localSocksPort.
 */
object HotspotProxyBridge {

    private const val TAG = "HotspotProxyBridge"
    private val isRunning = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService? = null
    private var acceptThread: Thread? = null
    private val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()

    @Synchronized
    fun start(
        lanPort: Int,
        targetLocalPort: Int,
        authEnabled: Boolean = false,
        username: String = "",
        password: String = ""
    ) {
        if (isRunning.get()) {
            Logx.d(TAG, "Bridge already running, stopping existing instance first")
            stop()
        }
        isRunning.set(true)

        try {
            val sSocket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress(InetAddress.getByName("0.0.0.0"), lanPort), 50)
            }
            serverSocket = sSocket
            val pool = ThreadPoolExecutor(
                4,
                32,
                60L,
                TimeUnit.SECONDS,
                SynchronousQueue(),
                { r -> Thread(r, "HotspotBridgeWorker").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy()
            )
            executor = pool

            Logx.i(TAG, "Hotspot proxy bridge started on 0.0.0.0:$lanPort -> 127.0.0.1:$targetLocalPort (auth=$authEnabled)")

            acceptThread = Thread({
                while (isRunning.get() && !sSocket.isClosed) {
                    try {
                        val client = sSocket.accept()
                        activeSockets.add(client)
                        if (!isAllowedClient(client.inetAddress)) {
                            Logx.w(TAG, "Rejecting unauthorized client from ${client.inetAddress}")
                            activeSockets.remove(client)
                            runCatching { client.close() }
                            continue
                        }
                        client.soTimeout = 30_000
                        try {
                            pool.execute {
                                try {
                                    handleClient(client, targetLocalPort, authEnabled, username, password)
                                } finally {
                                    activeSockets.remove(client)
                                    runCatching { client.close() }
                                }
                            }
                        } catch (re: RejectedExecutionException) {
                            Logx.w(TAG, "Hotspot worker pool saturated (32 active); rejecting client")
                            activeSockets.remove(client)
                            runCatching { client.close() }
                        }
                    } catch (e: Exception) {
                        if (!sSocket.isClosed) {
                            Logx.w(TAG, "Accept error: ${e.message}")
                        }
                    }
                }
            }, "HotspotBridge-Accept").apply {
                isDaemon = true
                start()
            }
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to start hotspot bridge on port $lanPort", e)
            isRunning.set(false)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        Logx.i(TAG, "Stopping hotspot proxy bridge")
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
        runCatching { executor?.shutdownNow() }
        executor = null
        activeSockets.forEach { sock ->
            runCatching { sock.close() }
        }
        activeSockets.clear()
    }

    private fun isAllowedClient(clientAddress: InetAddress): Boolean {
        var host = clientAddress.hostAddress ?: return false
        // Allow loopback (health checks, local tools)
        if (host == "127.0.0.1" || host == "::1") return true
        if (host.startsWith("::ffff:")) {
            host = host.removePrefix("::ffff:")
        }
        // RFC-1918 private ranges: 10/8, 172.16/12, 192.168/16
        // Covers all Android hotspot subnets (Samsung, Xiaomi, Huawei, AOSP, etc.)
        val parts = host.split(".")
        if (parts.size == 4) {
            val a = parts[0].toIntOrNull() ?: return false
            val b = parts[1].toIntOrNull() ?: return false
            if (a == 10) return true                       // 10.0.0.0/8
            if (a == 172 && b in 16..31) return true      // 172.16.0.0/12
            if (a == 192 && b == 168) return true          // 192.168.0.0/16
        }
        return false
    }

    val running: Boolean get() = isRunning.get()

    private fun handleClient(
        clientSocket: Socket,
        targetLocalPort: Int,
        authEnabled: Boolean,
        expectedUser: String,
        expectedPass: String
    ) {
        try {
            clientSocket.soTimeout = 30_000
            val cin = clientSocket.getInputStream()
            val cout = clientSocket.getOutputStream()

            val firstByte = cin.read()
            if (firstByte < 0) {
                clientSocket.close()
                return
            }

            if (firstByte == 0x05) {
                // SOCKS5 protocol
                handleSocks5(firstByte, cin, cout, clientSocket, targetLocalPort, authEnabled, expectedUser, expectedPass)
            } else {
                // HTTP / HTTPS (CONNECT) proxy protocol (e.g. Windows proxy, curl, browsers)
                handleHttp(firstByte, cin, cout, clientSocket, targetLocalPort, authEnabled, expectedUser, expectedPass)
            }
        } catch (_: Exception) {
            runCatching { clientSocket.close() }
        }
    }

    private fun handleSocks5(
        firstByte: Int,
        cin: InputStream,
        cout: OutputStream,
        clientSocket: Socket,
        targetLocalPort: Int,
        authEnabled: Boolean,
        expectedUser: String,
        expectedPass: String
    ) {
        val nMethods = cin.read()
        if (nMethods <= 0) {
            clientSocket.close()
            return
        }
        val methods = ByteArray(nMethods)
        cin.readFully(methods)

        val useAuth = authEnabled && expectedUser.isNotEmpty() && expectedPass.isNotEmpty()
        if (useAuth) {
            if (!methods.contains(0x02.toByte())) {
                cout.write(byteArrayOf(0x05, 0xFF.toByte()))
                cout.flush()
                clientSocket.close()
                return
            }
            cout.write(byteArrayOf(0x05, 0x02))
            cout.flush()

            // RFC 1929 username/password auth
            val authVer = cin.read()
            if (authVer != 0x01) { clientSocket.close(); return }
            val uLen = cin.read()
            if (uLen <= 0) { clientSocket.close(); return }
            val uBytes = ByteArray(uLen)
            cin.readFully(uBytes)
            val user = String(uBytes, Charsets.UTF_8)

            val pLen = cin.read()
            if (pLen < 0) { clientSocket.close(); return }
            val pBytes = ByteArray(pLen)
            if (pLen > 0) cin.readFully(pBytes)
            val pass = String(pBytes, Charsets.UTF_8)

            if (user != expectedUser || pass != expectedPass) {
                Logx.w(TAG, "SOCKS5 Auth failed from ${clientSocket.inetAddress.hostAddress}")
                cout.write(byteArrayOf(0x01, 0x01)) // RFC 1929 auth failure
                cout.flush()
                clientSocket.close()
                return
            }
            cout.write(byteArrayOf(0x01, 0x00)) // RFC 1929 auth success
            cout.flush()
        } else {
            cout.write(byteArrayOf(0x05, 0x00)) // no auth
            cout.flush()
        }

        // ── Read SOCKS5 CONNECT request (RFC 1928 §6) ────────────────────────
        val ver = cin.read()
        val cmd = cin.read()
        cin.read() // RSV (reserved, must be 0x00)
        val atyp = cin.read()

        if (ver != 0x05) { clientSocket.close(); return }

        // RFC 1928: only CONNECT (0x01) is supported here
        if (cmd != 0x01) {
            // 0x07 = Command not supported
            cout.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0,0,0,0, 0,0))
            cout.flush()
            clientSocket.close()
            return
        }

        // Parse destination address
        val destHost: String
        when (atyp) {
            0x01 -> { // IPv4
                val addrBytes = ByteArray(4)
                cin.readFully(addrBytes)
                destHost = addrBytes.joinToString(".") { (it.toInt() and 0xFF).toString() }
            }
            0x03 -> { // Domain name
                val len = cin.read()
                if (len <= 0) {
                    clientSocket.close()
                    return
                }
                val domainBytes = ByteArray(len)
                cin.readFully(domainBytes)
                destHost = String(domainBytes, Charsets.UTF_8)
            }
            0x04 -> { // IPv6 — not supported (FluxonCore SOCKS5 only accepts IPv4/domain)
                // RFC 1928: 0x08 = Address type not supported
                cout.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0,0,0,0, 0,0))
                cout.flush()
                Logx.w(TAG, "Rejected IPv6 SOCKS5 CONNECT from ${clientSocket.inetAddress.hostAddress}")
                clientSocket.close()
                return
            }
            else -> {
                cout.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0,0,0,0, 0,0))
                cout.flush()
                clientSocket.close()
                return
            }
        }
        val destPort = (cin.read() shl 8) or cin.read()

        // ── Forward to upstream SOCKS5 (FluxonCore internal listener) ────────
        val targetSocket = try {
            Socket().apply {
                soTimeout = 15000
                connect(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetLocalPort), 4000)
            }
        } catch (e: Exception) {
            Logx.w(TAG, "Cannot connect to upstream SOCKS5: ${e.message}")
            cout.write(byteArrayOf(0x05, 0x05, 0x00, 0x01, 0,0,0,0, 0,0)) // 0x05 = Connection refused
            cout.flush()
            clientSocket.close()
            return
        }
        activeSockets.add(targetSocket)

        val tin = targetSocket.getInputStream()
        val tout = targetSocket.getOutputStream()

        // Execute CONNECT on upstream SOCKS5
        val ok = socks5Connect(tin, tout, destHost, destPort)
        if (!ok) {
            activeSockets.remove(targetSocket)
            runCatching { targetSocket.close() }
            cout.write(byteArrayOf(0x05, 0x04, 0x00, 0x01, 0,0,0,0, 0,0)) // 0x04 = Host unreachable
            cout.flush()
            clientSocket.close()
            return
        }

        // Send success response to client
        // BND.ADDR = 0.0.0.0, BND.PORT = 0 (we are a relay, not the origin)
        cout.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0,0,0,0, 0,0))
        cout.flush()

        startPipe(cin, cout, tin, tout, clientSocket, targetSocket)
    }

    private fun handleHttp(
        firstByte: Int,
        cin: InputStream,
        cout: OutputStream,
        clientSocket: Socket,
        targetLocalPort: Int,
        authEnabled: Boolean,
        expectedUser: String,
        expectedPass: String
    ) {
        val restOfLine = readHeaderLine(cin)
        val firstLine = "${firstByte.toChar()}$restOfLine"
        val parts = firstLine.split(" ")
        if (parts.size < 2) {
            clientSocket.close()
            return
        }

        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = mutableListOf<String>()
        var proxyAuthHeader: String? = null
        var hostHeader: String? = null
        while (true) {
            val line = readHeaderLine(cin)
            if (line.isEmpty()) break
            headers.add(line)
            if (line.startsWith("Proxy-Authorization:", ignoreCase = true)) {
                proxyAuthHeader = line.substringAfter(":").trim()
            }
            if (line.startsWith("Host:", ignoreCase = true)) {
                hostHeader = line.substringAfter(":").trim()
            }
        }

        val useAuth = authEnabled && expectedUser.isNotEmpty() && expectedPass.isNotEmpty()
        if (useAuth) {
            var authOk = false
            if (proxyAuthHeader != null && proxyAuthHeader.startsWith("Basic ", ignoreCase = true)) {
                val b64 = proxyAuthHeader.substringAfter("Basic ").trim()
                val decoded = runCatching {
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                }.getOrNull()
                val creds = decoded?.split(":", limit = 2)
                if (creds != null && creds.size == 2 && creds[0] == expectedUser && creds[1] == expectedPass) {
                    authOk = true
                }
            }
            if (!authOk) {
                val resp = "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"Fluxon\"\r\n" +
                        "Content-Length: 0\r\n\r\n"
                cout.write(resp.toByteArray(Charsets.UTF_8))
                cout.flush()
                clientSocket.close()
                return
            }
        }

        val destHost: String
        val destPort: Int
        if (method == "CONNECT") {
            val hParts = target.split(":")
            destHost = hParts[0]
            destPort = if (hParts.size > 1) hParts[1].toIntOrNull() ?: 443 else 443
        } else {
            val uriHost = if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
                runCatching { java.net.URI(target).host }.getOrNull()
            } else null
            val rawHost = uriHost ?: hostHeader?.split(":")?.get(0) ?: ""
            if (rawHost.isEmpty()) {
                clientSocket.close()
                return
            }
            destHost = rawHost
            val uriPort = if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
                runCatching { java.net.URI(target).port.takeIf { it > 0 } }.getOrNull()
            } else null
            destPort = uriPort ?: hostHeader?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 80
        }

        val targetSocket = try {
            Socket().apply {
                soTimeout = 15000
                connect(java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), targetLocalPort), 4000)
            }
        } catch (e: Exception) {
            Logx.w(TAG, "Cannot connect to upstream HTTP target: ${e.message}")
            if (method == "CONNECT") {
                cout.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.UTF_8))
                cout.flush()
            }
            clientSocket.close()
            return
        }
        activeSockets.add(targetSocket)
        val tin = targetSocket.getInputStream()
        val tout = targetSocket.getOutputStream()

        val ok = socks5Connect(tin, tout, destHost, destPort)
        if (!ok) {
            activeSockets.remove(targetSocket)
            runCatching { targetSocket.close() }
            if (method == "CONNECT") {
                cout.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.UTF_8))
                cout.flush()
            }
            clientSocket.close()
            return
        }

        if (method == "CONNECT") {
            cout.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.UTF_8))
            cout.flush()
        } else {
            val reqSb = StringBuilder()
            reqSb.append(firstLine).append("\r\n")
            for (h in headers) {
                if (!h.startsWith("Proxy-Connection:", ignoreCase = true)) {
                    reqSb.append(h).append("\r\n")
                }
            }
            reqSb.append("\r\n")
            tout.write(reqSb.toString().toByteArray(Charsets.UTF_8))
            tout.flush()
        }

        startPipe(cin, cout, tin, tout, clientSocket, targetSocket)
    }

    private fun socks5Connect(tin: InputStream, tout: OutputStream, host: String, port: Int): Boolean {
        tout.write(byteArrayOf(0x05, 0x01, 0x00))
        tout.flush()
        val ver = tin.read()
        val method = tin.read()
        if (ver != 0x05 || method != 0x00) return false

        val hostBytes = host.toByteArray(Charsets.UTF_8)
        if (hostBytes.size > 255) return false
        val req = ByteArrayOutputStream()
        req.write(byteArrayOf(0x05, 0x01, 0x00, 0x03, (hostBytes.size and 0xFF).toByte()))
        req.write(hostBytes)
        req.write(byteArrayOf((port shr 8).toByte(), (port and 0xFF).toByte()))
        tout.write(req.toByteArray())
        tout.flush()

        val rVer = tin.read()
        val rRep = tin.read()
        val rRsv = tin.read()
        val rAtyp = tin.read()
        if (rVer != 0x05 || rRep != 0x00) return false

        val toSkip = when (rAtyp) {
            0x01 -> 4 + 2
            0x03 -> {
                val dLen = tin.read() and 0xFF
                dLen + 2
            }
            0x04 -> 16 + 2
            else -> return false
        }
        for (i in 0 until toSkip) {
            if (tin.read() < 0) return false
        }
        return true
    }

    private fun readHeaderLine(inStream: InputStream): String {
        val sb = StringBuilder()
        var prev = -1
        var count = 0
        while (true) {
            val b = inStream.read()
            if (b < 0) break
            count++
            if (count > 8192) throw java.io.IOException("Header line exceeded 8KB")
            if (b == '\n'.code && prev == '\r'.code) {
                sb.setLength(sb.length - 1)
                break
            }
            sb.append(b.toChar())
            prev = b
        }
        return sb.toString()
    }

    private fun startPipe(
        cin: InputStream,
        cout: OutputStream,
        tin: InputStream,
        tout: OutputStream,
        clientSocket: Socket,
        targetSocket: Socket
    ) {
        clientSocket.soTimeout = 30_000
        targetSocket.soTimeout = 30_000
        activeSockets.add(clientSocket)
        activeSockets.add(targetSocket)
        val t1 = Thread({
            try {
                pipe(cin, tout)
            } finally {
                activeSockets.remove(targetSocket)
                runCatching { targetSocket.close() }
                activeSockets.remove(clientSocket)
                runCatching { clientSocket.close() }
            }
        }, "HotspotBridge-ClientToTarget")
        val t2 = Thread({
            try {
                pipe(tin, cout)
            } finally {
                activeSockets.remove(clientSocket)
                runCatching { clientSocket.close() }
                activeSockets.remove(targetSocket)
                runCatching { targetSocket.close() }
            }
        }, "HotspotBridge-TargetToClient")
        t1.isDaemon = true
        t2.isDaemon = true
        t1.start()
        t2.start()
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buf = ByteArray(16384)
        try {
            var n: Int
            while (input.read(buf).also { n = it } != -1) {
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { input.close() }
            runCatching { output.close() }
        }
    }

    private fun InputStream.readFully(b: ByteArray) {
        var offset = 0
        while (offset < b.size) {
            val count = read(b, offset, b.size - offset)
            if (count < 0) throw java.io.EOFException("Unexpected EOF")
            offset += count
        }
    }
}
