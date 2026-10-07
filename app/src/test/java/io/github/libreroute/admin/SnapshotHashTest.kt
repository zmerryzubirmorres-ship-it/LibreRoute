package io.github.libreroute.admin

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SnapshotHashTest {
    @Test fun sharedGoVectorAndFieldsUnknownToModel() {
        val raw = javaClass.classLoader!!.getResource("snapshot-v2.json")!!.readText()
        val wire = Json.parseToJsonElement(raw).jsonObject
        val hash = computeClusterSnapshotHash(wire)
        assertEquals(wire["snapshot_hash"]!!.jsonPrimitive.content, hash)
        val nodes = wire["server_nodes"]!!.jsonArray
        val changedNode = JsonObject(nodes[0].jsonObject + ("public_key" to JsonPrimitive("changed-pin")))
        assertNotEquals(hash, computeClusterSnapshotHash(JsonObject(wire + ("server_nodes" to JsonArray(listOf(changedNode))))))
        assertNotEquals(hash, computeClusterSnapshotHash(JsonObject(wire + ("new_contract_field" to JsonPrimitive("covered")))))
        assertEquals(hash, computeClusterSnapshotHash(JsonObject(wire + ("timestamp" to JsonPrimitive(0)))))
    }
}
