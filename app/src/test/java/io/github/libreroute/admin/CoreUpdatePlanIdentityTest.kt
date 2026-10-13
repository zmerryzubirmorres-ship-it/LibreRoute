package io.github.libreroute.admin

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CoreUpdatePlanIdentityTest {
    // Wire fields of Go admin.UpdatePlan, including the existing Core 1.2.30 API.
    private val plan = Json.parseToJsonElement("""{
        "operation_id":"update-one", "namespace":"node-server", "operation_kind":"core-update",
        "server_id":"server", "server_public_key":"pinned", "control_profile_id":"control",
        "owner_device_id":"owner", "plan_hash":"immutable", "adapter":"systemd"
    }""").jsonObject

    private fun validate(candidate: JsonObject) = requireCoreUpdatePlanIdentity(candidate,
        "server", "pinned", "control", "update-one", "node-server")

    @Test fun acceptsServerWireFormatWithoutInventingKindField() {
        assertFalse(plan.containsKey("kind"))
        validate(plan)
    }

    @Test fun rejectsForeignServerKeyProfileOperationNamespaceAndKind() {
        for (field in listOf("server_id", "server_public_key", "control_profile_id",
            "operation_id", "namespace", "operation_kind")) {
            val error = assertThrows(IllegalArgumentException::class.java) {
                validate(JsonObject(plan + (field to JsonPrimitive("foreign"))))
            }
            assertTrue(error.message.orEmpty().contains(field))
        }
    }

    @Test fun missingOperationKindCannotBeReplacedByLocalKindField() {
        val candidate = JsonObject((plan - "operation_kind") + ("kind" to JsonPrimitive("core-update")))
        assertThrows(IllegalArgumentException::class.java) { validate(candidate) }
    }
}
