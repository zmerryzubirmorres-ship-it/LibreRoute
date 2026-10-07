package io.github.libreroute.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * Roles for administration in LibreRoute.
 * Invariant: Possession of a VPN profile NEVER grants admin rights on its own!
 * Role remains NONE until an authentic ADMIN_GRANT is received and validated.
 */
@Serializable
enum class AdminRole {
    NONE,
    PENDING,
    OWNER,
    OPERATOR,
    VIEWER
}

/**
 * Channels available for sending administrative envelopes.
 */
enum class AdminChannel(val displayName: String) {
    DOCUMENT("Документ профиля"),
    DIRECT_SSH("Прямой SSH к серверу"),
    SSH_VDS("SSH: VDS"),
    SSH_NETCRAZE("SSH: Netcraze (LAN)")
}

/**
 * Server node types in the managed cluster.
 */
@Serializable
enum class ServerNodeType(val displayName: String) {
    VDS("VDS-сервер"),
    ROUTER_KEENETIC("Роутер Keenetic"),
    ROUTER_OPENWRT("Роутер OpenWrt"),
    CUSTOM("Пользовательский сервер")
}

/**
 * Health / connectivity status of a server node.
 */
@Serializable
enum class ServerNodeStatus(val displayName: String) {
    ONLINE("В сети"),
    OFFLINE("Недоступен"),
    DEGRADED("Замедлен"),
    UNKNOWN("Не проверялся")
}

/**
 * SSH Authentication modes.
 */
enum class SshAuthType {
    KEY,
    PASSWORD
}

/**
 * Host inventory probed safely via SSH before any deployment or installation.
 */
@Serializable
data class ServerInventory(
    @SerialName("os_name") val osName: String = "",
    @SerialName("os_version") val osVersion: String = "",
    val arch: String = "",
    @SerialName("cpu_cores") val cpuCores: Int = 0,
    @SerialName("init_system") val initSystem: String = "",
    @SerialName("package_manager") val packageManager: String = "",
    @SerialName("ram_total_mb") val ramTotalMb: Long = 0L,
    @SerialName("ram_free_mb") val ramFreeMb: Long = 0L,
    @SerialName("disk_total_mb") val diskTotalMb: Long = 0L,
    @SerialName("disk_free_mb") val diskFreeMb: Long = 0L,
    @SerialName("has_docker") val hasDocker: Boolean = false,
    @SerialName("has_podman") val hasPodman: Boolean = false,
    @SerialName("installed_libreroute_version") val installedLibreRouteVersion: String? = null,
    @SerialName("managed_namespaces") val managedNamespaces: List<String> = emptyList(),
    @SerialName("installed_components") val installedComponents: List<String> = emptyList(),
    @SerialName("managed_containers") val managedContainers: List<String> = emptyList(),
    @SerialName("managed_users") val managedUsers: List<String> = emptyList(),
    @SerialName("installed_protocols") val installedProtocols: List<String> = emptyList(),
    @SerialName("is_compatible") val isCompatible: Boolean = false,
    @SerialName("probe_error") val probeError: String? = null,
    @SerialName("probed_at") val probedAt: Long = 0L
)

/**
 * A server node managed within the dynamic cluster.
 * Replaces hardcoded testbed servers (VDS89, Netcraze).
 */
