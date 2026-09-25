package io.github.p1neapplexpress.openflux.service

import android.content.Context
import io.github.p1neapplexpress.openflux.NativeBridge
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.ProcessRunner
import java.io.File

class Tun2SocksLauncher(private val context: Context) {

    companion object {
        private const val TAG = "Tun2SocksLauncher"
        private const val SEND_FD_ATTEMPTS = 6
        private const val SEND_FD_BASE_DELAY_MS = 400L
        private const val SEND_FD_MAX_DELAY_MS = 800L

        private const val NETIF_IPADDR = "26.26.26.2"
        private const val NETIF_NETMASK = "255.255.255.0"
        private const val NETIF_IP6ADDR = "fdfe:dcba:9876::2"
        private const val TUN_MTU = 1400
        private const val DNS_GW = "26.26.26.1:8091"
        private const val LOG_LEVEL = "3"
    }

    fun start(
        fd: Int,
        server: String,
        port: Int,
        username: String?,
        password: String?,
        dns: String,
        secondaryDns: String? = null,
        dnsPort: Int,
        ipv6: Boolean,
        udpgw: String?,
        mtu: Int = 1400,
    ): Boolean {
        if (fd <= 0) {
            Logx.e(TAG, "invalid tun fd: $fd")
            return false
        }

        val nativeDir = context.applicationInfo.nativeLibraryDir
        val pdnsdBin = "$nativeDir/libpdnsd.so"
        val tun2socksBin = "$nativeDir/libtun2socks.so"

        val sockPath = File(context.filesDir, "sock_path").apply {
            if (exists()) {
                runCatching { delete() }
            }
            runCatching {
                createNewFile()
                setWritable(true, true) // ownerOnly = true
                setReadable(true, true) // ownerOnly = true
            }.onFailure { Logx.w(TAG, "Failed to create sock_path: ${it.message}") }
        }

        
        makePdnsdConf(dns, dnsPort, secondaryDns)
        Logx.i(TAG, "starting pdnsd")
        ProcessRunner.execFireAndForget(
            command = listOf(pdnsdBin, "-c", "${context.filesDir}/pdnsd.conf"),
            workingDir = context.filesDir.absolutePath,
        )
        Thread.sleep(300L)

        
        Logx.i(TAG, "starting tun2socks")
        ProcessRunner.execFireAndForget(
            command = buildCommand(tun2socksBin, fd, server, port, username, password, ipv6, udpgw, sockPath, mtu),
            workingDir = context.filesDir.absolutePath,
        )
        Thread.sleep(300L)

        
        for (attempt in 1..SEND_FD_ATTEMPTS) {
            val r = NativeBridge.sendfd(fd, sockPath.absolutePath)
            if (r == 0) {
                Logx.i(TAG, "sendfd ok on attempt $attempt")
                return true
            }
            Logx.w(TAG, "sendfd attempt $attempt failed (ret=$r)")
            try {
                Thread.sleep((SEND_FD_BASE_DELAY_MS * attempt).coerceAtMost(SEND_FD_MAX_DELAY_MS))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }

        Logx.e(TAG, "sendfd failed after $SEND_FD_ATTEMPTS attempts")
        return false
    }

    fun stop() {
        Logx.i(TAG, "stop()")
        ProcessRunner.killPidFile("${context.filesDir}/tun2socks.pid")
        ProcessRunner.killPidFile("${context.filesDir}/pdnsd.pid")
        ProcessRunner.killAll()
        runCatching { File(context.filesDir, "sock_path").delete() }
        runCatching { File(context.applicationInfo.dataDir, "sock_path").delete() }
    }

    private fun buildCommand(
        bin: String,
        fd: Int,
        server: String,
        port: Int,
        user: String?,
        passwd: String?,
        ipv6: Boolean,
        udpgw: String?,
        sockPath: File,
        mtu: Int,
    ): List<String> = buildList {
        add(bin)
        add("--netif-ipaddr"); add(NETIF_IPADDR)
        add("--netif-netmask"); add(NETIF_NETMASK)
        add("--socks-server-addr"); add("$server:$port")
        add("--tunfd"); add(fd.toString())
        add("--tunmtu"); add(mtu.toString())
        val logLevel = if (Logx.isVerbose) "4" else LOG_LEVEL
        add("--loglevel"); add(logLevel)
        add("--pid"); add("${context.filesDir}/tun2socks.pid")
        add("--sock"); add(sockPath.absolutePath)
        if (server != "127.0.0.1" && server != "localhost" && !user.isNullOrEmpty() && !passwd.isNullOrEmpty()) {
            add("--username"); add(user)
            add("--password"); add(passwd)
        }
        if (ipv6) { add("--netif-ip6addr"); add(NETIF_IP6ADDR) }
        add("--dnsgw"); add(DNS_GW)
        udpgw?.let { add("--udpgw-remote-server-addr"); add(it) }
    }

    private fun makePdnsdConf(dns: String, port: Int, secondaryDns: String? = null) {
        val fallback = if (!secondaryDns.isNullOrBlank() && secondaryDns != dns) {
            """
server {
	label= "fallback";
	ip = $secondaryDns;
	port = $port;
	uptest = none;
	timeout = 4;
}
            """.trimIndent()
        } else ""

        val conf = context.getString(io.github.p1neapplexpress.openflux.R.string.pdnsd_conf)
            .replace("{DIR}", context.filesDir.toString())
            .replace("{IP}", dns)
            .replace("{PORT}", port.toString())
            .replace("{FALLBACK_SECTION}", fallback)

        val f = File(context.filesDir, "pdnsd.conf")
        f.writeText(conf)

        val cache = File(context.filesDir, "pdnsd.cache")
        if (cache.exists()) cache.delete()
        runCatching { cache.createNewFile() }
            .onFailure { Logx.w(TAG, "Failed to create pdnsd.cache: ${it.message}") }
    }
}
