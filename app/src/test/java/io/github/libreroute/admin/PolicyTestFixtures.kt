package io.github.libreroute.admin

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.util.Base64

internal fun policySnapshotPayload(request: AdminEnvelope, pub: String): String? {
    val command = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(request.payload), Charsets.UTF_8)).jsonObject
    if (command["op"]?.jsonPrimitive?.content != "get-cluster-snapshot") return null
    val snapshot = AdminClusterSnapshot(authorityId = pub, snapshotRevision = 5)
    val data = Json { encodeDefaults = true }.encodeToString(snapshot.copy(snapshotHash = computeClusterSnapshotHash(snapshot)))
    val body = buildJsonObject {
        put("op_id", request.opId); put("status", "applied"); put("data", Json.parseToJsonElement(data))
    }
    return Base64.getUrlEncoder().withoutPadding().encodeToString(body.toString().toByteArray(Charsets.UTF_8))
}
