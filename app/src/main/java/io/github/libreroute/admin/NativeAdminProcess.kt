package io.github.libreroute.admin

import java.io.File
import java.io.InputStream
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Drain both pipes while the command runs; stderr must not contaminate its JSON response. */
internal object NativeAdminProcess {
    fun run(cmd: List<String>, directory: File, request: String, timeoutSeconds: Long): Pair<Int, String> {
        val process = ProcessBuilder(cmd).directory(directory).start()
        fun <T> background(action: () -> T): FutureTask<T> = FutureTask(action).also { task ->
            Thread(task, "admin-process-io").apply { isDaemon = true; start() }
        }
        fun read(stream: InputStream, limit: Int): Pair<String, Boolean> = stream.bufferedReader(Charsets.UTF_8).use { reader ->
            val result = StringBuilder()
            val chunk = CharArray(4096)
            var truncated = false
            while (true) {
                val count = reader.read(chunk)
                if (count < 0) break
                val retained = minOf(count, limit - result.length)
                if (retained > 0) result.append(chunk, 0, retained)
                if (retained < count) truncated = true
            }
            result.toString().trim() to truncated
        }
        val stdout = background { read(process.inputStream, 512 * 1024) }
        val stderr = background { read(process.errorStream, 64 * 1024) }
        val stdin = background { process.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(request) } }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                return -1 to "Тайм-аут ожидания ответа по SSH (${timeoutSeconds}с)"
            }
            stdin.get(1, TimeUnit.SECONDS)
            val (output, truncated) = stdout.get(1, TimeUnit.SECONDS)
            val errors = stderr.get(1, TimeUnit.SECONDS).first
            if (truncated) return -2 to "Ответ SSH превышает допустимый размер; проверьте результат операции"
            return process.exitValue() to if (process.exitValue() == 0) output else errors
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
