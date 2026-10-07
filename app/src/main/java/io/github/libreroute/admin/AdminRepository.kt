package io.github.libreroute.admin

import android.content.Context
import android.content.Intent
import kotlinx.serialization.json.longOrNull
import io.github.libreroute.data.Tunnel
import io.github.libreroute.data.TunnelRepository
import io.github.libreroute.event.AppEvent
import io.github.libreroute.event.EventBus
import io.github.libreroute.util.Logx
import io.github.libreroute.util.SecurePreferences
import io.github.libreroute.util.JitsiTokenStore
import io.github.libreroute.util.TunnelLinkParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface AdminStorage {
    fun getString(key: String, default: String?): String?
    fun putString(key: String, value: String?)
    fun remove(key: String)
    fun getLong(key: String, default: Long): Long = getString(key, null)?.toLongOrNull() ?: default
    fun putLong(key: String, value: Long) = putString(key, value.toString())
}

class SecurePrefsAdminStorage(private val prefs: SecurePreferences) : AdminStorage {
    override fun getString(key: String, default: String?): String? = prefs.getString(key, default)
    override fun putString(key: String, value: String?) = prefs.putString(key, value)
    override fun remove(key: String) = prefs.remove(key)
}

class InMemoryAdminStorage : AdminStorage {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String, default: String?): String? = map[key] ?: default
    override fun putString(key: String, value: String?) {
        if (value == null) map.remove(key) else map[key] = value
    }
    override fun remove(key: String) { map.remove(key) }
}

/**
 * Repository for administrative state, keys, role capabilities, managed profiles, and command history.
 *
 * CORE INVARIANT:
 * Possession of an LibreRoute VPN profile NEVER grants administrative rights on its own!
 * Role remains strictly NONE until an authentic, unexpired, and server-signed ADMIN_GRANT
 * has been verified against the pinned server public key.
 */
