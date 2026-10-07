package io.github.libreroute.util

/** Redacts known connection material before native output reaches Logcat or exportable logs. */
class NativeLogRedactor(arguments: List<String>) {
    private val privateValues = buildList {
        arguments.forEachIndexed { index, argument ->
            val flag = argument.substringBefore('=').trimStart('-').lowercase()
            if (flag in setOf("url", "urls", "doh-url", "mqtt-broker") ||
                listOf("token", "password", "secret", "cookie", "socks5-pass", "mqtt-pass").any { flag.contains(it) }) {
                val value = if ('=' in argument) argument.substringAfter('=') else arguments.getOrNull(index + 1)
                if (!value.isNullOrEmpty() && !value.startsWith("--")) add(value)
            }
        }
    }.distinct().sortedByDescending { it.length }

    fun redact(line: String): String = privateValues.fold(line) { result, value -> result.replace(value, "[скрыто]") }
}
