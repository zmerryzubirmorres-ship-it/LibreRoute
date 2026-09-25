package io.github.p1neapplexpress.openflux.util

import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

object ProcessRunner {

    private const val TAG = "ProcessRunner"
    private val activeProcesses = CopyOnWriteArrayList<Process>()

    /**
     * Fire-and-forget: starts a process, reads its stdout in a background
     * thread, does not wait for completion. For daemons (pdnsd, tun2socks).
     */
    fun execFireAndForget(
        command: List<String>,
        workingDir: String? = null,
    ): Process? {
        return try {
            val pb = ProcessBuilder(command).redirectErrorStream(true)
            if (workingDir != null) pb.directory(File(workingDir))
            val p = pb.start()
            runCatching { p.outputStream.close() }
            activeProcesses.add(p)
            Thread {
                try {
                    p.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (line.isNotBlank()) {
                                Logx.i(TAG, line)
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    activeProcesses.remove(p)
                }
            }.apply { isDaemon = true; start() }
            p
        } catch (e: Exception) {
            Logx.e(TAG, "exec failed: ${command.firstOrNull()}", e)
            null
        }
    }

    fun killPidFile(path: String) {
        val f = File(path)
        if (!f.exists()) return
        try {
            val pid = f.readText().trim().toIntOrNull() ?: return
            if (pid <= 0) return
            Logx.i(TAG, "Killing process PID $pid from $path via SIGKILL")
            android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL)
        } catch (e: Exception) {
            Logx.w(TAG, "Failed to kill pid from $path: ${e.message}")
        } finally {
            runCatching { f.delete() }
        }
    }

    fun killAll() {
        Logx.i(TAG, "killAll active daemon processes (count=${activeProcesses.size})")
        for (p in activeProcesses) {
            try {
                if (p.isAlive) {
                    p.destroyForcibly()
                }
            } catch (_: Exception) {}
        }
        activeProcesses.clear()
    }
}