class AdminRepository(
    private val context: Context? = null,
    private val keyManager: AdminKeyManager = AdminKeyManager(context),
    private val sshClient: AdminSshClient = AdminSshClient(context ?: createDummyContext()),
    private val storage: AdminStorage = if (context != null) SecurePrefsAdminStorage(SecurePreferences(context, PREFS_NAME)) else InMemoryAdminStorage(),
    private val socketProbe: ((host: String, port: Int, timeoutMs: Int) -> Boolean)? = null,
    private val combinationProber: ((server: ServerNode, protocol: String, documentUrl: String?, timeoutMs: Int) -> ProbeResult)? = null,
    private val customTunnelStore: io.github.libreroute.data.TunnelStore? = null,
    private val vpnStopper: ((Context?) -> Unit)? = null,
    private val documentSyncRunner: ((docUrl: String, profileId: String, secretKey: String, env: AdminEnvelope, serverKey: String) -> AdminEnvelope)? = null,
    private val currentTimeSeconds: () -> Long = { System.currentTimeMillis() / 1000L },
    private val installationBackend: ServerInstallationBackend? = null,
    private val serverInventoryProbe: ((ServerNode) -> ServerInventory)? = null
) {

    companion object {
        fun createDummyContext(): Context {
            val appInfo = android.content.pm.ApplicationInfo().apply { nativeLibraryDir = "/data/app/lib" }
            return object : android.content.ContextWrapper(null) {
                override fun getApplicationInfo(): android.content.pm.ApplicationInfo = appInfo
                override fun getFilesDir(): java.io.File = java.io.File(System.getProperty("java.io.tmpdir") ?: ".")
            }
        }
        private const val TAG = "AdminRepository"
        private const val PREFS_NAME = "libreroute_admin_storage"

        private const val KEY_ROLE = "admin_role"
        private const val KEY_ACTIVE_GRANT = "admin_active_grant"
        private const val KEY_ACTIVE_GRANT_ENVELOPE = "admin_active_grant_envelope"
        private const val KEY_PINNED_SERVER_KEY = "admin_pinned_server_key"
        private const val KEY_PINNED_SERVER_KEY_PREFIX = "admin_pinned_server_key_"
        private const val KEY_ACTIVE_GRANT_PREFIX = "admin_active_grant_"
        private const val KEY_ACTIVE_GRANT_ENVELOPE_PREFIX = "admin_active_grant_envelope_"
        private const val KEY_MANAGED_PROFILES = "admin_managed_profiles"
        private const val KEY_COMMAND_HISTORY = "admin_command_history"
        private const val KEY_PENDING_REQUEST = "admin_pending_join_request"
        private const val KEY_LAST_PAIRING_CODE = "admin_last_pairing_code"
		private const val KEY_DOCUMENT_SESSION_PREFIX = "admin_document_session_"
        private const val KEY_SERVER_NODES = "admin_server_nodes"
        private const val KEY_ADMIN_SSH_KEY_PATH = "admin_ssh_key_path"
        private const val KEY_PROBLEM_REPORTS = "admin_problem_reports"
        private const val KEY_PENDING_USER_DOCUMENTS = "admin_pending_user_documents"
        private const val KEY_SYNC_DEVICE_ID = "sync_device_id"
        private const val KEY_SYNC_USER_ID = "sync_user_id"
        private const val KEY_SYNC_PROFILE_ID = "sync_profile_id"
        private const val KEY_SYNC_DOC_URL = "sync_doc_url"
        private const val KEY_SYNC_SECRET_KEY = "sync_secret_key"
        private const val KEY_SYNC_REVISION = "sync_revision"
        private const val KEY_SYNC_TRUSTED_SERVER_KEY = "sync_trusted_server_key"
        private const val KEY_SYNC_LAST_CHECKED_AT = "sync_last_checked_at"
        private const val KEY_SYNC_ROUTES_JSON = "sync_routes_json"
        private const val KEY_MANAGED_TUNNEL_IDS = "sync_managed_tunnel_ids"
        private const val KEY_SYNC_CONTEXTS = "client_sync_contexts"
        private const val KEY_ACTIVE_SYNC_CONTEXT = "client_active_sync_context"
        private const val KEY_ACCESS_OPERATION = "admin_access_operation"
        private const val KEY_INSTALL_OPERATIONS = "admin_install_operations"
        private const val KEY_PROTOCOL_OPERATIONS = "admin_protocol_install_operations"
        private const val KEY_REVOKED_SERVER_IDS = "admin_locally_revoked_servers"
        private const val KEY_PENDING_ADMIN_CONTEXT = "admin_pending_context"
        private const val KEY_DELEGATION_CHECKPOINT = "admin_delegation_checkpoint"
        private const val KEY_OWNER_TRANSFER_PREFIX = "admin_owner_transfer_"
        private const val KEY_SNAPSHOT_RECEIPT_PREFIX = "admin_snapshot_receipt_"
        private const val KEY_REDEMPTION_PREFIX = "client_redemption_checkpoint_"

        @Volatile
        private var instance: AdminRepository? = null

        fun getInstance(context: Context): AdminRepository {
            return instance ?: synchronized(this) {
                instance ?: AdminRepository(context.applicationContext).also { instance = it }
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /** Persist the latest access operation so rotation/process recreation can show its result. */
    fun saveAccessOperation(record: AccessOperationRecord) {
        storage.putString(KEY_ACCESS_OPERATION, json.encodeToString(record))
    }

    fun loadAccessOperation(): AccessOperationRecord? = storage.getString(KEY_ACCESS_OPERATION, null)
        ?.let { raw -> runCatching { json.decodeFromString<AccessOperationRecord>(raw) }.getOrNull() }

    fun clearAccessOperation() = storage.remove(KEY_ACCESS_OPERATION)

    private val _roleFlow = MutableStateFlow(AdminRole.NONE)
    val roleFlow: StateFlow<AdminRole> = _roleFlow.asStateFlow()

    private val _activeGrantFlow = MutableStateFlow<AdminGrant?>(null)
    val activeGrantFlow: StateFlow<AdminGrant?> = _activeGrantFlow.asStateFlow()

    private val _managedProfilesFlow = MutableStateFlow<List<ManagedProfile>>(emptyList())
    val managedProfilesFlow: StateFlow<List<ManagedProfile>> = _managedProfilesFlow.asStateFlow()

    private val _commandHistoryFlow = MutableStateFlow<List<AdminCommandRecord>>(emptyList())
    val commandHistoryFlow: StateFlow<List<AdminCommandRecord>> = _commandHistoryFlow.asStateFlow()

    private val _pendingRequestFlow = MutableStateFlow<AdminEnvelope?>(null)
    val pendingRequestFlow: StateFlow<AdminEnvelope?> = _pendingRequestFlow.asStateFlow()

    private val _lastPairingCodeFlow = MutableStateFlow<String?>(null)
    val lastPairingCodeFlow: StateFlow<String?> = _lastPairingCodeFlow.asStateFlow()

    private val _serverNodesFlow = MutableStateFlow<List<ServerNode>>(emptyList())
    val serverNodesFlow: StateFlow<List<ServerNode>> = _serverNodesFlow.asStateFlow()

    private val _problemReportsFlow = MutableStateFlow<List<ProblemReport>>(emptyList())
    val problemReportsFlow: StateFlow<List<ProblemReport>> = _problemReportsFlow.asStateFlow()

    private val _clusterSnapshotFlow = MutableStateFlow<AdminClusterSnapshot?>(null)
    val clusterSnapshotFlow: StateFlow<AdminClusterSnapshot?> = _clusterSnapshotFlow.asStateFlow()

    private val _installationOperations = MutableStateFlow<List<ServerInstallOperation>>(emptyList())
    val installationOperations: StateFlow<List<ServerInstallOperation>> = _installationOperations.asStateFlow()

    private val _protocolOperations = MutableStateFlow<List<ProtocolInstallationOperation>>(emptyList())
    val protocolOperations: StateFlow<List<ProtocolInstallationOperation>> = _protocolOperations.asStateFlow()

    private fun installationClient(): ServerInstallationBackend = installationBackend ?: object : ServerInstallationBackend {
        private val installer = ServerInstaller(requireNotNull(context))
        override fun plan(node: ServerNode, inventory: ServerInventory, operationId: String) = installer.plan(node, inventory, operationId)
        override fun install(node: ServerNode, inventory: ServerInventory, operationId: String, documentUrl: String,
                             join: AdminEnvelope, pairingCode: String, expectedPlan: kotlinx.serialization.json.JsonObject,
                             ownerRecovery: Boolean) =
            installer.install(node, inventory, operationId, documentUrl, join, pairingCode, expectedPlan, ownerRecovery)
        override fun status(node: ServerNode, operationId: String) = installer.status(node, operationId)
        override fun rollback(node: ServerNode, operationId: String) = installer.rollback(node, operationId)
        override fun recoverOwner(node: ServerNode, inventory: ServerInventory, operationId: String,
                                  documentUrl: String, join: AdminEnvelope, pairingCode: String,
                                  expectedPlan: kotlinx.serialization.json.JsonObject) =
            installer.recoverOwner(node, inventory, operationId, documentUrl, join, pairingCode, expectedPlan)
    }


    private val sshCredentialStorage: AdminStorage by lazy {
        if (context != null) SecurePrefsAdminStorage(io.github.libreroute.util.SecurePreferences(context, "admin_ssh_credentials")) else storage
    }

    private fun storeNodeCredentials(node: ServerNode): ServerNode {
        val ref = node.credentialRef ?: "ssh_" + node.id
        if (node.password != null) sshCredentialStorage.putString(ref + "_password", node.password)
        if (node.passphrase != null) sshCredentialStorage.putString(ref + "_passphrase", node.passphrase)
        return node.copy(credentialRef = ref)
    }

    private fun restoreNodeCredentials(node: ServerNode): ServerNode {
        val ref = node.credentialRef ?: return node
        return node.copy(password = sshCredentialStorage.getString(ref + "_password", null),
            passphrase = sshCredentialStorage.getString(ref + "_passphrase", null))
    }

    init { loadState() }

    @Synchronized
    private fun loadState() {
        // 1. Pinned server key
        val pinnedKey = storage.getString(KEY_PINNED_SERVER_KEY, null)

        // 2. Load active grant
        val grantJson = storage.getString(KEY_ACTIVE_GRANT, null)
        val grant = grantJson?.let { runCatching { json.decodeFromString<AdminGrant>(it) }.getOrNull() }
        val grantEnvelope = storage.getString(KEY_ACTIVE_GRANT_ENVELOPE, null)
            ?.let { runCatching { json.decodeFromString<AdminEnvelope>(it) }.getOrNull() }

        // 3. Load pending request
        val reqJson = storage.getString(KEY_PENDING_REQUEST, null)
        val pendingReq = reqJson?.let { runCatching { json.decodeFromString<AdminEnvelope>(it) }.getOrNull() }
        _pendingRequestFlow.value = pendingReq
        _lastPairingCodeFlow.value = storage.getString(KEY_LAST_PAIRING_CODE, null)

        // 4. Invariant check: Verify grant validity and expiration
        val nowSec = currentTimeSeconds()
        val isValidGrant = grant != null && grantEnvelope != null && pinnedKey != null &&
            grant.expiresAt > nowSec && grant.role in listOf(AdminRole.OWNER, AdminRole.OPERATOR, AdminRole.VIEWER) &&
            validGrantFields(grantEnvelope, grant, pinnedKey) &&
            keyManager.verifyServerResponse(grantEnvelope, pinnedKey, pinnedKey)

        if (isValidGrant) {
            _activeGrantFlow.value = grant
            _roleFlow.value = grant.role
        } else if (pendingReq != null && pendingReq.exp > nowSec) {
            _activeGrantFlow.value = null
            _roleFlow.value = AdminRole.PENDING
        } else {
            _activeGrantFlow.value = null
            _roleFlow.value = AdminRole.NONE
        }

        // 5. Load managed profiles
        val profilesJson = storage.getString(KEY_MANAGED_PROFILES, null)
        val profiles = profilesJson?.let {
            runCatching { json.decodeFromString<List<ManagedProfile>>(it) }.getOrNull()
        }.orEmpty()
        _managedProfilesFlow.value = profiles

        // 6. Load command history
        val historyJson = storage.getString(KEY_COMMAND_HISTORY, null)
        val history = historyJson?.let {
            runCatching { json.decodeFromString<List<AdminCommandRecord>>(it) }.getOrNull()
        }.orEmpty()
        _commandHistoryFlow.value = history

        // 7. Load managed cluster server nodes
        val nodesJson = storage.getString(KEY_SERVER_NODES, null)
        val nodes = nodesJson?.let {
            runCatching {
                json.parseToJsonElement(it).let { array ->
                    (array as kotlinx.serialization.json.JsonArray).map { raw ->
                        val node = json.decodeFromJsonElement<ServerNode>(raw)
                        val migrated = node.copy(password = raw.jsonObject["password"]?.jsonPrimitive?.content?.takeUnless { it == "null" },
                            passphrase = raw.jsonObject["passphrase"]?.jsonPrimitive?.content?.takeUnless { it == "null" })
                        restoreNodeCredentials(storeNodeCredentials(migrated))
                    }
                }
            }.getOrNull()
        }.orEmpty()
        _serverNodesFlow.value = nodes
        if (nodes.isNotEmpty()) saveServerNodes(nodes)
        // Persist an explicit identity pin for legacy single-node installations.
        // Future expiry/removal of a capability must not make cached snapshots untrusted.
        if (nodes.size == 1 && pinnedKey != null && legacyGrantBelongsTo(nodes.single().id)) {
            storage.putString(KEY_PINNED_SERVER_KEY_PREFIX + nodes.single().id, pinnedKey)
        }

        // Repair installations created before local bootstrap profiles existed.
        for (node in nodes) {
            val scopedGrant = verifiedGrantForServer(node.id)
            if (scopedGrant != null) {
                val session = storage.getString(KEY_DOCUMENT_SESSION_PREFIX + node.id, null)
                    ?.let { runCatching { json.decodeFromString<DocumentSessionKeys>(it) }.getOrNull() }
                val documentUrl = session?.documentUrl.orEmpty()
                ensureOwnerControlProfile(node, scopedGrant, documentUrl)
            }
        }

        if (!isValidGrant) {
            for (node in nodes) {
                val scopedGrant = verifiedGrantForServer(node.id) ?: continue
                _activeGrantFlow.value = scopedGrant
                _roleFlow.value = scopedGrant.role
                break
            }
        }

        // A valid OWNER grant is capability metadata; it must always have a
        // matching local control profile. Older installations could have a
        // grant whose server id was missing or a node id that was migrated,
        // leaving the admin with access but no visible profile. Repair the
        // single connected node deterministically on every startup.
        val ownerGrant = _activeGrantFlow.value
        if (ownerGrant != null) {
            val ownerNode = nodes.firstOrNull { it.id == ownerGrant.serverId }
                ?: nodes.singleOrNull()?.takeIf { ownerGrant.serverId.isNullOrBlank() && legacyGrantBelongsTo(it.id) }
            if (ownerNode != null) {
                val session = storage.getString(KEY_DOCUMENT_SESSION_PREFIX + ownerNode.id, null)
                    ?.let { runCatching { json.decodeFromString<DocumentSessionKeys>(it) }.getOrNull() }
                val documentUrl = session?.documentUrl.orEmpty()
                ensureOwnerControlProfile(ownerNode, ownerGrant, documentUrl)
            }
        }

        // 8. Load diagnostic problem reports
        val reportsJson = storage.getString(KEY_PROBLEM_REPORTS, null)
        val reports = reportsJson?.let {
            runCatching { json.decodeFromString<List<ProblemReport>>(it) }.getOrNull()
        }.orEmpty()
        _problemReportsFlow.value = reports
        if (nodes.size == 1) _clusterSnapshotFlow.value = cachedClusterSnapshot(nodes.single().id)

        val operations = storage.getString(KEY_INSTALL_OPERATIONS, null)?.let {
            runCatching { json.decodeFromString<List<ServerInstallOperation>>(it) }.getOrNull()
        }.orEmpty()
        _installationOperations.value = operations.map {
            if (it.stage == ServerInstallOperation.SUBMITTING) it.copy(stage = ServerInstallOperation.UNKNOWN,
                error = "Результат установки неизвестен. Проверьте сохранённую операцию через SSH") else it
        }
        persistInstallationOperations()
        _protocolOperations.value = storage.getString(KEY_PROTOCOL_OPERATIONS, null)?.let {
            runCatching { json.decodeFromString<List<ProtocolInstallationOperation>>(it) }.getOrNull()
        }.orEmpty().map {
            if (it.stage == ProtocolInstallationOperation.SUBMITTING) it.copy(
                stage = ProtocolInstallationOperation.UNKNOWN,
                error = "Результат установки протокола неизвестен. Проверим сервер") else it
        }
        persistProtocolOperations()
        // A receipt is written before the individual local projections. Replay only
        // Replay the separately stored node/pin after a reviewed identity binding.
        _installationOperations.value.filter { it.identityBindingConfirmed && it.stage !in
            setOf(ServerInstallOperation.READY, ServerInstallOperation.ROLLED_BACK) }.forEach { operation -> runCatching {
            val plan = json.parseToJsonElement(operation.expectedPlan).jsonObject
            val key = plan.getValue("server_public_key").jsonPrimitive.content
            require(plan["server_id"]?.jsonPrimitive?.content == operation.serverId &&
                plan["namespace"]?.jsonPrimitive?.content == operation.namespace)
            val nodesNow = _serverNodesFlow.value
            if (nodesNow.none { it.id == operation.serverId }) {
                val restored = nodesNow.filterNot { it.id == operation.priorLocalNodeId } + restoreNodeCredentials(operation.node)
                saveServerNodes(restored)
                _serverNodesFlow.value = restored
            }
            if (getPinnedServerPublicKey(operation.serverId) == null) setPinnedServerPublicKey(key, operation.serverId)
        } }
        // receipts that were already validated from the authenticated SSH session.
        _installationOperations.value.filter { it.stage == ServerInstallOperation.SERVER_CONFIRMED && it.receipt != null }
            .forEach { operation -> runCatching {
                reconcileInstallationReceipt(operation, json.parseToJsonElement(operation.receipt!!).jsonObject)
            } }
        storage.getString(KEY_DELEGATION_CHECKPOINT, null)?.let { raw -> runCatching {
            val checkpoint = json.parseToJsonElement(raw).jsonObject
            val request = AdminEnvelope.fromJson(checkpoint.getValue("request").toString()) ?: error("Нет запроса")
            _pendingRequestFlow.value = request
            storage.putString(KEY_PENDING_REQUEST, request.toJson())
            importAdminGrantLink(checkpoint.getValue("link").jsonPrimitive.content)
        } }

        Logx.i(TAG, "Admin state initialized: role=${_roleFlow.value}, profiles=${profiles.size}, servers=${nodes.size}, reports=${reports.size}, pinnedKey=${pinnedKey != null}")
    }

    fun getServerNodes(): List<ServerNode> = _serverNodesFlow.value

    /** Explicitly binds a locally saved SSH endpoint to the server identity returned by a reviewed SSH plan. */
    fun bindExistingServerIdentity(localNodeId: String, confirmedServerId: String,
                                   confirmedNamespace: String, confirmedServerPublicKey: String): Result<ServerNode> = runCatching {
        require(confirmedServerId.isNotBlank() && confirmedNamespace.isNotBlank() && confirmedServerPublicKey.isNotBlank()) {
            "Сверьте server_id, namespace и публичный ключ сервера перед привязкой"
        }
        val old = getServerNode(localNodeId) ?: error("SSH-узел не найден")
        val reviewed = _installationOperations.value.lastOrNull { operation ->
            operation.serverId == localNodeId && operation.stage == ServerInstallOperation.PLANNED &&
                runCatching {
                    val plan = json.parseToJsonElement(operation.expectedPlan).jsonObject
                    plan["server_id"]?.jsonPrimitive?.content == confirmedServerId &&
                        plan["namespace"]?.jsonPrimitive?.content == confirmedNamespace &&
                        plan["server_public_key"]?.jsonPrimitive?.content == confirmedServerPublicKey
                }.getOrDefault(false)
        } ?: error("Идентичность должна совпадать с сохранённым планом, полученным через доверенный SSH")
        require(reviewed.node.host == old.host && reviewed.node.port == old.port &&
            reviewed.node.hostPublicKey == old.hostPublicKey) { "SSH-подключение изменилось после проверки плана" }
        if (localNodeId == confirmedServerId) {
            val existingPin = getPinnedServerPublicKey(confirmedServerId)
            require(existingPin == null || existingPin == confirmedServerPublicKey) { "Ключ сервера изменился" }
            setPinnedServerPublicKey(confirmedServerPublicKey, confirmedServerId)
            return@runCatching old
        }
        require(old.managementNamespace.isNullOrBlank() || old.managementNamespace == confirmedNamespace) {
            "Namespace SSH-узла не совпадает с подтверждённым сервером"
        }
        val collision = getServerNode(confirmedServerId)
        require(collision == null || collision.host == old.host && collision.port == old.port) {
            "Подтверждённый server_id уже связан с другим SSH-адресом"
        }
        val migrated = old.copy(id = confirmedServerId, managementNamespace = confirmedNamespace)
        val nodes = _serverNodesFlow.value.filterNot { it.id == localNodeId || it.id == confirmedServerId } + migrated
        val oldRef = old.credentialRef ?: "ssh_$localNodeId"
        val newRef = migrated.credentialRef ?: "ssh_$confirmedServerId"
        for (suffix in listOf("_password", "_passphrase")) {
            sshCredentialStorage.getString(oldRef + suffix, null)?.let { sshCredentialStorage.putString(newRef + suffix, it) }
        }
        val operations = _installationOperations.value.map { operation ->
            if (operation.serverId != localNodeId) operation else operation.copy(
                serverId = confirmedServerId, namespace = confirmedNamespace, node = migrated,
                identityBindingConfirmed = true, priorLocalNodeId = localNodeId,
                profileId = operation.expectedPlan.let { raw ->
                    runCatching { json.parseToJsonElement(raw).jsonObject["control_profile_id"]?.jsonPrimitive?.content }
                        .getOrNull()?.takeIf { it.isNotBlank() } ?: operation.profileId
                }, updatedAt = System.currentTimeMillis())
        }
        _installationOperations.value = operations
        persistInstallationOperations()
        setPinnedServerPublicKey(confirmedServerPublicKey, confirmedServerId)
        saveServerNodes(nodes)
        _serverNodesFlow.value = nodes
        migrated
    }

    /** Only an explicit control binding is a preset; VPN names and counts prove nothing. */
    fun defaultDocumentUrl(serverId: String): String? =
        _managedProfilesFlow.value.firstOrNull {
            it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL && it.serverId == serverId
        }?.serverConfigs?.get(serverId)?.documentUrl?.takeIf { !it.isNullOrBlank() }
            ?: storage.getString(KEY_DOCUMENT_SESSION_PREFIX + serverId, null)?.let {
                runCatching { json.decodeFromString<DocumentSessionKeys>(it).documentUrl }.getOrNull()
            }?.takeIf { it.isNotBlank() }

    fun planServerInstallation(node: ServerNode, operationId: String): kotlinx.serialization.json.JsonObject {
        val inventory = probeServer(node)
        require(inventory.probeError == null) { "SSH-опрос не завершён" }
        val saved = getServerNode(node.id) ?: node
        val prior = loadServerInstallation(operationId)
        if (prior != null) {
            require(prior.serverId == node.id && prior.stage == ServerInstallOperation.PLANNED) {
                "Предыдущую операцию необходимо проверить; повторная установка запрещена"
            }
            return json.parseToJsonElement(prior.expectedPlan).jsonObject
        }
        val installer = installationClient()
        val plan = installer.plan(saved, inventory, operationId)
        val namespace = plan["namespace"]?.jsonPrimitive?.content
            ?: saved.managementNamespace ?: ("node-" + saved.id.replace("-", "").take(32))
        // An update may report a durable server_id different from the temporary
        // local node id. Persist the reviewed plan first; UI must explicitly call
        // bindExistingServerIdentity before any mutating action.
        val profile = plan["control_profile_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
            ?: getActiveGrant(saved.id)?.profileId ?: ("control-" + namespace)
        saveServerInstallation(ServerInstallOperation(operationId, saved.id, namespace, saved,
            plan.toString(), plan["plan_hash"]?.jsonPrimitive?.content ?: error("План не содержит контрольной суммы"),
            profile, keyManager.getFingerprint(),
            previousGrantEnvelope = storage.getString(KEY_ACTIVE_GRANT_ENVELOPE_PREFIX + saved.id, null),
            previousDocumentSession = storage.getString(documentSessionKey(profile, saved.id), null)
                ?: storage.getString(KEY_DOCUMENT_SESSION_PREFIX + saved.id, null)))
        return plan
    }

    /** Updating a known managed node requires its current OWNER and preserves its identity. */
    fun planCoreUpdate(node: ServerNode, operationId: String): kotlinx.serialization.json.JsonObject {
        require(getRole(node.id) == AdminRole.OWNER) { "Для обновления ядра нужны действующие права владельца сервера" }
        require(latestServerInstallation(node.id)?.requiresReconciliation != true) { "Сначала проверьте результат предыдущей операции" }
        val pinned = getPinnedServerPublicKey(node.id) ?: error("Ключ сервера не закреплён")
        val plan = planServerInstallation(node, operationId)
        require(plan["mode"]?.jsonPrimitive?.content == "update") { "Обновление допустимо только для существующей установки" }
        require(plan["server_id"]?.jsonPrimitive?.content == node.id &&
            plan["server_public_key"]?.jsonPrimitive?.content == pinned &&
            plan["control_profile_id"]?.jsonPrimitive?.content == getActiveGrant(node.id)?.profileId) {
            "План обновления не совпадает с подтверждённой идентичностью сервера"
        }
        val operation = loadServerInstallation(operationId) ?: error("План обновления не сохранён")
        saveServerInstallation(operation.copy(kind = ServerInstallOperation.KIND_CORE_UPDATE))
        return plan
    }

    private val recipientIdentityStorage: InvitationStateStore by lazy {
        if (context != null) SecureInvitationStateStore(context) else object : InvitationStateStore {
            override fun get(key: String): String? = storage.getString("recipient_identity_$key", null)
            override fun put(key: String, value: String) = storage.putString("recipient_identity_$key", value)
        }
    }

    private fun invitationHash(link: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(link.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun redemptionCheckpoint(link: String): ClientRedemptionCheckpoint? =
        storage.getString(KEY_REDEMPTION_PREFIX + invitationHash(link), null)?.let {
            runCatching { json.decodeFromString<ClientRedemptionCheckpoint>(it) }.getOrNull()
        }

    private fun saveRedemptionCheckpoint(record: ClientRedemptionCheckpoint) {
        storage.putString(KEY_REDEMPTION_PREFIX + record.invitationHash, json.encodeToString(record))
    }

    private fun completedRedemption(link: String): Pair<AdminOpResult, Tunnel?>? {
        return try {
        val record = redemptionCheckpoint(link)?.takeIf { it.completed } ?: return null
        val receipt = record.receipt?.let(AdminEnvelope::fromJson) ?: return null
        require(keyManager.verifyServerResponse(receipt, record.serverKey, record.serverKey)) { "Сохранённая квитанция недействительна" }
        val store = customTunnelStore ?: context?.let { TunnelRepository(it) }
        val current = store?.load().orEmpty()
        val ids = record.tunnels.map { it.id }.toSet()
        _lastRedeemedTunnels = current.filter { it.id in ids }
        return Pair(AdminOpResult.Success("Приглашение уже подтверждено; сохранённые маршруты сохранены", receipt),
            _lastRedeemedTunnels.firstOrNull())
        } catch (e: Exception) { Pair(AdminOpResult.Failure(e.message ?: "Сохранённая квитанция не проверена", e), null) }
    }

    private fun resumeRedemption(link: String, crypto: RecipientInvitationCrypto): Pair<AdminOpResult, Tunnel?>? {
        return try {
        completedRedemption(link)?.let { return it }
        val record = redemptionCheckpoint(link) ?: return null
        val candidate = record.candidate ?: return null
        val receipt = record.receipt?.let(AdminEnvelope::fromJson) ?: return null
        val request = AdminEnvelope.fromJson(record.request) ?: error("Нет сохранённого запроса погашения")
        require(receipt.opId == request.opId && receipt.profileId == request.profileId &&
            keyManager.verifyServerResponse(receipt, record.serverKey, record.serverKey) &&
            request.sender == crypto.publicKeyHashHex()) { "Сохранённая квитанция относится к другому устройству или запросу" }
        val activated = activateVerifiedCandidate(candidate, crypto, record.serverKey, receipt, record.tunnels)
        if (activated.first is AdminOpResult.Success) saveRedemptionCheckpoint(record.copy(completed = true))
        return activated
        } catch (e: Exception) { Pair(AdminOpResult.Failure(e.message ?: "Не удалось восстановить локальные маршруты", e), null) }
    }

    private fun runDocumentRedemption(document: String, profile: String, secret: String,
                                      request: AdminEnvelope, serverKey: String): AdminEnvelope =
        documentSyncRunner?.invoke(document, profile, secret, request, serverKey)
            ?: AdminDocumentClaimClient(context ?: error("Android context unavailable")).redeem(document, profile, secret, request, serverKey)

    fun loadServerInstallation(operationId: String): ServerInstallOperation? =
        _installationOperations.value.firstOrNull { it.operationId == operationId }

    fun prepareOwnerRecovery(node: ServerNode, operationId: String): Result<kotlinx.serialization.json.JsonObject> = runCatching {
        val plan = planServerInstallation(node, operationId)
        require(plan["mode"]?.jsonPrimitive?.content == "update") { "Восстановление владельца допустимо только для существующей установки" }
        require(plan["server_public_key"]?.jsonPrimitive?.content?.isNotBlank() == true &&
            plan["owner_device_ids"]?.let { (it as? kotlinx.serialization.json.JsonArray)?.isNotEmpty() } == true) {
            "SSH-план не содержит проверенной идентичности и владельцев для восстановления"
        }
        val operation = loadServerInstallation(operationId) ?: error("План не сохранён")
        saveServerInstallation(operation.copy(kind = ServerInstallOperation.KIND_OWNER_RECOVERY, ownerRecovery = true))
        plan
    }

    fun latestServerInstallation(serverId: String): ServerInstallOperation? =
        _installationOperations.value.lastOrNull { it.serverId == serverId }

    private fun persistInstallationOperations() {
        storage.putString(KEY_INSTALL_OPERATIONS, json.encodeToString(_installationOperations.value))
    }

    private fun saveServerInstallation(operation: ServerInstallOperation) {
        val list = _installationOperations.value.toMutableList()
        val index = list.indexOfFirst { it.operationId == operation.operationId }
        if (index < 0) list.add(operation) else list[index] = operation
        // Keep pending intents and the latest recovery receipts, never discard an unresolved operation.
        _installationOperations.value = list
        persistInstallationOperations()
    }

    fun setServerInstallationProfileName(operationId: String, name: String) {
        val operation = loadServerInstallation(operationId) ?: return
        saveServerInstallation(operation.copy(profileName = name.trim().take(64)))
    }

    fun latestProtocolInstallation(serverId: String, transport: String): ProtocolInstallationOperation? =
        _protocolOperations.value.lastOrNull { it.serverId == serverId && it.transport == transport }

    private fun persistProtocolOperations() {
        storage.putString(KEY_PROTOCOL_OPERATIONS, json.encodeToString(_protocolOperations.value))
    }

    private fun saveProtocolInstallation(operation: ProtocolInstallationOperation) {
        val list = _protocolOperations.value.toMutableList()
        val index = list.indexOfFirst { it.operationId == operation.operationId }
        if (index < 0) list.add(operation) else list[index] = operation
        _protocolOperations.value = list
        persistProtocolOperations()
    }

    /** Installs exactly one transport using a durable idempotency key. */
    fun installProtocol(
        node: ServerNode,
        transport: String,
        endpoint: String = "",
        channel: AdminChannel = AdminChannel.DIRECT_SSH
    ): AdminOpResult {
        val normalized = transport.lowercase().let { if (it == "yandex") "vyandex" else it }
        require(normalized in setOf("vyandex", "mqtt", "jitsi")) { "Неподдерживаемый протокол: $transport" }
        val required = setOf("protocol-create-v2") + when (normalized) {
            "vyandex" -> CoreCompatibility.REQUIRED_CAPABILITIES
            "mqtt" -> setOf("mqtt-wss-readiness-v1", "worker-backend-readiness-v1")
            "jitsi" -> setOf("worker-backend-readiness-v1")
            else -> emptySet()
        }
        checkCoreCompatibility(node, channel, required).exceptionOrNull()?.let {
            return AdminOpResult.Failure(it.message.orEmpty(), it)
        }
        val existing = latestProtocolInstallation(node.id, normalized)
        if (existing?.stage == ProtocolInstallationOperation.READY) {
            val profile = _managedProfilesFlow.value.firstOrNull {
                it.id == existing.profileId && it.isReady &&
                    (it.serverId == node.id || node.id in it.allowedServerIds)
            }
            if (profile != null && hasMaterializedProtocolTunnel(profile)) {
                return AdminOpResult.Success("Протокол уже установлен")
            }
            return reconcileProtocolInstallation(node, existing)
        }
        if (existing?.requiresReconciliation == true) {
            return reconcileProtocolInstallation(node, existing)
        }
        val changedEndpoint = existing != null && endpoint.isNotBlank() && endpoint != existing.endpoint
        val freshIntent = if (changedEndpoint && existing?.stage != ProtocolInstallationOperation.UNKNOWN &&
            existing?.stage != ProtocolInstallationOperation.SUBMITTING) null else existing
        val operation = (freshIntent ?: ProtocolInstallationOperation(
            operationId = UUID.randomUUID().toString(),
            serverId = node.id,
            transport = normalized,
            profileId = "libreroute-${normalized}-${UUID.randomUUID().toString().take(8)}",
            endpoint = endpoint
        )).let { current ->
            // Keep the same durable operation/profile identity across retries,
            // while recording the endpoint that was actually confirmed by the
            // user for this attempt.
            if (!current.requiresReconciliation && endpoint.isNotBlank() && endpoint != current.endpoint)
                current.copy(endpoint = endpoint)
            else current
        }
        saveProtocolInstallation(operation.copy(stage = ProtocolInstallationOperation.SUBMITTING, error = null))
        val result = createManagedProfile(
            name = "${normalized.uppercase()} — ${node.name}",
            documentUrl = operation.endpoint,
            transportType = normalized,
            sessionNegotiated = normalized == "vyandex",
            allowedServerIds = listOf(node.id),
            allowedProtocols = listOf(normalized),
            channel = channel,
            hostConfig = node.toSshHostConfig(),
            profileIdOverride = operation.profileId,
            operationIdOverride = operation.operationId,
            jitsiToken = if (normalized == "jitsi") {
                context?.let { JitsiTokenStore(it).get(operation.endpoint).orEmpty() }.orEmpty()
            } else ""
        )
        return when (result) {
            is AdminOpResult.Success -> {
                saveProtocolInstallation(operation.copy(stage = ProtocolInstallationOperation.READY, updatedAt = System.currentTimeMillis()))
                attachProtocolToOwnerProfile(node.id, normalized, operation.endpoint)
                result
            }
            is AdminOpResult.Failure -> {
                val stage = protocolFailureStage(normalized, result)
                saveProtocolInstallation(operation.copy(stage = stage, error = result.error,
                    updatedAt = System.currentTimeMillis()))
                result
            }
        }
    }

    private fun protocolFailureStage(transport: String, result: AdminOpResult.Failure): String {
        val actionRequired = when (transport) {
            "vyandex" -> requiresYandexBrowserAuth(result.error)
            "jitsi" -> listOf("moderator", "route transport not ready", "room").any { result.error.contains(it, true) }
            else -> false
        }
        return when {
            actionRequired -> ProtocolInstallationOperation.NEEDS_ACTION
            result.throwable !is ConfirmedAdminRejection -> ProtocolInstallationOperation.UNKNOWN
            else -> ProtocolInstallationOperation.FAILED
        }
    }

    fun reconcileProtocolInstallation(node: ServerNode, operation: ProtocolInstallationOperation): AdminOpResult {
        val profile = _managedProfilesFlow.value.firstOrNull { it.id == operation.profileId &&
            (it.serverId == node.id || node.id in it.allowedServerIds) }
        if (profile != null && profile.status == ManagedProfile.STATUS_READY && hasMaterializedProtocolTunnel(profile)) {
            saveProtocolInstallation(operation.copy(stage = ProtocolInstallationOperation.READY, error = null))
            attachProtocolToOwnerProfile(node.id, operation.transport, operation.endpoint)
            return AdminOpResult.Success("Протокол уже установлен; профиль восстановлен")
        }
        // A process death can happen after the signed create command was
        // committed remotely but before the local projection was written.
        // Replaying the same operation id lets the server journal return the
        // original response without creating a duplicate profile.
        val remoteProfiles = listServerManagedProfiles(node).getOrElse {
            return AdminOpResult.Failure("Не удалось проверить результат установки на сервере: ${it.message.orEmpty()}",
                UnknownAdminOperation(it.message.orEmpty()))
        }
        val remote = remoteProfiles
            .firstOrNull { it.profileId == operation.profileId && it.serverId == node.id }
        if (remote != null || operation.stage == ProtocolInstallationOperation.SUBMITTING ||
            operation.stage == ProtocolInstallationOperation.UNKNOWN) {
            val replay = createManagedProfile(
                name = "${operation.transport.uppercase()} — ${node.name}",
                documentUrl = operation.endpoint,
                transportType = operation.transport,
                sessionNegotiated = operation.transport == "vyandex",
                allowedServerIds = listOf(node.id),
                allowedProtocols = listOf(operation.transport),
                channel = AdminChannel.DIRECT_SSH,
                hostConfig = node.toSshHostConfig(),
                profileIdOverride = operation.profileId,
                operationIdOverride = operation.operationId,
                jitsiToken = if (operation.transport == "jitsi") {
                    context?.let { JitsiTokenStore(it).get(operation.endpoint).orEmpty() }.orEmpty()
                } else ""
            )
            if (replay is AdminOpResult.Success) {
                saveProtocolInstallation(operation.copy(stage = ProtocolInstallationOperation.READY, error = null, updatedAt = System.currentTimeMillis()))
                attachProtocolToOwnerProfile(node.id, operation.transport, operation.endpoint)
                return replay
            }
            if (replay is AdminOpResult.Failure) {
                saveProtocolInstallation(operation.copy(stage = protocolFailureStage(operation.transport, replay),
                    error = replay.error, updatedAt = System.currentTimeMillis()))
            }
            return replay
        }
        return AdminOpResult.Failure(operation.error ?: "Protocol installation result is unknown; check the server")
    }

    private fun hasMaterializedProtocolTunnel(profile: ManagedProfile): Boolean {
        val ctx = context ?: return true // Unit-test repositories have no Android tunnel store.
        return runCatching { TunnelRepository(ctx).load().any {
            it.name == profile.name && it.transportType.equals(profile.transportType, true) &&
                it.transportConnPayload.contains("--url")
        } }.getOrDefault(false)
    }

    /** A server capability alone is not an administrator connection. */
    fun isProtocolReady(serverId: String, transport: String): Boolean =
        _managedProfilesFlow.value.any { profile ->
            profile.profileType != ManagedProfile.PROFILE_TYPE_CONTROL &&
                (profile.serverId == serverId || serverId in profile.allowedServerIds) &&
                profile.transportType == transport &&
                profile.isReady && hasMaterializedProtocolTunnel(profile)
        }

    fun installServer(node: ServerNode, operationId: String, documentUrl: String = "", ownerRecovery: Boolean = false): AdminOpResult {
        val original = loadServerInstallation(operationId)
            ?: return AdminOpResult.Failure("Сначала подготовьте и подтвердите план установки")
        if (original.serverId != node.id) return AdminOpResult.Failure("План относится к другому серверу")
        val approvedPlan = json.parseToJsonElement(original.expectedPlan).jsonObject
        if (approvedPlan["mode"]?.jsonPrimitive?.content == "update" &&
            approvedPlan["server_id"]?.jsonPrimitive?.content != node.id) {
            return AdminOpResult.Failure("Сначала привяжите SSH-узел к подтверждённому server_id; установка заблокирована")
        }
        if (original.stage != ServerInstallOperation.PLANNED) {
            return AdminOpResult.Failure("Операция уже отправлялась. Проверьте результат через SSH")
        }
        return runCatching {
            val installer = installationClient()
            val saved = getServerNode(node.id) ?: node
            val suppliedDocument = documentUrl.ifBlank { approvedPlan["document_url"]?.jsonPrimitive?.content.orEmpty() }
            val resolved = if (suppliedDocument.isBlank()) "" else AdminDocumentClaimClient.resolveDocumentUrl(suppliedDocument)
            val join = original.join?.let(AdminEnvelope::fromJson) ?: createJoinRequest(original.profileId).first
            val joinBody = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(join.payload), Charsets.UTF_8)).jsonObject
            val pairing = joinBody.getValue("one_time_code").jsonPrimitive.content
            // A signed snapshot writes the server operation journal. After the
            // plan is approved, only inventory may be probed before execution.
            val inventory = probeServer(saved, checkCore = false)
            require(inventory.probeError == null) { "SSH-опрос не завершён" }
            val submitting = original.copy(node = saved, documentUrl = resolved, ownerRecovery = ownerRecovery, join = join.toJson(),
                stage = ServerInstallOperation.SUBMITTING, updatedAt = System.currentTimeMillis())
            saveServerInstallation(submitting)
            if (original.kind != ServerInstallOperation.KIND_CORE_UPDATE) markOwnerProvisioning(saved)
            val response = installer.install(saved, inventory, operationId, resolved, join, pairing,
                approvedPlan, ownerRecovery)
            reconcileInstallationReceipt(submitting, response)
        }.getOrElse {
            val message = it.message ?: "Результат установки неизвестен"
            val operation = loadServerInstallation(operationId) ?: original
            if (operation.stage == ServerInstallOperation.SUBMITTING) saveServerInstallation(operation.copy(
                stage = ServerInstallOperation.UNKNOWN, error = message, updatedAt = System.currentTimeMillis()))
            AdminOpResult.Failure(message)
        }
    }

    fun checkServerInstallation(node: ServerNode, operationId: String): AdminOpResult = runCatching {
        val operation = loadServerInstallation(operationId) ?: error("Операция не найдена локально")
        require(operation.serverId == node.id) { "Операция относится к другому серверу" }
        reconcileInstallationReceipt(operation, installationClient().status(getServerNode(node.id) ?: node, operationId))
    }.getOrElse { AdminOpResult.Failure(it.message ?: "Не удалось проверить сохранённую операцию") }

    fun rollbackServerInstallation(node: ServerNode, operationId: String): AdminOpResult = runCatching {
        val operation = loadServerInstallation(operationId) ?: error("Операция не найдена локально")
        require(operation.serverId == node.id) { "Операция относится к другому серверу" }
        reconcileInstallationReceipt(operation, installationClient().rollback(getServerNode(node.id) ?: node, operationId))
    }.getOrElse { AdminOpResult.Failure(it.message ?: "Откат не подтверждён. SSH-подключение сохранено") }

    private fun reconcileInstallationReceipt(operation: ServerInstallOperation,
                                            response: kotlinx.serialization.json.JsonObject): AdminOpResult {
        fun field(name: String) = response[name]?.jsonPrimitive?.content
        val status = field("status") ?: error("В ответе нет статуса операции")
        if (status == "not_found") {
            require(field("operation_id")?.let { it == operation.operationId } != false &&
                field("namespace")?.let { it == operation.namespace } != false) { "SSH-квитанция относится к другой операции" }
            val message = "На сервере нет квитанции этой операции. Проверены параметры сервера; результат установки пока не подтверждён."
            probeServer(operation.node)
            saveServerInstallation(operation.copy(stage = ServerInstallOperation.UNKNOWN, error = message,
                updatedAt = System.currentTimeMillis()))
            return AdminOpResult.Failure(message, UnknownAdminOperation(message))
        }
        require(field("operation_id") == operation.operationId && field("namespace") == operation.namespace) {
            "SSH-квитанция относится к другой операции"
        }
        require(field("plan_hash") == operation.planHash) { "План операции изменился" }
        if (status != "committed") {
            val stage = when (status) {
                "rolled_back" -> ServerInstallOperation.ROLLED_BACK
                "recovery_required" -> ServerInstallOperation.RECOVERY_REQUIRED
                else -> ServerInstallOperation.UNKNOWN
            }
            saveServerInstallation(operation.copy(stage = stage, error = "Состояние на сервере: $status",
                updatedAt = System.currentTimeMillis()))
            if (status == "rolled_back") restoreInstallationAccessAfterRollback(operation)
            return if (status == "rolled_back") AdminOpResult.Success("Откат подтверждён; SSH-подключение сохранено")
                else AdminOpResult.Failure("Состояние на сервере: $status. Выполните проверку или восстановление")
        }
        require(field("agent_ready") == "true" && field("ssh_management_ready") == "true" && field("control_profile_ready") == "true") {
            "Установка записана, но служба, SSH-управление или профиль администратора не готовы"
        }
        val join = operation.join?.let(AdminEnvelope::fromJson) ?: error("Локальный запрос установки не найден")
        val envelope = AdminEnvelope.fromJson(response.getValue("grant").toString()) ?: error("Нет подписанного допуска")
        val grant = json.decodeFromString<AdminGrant>(String(Base64.getUrlDecoder().decode(envelope.payload), Charsets.UTF_8))
        require(grant.serverId == operation.serverId && grant.profileId == operation.profileId &&
            grant.adminFingerprint == operation.deviceId && envelope.nonce == join.nonce && grant.role == AdminRole.OWNER) {
            "Допуск установки относится к другому серверу, устройству или профилю"
        }
        val serverKey = field("server_public_key") ?: error("Нет ключа сервера")
        require(grant.serverPublicKey == serverKey && validGrantFields(envelope, grant, serverKey) &&
            keyManager.verifyServerResponse(envelope, serverKey, serverKey)) { "Подпись допуска установки недействительна" }
        val pinned = getPinnedServerPublicKey(operation.serverId)
        require(pinned == null || pinned == serverKey) { "Серверный ключ отличается от закреплённого" }
        val document = field("document_url").orEmpty()
        val documentPending = field("document_status") == "pending" && document.isBlank() &&
            field("requested_document_url") == operation.documentUrl
        require(document == operation.documentUrl || documentPending) { "Управляющий документ не совпадает с намерением установки" }
        require(operation.serverId !in revokedServerIds() ||
            operation.kind == ServerInstallOperation.KIND_OWNER_RECOVERY && operation.ownerRecovery) {
            "Локальный допуск удалён. Запросите новый допуск; прежняя квитанция не восстанавливает отозванные права"
        }
        // Durable checkpoint BEFORE updating separately stored local projections.
        val confirmed = operation.copy(stage = ServerInstallOperation.SERVER_CONFIRMED,
            receipt = response.toString(), error = null, updatedAt = System.currentTimeMillis())
        saveServerInstallation(confirmed)
        _pendingRequestFlow.value = join
        storage.putString(KEY_PENDING_REQUEST, join.toJson())
        val result = importTrustedBootstrapGrant(envelope.toJson(), requireNotNull(keyManager.computeFingerprint(serverKey)))
        require(result is AdminOpResult.Success) { (result as AdminOpResult.Failure).error }
        val node = restoreNodeCredentials(operation.node).copy(managementNamespace = operation.namespace)
        if (getServerNode(node.id) == null) addServerNode(node) else updateServerNode(node)
        if (document.isNotBlank() && !field("client_key").isNullOrBlank() && !field("server_key").isNullOrBlank()) {
            storage.putString(KEY_DOCUMENT_SESSION_PREFIX + node.id, json.encodeToString(DocumentSessionKeys(
                field("client_key")!!, field("server_key")!!, document)))
            storage.putString(documentSessionKey(grant.profileId, node.id), json.encodeToString(DocumentSessionKeys(
                field("client_key")!!, field("server_key")!!, document)))
        }
        ensureOwnerControlProfile(node, grant, document, operation.profileName)
        saveServerInstallation(confirmed.copy(stage = ServerInstallOperation.READY))
        return AdminOpResult.Success(if (field("document_ready") == "true") "Сервер и оба канала проверены; профиль администратора готов"
            else "Сервер и SSH-управление проверены; профиль администратора готов. Документный канал не проверен", envelope)
    }

    /** Creates the idempotent local OWNER/control profile returned by bootstrap. */
    private fun ensureOwnerControlProfile(node: ServerNode, grant: AdminGrant, documentUrl: String, requestedName: String = "") {
        val profiles = _managedProfilesFlow.value.toMutableList()
        val profileId = grant.profileId.ifBlank { "admin-control-${node.id}" }
        val existingIndex = profiles.indexOfFirst {
            it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL &&
                (it.serverId == node.id || it.id == profileId)
        }
        val transport = if (documentUrl.isBlank()) "ssh" else "document"
        val current = if (existingIndex >= 0) profiles[existingIndex] else null
        val currentConfigs = current?.serverConfigs.orEmpty()
        val serverConfigs = if (currentConfigs.isNotEmpty()) {
            currentConfigs.toMutableMap().apply {
                val existing = get(node.id)
                if (documentUrl.isNotBlank() && (existing == null || existing.documentUrl.orEmpty().isBlank())) {
                    put(node.id, existing?.copy(documentUrl = documentUrl)
                        ?: ProfileServerConfig(node.id, documentUrl))
                }
            }
        } else mapOf(node.id to ProfileServerConfig(node.id, documentUrl))
        val control = (current ?: ManagedProfile(
            id = profileId,
            name = requestedName.ifBlank { "Администратор — ${node.name}" },
            transportType = transport,
            allowedServerIds = listOf(node.id),
            allowedProtocols = listOf(transport),
            serverConfigs = mapOf(node.id to ProfileServerConfig(node.id, documentUrl)),
            profileType = ManagedProfile.PROFILE_TYPE_CONTROL,
            role = grant.role,
            serverId = node.id,
            provisioningStatus = ManagedProfile.PROVISIONING
        )).copy(
            id = profileId,
            profileType = ManagedProfile.PROFILE_TYPE_CONTROL,
            role = grant.role,
            serverId = node.id,
            status = ManagedProfile.STATUS_READY,
            transportType = transport,
            sessionNegotiated = false,
            provisioningStatus = ManagedProfile.PROVISIONING_READY,
            serverConfigs = serverConfigs,
            allowedServerIds = if (current?.allowedServerIds?.isNotEmpty() == true) current.allowedServerIds else listOf(node.id),
            allowedProtocols = emptyList(),
            lastCheckTimestamp = System.currentTimeMillis()
        )
        if (existingIndex >= 0) profiles[existingIndex] = control else profiles.add(control)
        saveProfiles(profiles)
    }


    private fun markOwnerProvisioning(node: ServerNode) {
        val profiles = _managedProfilesFlow.value.toMutableList()
        val index = profiles.indexOfFirst { it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL && it.serverId == node.id }
        val protocol = node.supportedProtocols.firstOrNull() ?: "vyandex"
        val base = if (index >= 0) profiles[index] else ManagedProfile(
            id = "admin-control-${node.id}", name = "Администратор — ${node.name}",
            transportType = protocol, allowedServerIds = listOf(node.id),
            allowedProtocols = listOf(protocol), profileType = ManagedProfile.PROFILE_TYPE_CONTROL,
            role = AdminRole.NONE, serverId = node.id)
        val updated = base.copy(profileType = ManagedProfile.PROFILE_TYPE_CONTROL,
            serverId = node.id, provisioningStatus = ManagedProfile.PROVISIONING)
        if (index >= 0) profiles[index] = updated else profiles.add(updated)
        saveProfiles(profiles)
    }

    private fun markOwnerProvisioningFailed(node: ServerNode, error: String) {
        val profiles = _managedProfilesFlow.value.map {
            if (it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL && it.serverId == node.id) {
                it.copy(provisioningStatus = ManagedProfile.PROVISIONING_FAILED, status = ManagedProfile.STATUS_SUSPENDED)
            } else it
        }
        saveProfiles(profiles)
        Logx.e(TAG, "Owner profile provisioning failed for ${node.id}: $error")
    }

    fun getServerNode(id: String): ServerNode? = _serverNodesFlow.value.find { it.id == id }

    /** A new endpoint is committed only after authenticated, pinned SSH inventory succeeds. */
    fun connectServer(node: ServerNode): Result<ServerNode> = runCatching {
        require(!node.hostPublicKey.isNullOrBlank()) { "Подтвердите ключ SSH-сервера" }
        val existing = _serverNodesFlow.value.firstOrNull {
            it.host.equals(node.host, ignoreCase = true) && it.port == node.port && it.sshUser == node.sshUser
        }
        require(existing?.hostPublicKey.isNullOrBlank() || existing?.hostPublicKey == node.hostPublicKey) {
            "Ключ сохранённого SSH-сервера изменился. Повторное подключение остановлено."
        }
        val candidate = node.copy(id = existing?.id ?: node.id,
            managementNamespace = existing?.managementNamespace ?: node.managementNamespace)
        val inventory = serverInventoryProbe?.invoke(candidate) ?: sshClient.probeServer(candidate)
        require(inventory.probeError == null) { inventory.probeError ?: "SSH-проверка не выполнена" }
        val verified = candidate.copy(inventory = inventory, status = ServerNodeStatus.ONLINE,
            lastSeenAt = System.currentTimeMillis())
        addServerNode(verified)
        getServerNode(verified.id) ?: error("Не удалось сохранить сервер")
    }

    @Synchronized
    fun addServerNode(node: ServerNode) {
        val updated = _serverNodesFlow.value.filter { it.id != node.id } + restoreNodeCredentials(storeNodeCredentials(node))
        _serverNodesFlow.value = updated
        saveServerNodes(updated)
    }

    @Synchronized
    fun updateServerNode(node: ServerNode) {
        val updated = _serverNodesFlow.value.map { if (it.id == node.id) restoreNodeCredentials(storeNodeCredentials(node)) else it }
        _serverNodesFlow.value = updated
        saveServerNodes(updated)
    }

    @Synchronized
    fun removeServerNode(id: String) {
        val ref = getServerNode(id)?.credentialRef
        if (ref != null) { sshCredentialStorage.remove(ref + "_password"); sshCredentialStorage.remove(ref + "_passphrase") }
        val updated = _serverNodesFlow.value.filter { it.id != id }
        _serverNodesFlow.value = updated
        saveServerNodes(updated)
    }

    private fun saveServerNodes(nodes: List<ServerNode>) {
        val safeNodes = nodes.map { storeNodeCredentials(it) }
        val raw = json.encodeToString(safeNodes)
        storage.putString(KEY_SERVER_NODES, raw)
    }

    /**
     * Probes target host over SSH, updates its inventory and connectivity status in the repository.
     */
    fun probeServer(serverNode: ServerNode, checkCore: Boolean = true): ServerInventory {
        val inv = serverInventoryProbe?.invoke(serverNode) ?: sshClient.probeServer(serverNode)
        val updatedNode = if (inv.probeError == null) {
            serverNode.copy(
                inventory = inv,
                status = ServerNodeStatus.ONLINE,
                lastSeenAt = System.currentTimeMillis()
            )
        } else {
            serverNode.copy(
                inventory = inv,
                // A rejected inventory command does not prove that the SSH host is offline.
                status = ServerNodeStatus.UNKNOWN
            )
        }
        updateServerNode(updatedNode)
        if (checkCore && inv.probeError == null && (inv.managedNamespaces.isNotEmpty() || !inv.installedLibreRouteVersion.isNullOrBlank())) {
            refreshServerCore(updatedNode)
        }
        return inv
    }

    fun getRole(): AdminRole {
        val grant = _activeGrantFlow.value
        if (grant != null && grant.expiresAt <= currentTimeSeconds()) {
            // Expiry is a capability change, not deletion of the saved SSH
            // inventory. Keep signed receipts for diagnosis/re-authentication.
            val remaining = _serverNodesFlow.value.firstNotNullOfOrNull { verifiedGrantForServer(it.id) }
            _activeGrantFlow.value = remaining
            _roleFlow.value = remaining?.role ?: AdminRole.NONE
        }
        return _roleFlow.value
    }

    /** Console visibility is independent from per-server mutation permission. */
    fun hasAdminAccess(): Boolean {
        getRole()
        return hasCapabilityOnAnyServer { it.role == AdminRole.OWNER || it.role == AdminRole.OPERATOR }
    }

    fun hasAdminReadAccess(): Boolean {
        getRole()
        return hasCapabilityOnAnyServer { it.role in setOf(AdminRole.OWNER, AdminRole.OPERATOR, AdminRole.VIEWER) }
    }

    private fun hasCapabilityOnAnyServer(predicate: (AdminGrant) -> Boolean): Boolean =
        verifiedGrantForServer(null)?.let(predicate) == true ||
            _serverNodesFlow.value.any { verifiedGrantForServer(it.id)?.let(predicate) == true }

    fun getRole(serverId: String): AdminRole = verifiedGrantForServer(serverId)?.role ?: AdminRole.NONE

    fun getServerAccess(serverId: String? = null): AdminServerAccess {
        val grant = getActiveGrant(serverId)
        val revoked = revokedServerIds()
        if ((serverId ?: grant?.serverId ?: "legacy") in revoked) return AdminServerAccess(serverId, AdminAccessState.REVOKED)
        if (grant != null) {
            if (grant.expiresAt <= currentTimeSeconds()) return AdminServerAccess(serverId, AdminAccessState.EXPIRED,
                expiresAt = grant.expiresAt)
            val verified = verifiedGrantForServer(serverId)
            return AdminServerAccess(serverId, if (verified == null) AdminAccessState.INVALID else AdminAccessState.ACTIVE,
                verified?.role ?: AdminRole.NONE, grant.expiresAt)
        }
        return AdminServerAccess(serverId, if (_pendingRequestFlow.value?.exp?.let { it > currentTimeSeconds() } == true)
            AdminAccessState.PENDING else AdminAccessState.NONE)
    }

    /** Uses the signed target-server grant; another server's OWNER never authorizes this one. */
    fun canPerform(serverId: String?, operation: String): Boolean {
        if (serverId == null && _serverNodesFlow.value.size > 1) return false
        val grant = verifiedGrantForServer(serverId) ?: return false
        val readOperations = setOf("list", "list-users", "list-devices", "list-assignments", "list-invitations",
            "get-cluster-snapshot", "list-auth-incidents", "get-auth-session-status", "list-admins")
        val mutation = operation !in readOperations
        if (grant.role == AdminRole.VIEWER && mutation) return false
        if (grant.role == AdminRole.OPERATOR && operation in setOf("revoke", "rotate-key", "grant", "issue-admin-invite", "approve-admin-request",
                "confirm-owner-transfer", "complete-owner-transfer", "revoke-admin", "renew-admin-grant", "install", "rollback", "delete-service")) return false
        if (grant.scope.isEmpty() || (serverId ?: grant.serverId)?.let { it in grant.scope || "server:$it" in grant.scope } == true)
            return grant.role in setOf(AdminRole.OWNER, AdminRole.OPERATOR, AdminRole.VIEWER)
        return "*" in grant.scope || operation in grant.scope ||
            (!mutation && "read" in grant.scope) || (mutation && "manage" in grant.scope)
    }

    private fun revokedServerIds(): Set<String> = storage.getString(KEY_REVOKED_SERVER_IDS, null)?.let {
        runCatching { json.decodeFromString<List<String>>(it).toSet() }.getOrNull()
    }.orEmpty()

    fun cancelPendingAdminRequest() {
        storage.remove(KEY_PENDING_REQUEST)
        storage.remove(KEY_LAST_PAIRING_CODE)
        storage.remove(KEY_PENDING_ADMIN_CONTEXT)
        storage.remove(KEY_DELEGATION_CHECKPOINT)
        _pendingRequestFlow.value = null
        _lastPairingCodeFlow.value = null
        _roleFlow.value = _activeGrantFlow.value?.takeIf { it.expiresAt > currentTimeSeconds() }?.role ?: AdminRole.NONE
    }

    fun createAdminRequestLink(serverId: String?, profileId: String, role: AdminRole): String {
        require(profileId.isNotBlank() && role in setOf(AdminRole.OWNER, AdminRole.OPERATOR, AdminRole.VIEWER)) {
            "Нужен административный контекст сервера и запрашиваемая роль"
        }
        val request = createJoinRequest(profileId, role).first
        storage.putString(KEY_PENDING_ADMIN_CONTEXT, buildJsonObject {
            serverId?.let { put("server_id", it) }; put("profile_id", profileId); put("role", role.name)
        }.toString())
        return AdminDelegationLink.createRequest(request)
    }

    fun issueAdminInvitationTemplate(role: AdminRole, node: ServerNode,
                                     channel: AdminChannel = AdminChannel.DOCUMENT): Pair<AdminOpResult, String?> = try {
        require(role in setOf(AdminRole.OWNER, AdminRole.OPERATOR, AdminRole.VIEWER)) { "Выберите роль администратора" }
        var response: AdminEnvelope? = null
        val payload = buildJsonObject { put("op", "issue-admin-invite"); put("role", role.name.lowercase()); put("server_id", node.id) }.toString()
        executeAdminApiCommand(payload, targetServer = node, channel = channel,
            onResponse = { response = it }) { Unit }.getOrThrow()
        Pair(AdminOpResult.Success("Приглашение администратора подтверждено сервером", response),
            AdminDelegationLink.encodeInvitation(response ?: error("Нет подписанного приглашения")))
    } catch (e: Exception) { Pair(AdminOpResult.Failure(e.message ?: "Приглашение не подтверждено", e), null) }

    fun acceptAdminInvitationTemplate(link: String, fingerprint: String = ""): Pair<AdminOpResult, String?> = try {
        val invitation = AdminDelegationLink.parseInvitation(link) ?: error("Некорректное административное приглашение")
        verifyDelegationReceipt(invitation.response, invitation.serverId, invitation.serverPublicKey, fingerprint)
        require(invitation.expiresAt > currentTimeSeconds() && invitation.expiresAt <= invitation.response.exp) {
            "Приглашение администратора истекло"
        }
        setPinnedServerPublicKey(invitation.serverPublicKey, invitation.serverId)
        val requestLink = createAdminRequestLink(invitation.serverId, invitation.profileId, invitation.role)
        storage.putString(KEY_PENDING_ADMIN_CONTEXT, buildJsonObject {
            put("server_id", invitation.serverId); put("profile_id", invitation.profileId)
            put("role", invitation.role.name); put("document_url", invitation.documentUrl)
        }.toString())
        Pair(AdminOpResult.Success("Запрос подписан устройством. Передайте его владельцу для подтверждения"), requestLink)
    } catch (e: Exception) { Pair(AdminOpResult.Failure(e.message ?: "Приглашение не принято", e), null) }

    fun approveAdminRequest(link: String, role: AdminRole, serverNode: ServerNode,
                            channel: AdminChannel = AdminChannel.DOCUMENT,
                            transferOwnership: Boolean = false): Pair<AdminOpResult, String?> = try {
        val request = AdminDelegationLink.parseRequest(link) ?: error("Некорректный запрос администратора")
        require(request.exp > currentTimeSeconds()) { "Запрос администратора истекло" }
        val body = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(request.payload), Charsets.UTF_8)).jsonObject
        val publicKey = body.getValue("public_key").jsonPrimitive.content
        val requester = body.getValue("device_id").jsonPrimitive.content
        require(request.sender == requester && requester == keyManager.computeFingerprint(publicKey) &&
            body["profile_id"]?.jsonPrimitive?.content == request.profileId &&
            keyManager.parsePublicKey(publicKey)?.let { keyManager.verifyEnvelope(request, it) } == true) { "Подпись запроса устройства недействительна" }
        require(request.profileId == verifiedGrantForServer(serverNode.id)?.profileId) { "Запрос относится к другому профилю сервера" }
        require(role in setOf(AdminRole.OWNER, AdminRole.OPERATOR, AdminRole.VIEWER)) { "Роль недопустима" }
        require(!transferOwnership || role == AdminRole.OWNER) { "Передача владения требует роль OWNER" }
        var response: AdminEnvelope? = null
        val payload = buildJsonObject {
            put("op", "approve-admin-request"); put("profile_id", request.profileId); put("role", role.name.lowercase())
            put("server_id", serverNode.id)
            put("parameters", buildJsonObject {
                put("join_request", request.toJson()); if (transferOwnership) put("transfer_owner", "true")
            })
        }.toString()
        executeAdminApiCommand(payload, targetServer = serverNode, channel = channel, onResponse = { response = it }) { Unit }.getOrThrow()
        Pair(AdminOpResult.Success("Права подтверждены сервером; передайте подтверждение получателю", response),
            AdminDelegationLink.encodeGrant(response ?: error("Нет подписанного подтверждения")))
    } catch (e: Exception) { Pair(AdminOpResult.Failure(e.message ?: "Запрос не подтверждён", e), null) }

    private fun verifyDelegationReceipt(response: AdminEnvelope, serverId: String, serverKey: String, fingerprint: String) {
        val pin = getPinnedServerPublicKey(serverId)
        require(pin == null || pin == serverKey) { "Ключ сервера отличается от закреплённого" }
        if (pin == null) require(fingerprint.isNotBlank() && keyManager.computeFingerprint(serverKey) == fingerprint.trim()) {
            "Сверьте отпечаток серверного ключа с владельцем по доверенному каналу"
        }
        val body = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(response.payload), Charsets.UTF_8)).jsonObject
        require(response.type == AdminEnvelope.TYPE_ADMIN && response.subtype == AdminEnvelope.SUBTYPE_RESPONSE &&
            response.sender == "server" && response.exp > currentTimeSeconds() &&
            body["op_id"]?.jsonPrimitive?.content == response.opId && body["status"]?.jsonPrimitive?.content == "applied" &&
            keyManager.verifyServerResponse(response, serverKey, serverKey)) { "Подпись административного подтверждения недействительна" }
        require(body["data"]?.jsonObject?.get("server_id")?.jsonPrimitive?.content == serverId) { "Подтверждение относится к другому серверу" }
    }

    fun importAdminGrantLink(link: String, fingerprint: String = ""): AdminOpResult = runCatching {
        val delegated = AdminDelegationLink.parseGrant(link) ?: error("Некорректная ссылка подтверждения прав")
        verifyDelegationReceipt(delegated.response, delegated.serverId, delegated.serverPublicKey, fingerprint)
        val pending = _pendingRequestFlow.value ?: error("Нет запроса прав с этого устройства")
        val grant = json.decodeFromString<AdminGrant>(String(Base64.getUrlDecoder().decode(delegated.grant.payload), Charsets.UTF_8))
        require(grant.serverId == delegated.serverId && grant.serverPublicKey == delegated.serverPublicKey &&
            grant.profileId == pending.profileId && delegated.grant.nonce == pending.nonce &&
            grant.adminFingerprint == keyManager.getFingerprint()) { "Допуск не совпадает с запросом этого устройства" }
        val context = storage.getString(KEY_PENDING_ADMIN_CONTEXT, null)?.let { json.parseToJsonElement(it).jsonObject }
        require(context?.get("server_id")?.jsonPrimitive?.content.let { it == null || it == delegated.serverId }) { "Запрос ожидал другой сервер" }
        require(context?.get("document_url")?.jsonPrimitive?.content.let { it == null || it == delegated.documentUrl }) { "Управляющий документ был заменён" }
        require(validGrantFields(delegated.grant, grant, delegated.serverPublicKey) &&
            keyManager.verifyServerResponse(delegated.grant, delegated.serverPublicKey, delegated.serverPublicKey)) { "Подпись допуска недействительна" }
        setPinnedServerPublicKey(delegated.serverPublicKey, delegated.serverId)
        storage.putString(KEY_DELEGATION_CHECKPOINT, buildJsonObject {
            put("request", json.parseToJsonElement(pending.toJson())); put("link", link)
        }.toString())
        val applied = applyAdminGrant(delegated.grant, delegated.serverPublicKey)
        require(applied is AdminOpResult.Success) { (applied as AdminOpResult.Failure).error }
        val node = getServerNode(delegated.serverId) ?: ServerNode(id = delegated.serverId, name = delegated.serverId,
            host = "", supportedProtocols = emptyList()) .also { addServerNode(it) }
        if (delegated.documentUrl.isNotBlank() && delegated.clientKey.isNotBlank() && delegated.serverKey.isNotBlank()) {
            val session = DocumentSessionKeys(delegated.clientKey, delegated.serverKey, delegated.documentUrl)
            storage.putString(documentSessionKey(grant.profileId, delegated.serverId), json.encodeToString(session))
            storage.putString(KEY_DOCUMENT_SESSION_PREFIX + delegated.serverId, json.encodeToString(session))
        }
        ensureOwnerControlProfile(node, grant, delegated.documentUrl)
        delegated.transferId?.let { transfer ->
            storage.putString(KEY_OWNER_TRANSFER_PREFIX + delegated.serverId, buildJsonObject {
                put("transfer_id", transfer); put("profile_id", grant.profileId)
                put("expires_at", minOf(grant.expiresAt, currentTimeSeconds() + 24 * 3600L))
            }.toString())
        }
        storage.remove(KEY_DELEGATION_CHECKPOINT)
        storage.remove(KEY_PENDING_ADMIN_CONTEXT)
        AdminOpResult.Success("Права ${grant.role.name} и профиль администратора сохранены", delegated.grant)
    }.getOrElse { AdminOpResult.Failure(it.message ?: "Допуск не импортирован", it) }

    fun listAdministrators(channel: AdminChannel = AdminChannel.DOCUMENT,
                           node: ServerNode? = null): Result<List<AdministratorRecord>> =
        executeAdminApiCommand("{\"op\":\"list-admins\"}", targetServer = node, channel = channel) {
            json.decodeFromJsonElement<List<AdministratorRecord>>(it)
        }

    fun revokeAdministrator(deviceId: String, node: ServerNode,
                            channel: AdminChannel = AdminChannel.DOCUMENT): Result<Unit> {
        val payload = buildJsonObject {
            put("op", "revoke-admin"); put("target_device", deviceId); put("server_id", node.id)
        }.toString()
        return executeAdminApiCommand(payload, targetServer = node, channel = channel) { Unit }.map {
            if (deviceId == keyManager.getFingerprint()) removeLocalAdminGrant(node.id)
        }
    }

    fun createOwnerTransferAcceptanceLink(serverId: String): String? = runCatching {
        val grant = verifiedGrantForServer(serverId)?.takeIf { it.role == AdminRole.OWNER }
            ?: error("Новый допуск владельца ещё не подтверждён")
        val transfer = storage.getString(KEY_OWNER_TRANSFER_PREFIX + serverId, null)
            ?.let { json.parseToJsonElement(it).jsonObject } ?: error("Нет ожидающей передачи владения")
        require(transfer["profile_id"]?.jsonPrimitive?.content == grant.profileId &&
            transfer["expires_at"]?.jsonPrimitive?.content?.toLongOrNull()?.let { it > currentTimeSeconds() } == true) {
            "Подтверждение передачи истекло"
        }
        val payload = buildJsonObject {
            put("op", "accept-owner-transfer"); put("transfer_id", transfer.getValue("transfer_id"))
        }.toString()
        val acceptance = keyManager.signEnvelope(AdminEnvelope(subtype = AdminEnvelope.SUBTYPE_COMMAND,
            profileId = grant.profileId, sender = keyManager.getFingerprint(), rev = grant.revision,
            exp = minOf(grant.expiresAt, currentTimeSeconds() + 600),
            payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.toByteArray(Charsets.UTF_8))))
        AdminDelegationLink.createTransferAck(acceptance)
    }.getOrNull()

    fun completeOwnerTransfer(link: String, node: ServerNode,
                              channel: AdminChannel = AdminChannel.DOCUMENT): Result<Unit> = runCatching {
        val acceptance = AdminDelegationLink.parseTransferAck(link) ?: error("Некорректное подтверждение передачи")
        val body = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(acceptance.payload), Charsets.UTF_8)).jsonObject
        require(body["op"]?.jsonPrimitive?.content == "accept-owner-transfer" && acceptance.exp > currentTimeSeconds() &&
            acceptance.profileId == verifiedGrantForServer(node.id)?.profileId) { "Подтверждение относится к другому профилю или истекло" }
        val payload = buildJsonObject {
            put("op", "complete-owner-transfer"); put("server_id", node.id)
            put("parameters", buildJsonObject {
                put("transfer_id", body.getValue("transfer_id")); put("acceptance", acceptance.toJson())
            })
        }.toString()
        executeAdminApiCommand(payload, targetServer = node, channel = channel) { Unit }.getOrThrow()
        removeLocalAdminGrant(node.id)
        Unit
    }

    fun removeLocalAdminGrant(serverId: String) {
        storage.putString(KEY_REVOKED_SERVER_IDS, json.encodeToString((revokedServerIds() + serverId).toList()))
        storage.remove(KEY_ACTIVE_GRANT_PREFIX + serverId)
        storage.remove(KEY_ACTIVE_GRANT_ENVELOPE_PREFIX + serverId)
        if (_activeGrantFlow.value?.serverId == serverId || legacyGrantBelongsTo(serverId)) {
            storage.remove(KEY_ACTIVE_GRANT)
            storage.remove(KEY_ACTIVE_GRANT_ENVELOPE)
            _activeGrantFlow.value = _serverNodesFlow.value.firstNotNullOfOrNull { verifiedGrantForServer(it.id) }
            _roleFlow.value = _activeGrantFlow.value?.role ?: AdminRole.NONE
        }
    }

    private fun legacyGrantBelongsTo(serverId: String): Boolean {
        val legacy = storage.getString(KEY_ACTIVE_GRANT, null)
            ?.let { runCatching { json.decodeFromString<AdminGrant>(it) }.getOrNull() }
            ?: return false
        return legacy.serverId == serverId ||
            (legacy.serverId.isNullOrBlank() && _serverNodesFlow.value.singleOrNull()?.id == serverId)
    }

    fun getPinnedServerPublicKey(serverId: String? = null): String? {
        if (serverId.isNullOrBlank()) return storage.getString(KEY_PINNED_SERVER_KEY, null)
        val scoped = storage.getString(KEY_PINNED_SERVER_KEY_PREFIX + serverId, null)
        if (!scoped.isNullOrBlank()) return scoped
        return storage.getString(KEY_PINNED_SERVER_KEY, null)?.takeIf { legacyGrantBelongsTo(serverId) }
    }

    fun setPinnedServerPublicKey(keyBase64: String, serverId: String? = null) {
        if (!serverId.isNullOrBlank()) {
            storage.putString(KEY_PINNED_SERVER_KEY_PREFIX + serverId, keyBase64)
        }
        val legacy = storage.getString(KEY_PINNED_SERVER_KEY, null)
        if (legacy.isNullOrBlank() || serverId == null) {
            storage.putString(KEY_PINNED_SERVER_KEY, keyBase64)
        }
        Logx.i(TAG, "Pinned server public key: ${keyManager.computeFingerprint(keyBase64)}")
    }

    fun getActiveGrant(serverId: String? = null): AdminGrant? {
        if (!serverId.isNullOrBlank()) {
            val grantJson = storage.getString(KEY_ACTIVE_GRANT_PREFIX + serverId, null)
            val grant = grantJson?.let { runCatching { json.decodeFromString<AdminGrant>(it) }.getOrNull() }
            if (grant != null) return grant.takeIf { it.serverId.isNullOrBlank() || it.serverId == serverId }
            if (!legacyGrantBelongsTo(serverId)) return null
        }
        val legacyJson = storage.getString(KEY_ACTIVE_GRANT, null)
        return legacyJson?.let { runCatching { json.decodeFromString<AdminGrant>(it) }.getOrNull() }
    }

    fun getActiveGrantEnvelope(serverId: String? = null): AdminEnvelope? {
        if (!serverId.isNullOrBlank()) {
            val envJson = storage.getString(KEY_ACTIVE_GRANT_ENVELOPE_PREFIX + serverId, null)
            val env = envJson?.let { runCatching { json.decodeFromString<AdminEnvelope>(it) }.getOrNull() }
            if (env != null) return env
            if (!legacyGrantBelongsTo(serverId)) return null
        }
        val legacyJson = storage.getString(KEY_ACTIVE_GRANT_ENVELOPE, null)
        return legacyJson?.let { runCatching { json.decodeFromString<AdminEnvelope>(it) }.getOrNull() }
    }

    private fun verifiedGrantForServer(serverId: String?): AdminGrant? {
        val grant = getActiveGrant(serverId) ?: return null
        if ((serverId ?: grant.serverId ?: "legacy") in revokedServerIds()) return null
        val envelope = getActiveGrantEnvelope(serverId) ?: return null
        val pin = getPinnedServerPublicKey(serverId) ?: return null
        if (serverId != null && grant.serverId != null && grant.serverId != serverId) return null
        return grant.takeIf {
            validGrantFields(envelope, it, pin) && keyManager.verifyServerResponse(envelope, pin, pin)
        }
    }

    private fun documentSessionKey(profileId: String, serverId: String?): String =
        KEY_DOCUMENT_SESSION_PREFIX + if (serverId == null) profileId else "$serverId:$profileId"

    private fun legacyCommandServerId(hostConfig: SshHostConfig?, allowedServerIds: List<String>): String? {
        val matches = _serverNodesFlow.value.filter { node ->
            (allowedServerIds.isEmpty() || node.id in allowedServerIds) &&
                (hostConfig == null || (node.host == hostConfig.host && node.port == hostConfig.port &&
                    node.hostPublicKey == hostConfig.hostPublicKey))
        }
        // A legacy command without an explicit target must fail closed when
        // more than one node matches; callers render a needs-choice state.
        return if (matches.size != 1) null else matches[0].id
    }

    val sshKeyManager: AdminSshKeyManager = AdminSshKeyManager(context)

    fun hasAdminSshKey(): Boolean = sshKeyManager.hasAdminSshKey()

    fun getAdminSshPublicKey(): String? = sshKeyManager.getAdminSshPublicKey()

    fun generateAdminSshKey(): Pair<String, String> {
        val pair = sshKeyManager.generateSshKeyPair()
        setAdminSshKeyPath(pair.first)
        return pair
    }

    fun importAdminSshPrivateKey(pemContent: String, passphrase: String? = null): Boolean {
        val success = sshKeyManager.importSshPrivateKey(pemContent, passphrase)
        if (success) {
            setAdminSshKeyPath(sshKeyManager.getEffectiveAdminSshKeyPath())
        }
        return success
    }

    fun getEffectiveAdminSshKeyPath(): String? {
        val configured = storage.getString(KEY_ADMIN_SSH_KEY_PATH, null)
        if (!configured.isNullOrBlank() && java.io.File(configured).exists()) return configured
        return sshKeyManager.getEffectiveAdminSshKeyPath()
    }

    fun setAdminSshKeyPath(path: String?) {
        if (path == null) {
            storage.remove(KEY_ADMIN_SSH_KEY_PATH)
        } else {
            storage.putString(KEY_ADMIN_SSH_KEY_PATH, path)
        }
    }

    /** Imports an owner grant copied from an authenticated local SSH session.
     * The expected fingerprint must be read separately from that session.
     */
    fun importTrustedBootstrapGrant(envelopeJson: String, expectedFingerprint: String): AdminOpResult {
        val envelope = AdminEnvelope.fromJson(envelopeJson)
            ?: return AdminOpResult.Failure("Invalid signed grant envelope")
        val grant = runCatching {
            json.decodeFromString<AdminGrant>(
                String(java.util.Base64.getUrlDecoder().decode(envelope.payload), Charsets.UTF_8)
            )
        }.getOrNull() ?: return AdminOpResult.Failure("Invalid grant payload")
        val serverKey = grant.serverPublicKey
        val fingerprint = keyManager.computeFingerprint(serverKey)
            ?: return AdminOpResult.Failure("Invalid server public key")
        if (fingerprint != expectedFingerprint.trim()) {
            return AdminOpResult.Failure("Server key fingerprint does not match the trusted SSH session")
        }
        val serverId = grant.serverId
        val previousPin = getPinnedServerPublicKey(serverId)
        if (previousPin != null && previousPin != serverKey) {
            return AdminOpResult.Failure("Server key differs from the pinned key")
        }
        if (!keyManager.verifyServerResponse(envelope, serverKey, serverKey)) {
            return AdminOpResult.Failure("Invalid server signature")
        }
        if (previousPin == null) setPinnedServerPublicKey(serverKey, serverId)
        val result = applyAdminGrant(envelope, serverKey)
        if (result is AdminOpResult.Failure && previousPin == null) {
            storage.remove(if (serverId == null) KEY_PINNED_SERVER_KEY else KEY_PINNED_SERVER_KEY_PREFIX + serverId)
        }
        return result
    }

    /**
     * Submits an ADMIN_JOIN_REQUEST to request administrator capability for this device.
     */
    fun createJoinRequest(
        targetProfileId: String,
        desiredRole: AdminRole = AdminRole.OWNER,
        setupToken: String? = null,
        clientEphemeralKey: String? = null
    ): Pair<AdminEnvelope, String> {
        require(storage.getString(KEY_DELEGATION_CHECKPOINT, null) == null) {
            "Сначала завершите или отмените предыдущее подтверждение прав"
        }
        val nonce = UUID.randomUUID().toString()
        val oneTimeCode = keyManager.generateOneTimeCode(nonce)
        val fingerprint = keyManager.getFingerprint()
        val publicKeyBase64 = keyManager.getPublicKeyBase64()

        val payloadMap = mutableMapOf(
            "device_id" to fingerprint,
            "public_key" to publicKeyBase64,
            "profile_id" to targetProfileId,
            "role" to desiredRole.name.lowercase(),
            "one_time_code" to oneTimeCode
        )
        if (!setupToken.isNullOrBlank()) {
            payloadMap["setup_token"] = setupToken.trim()
        }
        if (!clientEphemeralKey.isNullOrBlank()) {
            payloadMap["client_ephemeral_key"] = clientEphemeralKey
        }
        val payloadStr = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.encodeToString(payloadMap).toByteArray(Charsets.UTF_8))

        val envelope = AdminEnvelope(
            ver = 1,
            type = AdminEnvelope.TYPE_ADMIN,
            subtype = AdminEnvelope.SUBTYPE_REQUEST,
            profileId = targetProfileId,
            opId = UUID.randomUUID().toString(),
            rev = 1L,
            exp = currentTimeSeconds() + 600L, // 10 minutes
            sender = fingerprint,
            nonce = nonce,
            payload = payloadStr
        )

        val signed = keyManager.signEnvelope(envelope)

        // Store pending request
        storage.putString(KEY_PENDING_REQUEST, json.encodeToString(signed))
        storage.putString(KEY_LAST_PAIRING_CODE, oneTimeCode)
        _pendingRequestFlow.value = signed
        _lastPairingCodeFlow.value = oneTimeCode
        // Requesting access to a second server does not remove existing capabilities.
        if (!hasAdminReadAccess()) _roleFlow.value = AdminRole.PENDING

        recordCommand(
            AdminCommandRecord(
                opId = signed.opId,
                targetProfileId = targetProfileId,
                commandType = "request",
                status = AdminCommandRecord.STATUS_PENDING,
                channel = "document",
                timestamp = System.currentTimeMillis(),
                revision = 1L,
                payload = "Join request for role ${desiredRole.name}"
            )
        )

        Logx.i(TAG, "Created ADMIN_JOIN_REQUEST opId=${signed.opId}, fingerprint=$fingerprint")
        return Pair(signed, oneTimeCode)
    }

    /** Claims the first owner over the existing editable profile document. */
    suspend fun claimOwnerThroughDocument(documentUrl: String, profileId: String, setupToken: String): AdminOpResult =
        withContext(Dispatchers.IO) {
            val appContext = context ?: return@withContext AdminOpResult.Failure("Android context unavailable")
            if (profileId.isBlank() || setupToken.isBlank()) {
                return@withContext AdminOpResult.Failure("Укажите ID профиля и одноразовый ключ")
            }
            val resolvedDocument = runCatching { AdminDocumentClaimClient.resolveDocumentUrl(documentUrl) }
                .getOrElse { return@withContext AdminOpResult.Failure(it.message ?: "Не удалось открыть документ") }
            val stateFile = AdminDocumentClaimClient.stateFileFor(appContext, resolvedDocument, profileId.trim())
            val generator = KeyPairGenerator.getInstance("EC")
            generator.initialize(ECGenParameterSpec("secp256r1"))
            val ephemeral = generator.generateKeyPair()
            val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ephemeral.public.encoded)
            val privateKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ephemeral.private.encoded)
            val join = if (stateFile.isFile) {
                _pendingRequestFlow.value?.takeIf { it.profileId == profileId.trim() }
                    ?: return@withContext AdminOpResult.Failure("Найден незавершённый запрос без локальной записи ключа устройства")
            } else {
                createJoinRequest(profileId.trim(), AdminRole.OWNER, clientEphemeralKey = publicKey).first
            }
            val claim = runCatching {
                AdminDocumentClaimClient(appContext).claim(resolvedDocument, profileId.trim(), setupToken.trim(), join, privateKey)
            }.getOrElse {
                updateCommandStatus(join.opId, AdminCommandRecord.STATUS_FAILED, error = it.message ?: "Ошибка документной привязки")
                return@withContext AdminOpResult.Failure(it.message ?: "Ошибка документной привязки")
            }
            val claimGrant = runCatching {
                json.decodeFromString<AdminGrant>(
                    String(Base64.getUrlDecoder().decode(claim.grant.payload), Charsets.UTF_8)
                )
            }.getOrNull() ?: return@withContext AdminOpResult.Failure("Некорректный допуск сервера")
            val serverId = claimGrant.serverId
            val previousPin = getPinnedServerPublicKey(serverId)
            if (previousPin != null && previousPin != claim.serverPublicKey) {
                return@withContext AdminOpResult.Failure("Ключ сервера отличается от ранее закреплённого")
            }
            if (previousPin == null) setPinnedServerPublicKey(claim.serverPublicKey, serverId)
            val result = applyAdminGrant(claim.grant, claim.serverPublicKey)
            if (result is AdminOpResult.Failure) {
                if (previousPin == null) {
                    storage.remove(if (serverId == null) KEY_PINNED_SERVER_KEY else KEY_PINNED_SERVER_KEY_PREFIX + serverId)
                }
                return@withContext result
            }
            val docKeys = json.encodeToString(DocumentSessionKeys(claim.clientKey, claim.serverKey, resolvedDocument))
            storage.putString(documentSessionKey(profileId.trim(), serverId), docKeys)
            if (serverId != null) {
                storage.putString(KEY_DOCUMENT_SESSION_PREFIX + serverId, docKeys)
            }
            stateFile.delete()
            result
        }

    /**
     * Validates and applies an incoming ADMIN_GRANT from the server.
     * Enforces signature verification and server public key pinning.
     */
    fun applyAdminGrant(
        grantEnvelope: AdminEnvelope,
        serverPublicKeyBase64: String
    ): AdminOpResult {
        val grant = try {
            json.decodeFromString<AdminGrant>(String(java.util.Base64.getUrlDecoder().decode(grantEnvelope.payload), Charsets.UTF_8))
        } catch (_: Exception) { return AdminOpResult.Failure("Invalid administrator grant payload") }
        val pinnedKey = getPinnedServerPublicKey(grant.serverId)
            ?: return AdminOpResult.Failure("Server key is not pinned from a trusted local source")
        val pending = _pendingRequestFlow.value
            ?: return AdminOpResult.Failure("No pending administrator request")
        val isValid = keyManager.verifyServerResponse(
            envelope = grantEnvelope,
            serverPublicKeyBase64 = serverPublicKeyBase64,
            pinnedServerPublicKeyBase64 = pinnedKey
        )

        if (!isValid) {
            val err = "Подпись сервера на ADMIN_GRANT недействительна или не совпадает с закреплённым ключом!"
            Logx.e(TAG, err)
            return AdminOpResult.Failure(err)
        }

        if (pending.profileId != grantEnvelope.profileId || pending.nonce != grantEnvelope.nonce ||
            !validGrantFields(grantEnvelope, grant, pinnedKey)
        ) return AdminOpResult.Failure("Administrator grant does not match this request")

        // Save grant
        try {
            val effServerId = grant.serverId ?: _serverNodesFlow.value.singleOrNull()?.id
            if (!effServerId.isNullOrBlank()) {
                storage.putString(KEY_ACTIVE_GRANT_PREFIX + effServerId, json.encodeToString(grant))
                storage.putString(KEY_ACTIVE_GRANT_ENVELOPE_PREFIX + effServerId, json.encodeToString(grantEnvelope))
                storage.putString(KEY_PINNED_SERVER_KEY_PREFIX + effServerId, serverPublicKeyBase64)
            }
            val legacyPin = getPinnedServerPublicKey()
            if (legacyPin == null || legacyPin == serverPublicKeyBase64) {
                storage.putString(KEY_ACTIVE_GRANT, json.encodeToString(grant))
                storage.putString(KEY_ACTIVE_GRANT_ENVELOPE, json.encodeToString(grantEnvelope))
                storage.putString(KEY_PINNED_SERVER_KEY, serverPublicKeyBase64)
            }
            storage.putString(KEY_ROLE, grant.role.name)
            storage.remove(KEY_PENDING_REQUEST)
            storage.remove(KEY_LAST_PAIRING_CODE)
        } catch (e: Exception) {
            Logx.e(TAG, "Failed to persist administrator grant", e)
            return AdminOpResult.Failure("Ошибка сохранения допуска администратора: ${e.message}")
        }

        _activeGrantFlow.value = grant
        _pendingRequestFlow.value = null
        _lastPairingCodeFlow.value = null
        _roleFlow.value = grant.role
        storage.putString(KEY_REVOKED_SERVER_IDS, json.encodeToString(
            (revokedServerIds() - (grant.serverId ?: "legacy")).toList()))
        grant.serverId?.let { serverId ->
            getServerNode(serverId)?.let { node -> ensureOwnerControlProfile(node, grant, defaultDocumentUrl(serverId).orEmpty()) }
        }

        updateCommandStatus(grantEnvelope.opId, AdminCommandRecord.STATUS_VERIFIED, grantEnvelope.rev)

        Logx.i(TAG, "ADMIN_GRANT verified and applied successfully. Role elevated to ${grant.role}")
        return AdminOpResult.Success("Права администратора подтверждены: роль ${grant.role}", grantEnvelope)
    }

    /**
     * Claims ownership of a server node using a Setup Key / Claim URI.
     * Generates a signed ADMIN_JOIN_REQUEST with setup_token,
     * sends it to the server via direct SSH channel,
     * applies the returned ADMIN_GRANT, pins server public key,
     * and adds the new ServerNode to the cluster.
     */
    fun claimServerWithSetupKey(
        claimInfo: ServerClaimInfo,
        hostPublicKey: String? = null,
        privateKeyPath: String? = null,
        password: String? = null,
        targetProfileId: String = "admin-global"
    ): AdminOpResult {
        val verifiedHostKey = hostPublicKey?.trim()?.takeIf { it.isNotEmpty() }
            ?: return AdminOpResult.Failure("Сначала сверьте и укажите публичный SSH-ключ сервера")
        // 1. Create join request with setup token
        val (requestEnvelope, _) = createJoinRequest(
            targetProfileId = targetProfileId,
            desiredRole = AdminRole.OWNER,
            setupToken = claimInfo.token
        )

        // 2. Prepare host config
        val effectiveAuth = if (!password.isNullOrBlank()) SshAuthType.PASSWORD else SshAuthType.KEY
        val effectiveKeyPath = privateKeyPath ?: getEffectiveAdminSshKeyPath() ?: "/data/data/io.github.libreroute/files/libreroute_admin_key"
        val hostConfig = SshHostConfig(
            host = claimInfo.host,
            port = claimInfo.port,
            username = "root",
            authType = effectiveAuth,
            privateKeyPath = effectiveKeyPath,
            password = password,
            hostPublicKey = verifiedHostKey,
            allowPasswordAuth = true
        )

        // 3. Execute request via direct SSH channel
        val sshResult = sshClient.executeAdminEnvelope(
            envelope = requestEnvelope,
            hostConfig = hostConfig,
            channel = AdminChannel.DIRECT_SSH
        )

        if (sshResult is AdminOpResult.Failure) {
            updateCommandStatus(requestEnvelope.opId, AdminCommandRecord.STATUS_FAILED, error = sshResult.error)
            return sshResult
        }

        val respEnvelope = (sshResult as AdminOpResult.Success).envelope
            ?: return AdminOpResult.Failure("Сервер не вернул конверт ответа")

        // 4. Parse server response
        val responsePayloadJson = runCatching {
            val raw = java.util.Base64.getUrlDecoder().decode(respEnvelope.payload)
            json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return AdminOpResult.Failure("Некорректный формат ответа сервера")

        val status = responsePayloadJson["status"]?.jsonPrimitive?.content
        if (status != "applied") {
            val error = responsePayloadJson["error"]?.jsonPrimitive?.content ?: "Сервер отклонил запрос сопряжения"
            updateCommandStatus(requestEnvelope.opId, AdminCommandRecord.STATUS_FAILED, error = error)
            return AdminOpResult.Failure(error)
        }

        // 5. Extract grant from response Data
        val grantData = responsePayloadJson["data"]?.jsonObject
            ?: return AdminOpResult.Failure("Ответ сервера не содержит данных допуска")

        val grantEnvelope = runCatching {
            json.decodeFromJsonElement<AdminEnvelope>(grantData)
        }.getOrNull() ?: return AdminOpResult.Failure("Некорректный формат конверта допуска")

        val grant = runCatching {
            val raw = java.util.Base64.getUrlDecoder().decode(grantEnvelope.payload)
            json.decodeFromString<AdminGrant>(String(raw, Charsets.UTF_8))
        }.getOrNull() ?: return AdminOpResult.Failure("Некорректный формат тела допуска")

        // 6. Pin server public key and apply grant
        if (grant.serverId.isNullOrBlank() && _serverNodesFlow.value.isNotEmpty()) {
            return AdminOpResult.Failure("Сервер не указал server_id для безопасной привязки второго узла")
        }
        val previousPin = getPinnedServerPublicKey(grant.serverId)
        if (previousPin != null && previousPin != grant.serverPublicKey) {
            return AdminOpResult.Failure("Публичный ключ сервера отличается от закреплённого")
        }
        if (previousPin == null) setPinnedServerPublicKey(grant.serverPublicKey, grant.serverId)
        val applyResult = applyAdminGrant(grantEnvelope, grant.serverPublicKey)
        if (applyResult is AdminOpResult.Failure) {
            if (previousPin == null && grant.serverId != null) {
                storage.remove(KEY_PINNED_SERVER_KEY_PREFIX + grant.serverId)
            }
            return applyResult
        }

        // 7. Automatically add this server to the dynamic ServerNode cluster
        val serverName = claimInfo.name ?: "Сервер ${claimInfo.host}"
        val newNode = ServerNode(
            id = grant.serverId ?: UUID.randomUUID().toString(),
            name = serverName,
            nodeType = if (claimInfo.port == 222) ServerNodeType.ROUTER_KEENETIC else ServerNodeType.VDS,
            host = claimInfo.host,
            port = claimInfo.port,
            sshUser = "root",
            authType = hostConfig.authType,
            privateKeyPath = hostConfig.privateKeyPath,
            password = hostConfig.password,
            passphrase = hostConfig.passphrase,
            allowPasswordAuth = hostConfig.allowPasswordAuth,
            hostPublicKey = verifiedHostKey,
            status = ServerNodeStatus.ONLINE,
            lastSeenAt = System.currentTimeMillis()
        )
        addServerNode(newNode)
        if (grant.role == AdminRole.OWNER) ensureOwnerControlProfile(newNode, grant, "")

        updateCommandStatus(requestEnvelope.opId, AdminCommandRecord.STATUS_APPLIED)
        return AdminOpResult.Success("Сервер «${serverName}» успешно привязан. Права Владельца подтверждены!", grantEnvelope)
    }

    /**
     * Drops local service capabilities without forgetting independent SSH
     * connections, trusted host keys or VPN/control profiles.
     */
    fun revokeAdminRights() {
        val serverIds = _serverNodesFlow.value.map { it.id } + listOfNotNull(_activeGrantFlow.value?.serverId)
        storage.putString(KEY_REVOKED_SERVER_IDS, json.encodeToString((revokedServerIds() + serverIds + "legacy").toList()))
        for (serverId in serverIds.distinct()) {
            storage.remove(KEY_ACTIVE_GRANT_PREFIX + serverId)
            storage.remove(KEY_ACTIVE_GRANT_ENVELOPE_PREFIX + serverId)
        }
        storage.remove(KEY_ACTIVE_GRANT)
        storage.remove(KEY_ACTIVE_GRANT_ENVELOPE)
        storage.remove(KEY_ROLE)
        storage.remove(KEY_PENDING_REQUEST)
        storage.remove(KEY_LAST_PAIRING_CODE)
        storage.remove(KEY_PENDING_ADMIN_CONTEXT)
        storage.remove(KEY_DELEGATION_CHECKPOINT)

        _activeGrantFlow.value = null
        _pendingRequestFlow.value = null
        _lastPairingCodeFlow.value = null
        _roleFlow.value = AdminRole.NONE

        Logx.i(TAG, "Local administrator access revoked. Role reset to NONE")
    }

    /**
     * Creates a new managed profile.
     */
    fun createManagedProfile(
        name: String,
        documentUrl: String = "",
        transportType: String = "vyandex",
        sessionNegotiated: Boolean = true,
        udpEnabled: Boolean = false,
        allowedDestCIDRs: List<String> = emptyList(),
        allowedServerIds: List<String> = emptyList(),
        allowedProtocols: List<String> = listOf("vyandex", "udp-ipv4"),
        serverConfigs: Map<String, ProfileServerConfig> = emptyMap(),
        channel: AdminChannel = AdminChannel.DOCUMENT,
        hostConfig: SshHostConfig? = null,
        profileIdOverride: String? = null,
        operationIdOverride: String? = null,
        jitsiToken: String = ""
    ): AdminOpResult {
        val endpointTransport = runCatching { io.github.libreroute.data.TransportType.from(transportType) }.getOrNull()
        val validDocumentUrl = if (documentUrl.isBlank() && endpointTransport in setOf(io.github.libreroute.data.TransportType.mqtt, io.github.libreroute.data.TransportType.jitsi)) {
            // Broker/topic are resolved by the server preset; no user-facing URL is needed.
            true
        } else runCatching {
            val parsed = java.net.URI(documentUrl)
            when (endpointTransport) {
                io.github.libreroute.data.TransportType.mqtt,
                io.github.libreroute.data.TransportType.jitsi ->
                    io.github.libreroute.util.ServiceRouteEndpoint.isValid(endpointTransport!!, documentUrl)
                else -> parsed.scheme == "https" && parsed.userInfo == null && parsed.port == -1 &&
                    !parsed.path.isNullOrBlank() &&
                    (parsed.host == "yandex.ru" || parsed.host?.endsWith(".yandex.ru") == true)
            }
        }.getOrDefault(false)
        if (!validDocumentUrl) {
            return AdminOpResult.Failure("Укажите корректный endpoint выбранного транспорта или выберите серверный preset")
        }
        if (channel != AdminChannel.DOCUMENT && hostConfig == null) {
            return AdminOpResult.Failure("Параметры SSH-сервера не настроены")
        }
        val serverId = legacyCommandServerId(hostConfig, allowedServerIds)
        if (_serverNodesFlow.value.isNotEmpty() && serverId == null) {
            return AdminOpResult.Failure("Выберите один сервер для создания профиля")
        }
        if (!hasManagePermission()) {
            return AdminOpResult.Failure("Недостаточно прав для создания профиля. Требуется роль OWNER.")
        }

        val profileId = profileIdOverride?.takeIf { it.isNotBlank() }
            ?: ("libreroute-" + UUID.randomUUID().toString().take(8))
        val newProfile = ManagedProfile(
            id = profileId,
            name = name,
            status = ManagedProfile.STATUS_READY,
            revision = 1L,
            transportType = transportType,
            sessionNegotiated = sessionNegotiated && !transportType.equals("mqtt", ignoreCase = true),
            udpEnabled = udpEnabled,
            allowedDestCIDRs = allowedDestCIDRs,
            allowedServerIds = allowedServerIds,
            allowedProtocols = allowedProtocols,
            serverConfigs = serverConfigs,
            serverId = serverId,
            lastCheckTimestamp = System.currentTimeMillis()
        )

        val command = buildJsonObject {
            put("op", "create")
            serverId?.let { put("server_id", it) }
            put("profile_id", profileId)
            // The worker uses its name as a file/service identifier. Keep the
            // user's display name in the local profile, and send a stable ASCII
            // name when it contains spaces, punctuation or non-Latin letters.
            val workerNamePattern = Regex("[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}")
            val workerName = when {
                workerNamePattern.matches(name) -> name
                workerNamePattern.matches(profileId) -> profileId
                else -> "lr-${UUID.nameUUIDFromBytes(profileId.toByteArray(Charsets.UTF_8))}"
            }
            put("name", workerName)
            put("url", documentUrl)
            put("parameters", buildJsonObject {
                put("transport", transportType)
                put("session_negotiate", (sessionNegotiated && !transportType.equals("mqtt", ignoreCase = true)).toString())
                put("udp_enabled", udpEnabled.toString())
                if (allowedDestCIDRs.isNotEmpty()) {
                    put("allowed_dest_cidrs", allowedDestCIDRs.joinToString(","))
                }
                if (allowedServerIds.isNotEmpty()) {
                    put("allowed_server_ids", allowedServerIds.joinToString(","))
                }
                if (allowedProtocols.isNotEmpty()) {
                    put("allowed_protocols", allowedProtocols.joinToString(","))
                }
                if (serverConfigs.isNotEmpty()) {
                    put("server_configs", json.encodeToString(serverConfigs))
                }
                if (transportType.equals("jitsi", true) && jitsiToken.isNotBlank()) {
                    put("jitsi_token", jitsiToken)
                }
            })
        }
        val prepared = runCatching { preparePolicyCommand(command.toString(), channel, policyServerForLegacy(serverId, hostConfig)) }
            .getOrElse { return AdminOpResult.Failure(it.message ?: "Policy revision unavailable") }
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(prepared.toByteArray(Charsets.UTF_8))
        val envelope = AdminEnvelope(
            ver = 1,
            type = AdminEnvelope.TYPE_ADMIN,
            subtype = AdminEnvelope.SUBTYPE_COMMAND,
            profileId = verifiedGrantForServer(serverId)?.profileId
                ?: return AdminOpResult.Failure("No administrator profile is bound to this server"),
            opId = operationIdOverride?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            rev = 1L,
            sender = keyManager.getFingerprint(),
            payload = payload
        )
        val signed = keyManager.signEnvelope(envelope)

        recordCommand(
            AdminCommandRecord(
                opId = signed.opId,
                targetProfileId = profileId,
                commandType = "create",
                status = AdminCommandRecord.STATUS_PENDING,
                channel = channel.name.lowercase(),
                timestamp = System.currentTimeMillis(),
                revision = 1L,
                payload = "Create profile $name"
            )
        )

        val sshResult = confirmServerResponse(
            if (channel == AdminChannel.DOCUMENT) executeDocumentCommand(signed, serverId)
            else sshClient.executeAdminEnvelope(signed, hostConfig!!, channel), signed, serverId)
        if (sshResult is AdminOpResult.Failure) {
            updateCommandStatus(signed.opId,
                if (sshResult.throwable is ConfirmedAdminRejection) AdminCommandRecord.STATUS_FAILED
                else AdminCommandRecord.STATUS_UNKNOWN, error = sshResult.error)
            return sshResult
        }
        val responseEnvelope = (sshResult as? AdminOpResult.Success)?.envelope
            ?: return AdminOpResult.Failure("Сервер не вернул подписанный ответ на создание профиля")

        // A remote commit is not a usable administrator connection until the
        // signed route has been saved locally. Keep an ambiguous operation
        // recoverable if local materialization fails after server commit.
        val materialized = runCatching { materializeProvisionedTunnel(newProfile, responseEnvelope) }
        if (materialized.isFailure) {
            val reason = "Сервер создал маршрут, но приложение не сохранило подключение: ${materialized.exceptionOrNull()?.message.orEmpty()}"
            updateCommandStatus(signed.opId, AdminCommandRecord.STATUS_UNKNOWN, error = reason)
            return AdminOpResult.Failure(reason, UnknownAdminOperation(reason))
        }
        // Commit profile locally
        val current = _managedProfilesFlow.value.toMutableList()
        current.removeAll { it.id == newProfile.id }
        current.add(newProfile)
        saveProfiles(current)
        updateCommandStatus(signed.opId, AdminCommandRecord.STATUS_APPLIED, newProfile.revision)

        return AdminOpResult.Success("Профиль «${name}» успешно создан", responseEnvelope)
    }

    /**
     * Materializes the generated route returned in the authenticated create
     * response. The secret is written only through the encrypted tunnel store.
     */
    private fun materializeProvisionedTunnel(profile: ManagedProfile, response: AdminEnvelope) {
        val ctx = context ?: return
        val tunnel = provisionedTunnel(profile, response)
        val store = TunnelRepository(ctx)
        val existing = store.load().filterNot {
            it.name == profile.name && it.transportType.equals(profile.transportType, true)
        }
        store.save(existing + TunnelLinkParser.ensureLocalKeyFile(ctx, tunnel))
    }

    internal fun provisionedTunnel(profile: ManagedProfile, response: AdminEnvelope): Tunnel {
        val body = json.parseToJsonElement(
            String(Base64.getUrlDecoder().decode(response.payload), Charsets.UTF_8)
        ).jsonObject
        val data = body["data"]?.jsonObject ?: error("Сервер не вернул данные маршрута")
        require(data["profile_id"]?.jsonPrimitive?.content == profile.id) { "Ответ относится к другому профилю" }
        val endpoint = data["endpoint"]?.jsonPrimitive?.content.orEmpty()
        val secret = data["secret_key"]?.jsonPrimitive?.content.orEmpty()
        val transport = data["transport"]?.jsonPrimitive?.content.orEmpty().ifBlank { profile.transportType }
        require(endpoint.isNotBlank() && secret.length >= 16 && transport == profile.transportType &&
            transport in setOf("vyandex", "mqtt", "jitsi")) { "Сервер вернул неполный маршрут" }
        val parameters = data["parameters"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val negotiated = parameters["session_negotiate"] == "true"
        return routeCandidateToTunnel(RouteCandidate(
            route_id = profile.id, name = profile.name, transport = transport,
            url = endpoint, secret_key = secret,
            codec = parameters["codec"] ?: "batched",
            encryption_layout = parameters["encryption_layout"] ?: if (negotiated) "batch" else "packet",
            parameters = parameters
        ))
    }

    /**
     * Keeps the automatically-created administrator profile in sync with the
     * protocols enabled on its server. This is a local projection of the
     * signed server grant; it makes the admin route immediately selectable in
     * the app after protocol setup without creating a second admin identity.
     */
    fun attachProtocolToOwnerProfile(serverId: String, protocol: String, documentUrl: String = ""): Boolean {
        val index = _managedProfilesFlow.value.indexOfFirst {
            it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL &&
                it.serverId == serverId && it.role == AdminRole.OWNER
        }
        if (index < 0) return false
        val profiles = _managedProfilesFlow.value.toMutableList()
        val current = profiles[index]
        val config = current.serverConfigs.toMutableMap()
        if (documentUrl.isNotBlank()) config[serverId] = ProfileServerConfig(serverId, documentUrl)
        profiles[index] = current.copy(
            allowedProtocols = (current.allowedProtocols + protocol).distinct(),
            allowedServerIds = (current.allowedServerIds + serverId).distinct(),
            serverConfigs = config,
            transportType = if (current.transportType == "ssh" || current.transportType == "document") protocol else current.transportType,
            lastCheckTimestamp = System.currentTimeMillis()
        )
        saveProfiles(profiles)
        getServerNode(serverId)?.let { node ->
            if (protocol !in node.supportedProtocols) updateServerNode(node.copy(supportedProtocols = (node.supportedProtocols + protocol).distinct()))
        }
        return true
    }

    /** Stops and revokes every user protocol profile assigned to a server. */
    fun clearServerProtocols(serverId: String, hostConfig: SshHostConfig): AdminOpResult {
        if (!hasManagePermission()) return AdminOpResult.Failure("Недостаточно прав для очистки протоколов")
        val node = getServerNode(serverId)
            ?: return AdminOpResult.Failure("Сервер не найден; очистка не запускалась")
        val remoteProfiles = listServerManagedProfiles(node).getOrElse {
            return AdminOpResult.Failure("Не удалось проверить протоколы на сервере: ${it.message.orEmpty()}",
                UnknownAdminOperation(it.message.orEmpty()))
        }.filter { it.transport in setOf("vyandex", "mqtt", "jitsi") && it.status != "revoked" }
        val targets = _managedProfilesFlow.value.filter {
            it.serverId == serverId && it.profileType != ManagedProfile.PROFILE_TYPE_CONTROL && it.status != ManagedProfile.STATUS_REVOKED
        }
        val failures = mutableListOf<String>()
        targets.forEach { profile ->
            val result = revokeProfile(profile.id, AdminChannel.DIRECT_SSH, hostConfig)
            if (result is AdminOpResult.Failure) failures += "${profile.name}: ${result.error}"
        }
        // Revoke profiles discovered on the server even when the phone lost its
        // local projection during a process death. The temporary projection is
        // removed immediately after the idempotent remote command completes.
        val known = targets.map { it.id }.toSet()
        remoteProfiles.filter { it.profileId !in known }.forEach { remote ->
            val fake = ManagedProfile(
                id = remote.profileId, name = remote.name.ifBlank { remote.profileId },
                transportType = remote.transport, allowedServerIds = listOf(serverId),
                serverId = serverId, revision = remote.revision
            )
            saveProfiles(_managedProfilesFlow.value + fake)
            val result = revokeProfile(remote.profileId, AdminChannel.DIRECT_SSH, hostConfig)
            if (result is AdminOpResult.Failure) {
                failures += "${remote.name}: ${result.error}"
            } else {
                saveProfiles(_managedProfilesFlow.value.filterNot { it.id == remote.profileId })
            }
        }
        if (failures.isNotEmpty()) return AdminOpResult.Failure(failures.joinToString("\n"))
        val profiles = _managedProfilesFlow.value.toMutableList()
        val ownerIndex = profiles.indexOfFirst {
            it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL && it.serverId == serverId && it.role == AdminRole.OWNER
        }
        if (ownerIndex >= 0) {
            val owner = profiles[ownerIndex]
            profiles[ownerIndex] = owner.copy(allowedProtocols = emptyList(), transportType = "ssh", lastCheckTimestamp = System.currentTimeMillis())
            saveProfiles(profiles)
        }
        // A successful explicit cleanup must also forget durable protocol
        // operation projections. Otherwise the next screen render would keep
        // showing a cleared transport as READY and block a fresh setup.
        _protocolOperations.value = _protocolOperations.value.filterNot { it.serverId == serverId }
        persistProtocolOperations()
        getServerNode(serverId)?.let { node ->
            val refreshed = probeServer(node)
            if (refreshed.probeError != null) return AdminOpResult.Failure("Протоколы удалены, но серверную проверку завершить не удалось: ${refreshed.probeError}")
            updateServerNode(node.copy(inventory = refreshed, supportedProtocols = refreshed.installedProtocols.distinct()))
        }
        return AdminOpResult.Success(if (targets.isEmpty() && remoteProfiles.isEmpty()) "Установленные протоколы не найдены" else "Установленные протоколы очищены")
    }

    /**
     * Probes an individual server node and protocol for pre-issuance quality verification.
     */
    fun probeServerCombination(
        server: ServerNode,
        protocol: String,
        documentUrl: String? = null,
        timeoutMs: Int = 2000
    ): ProbeResult {
        if (combinationProber != null) {
            return combinationProber.invoke(server, protocol, documentUrl, timeoutMs)
        }
        val startTime = System.currentTimeMillis()
        var reachable = false
        var message = "OK"

        // 1. Socket reachability check to server endpoint
        if (socketProbe != null) {
            reachable = socketProbe.invoke(server.host, server.port, timeoutMs)
            if (!reachable) message = "Хост недоступен (проверка сокета)"
        } else {
            try {
                java.net.Socket().use { socket ->
                    socket.connect(java.net.InetSocketAddress(server.host, server.port), timeoutMs)
                    reachable = socket.isConnected
                }
            } catch (e: Exception) {
                reachable = false
                message = "Хост недоступен: ${e.message}"
            }
        }

        val rtt = (System.currentTimeMillis() - startTime).coerceAtLeast(1L)

        // 2. Validate document URL for vyandex protocol
        if (reachable && protocol == "vyandex") {
            if (documentUrl.isNullOrBlank()) {
                reachable = false
                message = "Отсутствует адрес документа Яндекса"
            } else {
                val validDoc = runCatching {
                    val parsed = java.net.URI(documentUrl)
                    parsed.scheme == "https" && (parsed.host == "yandex.ru" || parsed.host?.endsWith(".yandex.ru") == true)
                }.getOrDefault(false)
                if (!validDoc) {
                    reachable = false
                    message = "Некорректный HTTPS-адрес документа Яндекса"
                }
            }
        }

        return ProbeResult(
            serverId = server.id,
            serverName = server.name,
            protocol = protocol,
            // SSH socket reachability and URL syntax cannot prove VPN traffic works.
            success = false,
            rttMs = if (reachable) rtt else -1L,
            message = if (reachable) "TCP доступен ($rtt мс); сквозная проверка $protocol не выполнена" else message
        )
    }

    /**
     * Runs synthetic pre-issuance tests for all server and protocol combinations
     * assigned to a managed profile.
     */
    fun testCombinations(
        profile: ManagedProfile,
        servers: List<ServerNode> = getServerNodes()
    ): List<ProbeResult> {
        val targetServers = if (profile.allowedServerIds.isNotEmpty()) {
            servers.filter { it.id in profile.allowedServerIds }
        } else {
            servers
        }

        if (targetServers.isEmpty()) {
            return listOf(
                ProbeResult(
                    serverId = "none",
                    serverName = "Нет серверов",
                    protocol = profile.transportType,
                    success = false,
                    rttMs = -1L,
                    message = "В кластере не настроено ни одного сервера для выдачи"
                )
            )
        }

        val protocols = if (profile.allowedProtocols.isNotEmpty()) {
            profile.allowedProtocols
        } else {
            listOf(profile.transportType)
        }

        val results = mutableListOf<ProbeResult>()
        for (server in targetServers) {
            val docUrl = profile.serverConfigs[server.id]?.documentUrl
            for (proto in protocols) {
                results.add(probeServerCombination(server, proto, docUrl))
            }
        }
        return results
    }

    /**
     * Issues an encrypted, server-signed invitation for a recipient device.
     * Enforces pre-flight quality verification across all routes before contacting the server.
     */
    fun issueInvitation(
        profileId: String,
        recipientPublicKeyBase64: String? = null,
        serverNode: ServerNode? = null,
        channel: AdminChannel = AdminChannel.DIRECT_SSH
    ): Pair<AdminOpResult, String> {
        if (!hasManagePermission()) {
            return Pair(AdminOpResult.Failure("Недостаточно прав для выдачи профиля. Требуется роль OWNER или OPERATOR."), "")
        }

        val profile = _managedProfilesFlow.value.firstOrNull { it.id == profileId }
            ?: return Pair(AdminOpResult.Failure("Профиль $profileId не найден"), "")

        if (profile.isRevoked) {
            return Pair(AdminOpResult.Failure("Отозванный профиль не может быть выдан"), "")
        }
        if (recipientPublicKeyBase64.isNullOrBlank()) {
            return Pair(AdminOpResult.Failure("Нужен публичный ключ устройства получателя"), "")
        }

        // Invariant (GEMINI.md): Do not show issuance successful without end-to-end probing of each combination
        val probeResults = testCombinations(profile)
        if (probeResults.any { !it.success }) {
            val failed = probeResults.first { !it.success }
            return Pair(AdminOpResult.Failure("Сквозная проверка связки «${failed.serverName}/${failed.protocol}» не пройдена: ${failed.message}"), "")
        }

        val eligibleServers = _serverNodesFlow.value.filter {
            profile.allowedServerIds.isEmpty() || it.id in profile.allowedServerIds
        }
        val targetServer = serverNode ?: eligibleServers.singleOrNull()

        if (targetServer == null) {
            return Pair(AdminOpResult.Failure("Выберите один целевой сервер для выдачи приглашения"), "")
        }

        val command = buildJsonObject {
            put("op", "issue")
            put("profile_id", profileId)
            put("device_pub", recipientPublicKeyBase64.trim())
        }
        val prepared = runCatching { preparePolicyCommand(command.toString(), channel, targetServer) }
            .getOrElse { return Pair(AdminOpResult.Failure(it.message ?: "Policy revision unavailable"), "") }
        val payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(prepared.toByteArray(Charsets.UTF_8))
        val envelope = AdminEnvelope(
            ver = 1,
            type = AdminEnvelope.TYPE_ADMIN,
            subtype = AdminEnvelope.SUBTYPE_COMMAND,
            profileId = profileId,
            opId = UUID.randomUUID().toString(),
            rev = profile.revision,
            sender = keyManager.getFingerprint(),
            payload = payload
        )
        val signed = keyManager.signEnvelope(envelope)

        recordCommand(
            AdminCommandRecord(
                opId = signed.opId,
                targetProfileId = profileId,
                commandType = "issue",
                status = AdminCommandRecord.STATUS_PENDING,
                channel = channel.name.lowercase(),
                timestamp = System.currentTimeMillis(),
                revision = profile.revision,
                payload = "Issue invitation for profile ${profile.name}"
            )
        )

        val sshResult = confirmServerResponse(
            sshClient.executeAdminEnvelope(signed, targetServer.toSshHostConfig(), channel),
            signed,
            targetServer.id
        )
        if (sshResult is AdminOpResult.Failure) {
            updateCommandStatus(signed.opId, AdminCommandRecord.STATUS_FAILED, error = sshResult.error)
            return Pair(sshResult, "")
        }

        val respEnvelope = (sshResult as AdminOpResult.Success).envelope
            ?: return Pair(AdminOpResult.Failure("Сервер не вернул конверт ответа выдачи"), "")

        val inviteLink = ServerIssuedInvitation.toLink(respEnvelope)
        updateCommandStatus(signed.opId, AdminCommandRecord.STATUS_APPLIED, profile.revision)
        return Pair(AdminOpResult.Success("Приглашение успешно сформировано сервером", respEnvelope), inviteLink)
    }

    /** Entry point for legacy bundle issuance; rejects open unverified bundles. */
    fun issueUserBundle(
        profileId: String,
        recipientPublicKeyBase64: String? = null
    ): Pair<AdminOpResult, String> {
        if (recipientPublicKeyBase64.isNullOrBlank()) {
            return Pair(AdminOpResult.Failure("Нужен публичный ключ устройства получателя"), "")
        }
        return issueInvitation(profileId, recipientPublicKeyBase64)
    }

    /**
     * Redeems a server-issued invitation on the client device.
     * Enforces:
     * 1. Decryption and cryptographic candidate verification using recipient's private ECDH key.
     * 2. Transmission of signed ADMIN_INVITATION_REDEEM to the server.
     * 3. Verification of the server's signed redemption receipt against pinnedServerPublicKey.
     * 4. Pre-flight reachability check before activating/saving the profile.
     */
    fun redeemInvitation(
        invitationLink: String,
        serverNode: ServerNode? = null,
        trustedServerPublicKeyBase64: String? = null,
        recipientCrypto: RecipientInvitationCrypto? = null
    ): Pair<AdminOpResult, Tunnel?> {
        if (invitationLink.startsWith(BootstrapInvitation.PREFIX)) return redeemBootstrapInvitation(invitationLink)
        completedRedemption(invitationLink)?.let { return it }
        val trustedKey = trustedServerPublicKeyBase64
            ?: serverNode?.let { getPinnedServerPublicKey(it.id) }
            ?: getPinnedServerPublicKey()
            ?: return Pair(AdminOpResult.Failure("Публичный ключ сервера не закреплён. Сопряжение невозможно без доверенного ключа сервера."), null)

        val crypto = recipientCrypto ?: RecipientInvitationCrypto(
            recipientIdentityStorage
        )
        resumeRedemption(invitationLink, crypto)?.let { return it }
        val invitationHandler = ServerIssuedInvitation(
            recipientCrypto = crypto,
            signatureVerifier = keyManager
        )

        // 1. Verify candidate & decrypt payload
        val candidate = try {
            invitationHandler.verifyCandidate(invitationLink, trustedKey)
        } catch (e: Exception) {
            return Pair(AdminOpResult.Failure("Не удалось расшифровать или проверить приглашение: ${e.message}"), null)
        }

        if (serverNode == null && candidate.documentUrl.isNotBlank()) {
            return redeemInvitationViaDocument(invitationLink, trustedKey, crypto)
        }

        // 2. Select target server for redemption
        val issuerServerId = candidate.serverId.ifBlank {
            candidate.routes.map { it.server_id }.filter { it.isNotBlank() }.distinct().singleOrNull()
                ?: candidate.parameters["allowed_server_ids"]?.split(",")
                    ?.map { it.trim() }?.filter { it.isNotBlank() }?.distinct()?.singleOrNull().orEmpty()
        }
        if (serverNode != null && issuerServerId.isNotBlank() && serverNode.id != issuerServerId) {
            return Pair(AdminOpResult.Failure("Приглашение выпущено другим сервером: $issuerServerId"), null)
        }
        val targetServer = serverNode
            ?: _serverNodesFlow.value.singleOrNull { issuerServerId.isNotBlank() && it.id == issuerServerId }
            ?: _serverNodesFlow.value.singleOrNull().takeIf { issuerServerId.isBlank() }

        if (targetServer == null) {
            return Pair(AdminOpResult.Failure("Нет однозначного сервера для подтверждения погашения приглашения"), null)
        }
        val scopedPin = getPinnedServerPublicKey(targetServer.id)
        if (scopedPin != null && scopedPin != trustedKey) {
            return Pair(AdminOpResult.Failure("Ключ приглашения не совпадает с закреплённым ключом сервера"), null)
        }

        // 3. Create signed redemption request
        val redeemEnv = redemptionCheckpoint(invitationLink)?.request?.let(AdminEnvelope::fromJson)
            ?: invitationHandler.createRedemption(candidate)
        saveRedemptionCheckpoint(ClientRedemptionCheckpoint(invitationHash(invitationLink), redeemEnv.toJson(), trustedKey))

        // 4. Send redemption request to server via direct SSH
        val sshResult = sshClient.executeAdminEnvelope(
            envelope = redeemEnv,
            serverNode = targetServer
        )
        if (sshResult is AdminOpResult.Failure) {
            return Pair(AdminOpResult.Failure("Не удалось передать подтверждение погашения на сервер: ${sshResult.error}"), null)
        }

        val receiptEnv = (sshResult as AdminOpResult.Success).envelope
            ?: return Pair(AdminOpResult.Failure("Сервер не вернул квитанцию о погашении"), null)

        // 5. Verify server's signed redemption receipt
        val verified = invitationHandler.verifyRedemptionReceipt(
            request = redeemEnv,
            receipt = receiptEnv,
            candidate = candidate,
            trustedServerPublicKeyBase64 = trustedKey
        )
        if (!verified) {
            return Pair(AdminOpResult.Failure("Серверная квитанция о погашении не прошла проверку подписи или ревизии!"), null)
        }

        val preparedTunnels = prepareCandidateTunnels(candidate)
        val checkpoint = ClientRedemptionCheckpoint(invitationHash(invitationLink), redeemEnv.toJson(), trustedKey,
            receiptEnv.toJson(), candidate, preparedTunnels)
        saveRedemptionCheckpoint(checkpoint)

        // 6. Pre-flight check: Reachability probe to server
        val isReachable = if (socketProbe != null) {
            socketProbe.invoke(targetServer.host, targetServer.port, 2000)
        } else {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress(targetServer.host, targetServer.port), 2000)
                    true
                }
            } catch (_: Exception) {
                false
            }
        }
        if (!isReachable) {
            return Pair(AdminOpResult.Failure("Pre-flight проверка: сервер ${targetServer.host}:${targetServer.port} недоступен"), null)
        }

        val activated = activateVerifiedCandidate(candidate, crypto, trustedKey, receiptEnv, preparedTunnels)
        if (activated.first is AdminOpResult.Success) saveRedemptionCheckpoint(checkpoint.copy(completed = true))
        return activated
    }

    private fun legacyConvertSshUnused(candidate: VerifiedInvitationCandidate, targetServer: ServerNode, receiptEnv: AdminEnvelope): Pair<AdminOpResult, Tunnel?> {
        val transport = candidate.parameters["transport"] ?: "vyandex"
        val transType = io.github.libreroute.data.TransportType.from(transport)
        val payload = buildList {
            add("--role=client")
            add("--transport")
            add(transType.cliName)
            add("--url")
            add(candidate.documentUrl)
            if (candidate.parameters["session_negotiate"] == "true") {
                add("--session-negotiate")
            }
            if (candidate.parameters["udp_enabled"] == "true") {
                add("--udp-ipv4")
            }
            val allowedCidrs = candidate.parameters["allowed_dest_cidrs"]
            if (!allowedCidrs.isNullOrBlank()) {
                add("--allow-dest-cidr")
                add(allowedCidrs)
            }
        }

        val tunnel = Tunnel(
            id = System.currentTimeMillis() * 1000L + kotlin.random.Random.nextLong(1000L),
            name = candidate.name,
            transportType = transType.name,
            transportConnPayload = payload,
            encryptionKey = candidate.secretKey
        )

        if (context != null) {
            TunnelLinkParser.ensureLocalKeyFile(context, tunnel)
        }

        return Pair(AdminOpResult.Success("Приглашение успешно подтверждено и погашено на сервере", receiptEnv), tunnel)
    }

    /**
     * Suspends a managed profile.
     */
    fun suspendProfile(
        profileId: String,
        channel: AdminChannel = AdminChannel.DOCUMENT,
        hostConfig: SshHostConfig? = null
    ): AdminOpResult {
        return updateProfileStatus(profileId, ManagedProfile.STATUS_SUSPENDED, AdminEnvelope.SUBTYPE_SUSPEND, channel, hostConfig)
    }

    /**
     * Resumes a suspended profile.
     */
    fun resumeProfile(
        profileId: String,
        channel: AdminChannel = AdminChannel.DOCUMENT,
        hostConfig: SshHostConfig? = null
    ): AdminOpResult {
        return updateProfileStatus(profileId, ManagedProfile.STATUS_READY, AdminEnvelope.SUBTYPE_RESUME, channel, hostConfig)
    }

    /**
     * Revokes a managed profile permanently.
     */
    fun revokeProfile(
        profileId: String,
        channel: AdminChannel = AdminChannel.DOCUMENT,
        hostConfig: SshHostConfig? = null
    ): AdminOpResult {
        return updateProfileStatus(profileId, ManagedProfile.STATUS_REVOKED, AdminEnvelope.SUBTYPE_REVOKE, channel, hostConfig)
    }

    private fun updateProfileStatus(
        profileId: String,
        newStatus: String,
        subtype: String,
        channel: AdminChannel,
        hostConfig: SshHostConfig?
    ): AdminOpResult {
        if (channel != AdminChannel.DOCUMENT && hostConfig == null) {
            return AdminOpResult.Failure("Параметры SSH-сервера не настроены")
        }
        if (!hasManagePermission()) {
            return AdminOpResult.Failure("Недостаточно прав для изменения статуса профиля")
        }

        val profiles = _managedProfilesFlow.value.toMutableList()
        val index = profiles.indexOfFirst { it.id == profileId }
        if (index == -1) return AdminOpResult.Failure("Профиль $profileId не найден")

        val current = profiles[index]
        val serverId = legacyCommandServerId(hostConfig, current.allowedServerIds)
        if (_serverNodesFlow.value.isNotEmpty() && serverId == null) {
            return AdminOpResult.Failure("Выберите один сервер для изменения профиля")
        }
        val newRev = current.revision + 1
        val updated = current.copy(status = newStatus, revision = newRev)

        val prepared = runCatching {
            preparePolicyCommand(json.encodeToString(mapOf("op" to subtype, "profile_id" to profileId)), channel, policyServerForLegacy(serverId, hostConfig))
        }.getOrElse { return AdminOpResult.Failure(it.message ?: "Policy revision unavailable") }
        val envelope = AdminEnvelope(
            ver = 1,
            type = AdminEnvelope.TYPE_ADMIN,
            subtype = AdminEnvelope.SUBTYPE_COMMAND,
            profileId = verifiedGrantForServer(serverId)?.profileId
                ?: return AdminOpResult.Failure("No administrator profile is bound to this server"),
            opId = UUID.randomUUID().toString(),
            rev = newRev,
            sender = keyManager.getFingerprint(),
            payload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                prepared.toByteArray(Charsets.UTF_8)
            )
        )
        val signed = keyManager.signEnvelope(envelope)

        recordCommand(
            AdminCommandRecord(
                opId = signed.opId,
                targetProfileId = profileId,
                commandType = subtype,
                status = AdminCommandRecord.STATUS_PENDING,
                channel = channel.name.lowercase(),
                timestamp = System.currentTimeMillis(),
                revision = newRev,
                payload = "$subtype profile ${current.name}"
            )
        )

        val sshResult = confirmServerResponse(
            if (channel == AdminChannel.DOCUMENT) executeDocumentCommand(signed, serverId)
            else sshClient.executeAdminEnvelope(signed, hostConfig!!, channel), signed, serverId)
        if (sshResult is AdminOpResult.Failure) {
            updateCommandStatus(signed.opId, AdminCommandRecord.STATUS_FAILED, error = sshResult.error)
            return sshResult
        }

        profiles[index] = updated
        saveProfiles(profiles)
        updateCommandStatus(signed.opId, AdminCommandRecord.STATUS_APPLIED, newRev)

        val statusLabel = when (newStatus) {
            ManagedProfile.STATUS_READY -> "возобновлён"
            ManagedProfile.STATUS_SUSPENDED -> "приостановлен"
            ManagedProfile.STATUS_REVOKED -> "отозван"
            else -> newStatus
        }
        return AdminOpResult.Success("Профиль «${current.name}» $statusLabel", signed)
    }

    /**
     * Submits a structured PROBLEM_REPORT for client diagnostics and server incident tracking.
     */
    fun submitProblemReport(
        profileId: String,
        code: String,
        details: Map<String, String> = emptyMap(),
        targetServer: ServerNode? = null
    ): AdminOpResult {
        val report = ProblemReport(
            code = code,
            profileId = profileId,
            timestamp = System.currentTimeMillis(),
            reportId = UUID.randomUUID().toString(),
            details = details
        )

        // 1. Record report locally
        val currentReports = _problemReportsFlow.value.toMutableList()
        currentReports.add(0, report)
        if (currentReports.size > 50) {
            currentReports.removeAt(currentReports.lastIndex)
        }
        _problemReportsFlow.value = currentReports
        saveProblemReports(currentReports)

        EventBus.dispatch(AppEvent.LogMessage("[W] Инцидент качества связи: $code"))

        // 2. Transmit to server node if available
        val server = targetServer ?: _serverNodesFlow.value.singleOrNull()

        if (server != null) {
            val cmdParams = mutableMapOf<String, String>()
            cmdParams["code"] = code
            cmdParams.putAll(details)

            val payloadMap = buildJsonObject {
                put("op", "problem-report")
                put("profile_id", profileId)
                put("parameters", buildJsonObject {
                    cmdParams.forEach { (key, value) -> put(key, value) }
                })
            }
            val payloadStr = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                payloadMap.toString().toByteArray(Charsets.UTF_8)
            )

            val envelope = AdminEnvelope(
                ver = 1,
                type = AdminEnvelope.TYPE_ADMIN,
                subtype = AdminEnvelope.SUBTYPE_COMMAND,
                profileId = profileId,
                opId = report.reportId,
                rev = 1L,
                sender = keyManager.getFingerprint(),
                payload = payloadStr
            )
            val signed = keyManager.signEnvelope(envelope)

            val sshResult = confirmServerResponse(
                sshClient.executeAdminEnvelope(envelope = signed, serverNode = server),
                signed,
                server.id
            )

            if (sshResult is AdminOpResult.Success) {
                EventBus.dispatch(AppEvent.LogMessage("[S] Отчёт об инциденте успешно передан на сервер «${server.name}»"))
                return AdminOpResult.Success("Отчёт об инциденте передан на сервер ${server.name}", signed)
            } else {
                Logx.w(TAG, "Problem report saved locally, but server transmission failed: ${(sshResult as? AdminOpResult.Failure)?.error}")
            }
        }

        return AdminOpResult.Failure("Отчёт сохранён локально; доставка на сервер не подтверждена")
    }

    private fun saveProblemReports(reports: List<ProblemReport>) {
        val jsonStr = json.encodeToString(reports)
        storage.putString(KEY_PROBLEM_REPORTS, jsonStr)
    }

    private fun hasManagePermission(): Boolean {
        return hasAdminAccess()
    }

    private fun confirmServerResponse(
        result: AdminOpResult,
        request: AdminEnvelope,
        serverId: String? = null
    ): AdminOpResult {
        if (result is AdminOpResult.Failure) return result
        val envelope = (result as AdminOpResult.Success).envelope
            ?: return AdminOpResult.Failure("SSH did not return a server response")
        val pinned = getPinnedServerPublicKey(serverId)
            ?: return AdminOpResult.Failure("Server key is not pinned")
        val now = currentTimeSeconds()
        if (envelope.ver != 1 || envelope.type != AdminEnvelope.TYPE_ADMIN ||
            envelope.subtype != AdminEnvelope.SUBTYPE_RESPONSE || envelope.sender != "server" ||
            envelope.profileId != request.profileId || envelope.rev != request.rev + 1L ||
            envelope.exp <= now || envelope.exp > now + 24 * 3600L ||
            !keyManager.verifyServerResponse(envelope, pinned, pinned)
        ) return AdminOpResult.Failure("Invalid or mismatched server response")
        val response = runCatching {
            val raw = java.util.Base64.getUrlDecoder().decode(envelope.payload)
            json.parseToJsonElement(String(raw, Charsets.UTF_8)).jsonObject
        }.getOrNull() ?: return AdminOpResult.Failure("Malformed server response")
        if (response["op_id"]?.jsonPrimitive?.content != request.opId) {
            return AdminOpResult.Failure("Server response has a different operation ID")
        }
        if (response["status"]?.jsonPrimitive?.content != "applied") {
            val reason = response["error"]?.jsonPrimitive?.content?.take(240)
                ?: "Сервер отклонил команду"
            return AdminOpResult.Failure(reason, ConfirmedAdminRejection(reason))
        }
        return result
    }

    private fun executeDocumentCommand(request: AdminEnvelope, serverId: String? = null): AdminOpResult {
        val appContext = context ?: return AdminOpResult.Failure("Android context unavailable")
        val scopedSession = serverId?.let {
            storage.getString(documentSessionKey(request.profileId, it), null)
                ?: storage.getString(KEY_DOCUMENT_SESSION_PREFIX + it, null)
                    ?.takeIf { verifiedGrantForServer(it)?.profileId == request.profileId }
        }
        val legacySession = if (serverId == null || legacyGrantBelongsTo(serverId)) {
            storage.getString(documentSessionKey(request.profileId, null), null)
        } else null
        val session = (scopedSession ?: legacySession)
            ?.let { runCatching { json.decodeFromString<DocumentSessionKeys>(it) }.getOrNull() }
            ?: return AdminOpResult.Failure("Ключи административного сеанса не найдены")
        val pinned = getPinnedServerPublicKey(serverId)
            ?: return AdminOpResult.Failure("Ключ сервера не закреплён")
        val response = runCatching {
            AdminDocumentClaimClient(appContext).execute(session.documentUrl, request.profileId,
                request, session, pinned)
        }.getOrElse { return AdminOpResult.Failure(it.message ?: "Документная команда не подтверждена") }
        return AdminOpResult.Success("Сервер ответил через документ", response)
    }

    /** Retry the SAME signed operation over the independent saved SSH channel. */
    private fun executeAdministrativeRequest(request: AdminEnvelope, node: ServerNode?, channel: AdminChannel): AdminOpResult {
        if (channel != AdminChannel.DOCUMENT) {
            return node?.let { sshClient.executeAdminEnvelope(request, it.toSshHostConfig(), channel) }
                ?: AdminOpResult.Failure("Выберите SSH-сервер")
        }
        val document = executeDocumentCommand(request, node?.id)
        if (document is AdminOpResult.Success || node == null || node.host.isBlank() || node.hostPublicKey.isNullOrBlank()) return document
        return sshClient.executeAdminEnvelope(request, node.toSshHostConfig(), AdminChannel.DIRECT_SSH)
    }

    /** Reads only the server's confirmed managed profiles. Local VPN profiles are separate. */
    fun listServerManagedProfiles(serverNode: ServerNode? = null): Result<List<ServerManagedProfile>> = runCatching {
        if (serverNode == null && _serverNodesFlow.value.size > 1) {
            error("Выберите сервер для просмотра конфигураций")
        }
        val adminProfileId = verifiedGrantForServer(serverNode?.id)?.profileId
            ?: error("Нет действующего допуска администратора для выбранного сервера")
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"op\":\"list\"}".toByteArray(Charsets.UTF_8))
        val request = keyManager.signEnvelope(AdminEnvelope(
            subtype = AdminEnvelope.SUBTYPE_COMMAND,
            profileId = adminProfileId,
            sender = keyManager.getFingerprint(),
            payload = payload
        ))
        val result = confirmServerResponse(
            executeAdministrativeRequest(request, serverNode, AdminChannel.DOCUMENT), request, serverNode?.id
        )
        val response = (result as? AdminOpResult.Success)?.envelope
            ?: error((result as? AdminOpResult.Failure)?.error ?: "Сервер не вернул список")
        val body = json.parseToJsonElement(
            String(Base64.getUrlDecoder().decode(response.payload), Charsets.UTF_8)
        ).jsonObject
        val data = body["data"] ?: error("Сервер не вернул данные профилей")
        json.decodeFromJsonElement<List<ServerManagedProfile>>(data)
    }

    private fun policyServerForLegacy(serverId: String?, host: SshHostConfig?): ServerNode? =
        serverId?.let { getServerNode(it) } ?: host?.let {
            ServerNode(id = serverId.orEmpty(), name = "SSH", host = it.host, port = it.port,
                sshUser = it.username, authType = it.authType, privateKeyPath = it.privateKeyPath,
                passphrase = it.passphrase, password = it.password, hostPublicKey = it.hostPublicKey,
                allowPasswordAuth = it.allowPasswordAuth)
        }

    private val policyMutationOps = setOf(
        "create", "update", "suspend", "resume", "revoke", "rotate-key", "grant", "issue",
        "create-user", "update-user", "suspend-user", "register-device", "revoke-device",
        "assign-profile", "revoke-assignment", "issue-invitation", "issue-enrollment",
        "issue-admin-invite", "approve-admin-request", "confirm-owner-transfer", "complete-owner-transfer", "revoke-admin"
    )

    private fun preparePolicyCommand(payload: String, channel: AdminChannel, server: ServerNode?): String {
        val command = json.parseToJsonElement(payload).jsonObject
        val operation = command["op"]?.jsonPrimitive?.content ?: error("Операция не указана")
        require(canPerform(server?.id, operation)) { "Операция $operation не разрешена допуском выбранного сервера" }
        if (operation !in policyMutationOps) return payload
        // Read immediately before sending; a race afterwards is reported as a conflict.
        val snapshot = getClusterSnapshot(channel, server).getOrThrow()
        return kotlinx.serialization.json.JsonObject(command + mapOf(
            "expected_policy_revision" to kotlinx.serialization.json.JsonPrimitive(snapshot.snapshotRevision)
        )).toString()
    }

    private fun <T> executeAdminApiCommand(
        commandPayloadJson: String,
        targetProfileId: String? = null,
        targetServer: ServerNode? = null,
        channel: AdminChannel = AdminChannel.DOCUMENT,
        onResponse: ((AdminEnvelope) -> Unit)? = null,
        dataDecoder: (kotlinx.serialization.json.JsonElement) -> T
    ): Result<T> = runCatching {
        val server = targetServer ?: _serverNodesFlow.value.singleOrNull()
        val serverId = server?.id
        if (serverId == null && _serverNodesFlow.value.size > 1) {
            error("Выберите целевой сервер для административной команды")
        }
        if (channel != AdminChannel.DOCUMENT && targetServer == null) {
            error("Целевой сервер (target_server_id) обязателен для выполнения SSH-команды")
        }
        val activeGrant = verifiedGrantForServer(serverId)
            ?: error("Нет действующего допуска администратора")
        val operation = json.parseToJsonElement(commandPayloadJson).jsonObject["op"]?.jsonPrimitive?.content
            ?: error("Операция не указана")
        require(canPerform(serverId, operation)) { "Операция $operation не разрешена допуском выбранного сервера" }
        val adminProfileId = targetProfileId ?: activeGrant.profileId


        val revision = activeGrant.revision
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(preparePolicyCommand(commandPayloadJson, channel, targetServer).toByteArray(Charsets.UTF_8))
        val envelope = AdminEnvelope(
            ver = 1,
            type = AdminEnvelope.TYPE_ADMIN,
            subtype = AdminEnvelope.SUBTYPE_COMMAND,
            profileId = adminProfileId,
            opId = UUID.randomUUID().toString(),
            rev = revision,
            sender = keyManager.getFingerprint(),
            payload = payload
        )
        val signed = keyManager.signEnvelope(envelope)

        recordCommand(
            AdminCommandRecord(
                opId = signed.opId,
                targetProfileId = adminProfileId,
                commandType = "command",
                status = AdminCommandRecord.STATUS_PENDING,
                channel = channel.name.lowercase(),
                timestamp = System.currentTimeMillis(),
                revision = revision,
                payload = "Admin operation " + runCatching {
                    json.parseToJsonElement(commandPayloadJson).jsonObject["op"]?.jsonPrimitive?.content
                }.getOrNull().orEmpty()
            )
        )

        val rawResult = executeAdministrativeRequest(signed, server, channel)

        val result = confirmServerResponse(rawResult, signed, serverId)
        if (result is AdminOpResult.Failure) {
            updateCommandStatus(signed.opId, if (result.throwable is ConfirmedAdminRejection)
                AdminCommandRecord.STATUS_FAILED else AdminCommandRecord.STATUS_UNKNOWN, error = result.error)
            throw result.throwable ?: UnknownAdminOperation(result.error)
        }
        val respEnvelope = (result as AdminOpResult.Success).envelope
            ?: error("Сервер не вернул конверт ответа")
        updateCommandStatus(signed.opId, AdminCommandRecord.STATUS_APPLIED, respEnvelope.rev)

        val body = json.parseToJsonElement(
            String(Base64.getUrlDecoder().decode(respEnvelope.payload), Charsets.UTF_8)
        ).jsonObject
        val data = body["data"] ?: error(body["error"]?.jsonPrimitive?.content ?: "Сервер вернул пустой ответ")
        val decoded = dataDecoder(data)
        onResponse?.invoke(respEnvelope)
        decoded
    }

    fun listUsers(channel: AdminChannel = AdminChannel.DOCUMENT, serverNode: ServerNode? = null): Result<List<AdminUser>> {
        val payload = buildJsonObject { put("op", "list-users") }.toString()
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<List<AdminUser>>(data)
        }
    }

    fun createUser(name: String, channel: AdminChannel = AdminChannel.DOCUMENT, serverNode: ServerNode? = null): Result<AdminUser> {
        val payload = json.encodeToString(CreateUserPayload(name = name.trim()))
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<AdminUser>(data)
        }
    }

    /** The recipient document is retained privately until a device key permits assignment. */
    fun savePendingUserDocument(userId: String, documentUrl: String) {
        val url = java.net.URI(documentUrl.trim())
        require(url.scheme == "https" && url.userInfo == null && url.port == -1 &&
            !url.path.isNullOrBlank() &&
            (url.host == "yandex.ru" || url.host?.endsWith(".yandex.ru") == true)) {
            "Нужен HTTPS адрес документа Яндекса"
        }
        val current = storage.getString(KEY_PENDING_USER_DOCUMENTS, null)
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            .orEmpty().toMutableMap()
        current[userId] = documentUrl.trim()
        storage.putString(KEY_PENDING_USER_DOCUMENTS, json.encodeToString(current))
    }

    fun hasPendingUserDocument(userId: String): Boolean =
        storage.getString(KEY_PENDING_USER_DOCUMENTS, null)
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?.containsKey(userId) == true

    fun getPendingUserDocument(userId: String): String? =
        storage.getString(KEY_PENDING_USER_DOCUMENTS, null)
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            ?.get(userId)

    fun clearPendingUserDocument(userId: String) {
        val current = storage.getString(KEY_PENDING_USER_DOCUMENTS, null)
            ?.let { runCatching { json.decodeFromString<Map<String, String>>(it) }.getOrNull() }
            .orEmpty().toMutableMap()
        current.remove(userId)
        storage.putString(KEY_PENDING_USER_DOCUMENTS, json.encodeToString(current))
    }

    fun updateUser(userId: String, name: String? = null, status: String? = null, channel: AdminChannel = AdminChannel.DOCUMENT, serverNode: ServerNode? = null): Result<AdminUser> {
        val payload = json.encodeToString(UpdateUserPayload(userId = userId, name = name?.trim(), status = status))
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<AdminUser>(data)
        }
    }

    fun suspendUser(userId: String, channel: AdminChannel = AdminChannel.DOCUMENT, serverNode: ServerNode? = null): Result<Unit> {
        val payload = json.encodeToString(SuspendUserPayload(userId = userId))
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) {
            Unit
        }
    }

    fun listDevices(userId: String? = null, channel: AdminChannel = AdminChannel.DOCUMENT, serverNode: ServerNode? = null): Result<List<AdminDevice>> {
        val payload = buildJsonObject {
            put("op", "list-devices")
            if (userId != null) put("user_id", userId)
        }.toString()
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<List<AdminDevice>>(data)
        }
    }

    fun registerDevice(userId: String, publicKey: String, channel: AdminChannel = AdminChannel.DOCUMENT, serverNode: ServerNode? = null): Result<AdminDevice> {
        val payload = json.encodeToString(RegisterDevicePayload(userId = userId, publicKey = publicKey.trim()))
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<AdminDevice>(data)
        }
    }

    fun revokeDevice(deviceId: String, channel: AdminChannel = AdminChannel.DOCUMENT, serverNode: ServerNode? = null): Result<Unit> {
        val payload = json.encodeToString(RevokeDevicePayload(deviceId = deviceId))
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) {
            Unit
        }
    }

    fun listAssignments(userId: String? = null, deviceId: String? = null, channel: AdminChannel = AdminChannel.DOCUMENT, serverNode: ServerNode? = null): Result<List<AdminAssignment>> {
        val payload = buildJsonObject {
            put("op", "list-assignments")
            if (userId != null) put("user_id", userId)
            if (deviceId != null) put("device_id", deviceId)
        }.toString()
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<List<AdminAssignment>>(data)
        }
    }

    fun assignProfile(
        userId: String,
        deviceId: String,
        serverId: String,
        transport: String = "vyandex",
        documentUrl: String? = null,
        parameters: Map<String, String>? = null,
        channel: AdminChannel = AdminChannel.DOCUMENT,
        serverNode: ServerNode? = null
    ): Result<AdminAssignment> {
        val payload = json.encodeToString(
            AssignProfilePayload(
                userId = userId,
                deviceId = deviceId,
                serverId = serverId,
                transport = transport,
                documentUrl = documentUrl,
                parameters = parameters
            )
        )
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<AdminAssignment>(data)
        }
    }

    fun revokeAssignment(assignmentId: String, channel: AdminChannel = AdminChannel.DOCUMENT, serverNode: ServerNode? = null): Result<Unit> {
        val payload = json.encodeToString(RevokeAssignmentPayload(assignmentId = assignmentId))
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) {
            Unit
        }
    }

    fun listInvitations(assignmentId: String? = null, channel: AdminChannel = AdminChannel.DOCUMENT, serverNode: ServerNode? = null): Result<List<AdminInvitation>> {
        val payload = buildJsonObject {
            put("op", "list-invitations")
            if (assignmentId != null) put("assignment_id", assignmentId)
        }.toString()
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<List<AdminInvitation>>(data)
        }
    }

    fun getClusterSnapshot(
        channel: AdminChannel = AdminChannel.DOCUMENT,
        serverNode: ServerNode? = null
    ): Result<AdminClusterSnapshot> {
        val target = serverNode ?: _serverNodesFlow.value.singleOrNull()
        val payload = json.encodeToString(GetClusterSnapshotPayload())
        var receipt: AdminEnvelope? = null
        val res = executeAdminApiCommand(payload, targetServer = target, channel = channel, onResponse = { receipt = it }) { data ->
            val wire = data.jsonObject
            require(wire["snapshot_hash"]?.jsonPrimitive?.content == computeClusterSnapshotHash(wire)) {
                "Контрольная сумма снимка кластера не совпадает с ответом сервера"
            }
            json.decodeFromJsonElement<AdminClusterSnapshot>(data)
        }
        return res.mapCatching { snapshot ->
            val expectedAuthority = getPinnedServerPublicKey(target?.id)
                ?: error("Ключ выбранного сервера не закреплён")
            if (snapshot.authorityId != expectedAuthority) {
                error("Снимок кластера получен от другого сервера")
            }
            val cached = cachedClusterSnapshot(target?.id)
            if (cached?.authorityId == snapshot.authorityId && snapshot.snapshotRevision < cached.snapshotRevision) {
                error("Отклонён устаревший снимок кластера: ревизия ${snapshot.snapshotRevision} < ${cached.snapshotRevision}")
            }
            _clusterSnapshotFlow.value = snapshot
            receipt?.let { storage.putString(KEY_SNAPSHOT_RECEIPT_PREFIX + (target?.id ?: "legacy"), it.toJson()) }
            snapshot
        }.onSuccess { snapshot ->
            target?.id?.let(::getServerNode)?.let { node ->
                updateServerNode(node.copy(coreObservation = CoreObservation(snapshot.coreInfo, System.currentTimeMillis())))
            }
        }.onFailure { error ->
            target?.id?.let(::getServerNode)?.let { node ->
                updateServerNode(node.copy(coreObservation = node.coreObservation.copy(error = error.message ?: "Проверка ядра не завершена")))
            }
        }
    }

    fun refreshServerCore(node: ServerNode): Result<CoreCompatibility?> =
        getClusterSnapshot(AdminChannel.DIRECT_SSH, node).map { it.coreInfo }

    /** Offline display only: verify the saved signature/hash, without granting any current authority. */
    fun cachedClusterSnapshot(serverId: String?): AdminClusterSnapshot? = runCatching {
        val receipt = storage.getString(KEY_SNAPSHOT_RECEIPT_PREFIX + (serverId ?: "legacy"), null)
            ?.let(AdminEnvelope::fromJson) ?: return null
        val key = getPinnedServerPublicKey(serverId) ?: return null
        require(receipt.sender == "server" && receipt.subtype == AdminEnvelope.SUBTYPE_RESPONSE &&
            keyManager.verifyServerResponse(receipt, key, key))
        val payload = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(receipt.payload), Charsets.UTF_8)).jsonObject
        require(payload["op_id"]?.jsonPrimitive?.content == receipt.opId && payload["status"]?.jsonPrimitive?.content == "applied")
        val data = payload.getValue("data").jsonObject
        require(data["snapshot_hash"]?.jsonPrimitive?.content == computeClusterSnapshotHash(data))
        json.decodeFromJsonElement<AdminClusterSnapshot>(data).also { require(it.authorityId == key) }
    }.getOrNull()

    fun reconcileAssignments(
        channel: AdminChannel = AdminChannel.DOCUMENT,
        serverNode: ServerNode? = null
    ): Result<List<AdminAssignment>> {
        val payload = json.encodeToString(ReconcileAssignmentsPayload())
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<List<AdminAssignment>>(data)
        }
    }

    fun beginAuthSession(
        targetId: String,
        profileId: String,
        channel: AdminChannel = AdminChannel.DIRECT_SSH,
        serverNode: ServerNode? = null,
        documentUrl: String = ""
    ): Result<AuthSession> {
        checkCoreCompatibility(serverNode, channel, setOf("setup-auth-document-v1", "setup-auth-worker-cookies-v1")).exceptionOrNull()?.let {
            return Result.failure(it)
        }
        val payload = json.encodeToString(BeginAuthSessionPayload(targetId = targetId, profileId = profileId, url = documentUrl))
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<AuthSession>(data)
        }
    }

    private fun checkCoreCompatibility(node: ServerNode?, channel: AdminChannel, required: Set<String>): Result<Unit> = runCatching {
        val snapshot = getClusterSnapshot(channel, node).getOrThrow()
        require(snapshot.coreInfo?.supports(required) == true) {
            "Core несовместим с этой операцией. Обновите ядро сервера и повторите настройку. SSH-доступ сохранён."
        }
    }

    fun getAuthSessionStatus(
        authSessionId: String,
        channel: AdminChannel = AdminChannel.DIRECT_SSH,
        serverNode: ServerNode? = null
    ): Result<AuthSession> {
        val payload = json.encodeToString(GetAuthSessionStatusPayload(authSessionId = authSessionId))
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<AuthSession>(data)
        }
    }

    fun importAuthBundle(
        authSessionId: String,
        serverPub: String,
        bundle: AuthBundlePlaintext,
        channel: AdminChannel = AdminChannel.DIRECT_SSH,
        serverNode: ServerNode? = null
    ): Result<Unit> {
        val bundleJson = json.encodeToString(bundle)
        val encrypted = runCatching { RecipientInvitationCrypto.encryptForDevice(
            recipientPublicKeyBase64 = serverPub,
            plaintext = bundleJson.toByteArray(Charsets.UTF_8)
        ) }.getOrElse { return Result.failure(it) }
        val (ephemeralPub, nonce, ciphertext) = encrypted
        val payload = json.encodeToString(
            AuthImportPayload(
                authSessionId = authSessionId,
                ephemeralPub = ephemeralPub,
                nonce = nonce,
                encryptedPayload = ciphertext,
                contentHash = bundle.computeContentHash()
            )
        )
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) {
            Unit
        }
    }

    fun listAuthIncidents(
        profileId: String? = null,
        channel: AdminChannel = AdminChannel.DIRECT_SSH,
        serverNode: ServerNode? = null
    ): Result<List<AuthIncident>> {
        val payload = json.encodeToString(ListAuthIncidentsPayload(profileId = profileId))
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) { data ->
            json.decodeFromJsonElement<List<AuthIncident>>(data)
        }
    }

    fun ackAuthIncident(
        incidentId: String,
        channel: AdminChannel = AdminChannel.DIRECT_SSH,
        serverNode: ServerNode? = null
    ): Result<Unit> {
        val payload = json.encodeToString(AckAuthIncidentPayload(incidentId = incidentId))
        return executeAdminApiCommand(payload, targetServer = serverNode, channel = channel) {
            Unit
        }
    }

    fun issueBootstrapInvitation(userId: String, routes: List<EnrollmentRoute>, channel: AdminChannel,
                                 serverNode: ServerNode?): Result<String> = runCatching {
        val payload = buildJsonObject {
            put("op", "issue-enrollment"); put("user_id", userId)
            put("enrollment_routes", json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(EnrollmentRoute.serializer()), routes))
        }.toString()
        var signedResponse: AdminEnvelope? = null
        executeAdminApiCommand(payload, targetServer = serverNode, channel = channel,
            onResponse = { signedResponse = it }) { Unit }.getOrThrow()
        BootstrapInvitation.toLink(signedResponse ?: error("Signed enrollment response missing"))
    }

    private fun redeemBootstrapInvitation(link: String): Pair<AdminOpResult, Tunnel?> {
        return try {
        val crypto = RecipientInvitationCrypto(recipientIdentityStorage)
        val resumed = resumeRedemption(link, crypto)
        if (resumed != null) return resumed
        val invitation = BootstrapInvitation.parse(link, keyManager)
        val requestKey = "pending_enrollment_" + invitation.enrollmentId
        val request = redemptionCheckpoint(link)?.request?.let(AdminEnvelope::fromJson)
            ?: storage.getString(requestKey, null)?.let { AdminEnvelope.fromJson(it) }
            ?: invitation.createRequest(crypto).also { storage.putString(requestKey, it.toJson()) }
        saveRedemptionCheckpoint(ClientRedemptionCheckpoint(invitationHash(link), request.toJson(), invitation.serverPublicKey))
        val response = runDocumentRedemption(invitation.documentUrl, invitation.profileId,
            "", request, invitation.serverPublicKey)
        require(response.opId == request.opId && response.profileId == invitation.profileId) { "Enrollment receipt binding mismatch" }
        val candidate = ServerIssuedInvitation(crypto, keyManager).verifyCandidate(ServerIssuedInvitation.toLink(response), invitation.serverPublicKey)
        require(candidate.deviceId == crypto.publicKeyHashHex() && candidate.routes.isNotEmpty()) { "Enrollment package recipient mismatch" }
        val bound = candidate.copy(profileId = invitation.profileId, documentUrl = invitation.documentUrl, secretKey = "")
        val tunnels = bound.routes.map { routeCandidateToTunnel(it, bound.name) }
        val checkpoint = ClientRedemptionCheckpoint(invitationHash(link), request.toJson(), invitation.serverPublicKey,
            response.toJson(), bound, tunnels)
        saveRedemptionCheckpoint(checkpoint)
        val result = activateVerifiedCandidate(bound, crypto, invitation.serverPublicKey, response, tunnels)
        if (result.first is AdminOpResult.Success) {
            saveRedemptionCheckpoint(checkpoint.copy(completed = true)); storage.remove(requestKey)
        }
        result
        } catch (e: Exception) { Pair(AdminOpResult.Failure(e.message ?: "Bootstrap activation failed", e), null) }
    }

    private fun restoreInstallationAccessAfterRollback(operation: ServerInstallOperation) {
        val currentEnvelope = storage.getString(KEY_ACTIVE_GRANT_ENVELOPE_PREFIX + operation.serverId, null)
            ?.let(AdminEnvelope::fromJson)
        val issuedEnvelope = operation.receipt?.let {
            runCatching { AdminEnvelope.fromJson(json.parseToJsonElement(it).jsonObject.getValue("grant").toString()) }.getOrNull()
        }
        // Never revoke or replace a newer independent invitation/recovery grant.
        if (currentEnvelope == null || issuedEnvelope == null || currentEnvelope.nonce != issuedEnvelope.nonce) return
        removeLocalAdminGrant(operation.serverId)
        storage.remove(documentSessionKey(operation.profileId, operation.serverId))
        storage.remove(KEY_DOCUMENT_SESSION_PREFIX + operation.serverId)
        operation.previousDocumentSession?.let {
            storage.putString(documentSessionKey(operation.profileId, operation.serverId), it)
            storage.putString(KEY_DOCUMENT_SESSION_PREFIX + operation.serverId, it)
        }
        val previous = operation.previousGrantEnvelope?.let(AdminEnvelope::fromJson) ?: return
        val previousGrant = runCatching { json.decodeFromString<AdminGrant>(String(Base64.getUrlDecoder().decode(previous.payload))) }
            .getOrNull() ?: return
        val key = getPinnedServerPublicKey(operation.serverId) ?: return
        if (previousGrant.serverId != operation.serverId || !validGrantFields(previous, previousGrant, key) ||
            !keyManager.verifyServerResponse(previous, key, key)) return
        storage.putString(KEY_ACTIVE_GRANT_PREFIX + operation.serverId, json.encodeToString(previousGrant))
        storage.putString(KEY_ACTIVE_GRANT_ENVELOPE_PREFIX + operation.serverId, previous.toJson())
        storage.putString(KEY_REVOKED_SERVER_IDS, json.encodeToString((revokedServerIds() - operation.serverId).toList()))
        verifiedGrantForServer(operation.serverId)?.let { grant ->
            _activeGrantFlow.value = grant
            _roleFlow.value = grant.role
            val document = operation.previousDocumentSession?.let {
                runCatching { json.decodeFromString<DocumentSessionKeys>(it).documentUrl }.getOrNull()
            }.orEmpty()
            getServerNode(operation.serverId)?.let { ensureOwnerControlProfile(it, grant, document, operation.profileName) }
        }
    }

    /** Explicit same-device owner recovery. It never bootstraps a new owner implicitly. */
    fun recoverOwnerServer(node: ServerNode, operationId: String, documentUrl: String = ""): AdminOpResult {
        val original = loadServerInstallation(operationId)
            ?: return AdminOpResult.Failure("Сначала подготовьте план восстановления владельца")
        if (original.kind != ServerInstallOperation.KIND_OWNER_RECOVERY && !original.ownerRecovery)
            return AdminOpResult.Failure("Операция не помечена как восстановление владельца")
        if (original.serverId != node.id || original.stage != ServerInstallOperation.PLANNED)
            return AdminOpResult.Failure("План восстановления относится к другому серверу или уже выполнялся")
        return runCatching {
            val saved = getServerNode(node.id) ?: node
            val approvedPlan = json.parseToJsonElement(original.expectedPlan).jsonObject
            require(approvedPlan["server_id"]?.jsonPrimitive?.content == saved.id &&
                approvedPlan["server_public_key"]?.jsonPrimitive?.content == getPinnedServerPublicKey(saved.id)) {
                "Сначала привяжите SSH-узел к подтверждённой идентичности существующего сервера"
            }
            val suppliedDocument = documentUrl.ifBlank { approvedPlan["document_url"]?.jsonPrimitive?.content.orEmpty() }
            val resolvedDocument = if (suppliedDocument.isBlank()) "" else AdminDocumentClaimClient.resolveDocumentUrl(suppliedDocument)
            val join = original.join?.let(AdminEnvelope::fromJson) ?: createJoinRequest(original.profileId, AdminRole.OWNER).first
            val body = json.parseToJsonElement(String(Base64.getUrlDecoder().decode(join.payload), Charsets.UTF_8)).jsonObject
            val pairing = body.getValue("one_time_code").jsonPrimitive.content
            val inventory = probeServer(saved)
            require(inventory.probeError == null) { "SSH-опрос не завершён" }
            val submitting = original.copy(kind = ServerInstallOperation.KIND_OWNER_RECOVERY,
                ownerRecovery = true, documentUrl = resolvedDocument, join = join.toJson(),
                stage = ServerInstallOperation.SUBMITTING, updatedAt = System.currentTimeMillis())
            saveServerInstallation(submitting)
            val response = installationClient().recoverOwner(saved, inventory, operationId, resolvedDocument, join, pairing,
                approvedPlan)
            reconcileInstallationReceipt(submitting, response)
        }.getOrElse { e ->
            val operation = loadServerInstallation(operationId) ?: original
            if (operation.stage == ServerInstallOperation.SUBMITTING) saveServerInstallation(operation.copy(
                stage = ServerInstallOperation.UNKNOWN, error = e.message ?: "Результат восстановления неизвестен"))
            AdminOpResult.Failure(e.message ?: "Восстановление владельца не подтверждено", e)
        }
    }

    fun issueAssignmentInvitation(
        assignmentId: String,
        channel: AdminChannel = AdminChannel.DOCUMENT,
        serverNode: ServerNode? = null
    ): Pair<AdminOpResult, String> {
        if (!hasManagePermission()) {
            return Pair(AdminOpResult.Failure("Недостаточно прав для выдачи приглашения. Требуется роль OWNER или OPERATOR."), "")
        }
        if (serverNode == null && _serverNodesFlow.value.size > 1) {
            return Pair(AdminOpResult.Failure("Выберите целевой сервер для выдачи приглашения"), "")
        }
        if (channel != AdminChannel.DOCUMENT && serverNode == null) {
            return Pair(AdminOpResult.Failure("Целевой сервер (target_server_id) обязателен для выполнения SSH-команды"), "")
        }
        val activeGrant = verifiedGrantForServer(serverNode?.id)
            ?: return Pair(AdminOpResult.Failure("Нет действующего допуска администратора для выбранного сервера"), "")
        val adminProfileId = activeGrant.profileId
        val revision = activeGrant.revision

        val payload = runCatching { preparePolicyCommand(json.encodeToString(IssueInvitationPayload(assignmentId = assignmentId)), channel, serverNode) }.getOrElse { return Pair(AdminOpResult.Failure(it.message ?: "Не удалось получить ревизию политики"), "") }
        val base64Payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.toByteArray(Charsets.UTF_8))
        val envelope = AdminEnvelope(
            ver = 1,
            type = AdminEnvelope.TYPE_ADMIN,
            subtype = AdminEnvelope.SUBTYPE_COMMAND,
            profileId = adminProfileId,
            opId = UUID.randomUUID().toString(),
            rev = revision,
            sender = keyManager.getFingerprint(),
            payload = base64Payload
        )
        val signed = keyManager.signEnvelope(envelope)

        recordCommand(
            AdminCommandRecord(
                opId = signed.opId,
                targetProfileId = adminProfileId,
                commandType = "issue-invitation",
                status = AdminCommandRecord.STATUS_PENDING,
                channel = channel.name.lowercase(),
                timestamp = System.currentTimeMillis(),
                revision = revision,
                payload = "Issue invitation for assignment $assignmentId"
            )
        )

        val rawResult = if (channel == AdminChannel.DOCUMENT) {
            executeDocumentCommand(signed, serverNode?.id)
        } else {
            val server = serverNode
                ?: return Pair(AdminOpResult.Failure("Целевой сервер (target_server_id) обязателен для выполнения SSH-команды"), "")
            sshClient.executeAdminEnvelope(signed, server.toSshHostConfig(), channel)
        }

        val result = confirmServerResponse(rawResult, signed, serverNode?.id)
        if (result is AdminOpResult.Failure) {
            updateCommandStatus(signed.opId, if (result.throwable is ConfirmedAdminRejection)
                AdminCommandRecord.STATUS_FAILED else AdminCommandRecord.STATUS_UNKNOWN, error = result.error)
            return Pair(result, "")
        }

        val respEnvelope = (result as AdminOpResult.Success).envelope
            ?: return Pair(AdminOpResult.Failure("Сервер не вернул конверт ответа выдачи"), "")

        val inviteLink = ServerIssuedInvitation.toLink(respEnvelope)
        updateCommandStatus(signed.opId, AdminCommandRecord.STATUS_APPLIED, respEnvelope.rev)
        return Pair(AdminOpResult.Success("Приглашение успешно сформировано сервером", respEnvelope), inviteLink)
    }

    fun redeemInvitation(
        invitationLink: String,
        serverNode: ServerNode? = null
    ): Pair<AdminOpResult, Tunnel?> {
        if (invitationLink.startsWith(BootstrapInvitation.PREFIX)) return redeemBootstrapInvitation(invitationLink)
        val serverPub = serverNode?.let { getPinnedServerPublicKey(it.id) } ?: getPinnedServerPublicKey()
        return redeemInvitationViaDocument(invitationLink, serverPub)
    }

    fun redeemInvitationViaDocument(
        invitationLink: String,
        trustedServerPublicKeyBase64: String? = null,
        recipientCrypto: RecipientInvitationCrypto? = null
    ): Pair<AdminOpResult, Tunnel?> {
        if (invitationLink.startsWith(BootstrapInvitation.PREFIX)) return redeemBootstrapInvitation(invitationLink)
        completedRedemption(invitationLink)?.let { return it }
        val trustedKey = trustedServerPublicKeyBase64
            ?: getPinnedServerPublicKey()
            ?: return Pair(AdminOpResult.Failure("Публичный ключ сервера не закреплён. Сопряжение невозможно без доверенного ключа сервера."), null)

        val crypto = recipientCrypto ?: RecipientInvitationCrypto(
            recipientIdentityStorage
        )
        val invitationHandler = ServerIssuedInvitation(
            recipientCrypto = crypto,
            signatureVerifier = keyManager
        )
        resumeRedemption(invitationLink, crypto)?.let { return it }

        // 1. Verify candidate & decrypt payload
        val candidate = try {
            invitationHandler.verifyCandidate(invitationLink, trustedKey)
        } catch (e: Exception) {
            return Pair(AdminOpResult.Failure("Не удалось расшифровать или проверить приглашение: ${e.message}"), null)
        }

        // 2. Create signed redemption request
        val redeemEnv = redemptionCheckpoint(invitationLink)?.request?.let(AdminEnvelope::fromJson)
            ?: invitationHandler.createRedemption(candidate)
        saveRedemptionCheckpoint(ClientRedemptionCheckpoint(invitationHash(invitationLink), redeemEnv.toJson(), trustedKey))

        // 3. Send redemption request to server via document relay
        val receiptEnv = try {
            runDocumentRedemption(candidate.documentUrl, candidate.profileId, candidate.secretKey, redeemEnv, trustedKey)
        } catch (e: Exception) {
            return Pair(AdminOpResult.Failure("Не удалось передать подтверждение погашения через документ: ${e.message}"), null)
        }

        // 4. Verify server's signed redemption receipt
        val verified = invitationHandler.verifyRedemptionReceipt(
            request = redeemEnv,
            receipt = receiptEnv,
            candidate = candidate,
            trustedServerPublicKeyBase64 = trustedKey
        )
        if (!verified) {
            return Pair(AdminOpResult.Failure("Серверная квитанция о погашении не прошла проверку подписи или ревизии!"), null)
        }

        val tunnels = prepareCandidateTunnels(candidate)
        val checkpoint = ClientRedemptionCheckpoint(invitationHash(invitationLink), redeemEnv.toJson(), trustedKey,
            receiptEnv.toJson(), candidate, tunnels)
        saveRedemptionCheckpoint(checkpoint)
        val activated = activateVerifiedCandidate(candidate, crypto, trustedKey, receiptEnv, tunnels)
        if (activated.first is AdminOpResult.Success) saveRedemptionCheckpoint(checkpoint.copy(completed = true))
        return activated
    }

    private fun activateVerifiedCandidate(candidate: VerifiedInvitationCandidate, crypto: RecipientInvitationCrypto,
                                          trustedKey: String, receiptEnv: AdminEnvelope,
                                          preparedTunnels: List<Tunnel>? = null): Pair<AdminOpResult, Tunnel?> {
        // 5. Convert verified candidate to Tunnels
        val tunnels = preparedTunnels ?: prepareCandidateTunnels(candidate)
        _lastRedeemedTunnels = tunnels
        val primaryTunnel = tunnels.first()

        val enrolledDevId = if (candidate.deviceId.isNotBlank()) candidate.deviceId else crypto.publicKeyHashHex()
        saveEnrolledSyncState(
            deviceId = enrolledDevId,
            userId = candidate.userId,
            profileId = candidate.profileId,
            documentUrl = candidate.documentUrl,
            secretKey = candidate.secretKey,
            revision = candidate.revision,
            trustedServerPub = trustedKey,
            routes = candidate.routes
        )
        updateManagedTunnels(tunnels)

        return Pair(AdminOpResult.Success("Приглашение успешно подтверждено и погашено на сервере", receiptEnv), primaryTunnel)
    }

    private fun prepareCandidateTunnels(candidate: VerifiedInvitationCandidate): List<Tunnel> =
        if (candidate.routes.isNotEmpty()) {
            candidate.routes.map { routeCandidateToTunnel(it, candidate.name) }
        } else {
            val fallbackRoute = RouteCandidate(
                route_id = candidate.profileId,
                name = candidate.name,
                url = candidate.documentUrl,
                secret_key = candidate.secretKey,
                parameters = candidate.parameters,
                revision = candidate.revision
            )
            listOf(routeCandidateToTunnel(fallbackRoute, candidate.name))
        }

    private fun legacyConvertDocUnused(candidate: VerifiedInvitationCandidate, appContext: Context, receiptEnv: AdminEnvelope): Pair<AdminOpResult, Tunnel?> {
        val transport = candidate.parameters["transport"] ?: "vyandex"
        if (transport != "vyandex" && transport != "yandex") {
            return Pair(AdminOpResult.Failure("Неподдерживаемый транспорт: '$transport'. Для многоклиентного доступа поддерживается только vyandex."), null)
        }
        val transType = io.github.libreroute.data.TransportType.from(transport)
        val payload = buildList {
            add("--role=client")
            add("--transport")
            add(transType.cliName)
            add("--url")
            add(candidate.documentUrl)
            if (candidate.parameters["session_negotiate"] == "true") {
                add("--session-negotiate")
            }
            if (candidate.parameters["udp_enabled"] == "true") {
                add("--udp-ipv4")
            }
            val allowedCidrs = candidate.parameters["allowed_dest_cidrs"]
            if (!allowedCidrs.isNullOrBlank()) {
                add("--allow-dest-cidr")
                add(allowedCidrs)
            }
        }

        val tunnel = Tunnel(
            id = System.currentTimeMillis() * 1000L + kotlin.random.Random.nextLong(1000L),
            name = candidate.name,
            transportType = transType.name,
            transportConnPayload = payload,
            encryptionKey = candidate.secretKey
        )

        TunnelLinkParser.ensureLocalKeyFile(appContext, tunnel)

        return Pair(AdminOpResult.Success("Приглашение успешно подтверждено и погашено на сервере", receiptEnv), tunnel)
    }

    fun getRecipientDevicePublicKey(): String {
        val crypto = RecipientInvitationCrypto(
            recipientIdentityStorage
        )
        return crypto.getOrCreatePublicKeyBase64()
    }

    fun getRecipientDeviceFingerprint(): String {
        val pubBase64 = getRecipientDevicePublicKey()
        val raw = runCatching { Base64.getUrlDecoder().decode(pubBase64) }
            .getOrElse { Base64.getDecoder().decode(pubBase64) }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun hasIssuePermission(): Boolean {
        return hasAdminAccess()
    }

    private fun validGrantFields(envelope: AdminEnvelope, grant: AdminGrant, pinnedKey: String): Boolean =
        envelope.type == AdminEnvelope.TYPE_ADMIN && envelope.subtype == AdminEnvelope.SUBTYPE_GRANT &&
            runCatching { json.decodeFromString<AdminGrant>(String(java.util.Base64.getUrlDecoder().decode(envelope.payload), Charsets.UTF_8)) == grant }.getOrDefault(false) &&
            envelope.exp > currentTimeSeconds() &&
            grant.role in listOf(AdminRole.OWNER, AdminRole.OPERATOR, AdminRole.VIEWER) &&
            grant.profileId == envelope.profileId && grant.revision == envelope.rev &&
            grant.expiresAt <= envelope.exp && grant.expiresAt > currentTimeSeconds() &&
            grant.adminFingerprint == keyManager.getFingerprint() &&
            grant.serverPublicKey == pinnedKey

    private fun saveProfiles(profiles: List<ManagedProfile>) {
        _managedProfilesFlow.value = profiles
        storage.putString(KEY_MANAGED_PROFILES, json.encodeToString(profiles))
    }

    fun recordCommand(record: AdminCommandRecord) {
        val list = _commandHistoryFlow.value.toMutableList()
        list.add(0, record)
        if (list.size > 50) {
            list.removeAt(list.size - 1)
        }
        _commandHistoryFlow.value = list
        storage.putString(KEY_COMMAND_HISTORY, json.encodeToString(list))
    }

    fun updateCommandStatus(
        opId: String,
        status: String,
        revision: Long? = null,
        error: String? = null
    ) {
        val list = _commandHistoryFlow.value.map { cmd ->
            if (cmd.opId == opId) {
                cmd.copy(
                    status = status,
                    revision = revision ?: cmd.revision,
                    error = error ?: cmd.error
                )
            } else cmd
        }
        _commandHistoryFlow.value = list
        storage.putString(KEY_COMMAND_HISTORY, json.encodeToString(list))
    }

    private var _lastRedeemedTunnels: List<Tunnel> = emptyList()
    fun getLastRedeemedTunnels(): List<Tunnel> = _lastRedeemedTunnels

    fun routeCandidateToTunnel(route: RouteCandidate, fallbackName: String = ""): Tunnel {
        val transport = if (route.transport.isNotBlank()) route.transport else "vyandex"
        val transType = io.github.libreroute.data.TransportType.from(transport)
        if (transType in setOf(io.github.libreroute.data.TransportType.mqtt, io.github.libreroute.data.TransportType.jitsi)) {
            require(io.github.libreroute.util.ServiceRouteEndpoint.isValid(transType, route.url)) { "Некорректный адрес маршрута" }
            require(route.secret_key.length >= 16) { "У маршрута отсутствует ключ шифрования" }
        }
        val payload = buildList {
            add("--role=client")
            add("--transport")
            add(transType.cliName)
            add("--url")
            add(route.url)
            add("--codec=${route.parameters["codec"] ?: route.codec.ifBlank { "batched" }}")
            add("--encryption-layout=${route.parameters["encryption_layout"] ?: route.encryption_layout.ifBlank { "packet" }}")
            add("--peer-health")
            for (flag in listOf("session_batch_bytes", "session_linger_ms", "session_compression")) {
                route.parameters[flag]?.let { add("--${flag.replace('_', '-')}=$it") }
            }
            if (route.parameters["session_negotiate"] == "true") {
                add("--session-negotiate")
            }
            if (route.parameters["udp_enabled"] == "true") {
                add("--udp-ipv4")
            }
            val allowedCidrs = route.parameters["allowed_dest_cidrs"]
            if (!allowedCidrs.isNullOrBlank()) {
                add("--allow-dest-cidr")
                add(allowedCidrs)
            }
        }

        val tunnelName = when {
            route.name.isNotBlank() -> route.name
            fallbackName.isNotBlank() -> fallbackName
            else -> "VPN-${route.route_id.take(8)}"
        }

        val tunnel = Tunnel(
            id = System.currentTimeMillis() * 1000L + kotlin.random.Random.nextLong(1000L),
            name = tunnelName,
            transportType = transType.name,
            transportConnPayload = payload,
            encryptionKey = route.secret_key
        )

        return context?.let { TunnelLinkParser.ensureLocalKeyFile(it, tunnel) } ?: tunnel
    }

    fun saveEnrolledSyncState(
        deviceId: String,
        userId: String,
        profileId: String,
        documentUrl: String,
        secretKey: String,
        revision: Long,
        trustedServerPub: String,
        routes: List<RouteCandidate>
    ) {
        val contexts = syncContexts()
        val existing = contexts.firstOrNull { prefix ->
            storage.getString(prefix + KEY_SYNC_PROFILE_ID, null) == profileId &&
                storage.getString(prefix + KEY_SYNC_TRUSTED_SERVER_KEY, null) == trustedServerPub &&
                storage.getString(prefix + KEY_SYNC_DEVICE_ID, null) == deviceId
        }
        val prefix = existing ?: "client_sync_${invitationHash("$trustedServerPub|$profileId|$deviceId")}_"
        storage.putString(prefix + KEY_SYNC_DEVICE_ID, deviceId)
        storage.putString(prefix + KEY_SYNC_USER_ID, userId)
        storage.putString(prefix + KEY_SYNC_PROFILE_ID, profileId)
        storage.putString(prefix + KEY_SYNC_DOC_URL, documentUrl)
        storage.putString(prefix + KEY_SYNC_SECRET_KEY, secretKey)
        storage.putLong(prefix + KEY_SYNC_REVISION, revision)
        storage.putString(prefix + KEY_SYNC_TRUSTED_SERVER_KEY, trustedServerPub)
        storage.putLong(prefix + KEY_SYNC_LAST_CHECKED_AT, System.currentTimeMillis())
        storage.putString(prefix + KEY_SYNC_ROUTES_JSON, json.encodeToString(routes))
        storage.putString(KEY_SYNC_CONTEXTS, json.encodeToString((contexts + prefix).distinct()))
        storage.putString(KEY_ACTIVE_SYNC_CONTEXT, prefix)
    }

    private fun syncContexts(): List<String> {
        val indexed = storage.getString(KEY_SYNC_CONTEXTS, null)?.let {
            runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList())
        }.orEmpty()
        val legacy = if (!storage.getString(KEY_SYNC_DEVICE_ID, null).isNullOrBlank()) listOf("") else emptyList()
        return (indexed + legacy).distinct().filter { !storage.getString(it + KEY_SYNC_DEVICE_ID, null).isNullOrBlank() }
    }

    private fun activeSyncContext(): String = storage.getString(KEY_ACTIVE_SYNC_CONTEXT, null)
        ?.takeIf { it in syncContexts() } ?: syncContexts().lastOrNull().orEmpty()

    fun isEnrolledInRouteSync(): Boolean = syncContexts().isNotEmpty()

    fun clearEnrolledSyncState() {
        syncContexts().forEach(::clearSyncContext)
    }

    private fun clearSyncContext(prefix: String) {
        val remaining = syncContexts().filterNot { it == prefix }
        for (key in listOf(KEY_SYNC_DEVICE_ID, KEY_SYNC_USER_ID, KEY_SYNC_PROFILE_ID, KEY_SYNC_DOC_URL,
            KEY_SYNC_SECRET_KEY, KEY_SYNC_REVISION, KEY_SYNC_TRUSTED_SERVER_KEY, KEY_SYNC_LAST_CHECKED_AT,
            KEY_SYNC_ROUTES_JSON, KEY_MANAGED_TUNNEL_IDS)) storage.remove(prefix + key)
        storage.putString(KEY_SYNC_CONTEXTS, json.encodeToString(remaining))
        if (storage.getString(KEY_ACTIVE_SYNC_CONTEXT, null) == prefix)
            storage.putString(KEY_ACTIVE_SYNC_CONTEXT, remaining.lastOrNull())
    }

    @Synchronized
    fun syncRoutes(force: Boolean = false): RouteSyncResult {
        val contexts = syncContexts()
        if (contexts.isEmpty()) return RouteSyncResult.Failed("Устройство не зарегистрировано для получения маршрутов")
        val results = contexts.map { prefix -> runCatching { syncRouteContext(prefix, force) }
            .getOrElse { RouteSyncResult.Failed(it.message ?: "Не удалось применить маршруты") } }
        results.filterIsInstance<RouteSyncResult.Failed>().firstOrNull()?.let { return it }
        val updates = results.filterIsInstance<RouteSyncResult.Updated>()
        if (updates.isNotEmpty()) return RouteSyncResult.Updated(updates.maxOf { it.newRevision }, updates.flatMap { it.routes })
        if (results.any { it is RouteSyncResult.Revoked }) return RouteSyncResult.Revoked
        return results.last()
    }

    private fun syncRouteContext(prefix: String, force: Boolean): RouteSyncResult {
        val deviceId = storage.getString(prefix + KEY_SYNC_DEVICE_ID, null)
            ?: return RouteSyncResult.Failed("Устройство не зарегистрировано для получения маршрутов")
        val profileId = storage.getString(prefix + KEY_SYNC_PROFILE_ID, null)
            ?: return RouteSyncResult.Failed("Отсутствует ID профиля синхронизации")
        val docUrl = storage.getString(prefix + KEY_SYNC_DOC_URL, null)
            ?: return RouteSyncResult.Failed("Отсутствует URL документа синхронизации")
        val secretKey = storage.getString(prefix + KEY_SYNC_SECRET_KEY, null)
            ?: return RouteSyncResult.Failed("Отсутствует секретный ключ")
        val currentRev = storage.getLong(prefix + KEY_SYNC_REVISION, 1L)
        val trustedServerKey = storage.getString(prefix + KEY_SYNC_TRUSTED_SERVER_KEY, null)
            ?: getPinnedServerPublicKey()
            ?: return RouteSyncResult.Failed("Публичный ключ сервера не найден")
        val lastCheckedAt = storage.getLong(prefix + KEY_SYNC_LAST_CHECKED_AT, 0L)
        val now = System.currentTimeMillis()

        if (!force && now - lastCheckedAt < 10_000L) {
            return RouteSyncResult.Unchanged(currentRev, lastCheckedAt)
        }

        val crypto = RecipientInvitationCrypto(
            recipientIdentityStorage
        )

        val cmdPayload = json.encodeToString(
            mapOf(
                "op" to "sync-routes",
                "device_id" to deviceId,
                "expected_revision" to currentRev.toString()
            )
        )
        val payloadBase64 = Base64.getUrlEncoder().withoutPadding().encodeToString(cmdPayload.toByteArray(Charsets.UTF_8))
        val env = AdminEnvelope(
            ver = 1,
            type = AdminEnvelope.TYPE_ADMIN,
            subtype = AdminEnvelope.SUBTYPE_COMMAND,
            profileId = profileId,
            opId = UUID.randomUUID().toString(),
            rev = currentRev,
            sender = crypto.publicKeyHashHex(),
            payload = payloadBase64,
            exp = (now / 1000L) + 300L
        )
        val signedEnv = crypto.signRedeemEnvelope(env)

        val receiptEnv = try {
            if (documentSyncRunner != null) {
                documentSyncRunner.invoke(docUrl, profileId, secretKey, signedEnv, trustedServerKey)
            } else {
                val appContext = context ?: return RouteSyncResult.Failed("Context unavailable")
                AdminDocumentClaimClient(appContext).sync(
                    documentUrl = docUrl,
                    profileId = profileId,
                    secretKey = secretKey,
                    syncEnvelope = signedEnv,
                    serverPublicKey = trustedServerKey
                )
            }
        } catch (e: Exception) {
            return RouteSyncResult.Failed("Ошибка синхронизации через документ: ${e.message}")
        }

        if (!keyManager.verifyServerResponse(receiptEnv, trustedServerKey, trustedServerKey)) {
            return RouteSyncResult.Failed("Серверная подпись ответа синхронизации недействительна")
        }
        if (receiptEnv.opId != signedEnv.opId || receiptEnv.profileId != profileId) {
            return RouteSyncResult.Failed("Ответ синхронизации относится к другому профилю или запросу")
        }

        val rawPayload = runCatching { Base64.getUrlDecoder().decode(receiptEnv.payload) }
            .getOrElse { Base64.getDecoder().decode(receiptEnv.payload) }
        val respJson = String(rawPayload, Charsets.UTF_8)
        val respObj = json.parseToJsonElement(respJson).jsonObject
        val status = respObj["status"]?.jsonPrimitive?.content ?: ""
        val dataObj = respObj["data"]?.jsonObject
        if (respObj["op_id"]?.jsonPrimitive?.content != signedEnv.opId ||
            dataObj?.get("device_id")?.jsonPrimitive?.content?.let { it != deviceId } == true) {
            return RouteSyncResult.Failed("Ответ синхронизации относится к другому устройству или операции")
        }

        val syncStatus = dataObj?.get("status")?.jsonPrimitive?.content ?: status

        when (syncStatus) {
            "revoked" -> {
                stopActiveTunnelIfManaged(managedTunnelIds(prefix))
                updateManagedTunnels(emptyList(), prefix)
                clearSyncContext(prefix)
                return RouteSyncResult.Revoked
            }
            "unchanged" -> {
                storage.putLong(prefix + KEY_SYNC_LAST_CHECKED_AT, now)
                return RouteSyncResult.Unchanged(currentRev, now)
            }
            "updated" -> {
                val newRev = dataObj?.get("revision")?.jsonPrimitive?.longOrNull ?: (currentRev + 1)
                if (newRev < currentRev) return RouteSyncResult.Failed("Отклонена устаревшая ревизия маршрутов")
                val rawRoutes = dataObj?.get("routes")
                val routesList = if (rawRoutes != null) {
                    json.decodeFromJsonElement<List<RouteCandidate>>(rawRoutes)
                } else emptyList()

                val store = getTunnelStore()
                val oldManagedIds = managedTunnelIds(prefix)
                val oldRoutes = storage.getString(prefix + KEY_SYNC_ROUTES_JSON, null)?.let {
                    runCatching { json.decodeFromString<List<RouteCandidate>>(it) }.getOrNull()
                }.orEmpty()
                val oldIds = oldManagedIds.toList()
                val stableIds = oldRoutes.mapIndexedNotNull { index, route ->
                    oldIds.getOrNull(index)?.let { route.route_id to it }
                }.toMap()
                val newTunnels = routesList.map { route ->
                    val tunnel = routeCandidateToTunnel(route)
                    stableIds[route.route_id]?.let { tunnel.copy(id = it) } ?: tunnel
                }
                val selectedId = store?.getSelectedId()
                if (selectedId != null && selectedId in oldManagedIds) {
                    val activeTunnel = store.load().firstOrNull { it.id == selectedId }
                    val stillPresent = activeTunnel != null && newTunnels.any {
                        (it.id == selectedId || it.name == activeTunnel.name) &&
                            it.transportConnPayload == activeTunnel.transportConnPayload && it.encryptionKey == activeTunnel.encryptionKey
                    }
                    if (!stillPresent) {
                        stopActiveTunnelIfManaged(setOf(selectedId))
                    }
                }

                updateManagedTunnels(newTunnels, prefix)
                storage.putLong(prefix + KEY_SYNC_REVISION, newRev)
                storage.putLong(prefix + KEY_SYNC_LAST_CHECKED_AT, now)
                storage.putString(prefix + KEY_SYNC_ROUTES_JSON, json.encodeToString(routesList))
                return RouteSyncResult.Updated(newRev, newTunnels)
            }
            else -> {
                return RouteSyncResult.Failed("Неизвестный статус синхронизации: $syncStatus")
            }
        }
    }

    private fun getTunnelStore(): io.github.libreroute.data.TunnelStore? {
        if (customTunnelStore != null) return customTunnelStore
        val ctx = context ?: return null
        return io.github.libreroute.data.TunnelRepository(ctx)
    }

    private fun stopVpnServices() {
        if (vpnStopper != null) {
            vpnStopper.invoke(context)
            return
        }
        val ctx = context ?: return
        try {
            val disconnectVpnIntent = Intent(ctx, io.github.libreroute.service.SocksVpnService::class.java).apply {
                action = io.github.libreroute.service.SocksVpnService.ACTION_DISCONNECT
            }
            ctx.startService(disconnectVpnIntent)
        } catch (e: Exception) {
            Logx.w(TAG, "Failed stopping managed VPN: ${e.message}")
        }
        try {
            val disconnectProxyIntent = Intent(ctx, io.github.libreroute.service.LibreRouteProxyService::class.java).apply {
                action = io.github.libreroute.service.LibreRouteProxyService.ACTION_DISCONNECT
            }
            ctx.startService(disconnectProxyIntent)
        } catch (e: Exception) {
            Logx.w(TAG, "Failed stopping managed Proxy: ${e.message}")
        }
    }

    fun stopActiveTunnelIfManaged(managedIdsToStop: Set<Long>? = null): Boolean {
        val store = getTunnelStore() ?: return false
        val managedIds = managedIdsToStop ?: syncContexts().flatMap { managedTunnelIds(it) }.toSet()
        val selectedId = store.getSelectedId()
        if (selectedId != null && selectedId in managedIds) {
            stopVpnServices()
            return true
        }
        return false
    }

    private fun managedTunnelIds(prefix: String): Set<Long> = storage.getString(prefix + KEY_MANAGED_TUNNEL_IDS, "")
        ?.split(",")?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()

    @Synchronized
    fun updateManagedTunnels(newTunnels: List<Tunnel>) = updateManagedTunnels(newTunnels, activeSyncContext())

    private fun updateManagedTunnels(newTunnels: List<Tunnel>, prefix: String) {
        val store = getTunnelStore() ?: return
        val currentTunnels = store.load().toMutableList()
        val oldManagedIds = managedTunnelIds(prefix)

        currentTunnels.removeAll { it.id in oldManagedIds }
        val preparedTunnels = newTunnels.map { t ->
            // A save may have succeeded immediately before the ownership marker failed.
            // Replaying the exact checkpoint must upsert that record with its stable id.
            currentTunnels.removeAll { it == t }
            val unique = if (currentTunnels.any { it.id == t.id }) {
                var id = -java.nio.ByteBuffer.wrap(java.security.MessageDigest.getInstance("SHA-256")
                    .digest((prefix + t.toString()).toByteArray())).long.and(Long.MAX_VALUE)
                while (currentTunnels.any { it.id == id && it != t.copy(id = id) }) id--
                currentTunnels.removeAll { it == t.copy(id = id) }
                t.copy(id = id)
            } else t
            context?.let { TunnelLinkParser.ensureLocalKeyFile(it, unique) } ?: unique
        }
        currentTunnels.addAll(preparedTunnels)
        store.save(currentTunnels)
        storage.putString(prefix + KEY_MANAGED_TUNNEL_IDS, preparedTunnels.joinToString(",") { it.id.toString() })
    }
}

