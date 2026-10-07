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
                      val summary: String = "", val error: String? = null)

/** Keeps the installation protocol internal; the user sees check → loading → result. */
class ServerSetupViewModel(app: android.app.Application) : AndroidViewModel(app) {
    private val repo = AdminRepository.getInstance(app)
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

    fun restore(saved: Bundle?) {
        if (saved == null) return
        operationId = saved.getString("setup_operation_id").orEmpty()
        document = saved.getString("setup_document").orEmpty()
        adminProfileName = saved.getString("setup_profile_name").orEmpty()
        autoProvision = saved.getBoolean("setup_auto_provision", autoProvision)
        val wasBusy = saved.getBoolean("setup_busy", false)
        mutableState.value = SetupState(
            stage = if (wasBusy) "result" else saved.getString("setup_stage") ?: "check",
            summary = saved.getString("setup_summary").orEmpty(),
            error = if (wasBusy) "Установка прервалась. Проверим фактическое состояние сервера." else saved.getString("setup_error"))
    }

    fun save(out: Bundle) {
        val s = mutableState.value
        out.putString("setup_operation_id", operationId)
        out.putString("setup_document", document)
        out.putString("setup_profile_name", adminProfileName)
        out.putString("setup_stage", s.stage)
        out.putBoolean("setup_busy", s.busy)
        out.putBoolean("setup_auto_provision", autoProvision)
        out.putString("setup_summary", s.summary)
        out.putString("setup_error", s.error)
    }

    fun initialize(id: String, recovery: Boolean = false, automatic: Boolean = false, profileName: String = "") {
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
        if (!mutableState.value.busy && mutableState.value.stage == "check" && needsProbe) check()
    }

    fun check() {
        if (mutableState.value.busy) return
        val target = node ?: return
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
        operationId = UUID.randomUUID().toString()
        mutableState.value = SetupState(stage = "install", busy = true, summary = "Готовим установку…")
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
        mutableState.value = SetupState(stage = "install", busy = true, summary = "Обновляем ядро до ${CoreCompatibility.REQUIRED_VERSION}…")
        InstallationLog.append(getApplication(), "core_update_start", "host=${target.host} operation_id=$operationId")
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val plan = repo.planCoreUpdate(target, operationId)
                    val originalDocument = plan["document_url"]?.jsonPrimitive?.content.orEmpty()
                    var outcome = repo.installServer(target, operationId, originalDocument)
                    if (outcome is AdminOpResult.Failure && repo.loadServerInstallation(operationId)?.requiresReconciliation == true) {
                        outcome = repo.checkServerInstallation(target, operationId)
                    }
                    if (outcome is AdminOpResult.Success) {
                        val updated = repo.getServerNode(target.id) ?: target
                        val info = repo.refreshServerCore(updated).getOrThrow()
                        require(info?.needsUpdate == false) { "Обновление записано, но работающее ядро ещё не прошло проверку совместимости. Повторите проверку." }
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
        val installed = inventory.managedNamespaces.isNotEmpty() ||
            !inventory.installedLibreRouteVersion.isNullOrBlank()
        if (!installed) return false
        val access = repo.getServerAccess(serverId)
        if (access.state != AdminAccessState.ACTIVE || access.role != AdminRole.OWNER) return false
        return repo.managedProfilesFlow.value.any {
            it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL &&
                it.serverId == serverId &&
                it.role == AdminRole.OWNER &&
                it.status == ManagedProfile.STATUS_READY
        }
    }

    fun setNamespace(value: String) {
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
        if (current.busy && current.stage != "install") mutableState.value = current.copy(stage = "install")
        else mutableState.value = current.copy(stage = "install", busy = true, error = null, summary = "Устанавливаем LibreRoute…")
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
            val result = withContext(Dispatchers.IO) { repo.checkServerInstallation(target, operationId) }
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
    private lateinit var content: LinearLayout
    private var protocolsOpened = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { vm.state.collect { render(it) } } }
        content.post { vm.start() }
    }

    override fun onSaveInstanceState(outState: Bundle) { vm.save(outState); super.onSaveInstanceState(outState) }

