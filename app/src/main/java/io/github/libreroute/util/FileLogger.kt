package io.github.libreroute.util

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Thread-safe, non-blocking persistent file logger.
 * Writes diagnostic and operational logs to files/libreroute.log with rotation and redaction.
 */
object FileLogger {
    private const val FILE_NAME = "libreroute.log"
    const val MAX_BYTES = 2 * 1024 * 1024L // 2 MB
    private const val BACKUP_SUFFIX = ".1"

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "FileLoggerThread").apply { isDaemon = true }
    }
    private val lock = Any()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun getLogFile(context: Context? = appContext): File? {
        val ctx = context ?: appContext ?: return null
        return File(ctx.filesDir, FILE_NAME)
    }

    fun append(message: String, context: Context? = appContext) {
        val ctx = context ?: appContext ?: return
        if (message.isBlank()) return

        executor.execute {
            synchronized(lock) {
                runCatching {
                    val file = File(ctx.filesDir, FILE_NAME)
                    file.parentFile?.mkdirs()

                    if (file.exists() && file.length() > MAX_BYTES) {
                        val backup = File(ctx.filesDir, "$FILE_NAME$BACKUP_SUFFIX")
                        backup.delete()
                        file.renameTo(backup)
                    }

                    val now = dateFormat.format(Date())
                    val safeMsg = sanitize(message)
                    FileWriter(file, true).use { fw ->
                        PrintWriter(fw).use { pw ->
                            safeMsg.split('\n').forEach { line ->
                                val trimmed = line.trimEnd()
                                if (trimmed.isNotEmpty()) {
                                    if (trimmed.startsWith("202") || trimmed.startsWith("[")) {
                                        pw.println("$now $trimmed")
                                    } else {
                                        pw.println("$now [LOG] $trimmed")
                                    }
                                }
                            }
                        }
                    }
                    file.setReadable(false, false)
                    file.setReadable(true, true)
                    file.setWritable(false, false)
                    file.setWritable(true, true)
                }
            }
        }
    }

    fun clear(context: Context? = appContext) {
        val ctx = context ?: appContext ?: return
        executor.execute {
            synchronized(lock) {
                runCatching {
                    val file = File(ctx.filesDir, FILE_NAME)
                    if (file.exists()) file.delete()
                    val backup = File(ctx.filesDir, "$FILE_NAME$BACKUP_SUFFIX")
                    if (backup.exists()) backup.delete()
                }
            }
        }
    }

    fun readRecentLines(context: Context? = appContext, maxLines: Int = 1000): List<String> {
        val ctx = context ?: appContext ?: return emptyList()
        val file = File(ctx.filesDir, FILE_NAME)
        if (!file.exists() || !file.canRead()) return emptyList()

        return runCatching {
            file.useLines { lines ->
                val list = ArrayList<String>(maxLines)
                for (line in lines) {
                    if (list.size >= maxLines) {
                        list.removeAt(0)
                    }
                    list.add(line)
                }
                list
            }
        }.getOrDefault(emptyList())
    }

    fun sanitize(input: String): String {
        return input
            .replace(Regex("(?i)(password|passphrase|secret|private_key|socks5-pass|mqtt-pass)[=:]\\s*[^,\\s}]+"), "$1=<redacted>")
            .replace(Regex("(?i)(authorization|bearer|cookie|token)[=:\\s]+[^,\\s}]+"), "$1=<redacted>")
    }
}
