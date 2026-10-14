package io.github.libreroute.admin

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.material.button.MaterialButton
import io.github.libreroute.ui.CalmViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

data class SetupState(val stage: String = "check", val busy: Boolean = false,
                      val summary: String = "", val error: String? = null,
                      val preparationCancellable: Boolean = false, val cancellationRequested: Boolean = false)

internal data class SetupCheckpoint(val operationId: String, val channel: AdminChannel,
    val document: String, val profileName: String, val automatic: Boolean, val state: SetupState)

/** Keeps the installation protocol internal; the user sees check → loading → result. */
class ServerSetupViewModel @JvmOverloads constructor(app: android.app.Application,
    private val repo: AdminRepository = AdminRepository.getInstance(app)) : AndroidViewModel(app) {
    private val mutableState = MutableStateFlow(SetupState())
    val state = mutableState.asStateFlow()
    var node: ServerNode? = null; private set
    var document: String = ""; private set
    private var operationId: String = ""
    private var takeoverRecovery = false
    private var autoProvision = false
    private var needsProbe = true
    private var planJson: String = ""
    private var adminProfileName: String = ""
    var updateChannel: AdminChannel = AdminChannel.AUTO; private set
    private var initialized = false
    private var restoredOperationNeedsCheck = false

    init {
        viewModelScope.launch {
            repo.installationOperations.collect { operations ->
                val operation = operations.firstOrNull { it.operationId == operationId } ?: return@collect
                if (mutableState.value.busy) {
                    val state = mutableState.value
                    mutableState.value = state.copy(
                        summary = if (operation.coordinator && !state.cancellationRequested) coreUpdateProgressLabel(operation) else state.summary,
                        preparationCancellable = !state.cancellationRequested && operation.kind == ServerInstallOperation.KIND_CORE_UPDATE &&
                            operation.stage !in setOf(ServerInstallOperation.READY, ServerInstallOperation.ROLLED_BACK,
                                ServerInstallOperation.CANCELLED_BEFORE_SUBMIT, ServerInstallOperation.FAILED_BEFORE_SUBMIT) &&
                            (operation.stage == ServerInstallOperation.PLANNED || operation.coordinator &&
                                operation.serverStage in setOf("", "planned", "receiving", "verified", "prepared")))
                }
            }
        }
    }

    fun selectUpdateChannel(channel: AdminChannel) {
        if (!mutableState.value.busy && updateChannel != channel) {
            updateChannel = channel
            mutableState.value = mutableState.value.copy(summary = "Выбран канал: ${channel.displayName}")
        }
    }
    fun cancelPreparation() {
        if (!mutableState.value.preparationCancellable || mutableState.value.cancellationRequested) return
        mutableState.value = mutableState.value.copy(preparationCancellable = false, cancellationRequested = true,
            summary = "Останавливаем подготовку. Сохранённый прогресс будет доступен после остановки…")
        repo.cancelCoreUpdatePreparation(operationId)
    }
    fun continueUpdate() {
        val target = node ?: return
        if (mutableState.value.busy) return
        val id = operationId
        val channel = updateChannel
        mutableState.value = SetupState(stage = "result", busy = true, summary = "Продолжаем обновление…")
        InstallationLog.append(getApplication(), "core_update_continue_start", "operation_id=$id channel=${channel.name}")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { repo.continueCoreUpdate(target, id, channel) }
                node = repo.getServerNode(target.id) ?: target
                InstallationLog.append(getApplication(), "core_update_continue_result", "operation_id=$id result=${result::class.simpleName} error=${(result as? AdminOpResult.Failure)?.error.orEmpty()}")
                mutableState.value = SetupState(stage = "result", error = (result as? AdminOpResult.Failure)?.error,
                    summary = if (result is AdminOpResult.Success) "Обновление подтверждено" else "")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                InstallationLog.append(getApplication(), "core_update_continue_error", "operation_id=$id message=${e.message.orEmpty()}")
                mutableState.value = SetupState(stage = "result", error = e.message ?: "Не удалось продолжить обновление")
            }
        }
    }

    fun rollbackUpdate() {
        val target = node ?: return
        if (mutableState.value.busy) return
        val id = operationId
        val channel = updateChannel
        mutableState.value = SetupState(stage = "result", busy = true, summary = "Запрашиваем восстановление…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { repo.rollbackServerInstallation(target, id, channel) }
            InstallationLog.append(getApplication(), "core_update_cancel_result", "operation_id=$id result=${result::class.simpleName} error=${(result as? AdminOpResult.Failure)?.error.orEmpty()}")
            mutableState.value = SetupState(stage = "result", error = (result as? AdminOpResult.Failure)?.error)
        }
    }

    fun restore(saved: Bundle?) {
        // by viewModels retains this instance and its job on rotation. A Bundle
        // belongs to process restoration, never to the running retained model.
        if (initialized || saved == null) return
        restoreCheckpoint(SetupCheckpoint(saved.getString("setup_operation_id").orEmpty(),
            runCatching { AdminChannel.valueOf(saved.getString("update_channel").orEmpty()) }.getOrDefault(AdminChannel.AUTO),
            saved.getString("setup_document").orEmpty(), saved.getString("setup_profile_name").orEmpty(),
            saved.getBoolean("setup_auto_provision", autoProvision),
            SetupState(saved.getString("setup_stage") ?: "check", saved.getBoolean("setup_busy", false),
                saved.getString("setup_summary").orEmpty(), saved.getString("setup_error"))))
    }

    internal fun restoreCheckpoint(saved: SetupCheckpoint) {
        if (initialized) return
        operationId = saved.operationId
        updateChannel = saved.channel
        document = saved.document
        adminProfileName = saved.profileName
        autoProvision = saved.automatic
        val wasBusy = saved.state.busy
        restoredOperationNeedsCheck = wasBusy
        mutableState.value = SetupState(
            stage = if (wasBusy) "result" else saved.state.stage,
            summary = saved.state.summary,
            error = if (wasBusy) null else saved.state.error)
    }

    fun save(out: Bundle) {
        val s = mutableState.value
        out.putString("setup_operation_id", operationId)
        out.putString("update_channel", updateChannel.name)
        out.putString("setup_document", document)
        out.putString("setup_profile_name", adminProfileName)
        out.putString("setup_stage", s.stage)
        out.putBoolean("setup_busy", s.busy)
        out.putBoolean("setup_auto_provision", autoProvision)
        out.putString("setup_summary", s.summary)
        out.putString("setup_error", s.error)
    }

    fun initialize(id: String, recovery: Boolean = false, automatic: Boolean = false, profileName: String = "") {
        if (initialized) return
        initialized = true
        // The first Activity may have migrated a temporary SSH node to the
        // server-owned identity.  Android can recreate this Activity with the
        // original Intent extra, so resolve the durable operation before
        // giving up on the node lookup.
        node = repo.serverNodesFlow.value.firstOrNull { it.id == id }
        if (node == null && operationId.isNotBlank()) {
            val operation = repo.loadServerInstallation(operationId)
            node = operation?.let {
                repo.getServerNode(it.serverId)
                    ?: it.priorLocalNodeId?.let(repo::getServerNode)
                    ?: it.node.takeIf { saved -> saved.host.isNotBlank() }
            }
        }
        if (node == null) {
            val pending = repo.installationOperations.value.lastOrNull {
                it.serverId == id || it.priorLocalNodeId == id
            }
            if (pending != null) {
                operationId = pending.operationId
                node = repo.getServerNode(pending.serverId)
                    ?: pending.priorLocalNodeId?.let(repo::getServerNode)
                    ?: pending.node.takeIf { it.host.isNotBlank() }
            }
        }
        if (node == null) return
        takeoverRecovery = recovery || takeoverRecovery
        autoProvision = automatic || autoProvision
        if (profileName.isNotBlank() || adminProfileName.isBlank()) adminProfileName = profileName
        if (document.isBlank()) document = repo.defaultDocumentUrl(node?.id.orEmpty()).orEmpty()
        // Resume an unfinished operation only for an internal status check. No
        // operation details are shown to the user.
        if (operationId.isBlank()) {
            val pending = repo.latestServerInstallation(id)
                ?: repo.installationOperations.value.lastOrNull { it.priorLocalNodeId == id }
            pending?.let {
                operationId = it.operationId
                // Identity binding can replace the temporary SSH node ID. On
                // Activity recreation continue with the confirmed node.
                repo.getServerNode(it.serverId)?.let { confirmed -> node = confirmed }
            }
        }
    }

    fun start() {
        if (restoredOperationNeedsCheck) {
            restoredOperationNeedsCheck = false
            if (operationId.isNotBlank() && repo.loadServerInstallation(operationId) != null) reconcile()
            else {
                mutableState.value = SetupState(stage = "check")
                check()
            }
            return
        }
        if (!mutableState.value.busy && mutableState.value.stage == "check" && needsProbe) check()
    }

    fun check() {
        if (mutableState.value.busy) return
        val target = node ?: return
        if (updateChannel == AdminChannel.DOCUMENT || updateChannel == AdminChannel.ACTIVE_TRANSPORT ||
            (updateChannel == AdminChannel.AUTO && repo.getActiveGrant(target.id) != null)) {
            mutableState.value = SetupState(stage = "check", busy = true, summary = "Проверяем выбранный канал управления…")
            viewModelScope.launch {
                val result = withContext(Dispatchers.IO) { repo.getClusterSnapshot(updateChannel, target) }
                node = repo.getServerNode(target.id) ?: target
                needsProbe = false
                mutableState.value = SetupState(stage = "result", error = result.exceptionOrNull()?.message)
                if (result.isSuccess && repo.latestServerInstallation(target.id)?.requiresReconciliation == true) {
                    operationId = repo.latestServerInstallation(target.id)?.operationId.orEmpty()
                    reconcile()
                }
            }
            return
        }
        if (needsProbe) {
            mutableState.value = SetupState(stage = "check", busy = true, summary = "Проверяем сервер…")
            InstallationLog.append(getApplication(), "probe_start", "host=${target.host} port=${target.port}")
            viewModelScope.launch {
                val inventory = withContext(Dispatchers.IO) { repo.probeServer(target) }
                node = repo.getServerNode(target.id) ?: target
                needsProbe = false
                InstallationLog.append(getApplication(), "probe_result", "host=${target.host} error=${inventory.probeError.orEmpty()} namespaces=${inventory.managedNamespaces.size}")
                if (inventory.probeError != null) {
                    mutableState.value = SetupState(stage = "check", error = inventory.probeError)
                    return@launch
                }
                val namespaces = inventory.managedNamespaces
                if (node?.managementNamespace.isNullOrBlank() && namespaces.size == 1) {
                    node = node?.copy(managementNamespace = namespaces.single())
                    node?.let(repo::updateServerNode)
                }
                val pending = operationId.takeIf { it.isNotBlank() }?.let(repo::loadServerInstallation)
                if (pending?.requiresReconciliation == true) {
                    mutableState.value = SetupState(stage = "result")
                    reconcile()
                } else if (!takeoverRecovery && isAlreadyManaged(target.id, inventory)) {
                    // Re-entering the server screen must be read-only once the
                    // node and its OWNER/control profile are already ready.
                    // Starting a new plan here would needlessly rotate the
                    // installation operation and could replace a healthy
                    // binary. Protocol setup remains available from the
                    // result screen.
                    mutableState.value = SetupState(stage = "result")
                } else {
                    mutableState.value = SetupState(stage = "check")
                }
            }
            return
        }
        val current = repo.loadServerInstallation(operationId)
        if (current != null && current.requiresReconciliation) { reconcile(); return }
        // Local grants/control profiles may be absent in a fresh app install.
        // That is an access problem, never permission to replace an existing Core.
        if (!takeoverRecovery && target.inventory?.let(::hasInstalledCore) == true) {
            mutableState.value = SetupState(stage = if (isAlreadyManaged(target.id, target.inventory!!)) "result" else "check")
            return
        }
        operationId = UUID.randomUUID().toString()
        mutableState.value = SetupState(stage = if (takeoverRecovery) "recovery" else "install", busy = true,
            summary = if (takeoverRecovery) "Готовим восстановление прав владельца…" else "Готовим установку…")
        InstallationLog.append(getApplication(), "install_prepare", "host=${target.host} operation_id=$operationId")
        viewModelScope.launch {
            try {
                if (target.inventory?.managedNamespaces?.isNotEmpty() == true && !takeoverRecovery &&
                    repo.getServerAccess(target.id).role != AdminRole.OWNER) {
                    mutableState.value = SetupState(stage = "check",
                        error = "На сервере уже есть LibreRoute. Выберите восстановление владельца явно.")
                    return@launch
                }
                val plan = withContext(Dispatchers.IO) {
                    if (takeoverRecovery) repo.prepareOwnerRecovery(target, operationId).getOrThrow()
                    else repo.planServerInstallation(target, operationId)
                }
                planJson = plan.toString()
                repo.setServerInstallationProfileName(operationId, adminProfileName)
                if (!bindReviewedIdentity()) {
                    mutableState.value = SetupState(stage = "result", error = "Не удалось подтвердить сервер")
                    return@launch
                }
                install()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                InstallationLog.append(getApplication(), "install_prepare_error", "host=${target.host} operation_id=$operationId message=${e.message.orEmpty()}")
                mutableState.value = SetupState(stage = "result", error = e.message ?: "Не удалось подготовить установку")
            }
        }
    }

    fun requestOwnerRecovery() {
        if (mutableState.value.busy) return
        takeoverRecovery = true
        check()
    }

    fun updateCore() {
        if (mutableState.value.busy) return
        val target = node ?: return
        operationId = UUID.randomUUID().toString()
        mutableState.value = SetupState(stage = "install", busy = true, summary = "Обновляем ядро до ${CoreCompatibility.REQUIRED_VERSION}…",
            preparationCancellable = true)
        InstallationLog.append(getApplication(), "core_update_start", "host=${target.host} operation_id=$operationId")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val plan = repo.planCoreUpdate(target, operationId, updateChannel)
                    val originalDocument = plan["document_url"]?.jsonPrimitive?.content.orEmpty()
                    var outcome = repo.installServer(target, operationId, originalDocument)
                    if (outcome is AdminOpResult.Failure && repo.loadServerInstallation(operationId)?.requiresReconciliation == true) {
                        outcome = repo.checkServerInstallation(target, operationId)
                    }
                    if (outcome is AdminOpResult.Success) {
                        val updated = repo.getServerNode(target.id) ?: target
                        val info = requireNotNull(if (repo.loadServerInstallation(operationId)?.coordinator == true) updated.coreObservation.info
                            else repo.refreshServerCore(updated).getOrThrow()) { "Ядро не вернуло сведения о работающей сборке" }
                        require(info.needsUpdate == false) { "Обновление записано, но работающее ядро ещё не прошло проверку совместимости. Повторите проверку." }
                        val manifest = plan["manifest"]?.jsonObject ?: error("Нет подписанной целевой сборки")
                        require(info.buildId == manifest.string("sha256") && info.version == manifest.string("version")) { "Работающее ядро не совпадает с подписанной целевой сборкой" }
                    }
                    outcome
                }
                node = repo.getServerNode(target.id) ?: target
                InstallationLog.append(getApplication(), "core_update_result", "host=${target.host} operation_id=$operationId result=${result::class.simpleName} version=${node?.coreObservation?.info?.version.orEmpty()} error=${(result as? AdminOpResult.Failure)?.error.orEmpty()}")
                mutableState.value = SetupState(stage = "result", error = (result as? AdminOpResult.Failure)?.error,
                    summary = if (result is AdminOpResult.Success) "Ядро обновлено до ${node?.coreObservation?.info?.version}. Совместимость проверена." else "")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                node = repo.getServerNode(target.id) ?: target
                InstallationLog.append(getApplication(), "core_update_error", "host=${target.host} operation_id=$operationId message=${e.message.orEmpty()}")
                mutableState.value = SetupState(stage = "result", error = e.message ?: "Обновление ядра не подтверждено; проверьте результат")
            }
        }
    }

    private fun isAlreadyManaged(serverId: String, inventory: ServerInventory): Boolean {
        if (!hasInstalledCore(inventory)) return false
        val access = repo.getServerAccess(serverId)
        if (access.state != AdminAccessState.ACTIVE || access.role != AdminRole.OWNER) return false
        return repo.managedProfilesFlow.value.any {
            it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL &&
                it.serverId == serverId &&
                it.role == AdminRole.OWNER &&
                it.status == ManagedProfile.STATUS_READY
        }
    }

    private fun hasInstalledCore(inventory: ServerInventory): Boolean =
        inventory.managedNamespaces.isNotEmpty() || !inventory.installedLibreRouteVersion.isNullOrBlank()

    fun setNamespace(value: String) {
        if (mutableState.value.busy) return
        val target = node ?: return
        if (value.isNotBlank()) { node = target.copy(managementNamespace = value); repo.updateServerNode(node!!) }
    }

    private fun bindReviewedIdentity(): Boolean {
        val target = node ?: return false
        val plan = runCatching { Json.parseToJsonElement(planJson).jsonObject }.getOrNull() ?: return false
        val id = plan["server_id"]?.jsonPrimitive?.content.orEmpty()
        if (id.isBlank()) return true
        val bound = repo.bindExistingServerIdentity(target.id, id, plan["namespace"]?.jsonPrimitive?.content.orEmpty(), plan["server_public_key"]?.jsonPrimitive?.content.orEmpty())
        bound.onSuccess { node = it }
        return bound.isSuccess
    }

    fun install() {
        val target = node ?: return
        val current = mutableState.value
        mutableState.value = current.copy(stage = if (takeoverRecovery) "recovery" else "install", busy = true,
            error = null, summary = if (takeoverRecovery) "Восстанавливаем доступ к установленному ядру…" else "Устанавливаем LibreRoute…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (takeoverRecovery) repo.recoverOwnerServer(target, operationId, document)
                else repo.installServer(target, operationId, document)
            }
            // If the SSH command completed ambiguously, reconcile once in the
            // background. The user should see only the final outcome.
            val finalResult = if (result is AdminOpResult.Failure &&
                repo.latestServerInstallation(target.id)?.requiresReconciliation == true) {
                withContext(Dispatchers.IO) { repo.checkServerInstallation(target, operationId) }
            } else result
            InstallationLog.append(getApplication(), "install_result", "host=${target.host} operation_id=$operationId result=${finalResult::class.simpleName} error=${(finalResult as? AdminOpResult.Failure)?.error.orEmpty()}")
            mutableState.value = SetupState(stage = "result", error = (finalResult as? AdminOpResult.Failure)?.error)
        }
    }

    fun reconcile() {
        val target = node ?: return
        if (operationId.isBlank() || mutableState.value.busy) return
        mutableState.value = SetupState(stage = "result", busy = true, summary = "Проверяем результат…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (repo.loadServerInstallation(operationId)?.coordinator == true) repo.checkCoreUpdateOverChannel(target, operationId, updateChannel)
                else repo.checkServerInstallation(target, operationId)
            }
            InstallationLog.append(getApplication(), "status_result", "host=${target.host} operation_id=$operationId error=${(result as? AdminOpResult.Failure)?.error.orEmpty()}")
            mutableState.value = SetupState(stage = "result", error = (result as? AdminOpResult.Failure)?.error)
        }
    }

    /** Starts a fresh read-only probe after a stale/foreign operation response. */
    fun restartProbe() {
        if (mutableState.value.busy) return
        operationId = ""
        planJson = ""
        takeoverRecovery = false
        needsProbe = true
        node = node?.copy(inventory = null)
        mutableState.value = SetupState(stage = "check")
        check()
    }
}

