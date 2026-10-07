package io.github.libreroute.admin

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Private diagnostic log for server setup. It is intentionally kept outside the
 * UI: support can pull it with `adb shell run-as` without exposing credentials.
 */
object InstallationLog {
    private const val FILE_NAME = "admin-installation.log"
    private const val MAX_BYTES = 512 * 1024L
    private val lock = Any()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US)

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun append(context: Context, event: String, details: String = "") {
        val target = file(context)
        synchronized(lock) {
            runCatching {
                target.parentFile?.mkdirs()
                if (target.length() > MAX_BYTES) {
                    val rotated = File(context.filesDir, "$FILE_NAME.1")
                    rotated.delete()
                    target.renameTo(rotated)
                }
                val safe = redact(details).replace('\n', ' ').trim()
                target.appendText("${format.format(Date())} $event${if (safe.isBlank()) "" else " $safe"}\n")
                target.setReadable(false, false)
                target.setReadable(true, true)
                target.setWritable(false, false)
                target.setWritable(true, true)
            }
        }
    }

    private fun redact(value: String): String = value
        .replace(Regex("(?i)(password|passphrase|private_key|private_key_path|browser_cookies|join|pairing_code|payload|secret)[=:]\\s*[^,} ]+"), "$1=<redacted>")
        .replace(Regex("(?i)(authorization|cookie):\\s*[^,} ]+"), "$1=<redacted>")
}