@Serializable
data class ServerNode(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val nodeType: ServerNodeType = ServerNodeType.VDS,
    val host: String,
    val port: Int = 22,
    val sshUser: String = "root",
    val authType: SshAuthType = SshAuthType.KEY,
    val privateKeyPath: String? = null,
    @kotlinx.serialization.Transient val passphrase: String? = null,
    @kotlinx.serialization.Transient val password: String? = null,
    val credentialRef: String? = null,
    val managementNamespace: String? = null,
    val hostPublicKey: String? = null,
    val allowPasswordAuth: Boolean = false,
    val supportedProtocols: List<String> = listOf("vyandex", "udp-ipv4"),
    val status: ServerNodeStatus = ServerNodeStatus.UNKNOWN,
    val inventory: ServerInventory? = null,
    val lastSeenAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val coreObservation: CoreObservation = CoreObservation()
) {
    fun toSshHostConfig(): SshHostConfig = SshHostConfig(
        host = host,
        port = port,
        username = sshUser,
        authType = authType,
        privateKeyPath = privateKeyPath,
        passphrase = passphrase,
        password = password,
        hostPublicKey = hostPublicKey,
        allowPasswordAuth = allowPasswordAuth,
        managementNamespace = managementNamespace
    )
}

/**
 * Host connection configuration for SSH fallback.
 */
@Serializable
data class SshHostConfig(
    val host: String = "10.10.1.1",
    val port: Int = 222,
    val username: String = "root",
    val authType: SshAuthType = SshAuthType.KEY,
    val privateKeyPath: String? = null,
    val passphrase: String? = null,
    val password: String? = null,
    val hostPublicKey: String? = null, // verified OpenSSH public host key
    val managementNamespace: String? = null,
    val allowPasswordAuth: Boolean = false // VDS policy requires key by default
)

/**
 * Granted administrative capability received from server.
 */
@Serializable
data class AdminGrant(
    val role: AdminRole,
    val profileId: String,
    val adminFingerprint: String,
    val serverPublicKey: String,
    val grantedAt: Long,
    val expiresAt: Long,
    val scope: List<String> = emptyList(),
    val revision: Long = 1L,
    val nonce: String = "",
    val serverSignature: String = "",
    @SerialName("server_id") val serverId: String? = null
)

/**
 * A client profile managed by this administrator device.
 */
@Serializable
data class ManagedProfile(
    val id: String,
    val name: String,
    val status: String = STATUS_READY, // "ready", "suspended", "revoked"
    val revision: Long = 1L,
    val transportType: String = "vyandex",
    val sessionNegotiated: Boolean = true,
    val udpEnabled: Boolean = false,
    val allowedDestCIDRs: List<String> = emptyList(),
    val allowedServerIds: List<String> = emptyList(),
    val allowedProtocols: List<String> = listOf("vyandex", "udp-ipv4"),
    val serverConfigs: Map<String, ProfileServerConfig> = emptyMap(),
    val lastCheckTimestamp: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val issuedTo: String? = null,
    /** Local ownership metadata. Older records deserialize with safe defaults. */
    @SerialName("profile_type") val profileType: String = PROFILE_TYPE_USER,
    val role: AdminRole = AdminRole.NONE,
    @SerialName("server_id") val serverId: String? = null,
    @SerialName("provisioning_status") val provisioningStatus: String = PROVISIONING_READY
) {
    companion object {
        const val STATUS_READY = "ready"
        const val STATUS_SUSPENDED = "suspended"
        const val STATUS_REVOKED = "revoked"
        const val PROFILE_TYPE_USER = "user"
        const val PROFILE_TYPE_CONTROL = "control"
        const val PROVISIONING_MISSING = "profile missing"
        const val PROVISIONING = "profile provisioning"
        const val PROVISIONING_READY = "profile ready"
        const val PROVISIONING_FAILED = "profile failed"
    }

    val isReady: Boolean get() = status.equals(STATUS_READY, ignoreCase = true)
    val isSuspended: Boolean get() = status.equals(STATUS_SUSPENDED, ignoreCase = true)
    val isRevoked: Boolean get() = status.equals(STATUS_REVOKED, ignoreCase = true)
}

/** Confirmed inventory returned by the server registry, with no secrets. */
@Serializable
data class ServerManagedProfile(
    val id: String = "",
    @kotlinx.serialization.SerialName("profile_id") val profileId: String = "",
    @kotlinx.serialization.SerialName("server_id") val serverId: String = "",
    val name: String = "",
    val transport: String = "",
    val status: String = "ready",
    val revision: Long = 1L
)

