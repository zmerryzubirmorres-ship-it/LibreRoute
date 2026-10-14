package io.github.libreroute.admin

/** Only explicit edits shadow settings that may arrive after the first render. */
internal class ProtocolEndpointDrafts {
    private val edits = mutableMapOf<String, String>()

    fun edit(protocol: String, value: String) { edits[protocol] = value }

    fun value(protocol: String, vararg defaults: String?): String =
        edits[protocol] ?: defaults.firstOrNull { !it.isNullOrBlank() }.orEmpty()

    fun savedEdits(): Map<String, String> = edits.toMap()
}