    private fun render(state: SetupState) {
        content.removeAllViews()
        val nodeName = vm.node?.name.orEmpty()
        val title = when (state.stage) { "install", "plan", "recovery_plan" -> "Установка"; "result" -> "Результат"; else -> "Проверка сервера" }
        content.addView(CalmViews.row(this, title, nodeName))
        if (state.busy) {
            content.addView(ProgressBar(this))
            content.addView(CalmViews.row(this, "Подождите", state.summary.ifBlank { "Проверяем сервер…" }))
            return
        }
        state.error?.let { content.addView(CalmViews.row(this, "Не удалось завершить", friendlyError(it))) }
        val repository = AdminRepository.getInstance(this)
        val currentNode = vm.node?.id?.let(repository::getServerNode) ?: vm.node
        currentNode?.let { content.addView(CalmViews.row(this, "Ядро сервера", coreVersionSummary(it.coreObservation))) }
        when (state.stage) {
            "check" -> renderCheck()
            "plan", "recovery_plan" -> action("Продолжить") { vm.install() }
            "install" -> {
                content.addView(ProgressBar(this))
                content.addView(CalmViews.row(this, "Установка", "Подождите завершения проверки сервера"))
            }
            "result" -> renderResult(state)
        }
    }

    private fun renderCheck() {
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
        val access = vm.node?.id?.let { AdminRepository.getInstance(this).getServerAccess(it) }
        when {
            namespaces.isNotEmpty() && (access?.state != AdminAccessState.ACTIVE || access.role != AdminRole.OWNER) ->
                action("Восстановить права владельца") { vm.requestOwnerRecovery() }
            else -> action(if (namespaces.isEmpty()) "Установить LibreRoute" else "Продолжить настройку") { vm.check() }
        }
    }

    private fun renderResult(state: SetupState) {
        val repository = AdminRepository.getInstance(this)
        val opState = vm.node?.id?.let(repository::latestServerInstallation)
        val access = vm.node?.id?.let(repository::getServerAccess)
        val ready = state.error == null && access?.state == AdminAccessState.ACTIVE &&
            access.role == AdminRole.OWNER && (opState?.stage == ServerInstallOperation.READY ||
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
        if (ready) {
            content.addView(CalmViews.row(this, "Готово", "Сервер подключён, управляющая часть и профиль администратора созданы."))
            val currentNode = vm.node?.id?.let(repository::getServerNode) ?: vm.node
            val observation = currentNode?.coreObservation
            if (observation != null && observation.checkedAt != 0L && observation.error == null && observation.info?.needsUpdate != false) {
                content.addView(CalmViews.row(this, "Нужно обновить ядро", "На сервере старое или несовместимое ядро. Доступно ${CoreCompatibility.REQUIRED_VERSION}. Обновление сохранит SSH, права владельца и настройки."))
                action("Обновить ядро до ${CoreCompatibility.REQUIRED_VERSION}") {
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Обновить ядро сервера?")
                        .setMessage("Будет установлена подписанная сборка ${CoreCompatibility.REQUIRED_VERSION}. Служба кратковременно перезапустится. Доступ и настройки сохранятся; при сбое предусмотрен откат.")
                        .setNegativeButton("Позже", null)
                        .setPositiveButton("Обновить") { _, _ -> vm.updateCore() }.show()
                }
            } else {
                action("Настроить протоколы") { openProtocols() }
            }
            if (state.summary.isNotBlank()) content.addView(CalmViews.row(this, "Обновление ядра", state.summary))
            action("Проверить версию ядра") { vm.restartProbe() }
            if (intent.getBooleanExtra("auto_provision", false) && !protocolsOpened && observation?.info?.needsUpdate == false && observation.error == null) {
                protocolsOpened = true
                content.postDelayed({ openProtocols() }, 250L)
            }
        } else if (rolledBack) {
            content.addView(CalmViews.row(this, "Изменения отменены", "Сервер и SSH-доступ сохранены."))
        } else if (state.error != null) {
            action("Проверить сервер заново") { vm.restartProbe() }
        } else {
            action("Проверить сервер") { vm.restartProbe() }
        }
        action("Вернуться к серверам") { finish() }
    }

    private fun friendlyError(raw: String): String = when {
        raw.contains("квитанц") || raw.contains("другой операции") -> "На сервере уже есть другая операция. Повторим безопасную проверку состояния."
        raw.contains("SSH", ignoreCase = true) -> "Не удалось подключиться к серверу по SSH. Проверьте доступ и повторите."
        else -> raw.take(300)
    }

    private fun openProtocols() {
        startActivity(android.content.Intent(this, ProtocolSetupActivity::class.java).putExtra(ProtocolSetupActivity.EXTRA_SERVER_ID, vm.node?.id.orEmpty()))
    }

    private fun action(text: String, click: () -> Unit) {
        content.addView(MaterialButton(this).apply { this.text = text; isAllCaps = false; setOnClickListener { click() } }, LinearLayout.LayoutParams(-1, CalmViews.dp(this, 56)))
    }

    private fun closeWhenIdle() {
        if (vm.state.value.busy) Toast.makeText(this, "Проверка продолжается; результат будет сохранён", Toast.LENGTH_SHORT).show()
        finish()
    }
}