/** User flow: automatic server check → one loading screen → success or error. */
class ServerSetupActivity : AppCompatActivity() {
    private val vm: ServerSetupViewModel by viewModels()
    private val dialogs by lazy { io.github.libreroute.ui.DialogScreens(this) { key, _ ->
        when (key) { "owner_recovery" -> confirmOwnerRecovery(); "core_update" -> confirmCoreUpdate() }
    } }
    private val scrollPosition by lazy { io.github.libreroute.ui.ScreenScrollPosition(this, content.parent as ScrollView) }
    private lateinit var content: LinearLayout
    private var protocolsOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        protocolsOpened = savedInstanceState?.getBoolean("protocols_opened") ?: false
        vm.restore(savedInstanceState)
        vm.initialize(intent.getStringExtra("server_id").orEmpty(), intent.getBooleanExtra("owner_recovery", false), intent.getBooleanExtra("auto_provision", false), intent.getStringExtra("admin_profile_name").orEmpty())
        if (vm.node == null) { finish(); return }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageButton(this).apply { contentDescription = "Назад"; setImageResource(io.github.libreroute.R.drawable.ic_arrow_back); setOnClickListener { closeWhenIdle() } }, LinearLayout.LayoutParams(CalmViews.dp(this, 56), CalmViews.dp(this, 56)))
        header.addView(TextView(this).apply { text = "Настройка сервера"; textSize = 24f })
        root.addView(header)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) { override fun handleOnBackPressed() = closeWhenIdle() })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(CalmViews.dp(context, 16), 0, CalmViews.dp(context, 16), CalmViews.dp(context, 24)) }
        root.addView(ScrollView(this).apply { id = io.github.libreroute.ui.DialogScreens.stateId("server-setup-scroll") }.apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { vm.state.collect { render(it) } } }
        content.post { vm.start() }
        dialogs.restore()
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("protocols_opened", protocolsOpened); vm.save(outState); super.onSaveInstanceState(outState) }

    private fun render(state: SetupState) {
        scrollPosition.beforeRebuild()
        content.removeAllViews()
        val nodeName = vm.node?.name.orEmpty()
        val title = when (state.stage) { "install", "plan" -> "Установка"; "recovery", "recovery_plan" -> "Восстановление доступа"; "result" -> "Результат"; else -> "Проверка сервера" }
        content.addView(CalmViews.row(this, title, nodeName))
        if (state.busy) {
            content.addView(ProgressBar(this))
            content.addView(CalmViews.row(this, "Подождите", state.summary.ifBlank { "Проверяем сервер…" }))
            if (state.preparationCancellable) {
                action("Отменить подготовку") { vm.cancelPreparation() }
            }
            return
        }
        state.error?.let { content.addView(CalmViews.row(this, "Не удалось завершить", friendlyError(it))) }
        val repository = AdminRepository.getInstance(this)
        val currentNode = vm.node?.id?.let(repository::getServerNode) ?: vm.node
        currentNode?.let { content.addView(CalmViews.row(this, "Ядро сервера", coreVersionSummary(it.coreObservation))) }
        when (state.stage) {
            "check" -> renderCheck()
            "plan", "recovery_plan" -> action("Продолжить") { vm.install() }
            "install", "recovery" -> {
                content.addView(ProgressBar(this))
                content.addView(CalmViews.row(this, "Установка", "Подождите завершения проверки сервера"))
            }
            "result" -> renderResult(state)
        }
    }

    private fun renderCheck() {
        if (vm.node?.id?.let { AdminRepository.getInstance(this).getActiveGrant(it) } != null) renderChannels()
        if (vm.updateChannel == AdminChannel.DOCUMENT || vm.updateChannel == AdminChannel.ACTIVE_TRANSPORT ||
            (vm.updateChannel == AdminChannel.AUTO && vm.node?.id?.let { AdminRepository.getInstance(this).getActiveGrant(it) } != null)) {
            content.addView(CalmViews.row(this, "Управление сервером", "Проверим доступ через выбранный канал управления."))
            action("Проверить соединение") { vm.check() }
            return
        }
        val inventory = vm.node?.inventory
        if (inventory == null) {
            content.addView(CalmViews.row(this, "Проверяем сервер", "Приложение подключается по SSH и определяет состояние LibreRoute."))
            action("Повторить проверку") { vm.restartProbe() }
            return
        }
        if (inventory.probeError != null) {
            action("Повторить подключение") { vm.restartProbe() }
            return
        }
        val namespaces = inventory.managedNamespaces
        val os = listOfNotNull(inventory.osName.takeIf { it.isNotBlank() }, inventory.osVersion.takeIf { it.isNotBlank() }).joinToString(" ")
        content.addView(CalmViews.row(this, "Сервер доступен", os.ifBlank { vm.node?.host.orEmpty() }))
        val installText = if (namespaces.isNotEmpty()) "Установлено: ${namespaces.joinToString(", ")}"
        else if (!inventory.installedLibreRouteVersion.isNullOrBlank()) "Найдено: ${inventory.installedLibreRouteVersion}"
        else "Не установлено"
        content.addView(CalmViews.row(this, "LibreRoute", installText))
        if (inventory.installedProtocols.isNotEmpty()) content.addView(CalmViews.row(this, "Протоколы", inventory.installedProtocols.joinToString(" · ")))
        if (namespaces.size > 1) {
            content.addView(CalmViews.row(this, "Выберите установку", "На сервере найдено несколько экземпляров LibreRoute."))
            val spinner = Spinner(this).apply { adapter = ArrayAdapter(this@ServerSetupActivity, android.R.layout.simple_spinner_dropdown_item, namespaces) }
            content.addView(spinner)
            spinner.setSelection(namespaces.indexOf(vm.node?.managementNamespace).coerceAtLeast(0))
            spinner.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { vm.setNamespace(namespaces[position]) }
            })
        }
        when {
            namespaces.isNotEmpty() -> {
                content.addView(CalmViews.row(this, "Ядро уже установлено",
                    "Повторная установка не требуется. Чтобы начать управление из этого приложения, восстановите права владельца или примите приглашение администратора."))
                action("Восстановить права владельца") {
                    confirmOwnerRecovery()
                }
            }
            !inventory.installedLibreRouteVersion.isNullOrBlank() -> {
                content.addView(CalmViews.row(this, "Ядро уже установлено",
                    "Не удалось определить управляемую установку. Повторная установка заблокирована; требуется восстановление управления сервером."))
                action("Повторить проверку") { vm.restartProbe() }
            }
            else -> action(if (namespaces.isEmpty()) "Установить LibreRoute" else "Продолжить настройку") { vm.check() }
        }
    }

    private fun renderChannels() {
        val channels = listOf(AdminChannel.AUTO, AdminChannel.ACTIVE_TRANSPORT, AdminChannel.DOCUMENT, AdminChannel.DIRECT_SSH)
        content.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@ServerSetupActivity, android.R.layout.simple_spinner_dropdown_item, channels.map { it.displayName })
            setSelection(channels.indexOf(vm.updateChannel).coerceAtLeast(0))
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { vm.selectUpdateChannel(channels[position]) }
            }
        })
    }

    private fun renderResult(state: SetupState) {
        val repository = AdminRepository.getInstance(this)
        val opState = vm.node?.id?.let(repository::latestServerInstallation)
        val access = vm.node?.id?.let(repository::getServerAccess)
        renderChannels()
        if (opState?.requiresReconciliation == true) {
            content.addView(CalmViews.row(this, "Проверка обновления", coreUpdateProgressLabel(opState)))
            action("Проверить состояние") { vm.reconcile() }
            val plannedVersion = runCatching { Json.parseToJsonElement(opState.expectedPlan).jsonObject["manifest"]?.jsonObject?.string("version") }.getOrNull().orEmpty()
            val targetAvailable = plannedVersion.isBlank() || plannedVersion == CoreCompatibility.REQUIRED_VERSION
            if (!targetAvailable) content.addView(CalmViews.row(this, "Нужна новая попытка",
                "Для исправления доступна сборка ${CoreCompatibility.REQUIRED_VERSION}. Отмените прежнюю попытку, проверьте версию ядра и начните обновление через «Прямой SSH к серверу»."))
            if (targetAvailable && ((opState.coordinator && opState.serverStage in setOf("planned", "receiving", "verified", "prepared")) || opState.serverStage == "receiving")) {
                action("Продолжить обновление") { vm.continueUpdate() }
            }
            if (opState.serverStage in setOf("planned", "receiving", "verified", "prepared", "backing_up", "switching", "checking", "awaiting_confirmation", "recovery_required")) {
                action(if (opState.serverStage in setOf("planned", "receiving", "verified", "prepared")) "Отменить обновление" else "Вернуть предыдущую версию") { vm.rollbackUpdate() }
            }
            action("Проверить соединение") { vm.restartProbe() }
            return
        }
        val block = vm.node?.id?.let { repository.serverMutationBlockReason(it, vm.updateChannel) }
        if (block != null) content.addView(CalmViews.row(this, "Изменения недоступны", block))
        val ready = state.error == null && access?.state == AdminAccessState.ACTIVE &&
            access.role == AdminRole.OWNER && (opState?.stage == ServerInstallOperation.READY ||
            ((vm.updateChannel in setOf(AdminChannel.AUTO, AdminChannel.DOCUMENT, AdminChannel.ACTIVE_TRANSPORT)) && vm.node?.coreObservation?.info != null &&
                repository.managedProfilesFlow.value.any { it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL && it.serverId == vm.node?.id }) ||
            (vm.node?.inventory?.let { inventory ->
                val installed = inventory.managedNamespaces.isNotEmpty() ||
                    !inventory.installedLibreRouteVersion.isNullOrBlank()
                installed && repository.managedProfilesFlow.value.any {
                    it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL &&
                        it.serverId == vm.node?.id &&
                        it.role == AdminRole.OWNER &&
                        it.status == ManagedProfile.STATUS_READY
                }
            } == true))
        val rolledBack = opState?.stage == ServerInstallOperation.ROLLED_BACK
        if (state.error != null) {
            content.addView(CalmViews.row(this, "Проверка канала", friendlyError(state.error)))
            action("Повторить проверку") { vm.restartProbe() }
            return
        }
        if (ready) {
            content.addView(CalmViews.row(this, "Готово", "Сервер подключён, управляющая часть и профиль администратора созданы."))
            val currentNode = vm.node?.id?.let(repository::getServerNode) ?: vm.node
            val observation = currentNode?.coreObservation
            if (observation != null && observation.checkedAt != 0L && observation.error == null && observation.info?.needsUpdate != false) {
                content.addView(CalmViews.row(this, "Нужно обновить ядро", "На сервере старое или несовместимое ядро. Доступно ${CoreCompatibility.REQUIRED_VERSION}. Обновление сохранит SSH, права владельца и настройки."))
                action("Обновить ядро до ${CoreCompatibility.REQUIRED_VERSION}", enabled = block == null) {
                    confirmCoreUpdate()
                }
            }
            // Local drafts and browser authorization can be prepared before
            // a Core update. ProtocolSetup keeps server mutations gated.
            action("Настроить протоколы") { openProtocols() }
            if (state.summary.isNotBlank()) content.addView(CalmViews.row(this, "Обновление ядра", state.summary))
            action("Проверить версию ядра") { vm.restartProbe() }
            if (intent.getBooleanExtra("auto_provision", false) && !protocolsOpened && observation?.info?.needsUpdate == false && observation.error == null) {
                protocolsOpened = true
                content.postDelayed({ openProtocols() }, 250L)
            }
        } else if (rolledBack) {
            content.addView(CalmViews.row(this, "Изменения отменены", "Сервер и SSH-доступ сохранены."))
        } else {
            action("Проверить сервер") { vm.restartProbe() }
        }
        action("Вернуться к серверам") { finish() }
    }

    private fun friendlyError(raw: String): String = when {
        raw.startsWith("SSH:") -> raw.take(300)
        raw.contains("baseline", ignoreCase = true) -> "После создания плана изменилось состояние сервера. Отмените эту попытку обновления и подготовьте новую."
        raw.contains("update rejected", ignoreCase = true) -> "Сервер отклонил старый план обновления. Отмените эту попытку; для нового обновления выберите «Прямой SSH к серверу»."
        raw.contains("квитанц") || raw.contains("другой операции") -> "На сервере уже есть другая операция. Повторим безопасную проверку состояния."
        raw.contains("SSH", ignoreCase = true) -> "Не удалось подключиться к серверу по SSH. Проверьте доступ и повторите."
        else -> raw.take(300)
    }

    private fun openProtocols() {
        startActivity(android.content.Intent(this, ProtocolSetupActivity::class.java).putExtra(ProtocolSetupActivity.EXTRA_SERVER_ID, vm.node?.id.orEmpty()))
    }

    private fun confirmOwnerRecovery() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Восстановить доступ к серверу?")
            .setMessage("Будут восстановлены права владельца для этого приложения через SSH. Установленное ядро и настройки сохранятся. Для восстановления передаётся служебная сборка; обновление ядра выполняется отдельно.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Восстановить") { _, _ -> vm.requestOwnerRecovery() }.create()
        dialogs.show(dialog, "owner_recovery")
    }

    private fun confirmCoreUpdate() {
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Обновить ядро сервера?")
            .setMessage("Будет установлена подписанная сборка ${CoreCompatibility.REQUIRED_VERSION}. Служба кратковременно перезапустится. Доступ и настройки сохранятся; при сбое предусмотрен откат.")
            .setNegativeButton("Позже", null)
            .setPositiveButton("Обновить") { _, _ -> vm.updateCore() }.create()
        dialogs.show(dialog, "core_update")
    }

    private fun action(text: String, enabled: Boolean = true, click: () -> Unit) {
        content.addView(MaterialButton(this).apply { this.text = text; isAllCaps = false; isEnabled = enabled; setOnClickListener { click() } }, LinearLayout.LayoutParams(-1, CalmViews.dp(this, 56)))
    }

    private fun closeWhenIdle() {
        if (vm.state.value.busy) Toast.makeText(this, "Проверка продолжается; результат будет сохранён", Toast.LENGTH_SHORT).show()
        finish()
    }
}