/**
 * Per-server endpoint and document configuration for a managed profile.
 */
@Serializable
data class ProfileServerConfig(
    val serverId: String,
    val documentUrl: String? = null,
    val extraParams: String? = null
)

/**
 * Pre-issuance probe result for testing a server/protocol combination.
 */
@Serializable
data class ProbeResult(
    val serverId: String,
    val serverName: String,
    val protocol: String,
    val success: Boolean,
    val rttMs: Long,
    val message: String
)

/**
 * Multi-server bundle for Zero-Config client onboarding.
 */
@Serializable
data class UserBundle(
    val bundleVersion: Int = 1,
    val bundleId: String = java.util.UUID.randomUUID().toString(),
    val profileId: String,
    val userName: String,
    val serverEntries: List<UserBundleServerEntry>,
    val issuedAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = System.currentTimeMillis() / 1000L + 30L * 86400L,
    val signature: String = ""
) {
    fun toLink(): String {
        error("Legacy UserBundle contains unverified plaintext; encrypted server-issued invitations are required")
    }

    companion object {
        // Legacy plaintext bundles are never valid invitations.
        fun fromLink(@Suppress("UNUSED_PARAMETER") link: String): UserBundle? = null
    }
}

/**
 * An individual server endpoint entry inside a UserBundle.
 */
@Serializable
data class UserBundleServerEntry(
    val serverId: String,
    val serverName: String,
    val host: String,
    val port: Int = 22,
    val transportType: String = "vyandex",
    val documentUrl: String? = null,
    val encryptionKey: String? = null,
    val sessionNegotiated: Boolean = true,
    val udpEnabled: Boolean = false,
    val extraParams: String? = null
)

/**
 * Audit record of administrative commands sent by this device.
 */
@Serializable
data class AdminCommandRecord(
    val opId: String,
    val targetProfileId: String,
    val commandType: String, // "request", "create", "issue", "suspend", "resume", "revoke", "update"
    val status: String, // "pending", "accepted", "applied", "verified", "failed"
    val channel: String, // "document", "ssh_vds", "ssh_netcraze"
    val timestamp: Long,
    val revision: Long,
    val payload: String = "",
    val error: String? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_ACCEPTED = "accepted"
        const val STATUS_APPLIED = "applied"
        const val STATUS_VERIFIED = "verified"
        const val STATUS_FAILED = "failed"
        const val STATUS_UNKNOWN = "unknown"
    }
}

/**
 * Structured problem report from client diagnostic subsystem.
 */
@Serializable
data class ProblemReport(
    val code: String, // "queue_overflow", "video_stall", "high_rtt", "packet_loss", "send_failure", "other"
    val profileId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val reportId: String,
    val details: Map<String, String> = emptyMap()
) {
    companion object {
        const val CODE_QUEUE_OVERFLOW = "queue_overflow"
        const val CODE_VIDEO_STALL = "video_stall"
        const val CODE_HIGH_RTT = "high_rtt"
        const val CODE_PACKET_LOSS = "packet_loss"
        const val CODE_SEND_FAILURE = "send_failure"
        const val CODE_ENDPOINT_UNREACHABLE = "endpoint_unreachable"
        const val CODE_OTHER = "other"
    }
}

/**
 * Result of an administrative operation attempt.
 */
sealed class AdminOpResult {
    data class Success(val message: String, val envelope: AdminEnvelope? = null) : AdminOpResult()
    data class Failure(val error: String, val throwable: Throwable? = null) : AdminOpResult()
}

/**
 * Result of client route synchronization check.
 */
sealed class RouteSyncResult {
    data class Unchanged(val revision: Long, val lastCheckedAt: Long) : RouteSyncResult()
    data class Updated(val newRevision: Long, val routes: List<io.github.libreroute.data.Tunnel>) : RouteSyncResult()
    object Revoked : RouteSyncResult()
    data class Failed(val error: String) : RouteSyncResult()
}

