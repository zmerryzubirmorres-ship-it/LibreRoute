package io.github.libreroute.admin

import kotlinx.serialization.json.*
import java.security.MessageDigest

/** Version 2 covers the complete wire JSON, including unknown fields. */
fun computeClusterSnapshotHash(wire: JsonObject): String {
    require(wire["snapshot_hash_version"]?.jsonPrimitive?.intOrNull == 2) {
        "Неподдерживаемая версия контрольной суммы снимка"
    }
    val collections = setOf("servers", "server_nodes", "users", "devices", "assignments", "profiles", "worker_statuses")
    val canonical = JsonObject(wire.filterKeys { it != "snapshot_hash" && it != "timestamp" }.mapValues { (key, value) ->
        if (key in collections && value is JsonArray) JsonArray(value.sortedBy { row ->
            if (row is JsonPrimitive) row.content else {
                val obj = row.jsonObject
                obj["id"]?.jsonPrimitive?.content
                    ?: (obj["server_id"]!!.jsonPrimitive.content + "/" + (obj["profile_id"]?.jsonPrimitive?.content ?: "<nil>"))
            }
        }) else value
    })
    val md = MessageDigest.getInstance("SHA-256")
    fun token(s: String) {
        val bytes = s.toByteArray(Charsets.UTF_8)
        md.update("${bytes.size}:".toByteArray(Charsets.UTF_8))
        md.update(bytes)
    }
    fun visit(value: JsonElement) {
        when (value) {
            is JsonObject -> {
                token("object"); token(value.size.toString())
                value.keys.sorted().forEach { key -> token(key); visit(value.getValue(key)) }
            }
            is JsonArray -> {
                token("array"); token(value.size.toString()); value.forEach { visit(it) }
            }
            JsonNull -> token("null")
            is JsonPrimitive -> {
                token(if (value.isString) "string" else if (value.booleanOrNull != null) "bool" else "number")
                token(value.content)
            }
        }
    }
    visit(canonical)
    return md.digest().joinToString("") { "%02x".format(it) }
}
