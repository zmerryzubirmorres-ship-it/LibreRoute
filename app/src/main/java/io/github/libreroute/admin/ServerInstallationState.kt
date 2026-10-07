package io.github.libreroute.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Durable local intent. Stored only in the encrypted administrative store. */
@Serializable
data class ServerInstallOperation(
    val operationId: String,
    val serverId: String,
    val namespace: String,
    val node: ServerNode,
    val expectedPlan: String,
    val planHash: String,
    val profileId: String,
    val deviceId: String,
    val documentUrl: String = "",
    val ownerRecovery: Boolean = false,
    val kind: String = KIND_INSTALL,
    val join: String? = null,
    val stage: String = PLANNED,
    val receipt: String? = null,
    val previousGrantEnvelope: String? = null,
    val previousDocumentSession: String? = null,
    val identityBindingConfirmed: Boolean = false,
    val priorLocalNodeId: String? = null,
    val error: String? = null,
    val profileName: String = "",
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val PLANNED = "planned"
        const val SUBMITTING = "submitting"
        const val UNKNOWN = "unknown"
        const val SERVER_CONFIRMED = "server_confirmed"
        const val READY = "ready"
        const val ROLLED_BACK = "rolled_back"
        const val RECOVERY_REQUIRED = "recovery_required"
        const val KIND_INSTALL = "install"
        const val KIND_OWNER_RECOVERY = "recover-owner"
        const val KIND_CORE_UPDATE = "core-update"
    }
    val requiresReconciliation: Boolean get() = stage in setOf(SUBMITTING, UNKNOWN, SERVER_CONFIRMED, RECOVERY_REQUIRED)
}

/** Explicit seam for failure/restart integration tests; production delegates to the SSH installer. */
interface ServerInstallationBackend {
    fun plan(node: ServerNode, inventory: ServerInventory, operationId: String): JsonObject
    fun install(node: ServerNode, inventory: ServerInventory, operationId: String,
                documentUrl: String, join: AdminEnvelope, pairingCode: String, expectedPlan: JsonObject,
                ownerRecovery: Boolean = false): JsonObject
    fun status(node: ServerNode, operationId: String): JsonObject
    fun rollback(node: ServerNode, operationId: String): JsonObject
    fun recoverOwner(node: ServerNode, inventory: ServerInventory, operationId: String,
                     documentUrl: String, join: AdminEnvelope, pairingCode: String,
                     expectedPlan: JsonObject): JsonObject =
        error("SSH owner recovery adapter is not available")
}

@Serializable
enum class AdminAccessState { NONE, PENDING, ACTIVE, EXPIRED, REVOKED, INVALID }

data class AdminServerAccess(val serverId: String?, val state: AdminAccessState,
                           val role: AdminRole = AdminRole.NONE, val expiresAt: Long? = null)

/** A verified negative ACK differs from a timeout after a possibly accepted mutation. */
class ConfirmedAdminRejection(message: String) : IllegalStateException(message)
class UnknownAdminOperation(message: String) : IllegalStateException(message)

/** A redeemed invitation is not sent again after Activity/process recreation. */
@Serializable
data class ClientRedemptionCheckpoint(
    val invitationHash: String,
    val request: String,
    val serverKey: String,
    val receipt: String? = null,
    val candidate: VerifiedInvitationCandidate? = null,
    val tunnels: List<io.github.libreroute.data.Tunnel> = emptyList(),
    val completed: Boolean = false
)