/**
 * Parsed parameters from an libreroute://claim Setup Key URI or QR code.
 */
@Serializable
data class ServerClaimInfo(
    val host: String,
    val port: Int = 22,
    val token: String,
    val name: String? = null
) {
    companion object {
        fun parse(uriString: String): ServerClaimInfo? {
            val trimmed = uriString.trim()
            if (!trimmed.startsWith("libreroute://claim", ignoreCase = true)) return null
            return runCatching {
                val uri = java.net.URI(trimmed)
                val query = uri.rawQuery ?: uri.schemeSpecificPart.substringAfter("?", "")
                val params = query.split("&").associate { param ->
                    val parts = param.split("=", limit = 2)
                    val key = java.net.URLDecoder.decode(parts[0], "UTF-8")
                    val value = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
                    key to value
                }
                val server = params["server"] ?: return null
                val token = params["token"] ?: return null
                val port = params["port"]?.toIntOrNull() ?: 22
                val name = params["name"]
                ServerClaimInfo(host = server, port = port, token = token, name = name)
            }.getOrNull()
        }
    }
}

/**
 * An authorized user in the LibreRoute administrative system.
 */
@Serializable
data class AdminUser(
    val id: String,
    val name: String,
    val status: String = STATUS_ACTIVE,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_SUSPENDED = "suspended"
        const val STATUS_REVOKED = "revoked"
    }
    val isActive: Boolean get() = status.equals(STATUS_ACTIVE, ignoreCase = true)
    val isSuspended: Boolean get() = status.equals(STATUS_SUSPENDED, ignoreCase = true)
    val isRevoked: Boolean get() = status.equals(STATUS_REVOKED, ignoreCase = true)
}

/**
 * A registered physical device belonging to an AdminUser.
 */
@Serializable
data class AdminDevice(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("public_key") val publicKey: String,
    val fingerprint: String,
    val status: String = STATUS_ACTIVE,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
) {
    companion object {
        const val STATUS_ACTIVE = "active"
        const val STATUS_REVOKED = "revoked"
    }
    val isActive: Boolean get() = status.equals(STATUS_ACTIVE, ignoreCase = true)
    val isRevoked: Boolean get() = status.equals(STATUS_REVOKED, ignoreCase = true)
}

/**
 * Access assignment binding a User + Device to a ProfileInstance on a Server.
 */
@Serializable
data class AdminAssignment(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("user_name") val userName: String? = null,
    @SerialName("device_id") val deviceId: String,
    @SerialName("device_fingerprint") val deviceFingerprint: String? = null,
    @SerialName("profile_instance_id") val profileInstanceId: String,
    @SerialName("server_id") val serverId: String,
    val transport: String,
    val status: String,
    val revision: Long,
    @SerialName("desired_revision") val desiredRevision: Long = 0L,
    @SerialName("applied_revision") val appliedRevision: Long = 0L,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("retry_count") val retryCount: Int = 0,
    @SerialName("next_retry_at") val nextRetryAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("has_invitation") val hasInvitation: Boolean = false
) {
    companion object {
        const val STATUS_PROVISIONING = "provisioning"
        const val STATUS_READY = "ready"
        const val STATUS_INVITED = "invited"
        const val STATUS_ACTIVE = "active"
        const val STATUS_SUSPENDED = "suspended"
        const val STATUS_REVOKED = "revoked"
        const val STATUS_FAILED = "failed"
    }
    val isActive: Boolean get() = status.equals(STATUS_ACTIVE, ignoreCase = true)
    val isSuspended: Boolean get() = status.equals(STATUS_SUSPENDED, ignoreCase = true)
    val isRevoked: Boolean get() = status.equals(STATUS_REVOKED, ignoreCase = true)
    val isFailed: Boolean get() = status.equals(STATUS_FAILED, ignoreCase = true)
    val isPendingReconciliation: Boolean get() = desiredRevision > 0L && appliedRevision > 0L && desiredRevision != appliedRevision
    val isInBackoff: Boolean get() = nextRetryAt > 0L && (System.currentTimeMillis() / 1000) < nextRetryAt
}

