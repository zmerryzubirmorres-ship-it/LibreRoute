package io.github.libreroute.service

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** Runs on a background dispatcher. No document URL or cookies enter argv/logs. */
internal object YandexBrowserProbe {
    fun run(context: Context, documentUrl: String, candidate: File, onProcess: (Process?) -> Unit): JSONObject {
        val binary = File(context.applicationInfo.nativeLibraryDir, "liblibreroute_client.so")
        val process = ProcessBuilder(binary.absolutePath, "--config-stdin")
            .directory(candidate.parentFile).redirectErrorStream(false).start()
        onProcess(process)
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { stream ->
                    stream.forEachLine { line ->
                        if (output.length < 8192) output.append(line.take(1024)).append('\n')
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        // Provider cooldown logs go to stderr. Keep them out of the JSON result
        // and drain the pipe so a verbose native failure cannot block the probe.
        val errorReader = Thread {
            runCatching {
                process.errorStream.use { stream ->
                    val buffer = ByteArray(2048)
                    while (stream.read(buffer) != -1) { /* Discard private diagnostics. */ }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val args = JSONArray().put("--yandex-auth-probe").put("--url=$documentUrl")
                .put("--yandex-cookie-file=${candidate.absolutePath}")
            process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(args.toString()) }
            if (!process.waitFor(95, TimeUnit.SECONDS)) {
                return JSONObject().put("ok", false).put("reason", "network")
            }
            reader.join(1000)
            errorReader.join(1000)
            if (process.exitValue() != 0 || reader.isAlive) {
                return JSONObject().put("ok", false).put("reason", "network")
            }
            return runCatching { JSONObject(output.toString()) }
                .getOrElse { JSONObject().put("ok", false).put("reason", "network") }
        } finally {
            if (process.isAlive) process.destroyForcibly()
            onProcess(null)
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }
}
