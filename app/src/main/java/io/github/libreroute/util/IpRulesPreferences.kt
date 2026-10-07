package io.github.libreroute.util

import android.content.Context
import android.os.Build
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress

class IpRulesPreferences(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("libreroute_ip_rules", Context.MODE_PRIVATE)

    var rules: Set<String>
        get() = prefs.getStringSet("rules", emptySet())?.toSet() ?: emptySet()
        set(value) {
            val validOnly = value.map { it.trim() }.filter { it.isNotEmpty() && isValid(it) }.toSet()
            prefs.edit().putStringSet("rules", validOnly).apply()
        }

    fun update(raw: String): Boolean {
        val entries = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.any { !isValid(it) }) return false
        rules = entries.toSet()
        return true
    }

    fun addRule(rule: String): Boolean {
        val clean = rule.trim()
        if (!isValid(clean)) return false
        val updated = rules.toMutableSet()
        val added = updated.add(clean)
        if (added) rules = updated
        return added
    }

    fun writeRulesFile(): File = File(app.filesDir, "ip_rules.txt").also { file ->
        file.writeText(rules.sorted().joinToString("\n", postfix = "\n"))
    }

    companion object {
        fun isValid(value: String): Boolean {
            val trimmed = value.trim()
            if (trimmed.isEmpty()) return false
            val parts = trimmed.split('/')
            if (parts.size > 2) return false
            val address = parts[0]
            if (address.isEmpty()) return false

            val parsed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching { android.net.InetAddresses.parseNumericAddress(address) }.getOrNull()
            } else {
                runCatching {
                    if (isNumericIp(address)) {
                        @Suppress("DEPRECATION")
                        InetAddress.getByName(address)
                    } else null
                }.getOrNull()
            } ?: return false

            val bits = if (parsed is Inet4Address || parsed.address.size == 4) 32 else 128
            val prefix = if (parts.size == 2) {
                val pStr = parts[1]
                if (pStr.isEmpty() || !pStr.all { it.isDigit() }) return false
                pStr.toIntOrNull() ?: return false
            } else bits

            return prefix in 0..bits
        }

        private fun isNumericIp(str: String): Boolean {
            val v4Parts = str.split('.')
            if (v4Parts.size == 4) {
                return v4Parts.all { part ->
                    val num = part.toIntOrNull() ?: return@all false
                    num in 0..255 && (part == "0" || !part.startsWith("0"))
                }
            }
            if (str.contains(':') && str.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' }) {
                return true
            }
            return false
        }
    }
}