/**
 * Metadata for single-use cryptographically bound invitation.
 */
@Serializable
data class AdminInvitation(
    val id: String,
    @SerialName("assignment_id") val assignmentId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    val status: String,
    val revision: Long,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    companion object {
        const val STATUS_ISSUED = "issued"
        const val STATUS_REDEEMED = "redeemed"
        const val STATUS_EXPIRED = "expired"
        const val STATUS_REVOKED = "revoked"
    }
    val isIssued: Boolean get() = status.equals(STATUS_ISSUED, ignoreCase = true)
    val isRedeemed: Boolean get() = status.equals(STATUS_REDEEMED, ignoreCase = true)
}

/**
 * Worker status tracking desired vs applied revision.
 */
@Serializable
data class AdminWorkerStatus(
    @SerialName("server_id") val serverId: String = "",
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("desired_revision") val desiredRevision: Long = 0L,
    @SerialName("applied_revision") val appliedRevision: Long = 0L,
    val readiness: Boolean = false,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_checked") val lastChecked: String? = null
) {
    val isApplied: Boolean get() = (desiredRevision == 0L && appliedRevision > 0L) || (desiredRevision > 0L && appliedRevision >= desiredRevision)
    val isReady: Boolean get() = readiness && lastError.isNullOrBlank()
}

// Request payloads for v2 admin operations
@Serializable
data class CreateUserPayload(
    val op: String = "create-user",
    val name: String
)

@Serializable
data class UpdateUserPayload(
    val op: String = "update-user",
    @SerialName("user_id") val userId: String,
    val name: String? = null,
    val status: String? = null
)

@Serializable
data class SuspendUserPayload(
    val op: String = "suspend-user",
    @SerialName("user_id") val userId: String
)

@Serializable
data class RegisterDevicePayload(
    val op: String = "register-device",
    @SerialName("user_id") val userId: String,
    @SerialName("device_pub") val publicKey: String
)

@Serializable
data class RevokeDevicePayload(
    val op: String = "revoke-device",
    @SerialName("device_id") val deviceId: String
)

@Serializable
data class AssignProfilePayload(
    val op: String = "assign-profile",
    @SerialName("user_id") val userId: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("server_id") val serverId: String,
    val transport: String = "vyandex",
    @SerialName("url") val documentUrl: String? = null,
    val parameters: Map<String, String>? = null
)

@Serializable
data class RevokeAssignmentPayload(
    val op: String = "revoke-assignment",
    @SerialName("assignment_id") val assignmentId: String
)

@Serializable
data class IssueInvitationPayload(
    val op: String = "issue-invitation",
    @SerialName("assignment_id") val assignmentId: String
)

@Serializable
data class AuthSession(
    @SerialName("auth_session_id") val id: String,
    @SerialName("target_id") val targetId: String = "",
    @SerialName("profile_id") val profileId: String = "",
    @SerialName("document_url") val documentUrl: String = "",
    @SerialName("admin_device_id") val adminDeviceId: String = "",
    val nonce: String = "",
    @SerialName("allowed_origins") val allowedOrigins: List<String> = emptyList(),
    @SerialName("delivery_methods") val deliveryMethods: List<String> = emptyList(),
    @SerialName("server_pub") val serverPub: String = "",
    @SerialName("server_fingerprint") val serverFingerprint: String = "",
    @SerialName("current_generation") val currentGeneration: Long = 0L,
    val status: String = "pending",
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null
) {
    val isPending: Boolean get() = status.equals("pending", ignoreCase = true)
    val isApplied: Boolean get() = status.equals("applied", ignoreCase = true)
    val isFailed: Boolean get() = status.equals("failed", ignoreCase = true)
    val isExpired: Boolean get() = status.equals("expired", ignoreCase = true)
}

