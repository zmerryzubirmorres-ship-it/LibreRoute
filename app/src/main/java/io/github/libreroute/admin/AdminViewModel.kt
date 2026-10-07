package io.github.libreroute.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.libreroute.data.TunnelRepository
import io.github.libreroute.util.Logx
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class AdminUiEvent {
    data class ShowInstallPlan(val node: ServerNode, val operationId: String, val documentUrl: String, val summary: String) : AdminUiEvent()
    data class ShowToast(val message: String) : AdminUiEvent()
    data class ShowInviteDialog(val profile: ManagedProfile, val inviteLink: String) : AdminUiEvent()
    data class ShowAssignmentInviteDialog(val assignmentId: String, val inviteLink: String) : AdminUiEvent()
    data class ShowAccessOperation(val operation: AccessOperationRecord) : AdminUiEvent()
}

class AdminViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = AdminRepository.getInstance(app)
    private val keyManager = AdminKeyManager(app)
    private val tunnelRepo = TunnelRepository(app)

    private val restoredAccessOperation = repository.loadAccessOperation()?.let { record ->
        // A process recreation cannot observe whether a submitting request reached the
        // server. Keep that uncertainty explicit until an authoritative refresh.
        if (record.state == AccessOperationState.SUBMITTING) {
            record.copy(state = AccessOperationState.UNKNOWN, error = "Приложение было закрыто до подтверждения результата")
        } else record
    }
    init {
        if (restoredAccessOperation?.state == AccessOperationState.UNKNOWN) {
            repository.saveAccessOperation(restoredAccessOperation)
        }
    }
    private val _accessOperation = MutableStateFlow(restoredAccessOperation)
    /** Latest issuance result; survives rotation and process recreation. */
    val accessOperation: StateFlow<AccessOperationRecord?> = _accessOperation.asStateFlow()

    val role: StateFlow<AdminRole> = repository.roleFlow
    val managedProfiles: StateFlow<List<ManagedProfile>> = repository.managedProfilesFlow
    val commandHistory: StateFlow<List<AdminCommandRecord>> = repository.commandHistoryFlow
    val pendingRequest: StateFlow<AdminEnvelope?> = repository.pendingRequestFlow
    val lastPairingCode: StateFlow<String?> = repository.lastPairingCodeFlow
    val serverNodes: StateFlow<List<ServerNode>> = repository.serverNodesFlow
    val problemReports: StateFlow<List<ProblemReport>> = repository.problemReportsFlow
    val installationOperations: StateFlow<List<ServerInstallOperation>> = repository.installationOperations

    fun canPerform(operation: String): Boolean = repository.canPerform(currentServerNode()?.id, operation)
    fun currentServerAccess(): AdminServerAccess = repository.getServerAccess(currentServerNode()?.id)

    private val _serverProfiles = MutableStateFlow<List<ServerManagedProfile>>(emptyList())
    val serverProfiles: StateFlow<List<ServerManagedProfile>> = _serverProfiles.asStateFlow()
    private val _serverProfilesStatus = MutableStateFlow("Список сервера ещё не загружен")
    val serverProfilesStatus: StateFlow<String> = _serverProfilesStatus.asStateFlow()

    private val _users = MutableStateFlow(repository.clusterSnapshotFlow.value?.users.orEmpty())
    val users: StateFlow<List<AdminUser>> = _users.asStateFlow()
    private val _usersStatus = MutableStateFlow("Список пользователей ещё не загружен")
    val usersStatus: StateFlow<String> = _usersStatus.asStateFlow()
    private var usersRequestGeneration = 0

    private val _devices = MutableStateFlow<List<AdminDevice>>(emptyList())
    val devices: StateFlow<List<AdminDevice>> = _devices.asStateFlow()

    private val _assignments = MutableStateFlow(repository.clusterSnapshotFlow.value?.assignments.orEmpty())
    val assignments: StateFlow<List<AdminAssignment>> = _assignments.asStateFlow()
    private val _assignmentsStatus = MutableStateFlow("Назначения ещё не загружены")
    val assignmentsStatus: StateFlow<String> = _assignmentsStatus.asStateFlow()
    private var assignmentsRequestGeneration = 0

    private val _invitations = MutableStateFlow<List<AdminInvitation>>(emptyList())
    val invitations: StateFlow<List<AdminInvitation>> = _invitations.asStateFlow()

    private val _clusterSnapshot = MutableStateFlow(repository.clusterSnapshotFlow.value)
    private var clusterRequestGeneration = 0
    val clusterSnapshot: StateFlow<AdminClusterSnapshot?> = _clusterSnapshot.asStateFlow()
    private val _clusterSnapshotStatus = MutableStateFlow("Снимок кластера не загружен")
    val clusterSnapshotStatus: StateFlow<String> = _clusterSnapshotStatus.asStateFlow()

    private val _selectedServerNodeId = MutableStateFlow<String?>(null)
    val selectedServerNodeId: StateFlow<String?> = _selectedServerNodeId.asStateFlow()

    private val _selectedChannel = MutableStateFlow(AdminChannel.DOCUMENT)
    val selectedChannel: StateFlow<AdminChannel> = _selectedChannel.asStateFlow()

    private val _uiEvents = MutableSharedFlow<AdminUiEvent>()
    val uiEvents: SharedFlow<AdminUiEvent> = _uiEvents.asSharedFlow()

    private val _hasAdminSshKey = MutableStateFlow(repository.hasAdminSshKey())
    val hasAdminSshKey: StateFlow<Boolean> = _hasAdminSshKey.asStateFlow()

    private val _adminSshPublicKey = MutableStateFlow(repository.getAdminSshPublicKey())
    val adminSshPublicKey: StateFlow<String?> = _adminSshPublicKey.asStateFlow()

    fun refreshSshKeyStatus() {
        _hasAdminSshKey.value = repository.hasAdminSshKey()
        _adminSshPublicKey.value = repository.getAdminSshPublicKey()
    }

    fun generateSshKeyPair() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.generateAdminSshKey()
                refreshSshKeyStatus()
                _uiEvents.emit(AdminUiEvent.ShowToast("Сгенерирован новый SSH-ключ Ed25519"))
            } catch (e: Exception) {
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка генерации SSH-ключа: ${e.message}"))
            }
        }
    }

    fun importSshPrivateKey(pemContent: String, passphrase: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val success = repository.importAdminSshPrivateKey(pemContent, passphrase)
                if (success) {
                    refreshSshKeyStatus()
                    _uiEvents.emit(AdminUiEvent.ShowToast("SSH-ключ успешно импортирован"))
                } else {
                    _uiEvents.emit(AdminUiEvent.ShowToast("Некорректный формат PEM/OpenSSH ключа"))
                }
            } catch (e: Exception) {
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка импорта SSH-ключа: ${e.message}"))
            }
        }
    }

    fun getEffectiveAdminSshKeyPath(): String? = repository.getEffectiveAdminSshKeyPath()
    fun controlProfileForServer(node: ServerNode): String? = repository.getActiveGrant(node.id)?.profileId
    fun defaultDocumentUrl(serverId: String): String? = repository.defaultDocumentUrl(serverId)

    fun prepareServerInstallation(node: ServerNode, documentUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiEvents.emit(AdminUiEvent.ShowToast("Проверяем сервер и подписанную сборку…"))
            runCatching {
                val operation = UUID.randomUUID().toString()
                val plan = repository.planServerInstallation(node, operation)
                val summary = listOf("adapter", "binary_path", "state_dir", "service").joinToString("\n") { "$it: ${plan[it]}" }
                _uiEvents.emit(AdminUiEvent.ShowInstallPlan(node, operation, documentUrl, summary))
            }.onFailure { _uiEvents.emit(AdminUiEvent.ShowToast(it.message ?: "Не удалось подготовить установку")) }
        }
    }

    fun installServer(node: ServerNode, operationId: String, documentUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiEvents.emit(AdminUiEvent.ShowToast("Устанавливаем сервер. Дождитесь проверки службы…"))
            val result = repository.installServer(node, operationId, documentUrl)
            _uiEvents.emit(AdminUiEvent.ShowToast(if (result is AdminOpResult.Failure) result.error else (result as AdminOpResult.Success).message))
        }
    }

    fun checkServerInstallation(node: ServerNode, operationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.checkServerInstallation(node, operationId)
            _uiEvents.emit(AdminUiEvent.ShowToast(if (result is AdminOpResult.Failure) result.error else (result as AdminOpResult.Success).message))
        }
    }

    fun rollbackServerInstallation(node: ServerNode, operationId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = repository.rollbackServerInstallation(node, operationId)
            _uiEvents.emit(AdminUiEvent.ShowToast(if (result is AdminOpResult.Failure) result.error else (result as AdminOpResult.Success).message))
        }
    }

    val fingerprint: String get() = keyManager.getFingerprint()
    val formattedFingerprint: String get() = keyManager.getFormattedFingerprint()

    fun selectChannel(channel: AdminChannel) {
        clusterRequestGeneration++
        _selectedChannel.value = channel
        refreshUsers()
        refreshClusterSnapshot()
    }

    fun selectServerNode(id: String?) {
        clusterRequestGeneration++
        usersRequestGeneration++
        assignmentsRequestGeneration++
        _selectedServerNodeId.value = id
        val snapshot = repository.cachedClusterSnapshot(id ?: repository.getServerNodes().singleOrNull()?.id)
        _clusterSnapshot.value = snapshot
        _users.value = snapshot?.users.orEmpty()
        _assignments.value = snapshot?.assignments.orEmpty()
        _devices.value = emptyList()
        _invitations.value = emptyList()
        _serverProfiles.value = emptyList()
        _clusterSnapshotStatus.value = if (snapshot == null) "Снимок выбранного сервера ещё не загружен" else "Последний подтверждённый снимок; требуется обновление"
    }

    fun addServerNode(node: ServerNode) {
        repository.addServerNode(node)
        if (_selectedServerNodeId.value == null) {
            _selectedServerNodeId.value = node.id
        }
    }

    fun updateServerNode(node: ServerNode) {
        repository.updateServerNode(node)
    }

    fun removeServerNode(id: String) {
        repository.removeServerNode(id)
        if (_selectedServerNodeId.value == id) {
            _selectedServerNodeId.value = repository.getServerNodes().singleOrNull()?.id
        }
    }

    fun getAvailableVpnProfiles(): List<io.github.libreroute.data.Tunnel> {
        return tunnelRepo.load()
    }

    fun refreshServerProfiles() {
        viewModelScope.launch {
            _serverProfilesStatus.value = "Запрашиваем список у сервера…"
            val result = withContext(Dispatchers.IO) { repository.listServerManagedProfiles(currentServerNode()) }
            result.onSuccess { profiles ->
                _serverProfiles.value = profiles.sortedBy { it.name.lowercase() }
                _serverProfilesStatus.value = if (profiles.isEmpty())
                    "Сервер подтвердил: управляемых конфигураций пока нет"
                else "Сервер подтвердил ${profiles.size} конфигураций"
            }.onFailure { error ->
                _serverProfilesStatus.value = "Не удалось обновить список сервера; показаны последние данные: ${error.message ?: "нет ответа"}"
            }
        }
    }

    private fun currentServerNode(): ServerNode? {
        val selected = _selectedServerNodeId.value?.let(repository::getServerNode)
        return selected?.takeIf { nodeMatchesChannel(it, _selectedChannel.value) }
            ?: repository.getServerNodes().filter { nodeMatchesChannel(it, _selectedChannel.value) }.singleOrNull()
    }

    fun hasPendingUserDocument(userId: String): Boolean = repository.hasPendingUserDocument(userId)

    fun refreshUsers() {
        val generation = ++usersRequestGeneration
        viewModelScope.launch {
            _usersStatus.value = "Запрашиваем пользователей у сервера…"
            val channel = _selectedChannel.value
            val server = currentServerNode()
            val result = withContext(Dispatchers.IO) { repository.listUsers(channel, server) }
            if (generation != usersRequestGeneration) return@launch
            result.onSuccess { users ->
                _users.value = users.sortedBy { it.name.lowercase() }
                _usersStatus.value = if (users.isEmpty()) "На сервере пока нет пользователей"
                    else "Сервер подтвердил ${users.size} пользователей"
            }.onFailure { error ->
                _usersStatus.value = "Не удалось обновить пользователей; показан последний список: ${error.message ?: "нет ответа"}"
            }
        }
    }

    fun createUser(name: String, documentUrl: String) {
        viewModelScope.launch {
            val channel = _selectedChannel.value
            val server = currentServerNode()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val user = repository.createUser(name, channel, server).getOrThrow()
                    if (documentUrl.isNotBlank()) repository.savePendingUserDocument(user.id, documentUrl)
                    user
                }
            }
            result.onSuccess { user ->
                _uiEvents.emit(AdminUiEvent.ShowToast(
                    "Пользователь «${user.name}» создан. Откройте его карточку и настройте доступ к серверам."
                ))
                refreshUsers()
            }.onFailure { error ->
                _uiEvents.emit(AdminUiEvent.ShowToast("Не удалось добавить пользователя: ${error.message ?: "нет ответа"}"))
                _usersStatus.value = "Не удалось добавить пользователя: ${error.message ?: "нет ответа"}"
            }
        }
    }

    fun refreshAssignments() {
        val generation = ++assignmentsRequestGeneration
        viewModelScope.launch {
            _assignmentsStatus.value = "Запрашиваем назначения у сервера…"
            val channel = _selectedChannel.value
            val server = currentServerNode()
            val result = withContext(Dispatchers.IO) { repository.listAssignments(channel = channel, serverNode = server) }
            if (generation != assignmentsRequestGeneration) return@launch
            result.onSuccess {
                _assignments.value = it
                _assignmentsStatus.value = if (it.isEmpty()) "На сервере пока нет назначений"
                    else "Сервер подтвердил ${it.size} назначений"
            }.onFailure {
                _assignmentsStatus.value = "Не удалось обновить назначения; показан последний список: ${it.message ?: "нет ответа"}"
            }
        }
    }

    fun assignPendingUserDocument(user: AdminUser, devicePublicKey: String, transport: String = "vyandex", endpoint: String? = null) {
        viewModelScope.launch {
            val channel = _selectedChannel.value
            val server = currentServerNode()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val documentUrl = endpoint ?: repository.getPendingUserDocument(user.id)
                        ?: error("Для пользователя не сохранён документ")
                    val serverId = server?.id ?: error("Выберите сервер для назначения")
                    val key = devicePublicKey.trim()
                    val device = repository.listDevices(user.id, channel, server).getOrThrow()
                        .firstOrNull { it.publicKey == key }
                        ?: repository.registerDevice(user.id, key, channel, server).getOrThrow()
                    val assignment = repository.assignProfile(
                        userId = user.id,
                        deviceId = device.id,
                        serverId = serverId,
                        documentUrl = documentUrl,
                        transport = transport,
                        channel = channel,
                        serverNode = server
                    ).getOrThrow()
                    repository.clearPendingUserDocument(user.id)
                    assignment
                }
            }
            result.onSuccess { assignment ->
                _uiEvents.emit(AdminUiEvent.ShowToast(
                    "Маршрут назначен пользователю «${user.name}», состояние: ${assignment.status}"
                ))
                refreshUsers()
                refreshAssignments()
            }.onFailure { error ->
                _usersStatus.value = "Не удалось назначить маршрут: ${error.message ?: "нет ответа"}"
                _uiEvents.emit(AdminUiEvent.ShowToast(_usersStatus.value))
            }
        }
    }

    private fun currentHostConfig(): SshHostConfig? {
        if (_selectedChannel.value == AdminChannel.DOCUMENT) {
            return null
        }
        val targetId = _selectedServerNodeId.value
        if (targetId != null) {
            val node = repository.getServerNode(targetId)
            if (node != null && nodeMatchesChannel(node, _selectedChannel.value)) return node.toSshHostConfig()
        }
        val firstNode = repository.getServerNodes().firstOrNull { nodeMatchesChannel(it, _selectedChannel.value) }
        if (firstNode != null) {
            return firstNode.toSshHostConfig()
        }
        return null
    }

    private fun nodeMatchesChannel(node: ServerNode, channel: AdminChannel): Boolean = when (channel) {
        AdminChannel.SSH_VDS -> node.nodeType == ServerNodeType.VDS || node.nodeType == ServerNodeType.CUSTOM
        AdminChannel.SSH_NETCRAZE -> node.nodeType == ServerNodeType.ROUTER_KEENETIC || node.nodeType == ServerNodeType.ROUTER_OPENWRT
        AdminChannel.DIRECT_SSH -> true
        AdminChannel.DOCUMENT -> false
    }

    fun requestJoin(targetProfileId: String, desiredRole: AdminRole) {
        viewModelScope.launch {
            val (envelope, code) = repository.createJoinRequest(targetProfileId, desiredRole)
            _uiEvents.emit(AdminUiEvent.ShowToast("Запрос сформирован локально. Канал документа ещё не подключён. Код: $code"))
        }
    }

    fun pendingJoinRequestJson(): String? = repository.pendingRequestFlow.value?.toJson()

    fun importBootstrapGrant(grantJson: String, fingerprint: String) {
        viewModelScope.launch {
            val result = repository.importTrustedBootstrapGrant(grantJson, fingerprint)
            when (result) {
                is AdminOpResult.Success -> _uiEvents.emit(AdminUiEvent.ShowToast("Права администратора подтверждены"))
                is AdminOpResult.Failure -> _uiEvents.emit(AdminUiEvent.ShowToast(result.error))
            }
        }
    }

    fun claimServerBySetupUri(claimUri: String, hostPublicKey: String? = null) {
        val claimInfo = ServerClaimInfo.parse(claimUri)
        if (claimInfo == null) {
            viewModelScope.launch {
                _uiEvents.emit(AdminUiEvent.ShowToast("Некорректная ссылка или QR-код сопряжения (ожидается libreroute://claim?server=...&token=...)"))
            }
            return
        }

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.claimServerWithSetupKey(claimInfo, hostPublicKey)
            }
            when (result) {
                is AdminOpResult.Success -> {
                    _uiEvents.emit(AdminUiEvent.ShowToast(result.message))
                }
                is AdminOpResult.Failure -> {
                    _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка сопряжения: ${result.error}"))
                }
            }
        }
    }

    fun createProfile(
        name: String,
        documentUrl: String,
        transport: String,
        sessionNegotiate: Boolean,
        udpEnabled: Boolean,
        allowedServerIds: List<String> = emptyList(),
        allowedProtocols: List<String> = listOf("vyandex", "udp-ipv4"),
        serverConfigs: Map<String, ProfileServerConfig> = emptyMap()
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.createManagedProfile(
                name = name,
                documentUrl = documentUrl,
                transportType = transport,
                sessionNegotiated = sessionNegotiate,
                udpEnabled = udpEnabled,
                allowedServerIds = allowedServerIds,
                allowedProtocols = allowedProtocols,
                serverConfigs = serverConfigs,
                channel = _selectedChannel.value,
                hostConfig = currentHostConfig()
            ) }
            when (result) {
                is AdminOpResult.Success -> _uiEvents.emit(AdminUiEvent.ShowToast(result.message))
                is AdminOpResult.Failure -> _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка: ${result.error}"))
            }
        }
    }

    suspend fun testProfileCombinations(profile: ManagedProfile): List<ProbeResult> =
        withContext(Dispatchers.IO) { repository.testCombinations(profile) }

    fun issueInvite(profile: ManagedProfile, recipientPublicKey: String? = null) {
        viewModelScope.launch {
            val (result, inviteLink) = withContext(Dispatchers.IO) {
                repository.issueInvitation(profile.id, recipientPublicKey)
            }
            when (result) {
                is AdminOpResult.Success -> {
                    _uiEvents.emit(AdminUiEvent.ShowInviteDialog(profile, inviteLink))
                }
                is AdminOpResult.Failure -> {
                    _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка: ${result.error}"))
                }
            }
        }
    }

    fun suspendProfile(profileId: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.suspendProfile(profileId, _selectedChannel.value, currentHostConfig()) }
            when (result) {
                is AdminOpResult.Success -> _uiEvents.emit(AdminUiEvent.ShowToast(result.message))
                is AdminOpResult.Failure -> _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка: ${result.error}"))
            }
        }
    }

    fun resumeProfile(profileId: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.resumeProfile(profileId, _selectedChannel.value, currentHostConfig()) }
            when (result) {
                is AdminOpResult.Success -> _uiEvents.emit(AdminUiEvent.ShowToast(result.message))
                is AdminOpResult.Failure -> _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка: ${result.error}"))
            }
        }
    }

    fun revokeProfile(profileId: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repository.revokeProfile(profileId, _selectedChannel.value, currentHostConfig()) }
            when (result) {
                is AdminOpResult.Success -> _uiEvents.emit(AdminUiEvent.ShowToast(result.message))
                is AdminOpResult.Failure -> _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка: ${result.error}"))
            }
        }
    }

    fun revokeAdminRights() {
        viewModelScope.launch {
            repository.revokeAdminRights()
            _uiEvents.emit(AdminUiEvent.ShowToast("Права администратора на устройстве отозваны"))
        }
    }

    fun submitProblemReport(profileId: String, code: String, note: String) {
        viewModelScope.launch {
            val details = if (note.isNotBlank()) mapOf("note" to note) else emptyMap()
            val result = repository.submitProblemReport(profileId, code, details)
            when (result) {
                is AdminOpResult.Success -> _uiEvents.emit(AdminUiEvent.ShowToast(result.message))
                is AdminOpResult.Failure -> _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка: ${result.error}"))
            }
        }
    }
    private fun selectedServerNode(): ServerNode? {
        val targetId = _selectedServerNodeId.value
        if (targetId != null) {
            val node = repository.getServerNode(targetId)
            if (node != null) return node
        }
        return repository.getServerNodes().singleOrNull()
    }

    fun loadUsers() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.listUsers(_selectedChannel.value, selectedServerNode())
            }
            result.onSuccess { _users.value = it }
                .onFailure { _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка загрузки пользователей: ${it.message}")) }
        }
    }

    fun createUser(name: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.createUser(name, _selectedChannel.value, selectedServerNode())
            }
            result.onSuccess {
                _uiEvents.emit(AdminUiEvent.ShowToast("Пользователь ${it.name} создан"))
                loadUsers()
            }.onFailure {
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка создания пользователя: ${it.message}"))
            }
        }
    }

    fun suspendUser(userId: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.suspendUser(userId, _selectedChannel.value, selectedServerNode())
            }
            result.onSuccess {
                _uiEvents.emit(AdminUiEvent.ShowToast("Пользователь заблокирован"))
                loadUsers()
                loadAssignments()
            }.onFailure {
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка блокировки пользователя: ${it.message}"))
            }
        }
    }

    fun loadDevices(userId: String? = null) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.listDevices(userId, _selectedChannel.value, selectedServerNode())
            }
            result.onSuccess { _devices.value = it }
                .onFailure { _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка загрузки устройств: ${it.message}")) }
        }
    }

    fun registerDevice(userId: String, publicKey: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.registerDevice(userId, publicKey, _selectedChannel.value, selectedServerNode())
            }
            result.onSuccess {
                _uiEvents.emit(AdminUiEvent.ShowToast("Устройство зарегистрировано"))
                loadDevices(userId)
            }.onFailure {
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка регистрации устройства: ${it.message}"))
            }
        }
    }

    fun revokeDevice(deviceId: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.revokeDevice(deviceId, _selectedChannel.value, selectedServerNode())
            }
            result.onSuccess {
                _uiEvents.emit(AdminUiEvent.ShowToast("Устройство отозвано"))
                loadDevices()
                loadAssignments()
            }.onFailure {
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка отзыва устройства: ${it.message}"))
            }
        }
    }

    fun loadAssignments(userId: String? = null) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.listAssignments(userId, channel = _selectedChannel.value, serverNode = selectedServerNode())
            }
            result.onSuccess { _assignments.value = it }
                .onFailure { _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка загрузки назначений: ${it.message}")) }
        }
    }

    fun assignProfile(
        userId: String,
        deviceId: String,
        serverId: String,
        transport: String = "vyandex",
        documentUrl: String? = null,
        parameters: Map<String, String>? = null
    ) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.assignProfile(
                    userId = userId,
                    deviceId = deviceId,
                    serverId = serverId,
                    transport = transport,
                    documentUrl = documentUrl,
                    parameters = parameters,
                    channel = _selectedChannel.value,
                    serverNode = selectedServerNode()
                )
            }
            result.onSuccess {
                _uiEvents.emit(AdminUiEvent.ShowToast("Назначение создано"))
                loadAssignments()
            }.onFailure {
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка создания назначения: ${it.message}"))
            }
        }
    }

    fun revokeAssignment(assignmentId: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.revokeAssignment(assignmentId, _selectedChannel.value, selectedServerNode())
            }
            result.onSuccess {
                _uiEvents.emit(AdminUiEvent.ShowToast("Назначение отозвано"))
                loadAssignments()
            }.onFailure {
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка отзыва назначения: ${it.message}"))
            }
        }
    }

    fun issueBootstrapForPendingUser(user: AdminUser, transport: String = "vyandex", endpoint: String? = null) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val doc = endpoint ?: repository.getPendingUserDocument(user.id) ?: error("Подготовьте отдельный документ для устройства")
                    val channel = _selectedChannel.value
                    val node = selectedServerNode()
                    val snapshot = repository.getClusterSnapshot(channel, node).getOrThrow()
                    val serverId = node?.id ?: snapshot.servers.singleOrNull() ?: error("Выберите сервер")
                    val resolved = if (transport == "vyandex") AdminDocumentClaimClient.resolveDocumentUrl(doc) else doc
                    repository.issueBootstrapInvitation(user.id, listOf(EnrollmentRoute(serverId, transport = transport, url = resolved)), channel, node).getOrThrow()
                }
            }
            result.onSuccess { _uiEvents.emit(AdminUiEvent.ShowAssignmentInviteDialog(user.id, it)) }
                .onFailure { _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка приглашения: ${it.message}")) }
        }
    }

    fun configureUserAccess(user: AdminUser, routes: List<EnrollmentRoute>, devicePublicKey: String?) {
        val channel = _selectedChannel.value
        val authority = currentServerNode()
        val operationId = UUID.randomUUID().toString()
        val pending = AccessOperationRecord(
            operationId = operationId,
            userId = user.id,
            state = AccessOperationState.SUBMITTING,
            routes = routes.map { AccessRouteResult(it.server_id, it.transport) }
        )
        _accessOperation.value = pending
        repository.saveAccessOperation(pending)
        viewModelScope.launch {
            var submittedRoutes = 0
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    // Recheck capabilities against a fresh signed authority snapshot.
                    val fresh = repository.getClusterSnapshot(channel, authority).getOrThrow()
                    routes.forEach { route ->
                        require(fresh.methodsFor(route.server_id).any { it.transport == route.transport }) {
                            "Сервер изменил доступные протоколы. Откройте настройку заново"
                        }
                    }
                    val resolved = routes.map { route ->
                        if (route.transport == "vyandex") route.copy(url = AdminDocumentClaimClient.resolveDocumentUrl(route.url)) else route
                    }
                    if (devicePublicKey.isNullOrBlank()) {
                        repository.issueBootstrapInvitation(user.id, resolved, channel, authority).getOrThrow()
                    } else {
                        val key = devicePublicKey.trim()
                        val device = repository.listDevices(user.id, channel, authority).getOrThrow().firstOrNull { it.publicKey == key }
                            ?: repository.registerDevice(user.id, key, channel, authority).getOrThrow()
                        val existing = fresh.assignments.filter { it.deviceId == device.id &&
                            it.status in setOf("ready", "invited", "active") }
                        var assigned = 0
                        try {
                            resolved.forEach { route ->
                                if (existing.none { it.serverId == route.server_id && it.transport == route.transport }) {
                                    repository.assignProfile(user.id, device.id, route.server_id, route.transport,
                                        route.url.takeIf { it.isNotBlank() }, channel = channel, serverNode = authority).getOrThrow()
                                }
                                assigned++
                                submittedRoutes = assigned
                            }
                        } catch (failure: Exception) {
                            if (failure is UnknownAdminOperation) throw failure
                            throw IllegalStateException("Назначено $assigned из ${resolved.size} маршрутов. ${failure.message}", failure)
                        }
                        repository.clearPendingUserDocument(user.id)
                        ""
                    }
                }
            }
            result.onSuccess { invitation ->
                val completed = pending.copy(
                    state = AccessOperationState.CONFIRMED,
                    routes = pending.routes.map { it.copy(state = "created") },
                    updatedAt = System.currentTimeMillis()
                )
                _accessOperation.value = completed
                repository.saveAccessOperation(completed)
                if (invitation.isNotEmpty()) _uiEvents.emit(AdminUiEvent.ShowAssignmentInviteDialog(user.id, invitation))
                _uiEvents.emit(AdminUiEvent.ShowAccessOperation(completed))
            }.onFailure {
                val message = it.message ?: "Неизвестная ошибка"
                val isUnknown = it is UnknownAdminOperation
                val assigned = Regex("Назначено (\\d+) из (\\d+)").find(message)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: submittedRoutes
                val state = if (assigned > 0) AccessOperationState.PARTIAL
                    else if (isUnknown) AccessOperationState.UNKNOWN else AccessOperationState.FAILED
                val completed = pending.copy(
                    state = state,
                    routes = pending.routes.mapIndexed { index, route ->
                        route.copy(state = if (index < assigned) "created"
                            else if (isUnknown) "unknown" else "failed", reason = if (index < assigned) null else message)
                    },
                    error = message,
                    updatedAt = System.currentTimeMillis()
                )
                _accessOperation.value = completed
                repository.saveAccessOperation(completed)
                _uiEvents.emit(AdminUiEvent.ShowAccessOperation(completed))
                _uiEvents.emit(AdminUiEvent.ShowToast("Не удалось настроить доступ: $message"))
            }
            refreshAssignments()
            refreshClusterSnapshot()
        }
    }

    fun issueAssignmentInvitation(assignmentId: String) {
        viewModelScope.launch {
            val (result, inviteLink) = withContext(Dispatchers.IO) {
                repository.issueAssignmentInvitation(
                    assignmentId = assignmentId,
                    channel = _selectedChannel.value,
                    serverNode = selectedServerNode()
                )
            }
            when (result) {
                is AdminOpResult.Success -> {
                    _uiEvents.emit(AdminUiEvent.ShowAssignmentInviteDialog(assignmentId, inviteLink))
                    loadAssignments()
                }
                is AdminOpResult.Failure -> {
                    _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка: ${result.error}"))
                }
            }
        }
    }

    fun loadInvitations(assignmentId: String? = null) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                repository.listInvitations(assignmentId, _selectedChannel.value, selectedServerNode())
            }
            result.onSuccess { _invitations.value = it }
                .onFailure { _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка загрузки приглашений: ${it.message}")) }
        }
    }

    fun redeemClientInvitation(invitationLink: String) {
        viewModelScope.launch {
            val (result, tunnel) = withContext(Dispatchers.IO) {
                repository.redeemInvitation(invitationLink, selectedServerNode())
            }
            when (result) {
                is AdminOpResult.Success -> {
                    if (tunnel != null) {
                        val current = tunnelRepo.load().toMutableList()
                        current.removeAll { it.id == tunnel.id }
                        current.add(tunnel)
                        tunnelRepo.save(current)
                        _uiEvents.emit(AdminUiEvent.ShowToast("Туннель «${tunnel.name}» успешно активирован"))
                    } else {
                        _uiEvents.emit(AdminUiEvent.ShowToast(result.message))
                    }
                }
                is AdminOpResult.Failure -> {
                    _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка погашения приглашения: ${result.error}"))
                }
            }
        }
    }
    fun probeServer(node: ServerNode) {
        viewModelScope.launch {
            _uiEvents.emit(AdminUiEvent.ShowToast("Опрос характеристик сервера ${node.name}…"))
            val inv = withContext(Dispatchers.IO) {
                repository.probeServer(node)
            }
            if (inv.probeError != null) {
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка опроса ${node.name}: ${inv.probeError}"))
            } else {
                _uiEvents.emit(AdminUiEvent.ShowToast("Сервер ${node.name} в сети: ${inv.osName} ${inv.osVersion}, CPU ${inv.cpuCores}, RAM ${inv.ramTotalMb}MB"))
            }
        }
    }

    fun refreshClusterSnapshot() {
        val generation = ++clusterRequestGeneration
        val channel = _selectedChannel.value
        val server = currentServerNode()
        viewModelScope.launch {
            _clusterSnapshotStatus.value = "Запрашиваем снимок кластера…"
            val result = withContext(Dispatchers.IO) {
                repository.getClusterSnapshot(channel, server)
            }
            if (generation != clusterRequestGeneration) return@launch
            result.onSuccess { snapshot ->
                _clusterSnapshot.value = snapshot
                _assignments.value = snapshot.assignments
                _users.value = snapshot.users
                val syncStatuses = snapshot.perServerStatus.entries.joinToString(", ") {
                    "${it.key}: рев. ${it.value.appliedRevision} (готов: ${it.value.readiness})"
                }
                _clusterSnapshotStatus.value = "Снимок кластера: рев. ${snapshot.revision}, серверов: ${snapshot.servers.size}, назначений: ${snapshot.assignments.size}\nСинхронизация узлов: ${syncStatuses.ifEmpty { "нет данных" }}"
                _uiEvents.emit(AdminUiEvent.ShowToast("Снимок кластера обновлён (рев. ${snapshot.revision}, серверов: ${snapshot.servers.size}, назначений: ${snapshot.assignments.size})"))
            }.onFailure { error ->
                val reason = error.message?.take(240) ?: "нет ответа"
                _clusterSnapshotStatus.value = if (_clusterSnapshot.value == null) {
                    "Снимок кластера не загружен: $reason"
                } else {
                    "Не удалось обновить снимок; показаны последние данные: $reason"
                }
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка получения снимка кластера: ${error.message}"))
            }
        }
    }

    fun reconcileAssignments() {
        viewModelScope.launch {
            _uiEvents.emit(AdminUiEvent.ShowToast("Сверка и применение назначений…"))
            val result = withContext(Dispatchers.IO) {
                repository.reconcileAssignments(_selectedChannel.value, currentServerNode())
            }
            result.onSuccess { reconciled ->
                _assignments.value = reconciled
                val pending = reconciled.count { it.isPendingReconciliation }
                val failed = reconciled.count { it.isFailed }
                _uiEvents.emit(AdminUiEvent.ShowToast("Сверка завершена. Всего: ${reconciled.size}, ожидает: $pending, ошибок: $failed"))
                refreshClusterSnapshot()
            }.onFailure { error ->
                _uiEvents.emit(AdminUiEvent.ShowToast("Ошибка сверки: ${error.message}"))
            }
        }
    }
}