@Serializable
data class AuthBundlePlaintext(
    val version: Int = 1,
    @SerialName("target_id") val targetId: String,
    @SerialName("profile_id") val profileId: String,
    @SerialName("auth_session_id") val authSessionId: String,
    val generation: Long = 0L,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis() / 1000L,
    val nonce: String,
    @SerialName("user_agent") val userAgent: String,
    val origins: Map<String, String>,
    @SerialName("content_hash") val contentHash: String = ""
) {
    fun computeContentHash(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(targetId.toByteArray(Charsets.UTF_8))
        md.update(profileId.toByteArray(Charsets.UTF_8))
        md.update(authSessionId.toByteArray(Charsets.UTF_8))
        md.update(nonce.toByteArray(Charsets.UTF_8))
        md.update(userAgent.toByteArray(Charsets.UTF_8))
        for (origin in origins.keys.sorted()) {
            md.update(origin.toByteArray(Charsets.UTF_8))
            md.update((origins[origin] ?: "").toByteArray(Charsets.UTF_8))
        }
        val digest = md.digest()
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}

@Serializable
data class AuthIncident(
    @SerialName("incident_id") val incidentId: String,
    @SerialName("target_id") val targetId: String = "",
    @SerialName("profile_id") val profileId: String = "",
    val code: String,
    val severity: String = "warning",
    @SerialName("first_seen") val firstSeen: String? = null,
    @SerialName("last_seen") val lastSeen: String? = null,
    @SerialName("repeat_count") val repeatCount: Int = 1,
    @SerialName("auth_generation") val authGeneration: Long = 0L,
    val status: String = "open",
    val description: String = "",
    @SerialName("action_required") val actionRequired: String = ""
) {
    val isOpen: Boolean get() = status.equals("open", ignoreCase = true)
    val isAcknowledged: Boolean get() = status.equals("acknowledged", ignoreCase = true)
    val isResolved: Boolean get() = status.equals("resolved", ignoreCase = true)
}

@Serializable
data class BeginAuthSessionPayload(
      val op: String = "begin-auth-session",
      @SerialName("target_id") val targetId: String,
      @SerialName("profile_id") val profileId: String,
      val url: String = ""
)

@Serializable
data class GetAuthSessionStatusPayload(
    val op: String = "get-auth-session-status",
    @SerialName("auth_session_id") val authSessionId: String
)

@Serializable
data class AuthImportPayload(
    val op: String = "auth-import",
    @SerialName("auth_session_id") val authSessionId: String,
    @SerialName("ephemeral_pub") val ephemeralPub: String,
    val nonce: String,
    @SerialName("encrypted_payload") val encryptedPayload: String,
    @SerialName("content_hash") val contentHash: String
)

@Serializable
data class ListAuthIncidentsPayload(
    val op: String = "list-auth-incidents",
    @SerialName("profile_id") val profileId: String? = null
)

@Serializable
data class AckAuthIncidentPayload(
    val op: String = "ack-auth-incident",
    @SerialName("incident_id") val incidentId: String
)

@Serializable
data class AdminAccessMethod(
    val transport: String,
    @SerialName("endpoint_required") val endpointRequired: Boolean = true
)

/** Durable per-protocol installation intent. The operation ID is reused after
 * process death so an SSH command is never submitted twice with a new profile. */
@Serializable
data class ProtocolInstallationOperation(
    val operationId: String,
    val serverId: String,
    val transport: String,
    val profileId: String,
    val endpoint: String = "",
    val stage: String = PLANNED,
    val error: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val PLANNED = "planned"
        const val SUBMITTING = "submitting"
        const val UNKNOWN = "unknown"
        const val NEEDS_ACTION = "needs_action"
        const val READY = "ready"
        const val FAILED = "failed"
    }
    val requiresReconciliation: Boolean get() = stage == SUBMITTING || stage == UNKNOWN
}

@Serializable
data class AdministratorRecord(
    @SerialName("device_id") val deviceId: String,
    @SerialName("public_key") val publicKey: String = "",
    val role: String,
    @SerialName("profile_id") val profileId: String = "",
    val status: String = "active",
    @SerialName("created_at") val createdAt: String = "",
    val expiry: String = "",
    @SerialName("expires_at") val expiresAt: Long = 0L
)

@Serializable
data class AdminClusterSnapshot(
    @SerialName("core_info") val coreInfo: CoreCompatibility? = null,
    @SerialName("access_methods") val accessMethods: Map<String, List<AdminAccessMethod>> = emptyMap(),
    @SerialName("snapshot_revision") val snapshotRevision: Long = 1L,
    @SerialName("authority_id") val authorityId: String = "",
    val timestamp: Long = 0L,
    @SerialName("role_scope") val roleScope: String = "",
    @SerialName("is_complete") val isComplete: Boolean = true,
    val users: List<AdminUser> = emptyList(),
    val devices: List<AdminDevice> = emptyList(),
    val assignments: List<AdminAssignment> = emptyList(),
    val profiles: List<ServerManagedProfile> = emptyList(),
    val servers: List<String> = emptyList(),
    @SerialName("per_server_status") val perServerStatus: Map<String, AdminWorkerStatus> = emptyMap(),
    @SerialName("worker_statuses") val workerStatuses: List<AdminWorkerStatus> = emptyList(),
    @SerialName("snapshot_hash_version") val snapshotHashVersion: Int = 2,
    @SerialName("snapshot_hash") val snapshotHash: String = ""
) {
    val revision: Long get() = snapshotRevision
    fun methodsFor(serverId: String): List<AdminAccessMethod> =
        accessMethods[serverId] ?: profiles.filter { it.serverId == serverId && it.status == "ready" }
            .filter { it.transport.isNotBlank() }.map { AdminAccessMethod(it.transport) }.distinctBy { it.transport }
    val distinctServerIds: Set<String> get() {
        if (servers.isNotEmpty()) return servers.toSet()
        val set = mutableSetOf<String>()
        profiles.forEach { if (it.serverId.isNotBlank()) set.add(it.serverId) }
        workerStatuses.forEach { if (it.serverId.isNotBlank()) set.add(it.serverId) }
        return if (set.isNotEmpty()) set else perServerStatus.keys
    }
}

/** A durable, typed result for automatic access issuance. */
@Serializable
enum class AccessOperationState {
    IDLE, SUBMITTING, CONFIRMED, PARTIAL, FAILED, UNKNOWN
}

@Serializable
data class AccessRouteResult(
    @SerialName("server_id") val serverId: String,
    val transport: String,
    val state: String = "pending", // created, existing, skipped, failed, pending
    val reason: String? = null
)

@Serializable
data class AccessOperationRecord(
    @SerialName("operation_id") val operationId: String,
    @SerialName("user_id") val userId: String,
    val state: AccessOperationState = AccessOperationState.SUBMITTING,
    val routes: List<AccessRouteResult> = emptyList(),
    val error: String? = null,
    @SerialName("created_at") val createdAt: Long = System.currentTimeMillis(),
    @SerialName("updated_at") val updatedAt: Long = System.currentTimeMillis()
)

fun computeClusterSnapshotHash(snapshot: AdminClusterSnapshot): String = computeClusterSnapshotHash(
    kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToJsonElement(AdminClusterSnapshot.serializer(), snapshot).let {
        it as kotlinx.serialization.json.JsonObject
    }
)

@Serializable
data class GetClusterSnapshotPayload(
    val op: String = "get-cluster-snapshot"
)

@Serializable
data class ReconcileAssignmentsPayload(
    val op: String = "reconcile-assignments"
)


