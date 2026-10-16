package io.github.libreroute.admin

import android.app.Dialog
import android.text.InputType
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.text.Editable
import android.text.TextWatcher
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.RadioButton
import android.widget.RadioGroup
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.libreroute.R
import io.github.libreroute.util.ThemePreferences
import io.github.libreroute.util.performAppHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AdminActivity : AppCompatActivity() {

    private val dialogs by lazy { io.github.libreroute.ui.DialogScreens(this) { key, args ->
        when (key) {
            "message" -> showMessage(args.getString("title").orEmpty(), args.getString("body").orEmpty())
            "admins_choose_server" -> showAdministratorsDialog()
            "admins" -> showAdministratorList(args.getString("node").orEmpty(), kotlinx.serialization.json.Json.decodeFromString<List<AdministratorRecord>>(args.getString("admins").orEmpty()))
            "revoke_admin" -> showRevokeAdministrator(args.getString("node").orEmpty(), args.getString("device").orEmpty(), args.getString("role").orEmpty())
            "showAdminDelegationActions" -> showAdminDelegationActions()
            "showCompleteOwnerTransferDialog" -> showCompleteOwnerTransferDialog()
            "showAdminInvitationTemplateDialog" -> showAdminInvitationTemplateDialog()
            "showApproveAdminRequestDialog" -> showApproveAdminRequestDialog()
            "showImportGrantDialog" -> showImportGrantDialog()
            "showCreateUserDialog" -> showCreateUserDialog()
            "showSelectUserForAssignment" -> showSelectUserForAssignment()
            "showServerAuthDialog" -> showServerAuthDialog()
            "showInstallServerDialog" -> showInstallServerDialog()
            "showProbeServerDialog" -> showProbeServerDialog()
            "showSelectAssignmentForInvitation" -> showSelectAssignmentForInvitation()
            "showConfirmRevokeRightsDialog" -> showConfirmRevokeRightsDialog()
            "showManageSshKeyDialog" -> showManageSshKeyDialog()
            "showImportSshKeyDialog" -> showImportSshKeyDialog()
            "showPromptRecipientKeyDialog" -> vm.managedProfiles.value.firstOrNull { it.id == args.getString("id") }?.let { showPromptRecipientKeyDialog(it) }
            "showConfirmRevokeProfileDialog" -> vm.managedProfiles.value.firstOrNull { it.id == args.getString("id") }?.let { showConfirmRevokeProfileDialog(it) }
            "showAssignmentActionDialog" -> vm.assignments.value.firstOrNull { it.id == args.getString("id") }?.let { showAssignmentActionDialog(it) }
            "person" -> vm.users.value.firstOrNull { it.id == args.getString("id") }?.let { showPersonDetails(it) }
            "access" -> vm.users.value.firstOrNull { it.id == args.getString("id") }?.let { showAssignDocumentDialog(it) }
            "management_channel" -> showManagementChannelDialog()
            "server" -> vm.serverNodes.value.firstOrNull { it.id == args.getString("id") }?.let { showServerNodeDetailsDialog(it) }
            "suspend_user" -> confirmSuspendUser(args.getString("id").orEmpty())
            "delete_user_server" -> confirmDeleteUserFromServer(args.getString("id").orEmpty(),
                args.getString("server"), args.getString("authority"))
            "revoke_assignment" -> confirmRevokeAssignment(args.getString("id").orEmpty())
            "delete_server" -> confirmDeleteServer(args.getString("id").orEmpty())
        }
    } }
    private val dialogOperation by viewModels<io.github.libreroute.ui.ScreenOperationViewModel>()
    private data class AdminDialogOutcome(val message: String = "", val link: String? = null, val title: String = "")
    private data class AdministratorsLoaded(val nodeId: String, val admins: List<AdministratorRecord>)
    private val vm: AdminViewModel by viewModels()
    private lateinit var themePrefs: ThemePreferences

    // Views
    private lateinit var toggleChannel: MaterialButtonToggleGroup
    private lateinit var tvRoleTitle: TextView
    private lateinit var tvRoleDesc: TextView
    private lateinit var iconRoleStatus: ImageView
    private lateinit var tvDeviceFingerprint: TextView
    private lateinit var layoutPendingInfo: LinearLayout
    private lateinit var tvPairingCode: TextView
    private lateinit var btnActionPrimary: MaterialButton
    private lateinit var btnActionSecondary: MaterialButton
    private lateinit var btnAddProfile: MaterialButton
    private lateinit var tvEmptyProfiles: TextView
    private lateinit var rvManagedProfiles: RecyclerView
    private lateinit var tvEmptyHistory: TextView
    private lateinit var rvCommandHistory: RecyclerView
    private lateinit var btnRevokeAdminRights: MaterialButton
    private lateinit var tvSshKeyStatus: TextView
    private lateinit var btnManageSshKey: MaterialButton
    private lateinit var tvClusterServersSummary: TextView
    private lateinit var btnAddServerNode: MaterialButton
    private lateinit var tvLocalVpnProfiles: TextView
    private lateinit var tvServerProfilesStatus: TextView
    private lateinit var tvServerProfiles: TextView
    private lateinit var btnAddUser: MaterialButton
    private lateinit var tvUsersStatus: TextView
    private lateinit var tvUsers: TextView
    private lateinit var rvUserAssignments: RecyclerView
    private lateinit var tvEmptyAssignments: TextView
    private lateinit var btnAssignUserDocument: MaterialButton
    private lateinit var toggleAdminTabs: MaterialButtonToggleGroup
    private lateinit var layoutTabServers: LinearLayout
    private lateinit var layoutTabUsers: LinearLayout
    private lateinit var btnProbeServerNode: MaterialButton
    private lateinit var tvServerInventoryDetails: TextView
    private lateinit var btnReconcileAssignments: MaterialButton
    private lateinit var btnIssueAssignmentQr: MaterialButton
    private lateinit var cardClusterHealth: com.google.android.material.card.MaterialCardView
    private lateinit var iconClusterHealth: ImageView
    private lateinit var tvClusterSnapshotSummary: TextView
    private lateinit var headerAuditLog: LinearLayout
    private lateinit var iconAuditChevron: ImageView
    private var isAuditLogExpanded: Boolean = true


    private lateinit var profilesAdapter: AdminProfilesAdapter
    private lateinit var commandsAdapter: AdminCommandsAdapter
    private lateinit var assignmentsAdapter: AdminAssignmentsAdapter
    private lateinit var serverNodesAdapter: AdminServerNodesAdapter
    private lateinit var rvServerNodes: RecyclerView
    private lateinit var tvEmptyServers: TextView
    private lateinit var btnQuickInvite: MaterialButton
    private lateinit var peopleSearch: EditText
    private var peopleFilter: String = "all"
    private var serverFilter: String = "all"
    private val tabScrollPositions = mutableMapOf<Int, Int>()
    private var restoredOperationShown = false
    private var adminTab = R.id.nav_overview

    private fun setupCalmAdminNavigation(saved: Bundle?) {
        val nav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.adminNavigation)
        val scroll = findViewById<androidx.core.widget.NestedScrollView>(R.id.adminScroll)
        listOf(R.id.nav_overview, R.id.nav_people, R.id.nav_servers).forEach { id ->
            tabScrollPositions[id] = saved?.getInt("tab_scroll_$id") ?: 0
        }
        var navigationReady = false
        nav.setOnItemSelectedListener {
            if (it.itemId == R.id.nav_people && !vm.canOpenPeople()) return@setOnItemSelectedListener false
            if (navigationReady && adminTab != it.itemId) tabScrollPositions[adminTab] = scroll.scrollY
            adminTab = it.itemId
            findViewById<View>(R.id.adminOverview).visibility = if (adminTab == R.id.nav_overview) View.VISIBLE else View.GONE
            layoutTabUsers.visibility = if (adminTab == R.id.nav_people) View.VISIBLE else View.GONE
            layoutTabServers.visibility = if (adminTab == R.id.nav_servers) View.VISIBLE else View.GONE
            scroll.post { scroll.scrollTo(0, tabScrollPositions[adminTab] ?: 0) }
            navigationReady = true
            true
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (adminTab != R.id.nav_overview) nav.selectedItemId = R.id.nav_overview else finish()
            }
        })
        findViewById<View>(R.id.adminOverviewAdvanced).visibility = if (saved?.getBoolean("advanced") == true) View.VISIBLE else View.GONE
        nav.selectedItemId = saved?.getInt("admin_tab", R.id.nav_overview) ?: R.id.nav_overview
        findViewById<View>(R.id.adminReturnUser).setOnClickListener { finish() }
        findViewById<View>(R.id.overviewInvite).setOnClickListener { showCreateUserDialog() }
        findViewById<View>(R.id.overviewAddServer).setOnClickListener { showAddServerNodeDialog() }
        findViewById<View>(R.id.adminAcquireSsh).setOnClickListener {
            val node = vm.currentServerNode() ?: return@setOnClickListener
            startActivity(Intent(this, ServerSetupActivity::class.java).putExtra("server_id", node.id)
                .putExtra("ssh_administration", true))
        }
        findViewById<View>(R.id.adminUsersMetric).setOnClickListener { nav.selectedItemId = R.id.nav_people }
        findViewById<View>(R.id.adminServersMetric).setOnClickListener { nav.selectedItemId = R.id.nav_servers }
        findViewById<View>(R.id.adminChannelSummary).setOnClickListener { showManagementChannelDialog() }
        listOf(R.id.adminPeopleAdvancedToggle to R.id.adminPeopleAdvanced,
            R.id.adminServersAdvancedToggle to R.id.adminServersAdvanced).forEach { (toggle, body) ->
            findViewById<MaterialSwitch>(toggle).setOnCheckedChangeListener { _, checked ->
                findViewById<View>(body).visibility = if (checked) View.VISIBLE else View.GONE
            }
        }
        findViewById<View>(R.id.adminAdvancedToggle).setOnClickListener {
            val advanced = findViewById<View>(R.id.adminOverviewAdvanced)
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("restored_operation_shown", restoredOperationShown)
        outState.putInt("admin_tab", adminTab)
        outState.putString("people_filter", peopleFilter)
        outState.putString("server_filter", serverFilter)
        tabScrollPositions[adminTab] = findViewById<androidx.core.widget.NestedScrollView>(R.id.adminScroll).scrollY
        tabScrollPositions.forEach { (id, y) -> outState.putInt("tab_scroll_$id", y) }
        outState.putBoolean("audit_expanded", isAuditLogExpanded)
        outState.putBoolean("advanced", findViewById<View>(R.id.adminOverviewAdvanced).visibility == View.VISIBLE)
    }

    private fun renderOverviewCounts() {
        val count = findViewById<TextView>(R.id.adminOverviewCounts)
        val nodes = vm.serverNodes.value
        val users = vm.users.value
        val canReadUsers = vm.canOpenPeople()
        findViewById<TextView>(R.id.adminOverviewUserCount).text = if (canReadUsers) users.size.toString() else "—"
        findViewById<TextView>(R.id.adminOverviewUserHint).text = if (canReadUsers)
            "Доступ разрешён: ${users.count { it.isActive }}" else "Доступ к пользователям не подтверждён"
        findViewById<TextView>(R.id.adminOverviewServerCount).text = nodes.size.toString()
        findViewById<TextView>(R.id.adminOverviewServerHint).text = "В сети: ${nodes.count { it.status == ServerNodeStatus.ONLINE }}"
        count.text = when {
                nodes.isEmpty() -> "Добавьте первый сервер, затем пригласите пользователя"
                nodes.any { it.status == ServerNodeStatus.OFFLINE } -> "Есть недоступные серверы. Откройте раздел «Серверы»"
                nodes.any { it.status == ServerNodeStatus.UNKNOWN } -> "Часть серверов ещё не проверена"
                else -> "Откройте карточку пользователя для выдачи ссылки или карточку сервера для настройки."
            }
    }

    private fun renderPeople() {
        val rows = findViewById<LinearLayout>(R.id.adminPeopleRows)
        rows.removeAllViews()
        val query = peopleSearch.text?.toString()?.trim()?.lowercase().orEmpty()
        val filtered = vm.users.value.filter { user ->
            val statusMatches = when (peopleFilter) {
                "active" -> user.isActive
                "suspended" -> user.isSuspended
                "revoked" -> user.isRevoked
                else -> true
            }
            statusMatches && (query.isBlank() || user.name.lowercase().contains(query) || user.id.lowercase().contains(query))
        }
        val assignedByUser = vm.assignments.value.filter { it.status != AdminAssignment.STATUS_REVOKED }.groupBy { it.userId }
        val serverNames = vm.serverNodes.value.associate { it.id to it.name }
        filtered.forEach { user ->
            val routes = assignedByUser[user.id].orEmpty()
                .distinctBy { it.serverId to it.transport }.groupBy { it.serverId }
                .entries.joinToString("\n") { (id, assigned) ->
                    val name = serverNames[id] ?: id
                    "$name · ${assigned.joinToString { protocolLabel(it.transport) }}"
                }.ifBlank { "Подключения пока не назначены" }
            rows.addView(adminPeopleCard(this, user, vm.hasPendingUserDocument(user.id), routes) { showPersonDetails(user) })
        }
        findViewById<View>(R.id.adminPeopleEmpty).visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.adminPeopleEmptyTitle).text = if (vm.users.value.isEmpty()) "Пока нет пользователей" else "Ничего не найдено"
        findViewById<TextView>(R.id.adminPeopleEmptyHint).text = if (vm.users.value.isEmpty()) "Добавьте пользователя или обновите сведения."
            else "Измените поиск или выберите другой фильтр."
        findViewById<TextView>(R.id.tv_people_filter_status).text = if (filtered.isEmpty()) {
            if (vm.users.value.isEmpty()) "Пользователи ещё не загружены" else "По заданному фильтру ничего не найдено"
        } else "Показано: ${filtered.size} из ${vm.users.value.size}"
        renderOverviewCounts()
    }

    private fun setupPeopleSearch() {
        peopleSearch = findViewById(R.id.adminPeopleSearch)
        findViewById<MaterialButtonToggleGroup>(R.id.peopleFilters).check(when (peopleFilter) {
            "active" -> R.id.btn_people_active; "suspended" -> R.id.btn_people_suspended
            "revoked" -> R.id.btn_people_revoked; else -> R.id.btn_people_all
        })
        peopleSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderPeople()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        listOf(
            R.id.btn_people_all to "all",
            R.id.btn_people_active to "active",
            R.id.btn_people_suspended to "suspended",
            R.id.btn_people_revoked to "revoked"
        ).forEach { (id, filter) ->
            findViewById<MaterialButton>(id).setOnClickListener {
                peopleFilter = filter
                renderPeople()
            }
        }
    }

    private fun setupServerSearch() {
        val search = findViewById<EditText>(R.id.adminServersSearch)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = renderServerNodes()
            override fun afterTextChanged(s: Editable?) = Unit
        })
        findViewById<MaterialButtonToggleGroup>(R.id.serverFilters).check(when (serverFilter) {
            "online" -> R.id.btn_servers_online; "attention" -> R.id.btn_servers_attention; else -> R.id.btn_servers_all
        })
        listOf(R.id.btn_servers_all to "all", R.id.btn_servers_online to "online", R.id.btn_servers_attention to "attention")
            .forEach { (id, filter) -> findViewById<View>(id).setOnClickListener { serverFilter = filter; renderServerNodes() } }
    }

    private fun renderServerNodes() {
        if (!::serverNodesAdapter.isInitialized) return
        val query = findViewById<EditText>(R.id.adminServersSearch).text?.toString()?.trim().orEmpty()
        val nodes = vm.serverNodes.value
        val filtered = nodes.filter {
            (query.isBlank() || it.name.contains(query, true) || it.host.contains(query, true)) && when (serverFilter) {
                "online" -> it.status == ServerNodeStatus.ONLINE
                "attention" -> it.status != ServerNodeStatus.ONLINE
                else -> true
            }
        }
        tvClusterServersSummary.text = "Показано: ${filtered.size} из ${nodes.size} · В сети: ${nodes.count { it.status == ServerNodeStatus.ONLINE }}"
        tvEmptyServers.text = if (nodes.isEmpty()) "Добавьте первый сервер, чтобы настроить подключения." else "Серверы не найдены. Измените поиск или фильтр."
        tvEmptyServers.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
        rvServerNodes.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        serverNodesAdapter.submitList(filtered)
    }

    private fun showManagementChannelDialog() {
        val channels = listOf(AdminChannel.AUTO, AdminChannel.ACTIVE_TRANSPORT, AdminChannel.DOCUMENT, AdminChannel.DIRECT_SSH)
        val labels = arrayOf("Автоматически", "Текущее подключение", "Через документ", "Прямой SSH к серверу")
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Канал управления")
            .setSingleChoiceItems(labels, channels.indexOf(vm.selectedChannel.value)) { item, index ->
                if (index < 3) toggleChannel.check(listOf(R.id.btn_channel_auto, R.id.btn_channel_active, R.id.btn_channel_doc)[index])
                else vm.selectChannel(channels[index])
                item.dismiss()
            }.setNegativeButton("Закрыть", null).create()
        dialogs.show(dialog, "management_channel")
    }

    private fun protocolLabel(transport: String): String = when (transport) {
        "vyandex" -> "Документ Яндекса"
        "mqtt" -> "MQTT · WSS"
        "jitsi" -> "Jitsi Meet"
        "jazz" -> "СберДжаз"
        else -> transport
    }

    private fun assignmentStatusLabel(status: String): String = when (status) {
        "ready" -> "Готов"
        "invited" -> "Приглашение выдано"
        "active" -> "Доступ активен"
        "suspended" -> "Приостановлен"
        "revoked" -> "Отозван"
        "failed" -> "Ошибка настройки"
        "pending", "provisioning" -> "Настраивается"
        else -> "Состояние не подтверждено"
    }

    private fun showPersonDetails(user: AdminUser) {
        val screen = io.github.libreroute.ui.CalmDetailDialog(this, "Детали пользователя")
        val accessButtonId = io.github.libreroute.ui.DialogScreens.stateId("person-configure-access")
        fun render() {
            val readingPosition = screen.scrollView.scrollY
            screen.content.removeAllViews()
            screen.content.addView(io.github.libreroute.ui.CalmViews.row(this, user.name, when {
                user.isRevoked -> "Доступ отозван"
                user.isSuspended -> "Доступ приостановлен"
                else -> "Активен"
            }))
            screen.text("Доступные серверы и протоколы")
            val snapshot = vm.clusterSnapshot.value
            if (snapshot == null) {
                screen.text(vm.clusterSnapshotStatus.value)
                screen.action("Обновить", secondary = true) { vm.refreshClusterSnapshot() }
            }
            else if (snapshot.distinctServerIds.isEmpty()) screen.text("Сервер пока не сообщил доступные узлы")
            snapshot?.distinctServerIds?.sorted()?.forEach { serverId ->
                val name = vm.serverNodes.value.firstOrNull { it.id == serverId }?.name ?: serverId
                val methods = snapshot.methodsFor(serverId)
                val worker = snapshot.perServerStatus[serverId]
                val state = when {
                    worker == null -> "Состояние не проверено"
                    worker.isReady -> "Рабочие маршруты готовы"
                    else -> "Готовность маршрутов не подтверждена"
                }
                screen.content.addView(io.github.libreroute.ui.CalmViews.row(this, name,
                    "$state\n" + methods.joinToString(" · ") { protocolLabel(it.transport) }.ifEmpty { "Протоколы не подтверждены" }))
            }
            screen.text("Назначенные маршруты")
            val assignments = vm.assignments.value.filter { it.userId == user.id }
            val operation = vm.accessOperation.value?.takeIf { it.userId == user.id }
            if (operation?.state == AccessOperationState.SUBMITTING) {
                screen.text("Настраиваем доступ. Подготавливаем устройство и подключение на сервере…")
            } else if (operation?.state in setOf(AccessOperationState.FAILED, AccessOperationState.UNKNOWN, AccessOperationState.PARTIAL)) {
                screen.text(if (operation?.state == AccessOperationState.UNKNOWN)
                    "Сервер не подтвердил завершение настройки. Бронирование могло сохраниться. Повторите выдачу для проверки результата."
                    else "Не удалось завершить настройку доступа.")
                operation?.error?.let { screen.text(it) }
            } else if (assignments.isEmpty()) screen.text(if (operation?.state == AccessOperationState.CONFIRMED)
                "Ссылка создана. Обновляем сведения о назначении…" else "Доступ ещё не назначен. Настройте серверы и выдайте приглашение")
            assignments.forEach { assignment ->
                val name = vm.serverNodes.value.firstOrNull { it.id == assignment.serverId }?.name ?: assignment.serverId
                screen.content.addView(io.github.libreroute.ui.CalmViews.row(this, name,
                    "${protocolLabel(assignment.transport)} · ${assignmentStatusLabel(assignment.status)}") { showAssignmentActionDialog(assignment) })
            }
            if (user.isActive && vm.canPerform("assign-profile")) screen.content.addView(
                io.github.libreroute.ui.CalmViews.action(this, "Настроить доступ") { showAssignDocumentDialog(user) }
                    .apply { id = accessButtonId })
            screen.scrollView.post { screen.scrollView.scrollTo(0, readingPosition) }
            if (user.isActive && vm.canPerform("suspend-user")) screen.action("Приостановить доступ", destructive = true) {
                confirmSuspendUser(user.id)
            }
            if (vm.canPerform("delete-user")) screen.action("Удалить с сервера…", destructive = true) {
                confirmDeleteUserFromServer(user.id)
            }
        }
        render()
        val updates = lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(vm.clusterSnapshot, vm.assignments, vm.serverNodes, vm.clusterSnapshotStatus, vm.liveAccess, vm.accessOperation) { _ -> Unit }
                .collect { if (screen.isShowing) render() }
        }
        dialogs.show(screen, "person", Bundle().apply { putString("id", user.id) },
            progressViewIds = setOf(accessButtonId), pendingLabel = {
                vm.accessOperation.value?.takeIf { it.state == AccessOperationState.SUBMITTING }
                    ?.let { "Настраиваем доступ…" }
            }) { updates.cancel() }
        vm.loadAdminSession()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdminCommandFeedback.bind(this, window.decorView, AdminRepository.getInstance(this).commandControl,
            independentViewIds = setOf(R.id.overviewAddServer, R.id.btn_add_server_node, R.id.adminNavigation,
                R.id.adminAcquireSsh), onIdle = { updateCreateAvailability() })
        themePrefs = ThemePreferences(this)
        themePrefs.applyTheme()

        val isNight = when (themePrefs.themeMode) {
            ThemePreferences.THEME_LIGHT -> false
            ThemePreferences.THEME_DARK -> true
            else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        }
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.isAppearanceLightStatusBars = !isNight
        insetsController.isAppearanceLightNavigationBars = !isNight

        val adminRepo = AdminRepository.getInstance(this)
        if (!adminRepo.hasAdminReadAccess()) {
            Toast.makeText(this, "Доступ ограничен: требуются подтверждённые права администратора (OWNER или OPERATOR)", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AdminSetupActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_admin)

        initViews()
        restoredOperationShown = savedInstanceState?.getBoolean("restored_operation_shown") ?: false
        peopleFilter = savedInstanceState?.getString("people_filter") ?: "all"
        serverFilter = savedInstanceState?.getString("server_filter") ?: "all"
        isAuditLogExpanded = savedInstanceState?.getBoolean("audit_expanded", true) ?: true
        setupPeopleSearch()
        setupServerSearch()
        setupTopBar()
        setupChannelSelector()
        setupAdapters()
        setupCalmAdminNavigation(savedInstanceState)
        observeViewModel()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                dialogOperation.state.collect { state ->
                    state.result?.let { result ->
                        dialogOperation.consumed()
                        result.onSuccess { value -> when (value) {
                            is AdminDialogOutcome -> {
                                if (value.message.isNotBlank()) Toast.makeText(this@AdminActivity, value.message, Toast.LENGTH_LONG).show()
                                value.link?.takeIf { it.isNotBlank() }?.let { shareAdminLink(it, value.title) }
                            }
                            is AdministratorsLoaded -> showAdministratorList(value.nodeId, value.admins)
                        } }.onFailure { Toast.makeText(this@AdminActivity, it.message ?: "Операция не завершена. Проверьте результат.", Toast.LENGTH_LONG).show() }
                    }
                }
            }
        }
        vm.loadAdminSession()
        if (savedInstanceState == null) intent.getStringExtra("admin_transfer_ack")?.let {
            showCompleteOwnerTransferDialog(it); intent.removeExtra("admin_transfer_ack")
        }
        dialogs.restore()
    }

    private var adoptedSshOperationId: String? = null

    override fun onResume() {
        super.onResume()
        refreshLocalProfiles()
        val repo = AdminRepository.getInstance(this)
        val operation = vm.currentServerNode()?.id?.let(repo::latestServerInstallation)
        if (operation?.preserveOwners == true && operation.stage == ServerInstallOperation.READY &&
            repo.sessionAdminChannel == AdminChannel.DIRECT_SSH &&
            adoptedSshOperationId != operation.operationId) {
            adoptedSshOperationId = operation.operationId
            vm.useSshSession()
        }
    }

    private fun refreshLocalProfiles() {
        if (!::tvLocalVpnProfiles.isInitialized) return
        val profiles = vm.getAvailableVpnProfiles()
        tvLocalVpnProfiles.text = if (profiles.isEmpty()) "На телефоне нет VPN-профилей"
            else profiles.joinToString("\n") { "• ${it.name} (${it.transportType})" }
    }

    private fun initViews() {
        toggleChannel = findViewById(R.id.toggle_channel)
        tvRoleTitle = findViewById(R.id.tv_role_title)
        tvRoleDesc = findViewById(R.id.tv_role_desc)
        iconRoleStatus = findViewById(R.id.icon_role_status)
        tvDeviceFingerprint = findViewById(R.id.tv_device_fingerprint)
        layoutPendingInfo = findViewById(R.id.layout_pending_info)
        tvPairingCode = findViewById(R.id.tv_pairing_code)
        btnActionPrimary = findViewById(R.id.btn_action_primary)
        btnActionSecondary = findViewById(R.id.btn_action_secondary)
        btnAddProfile = findViewById(R.id.btn_add_profile)
        tvEmptyProfiles = findViewById(R.id.tv_empty_profiles)
        rvManagedProfiles = findViewById(R.id.rv_managed_profiles)
        tvEmptyHistory = findViewById(R.id.tv_empty_history)
        rvCommandHistory = findViewById(R.id.rv_command_history)
        btnRevokeAdminRights = findViewById(R.id.btn_revoke_admin_rights)
        tvSshKeyStatus = findViewById(R.id.tv_ssh_key_status)
        btnManageSshKey = findViewById(R.id.btn_manage_ssh_key)
        tvClusterServersSummary = findViewById(R.id.tv_cluster_servers_summary)
        btnAddServerNode = findViewById(R.id.btn_add_server_node)
        rvServerNodes = findViewById(R.id.rv_server_nodes)
        tvEmptyServers = findViewById(R.id.tv_empty_servers)
        btnQuickInvite = findViewById(R.id.btn_quick_invite)
        btnQuickInvite.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showCreateUserDialog()
        }
        tvLocalVpnProfiles = findViewById(R.id.tv_local_vpn_profiles)
        tvServerProfilesStatus = findViewById(R.id.tv_server_profiles_status)
        tvServerProfiles = findViewById(R.id.tv_server_profiles)
        btnAddUser = findViewById(R.id.btn_add_user)
        tvUsersStatus = findViewById(R.id.tv_users_status)
        tvUsers = findViewById(R.id.tv_users)
        rvUserAssignments = findViewById(R.id.rv_user_assignments)
        tvEmptyAssignments = findViewById(R.id.tv_empty_assignments)
        cardClusterHealth = findViewById(R.id.card_cluster_health)
        iconClusterHealth = findViewById(R.id.icon_cluster_health)
        headerAuditLog = findViewById(R.id.header_audit_log)
        iconAuditChevron = findViewById(R.id.icon_audit_chevron)
        btnAssignUserDocument = findViewById(R.id.btn_assign_user_document)
        toggleAdminTabs = findViewById(R.id.toggle_admin_tabs)
        layoutTabServers = findViewById(R.id.layout_tab_servers)
        layoutTabUsers = findViewById(R.id.layout_tab_users)
        btnProbeServerNode = findViewById(R.id.btn_probe_server_node)
        findViewById<MaterialButton>(R.id.btn_install_server_node).setOnClickListener { showInstallServerDialog() }
        findViewById<MaterialButton>(R.id.btn_auth_server_node).setOnClickListener { showServerAuthDialog() }
        tvServerInventoryDetails = findViewById(R.id.tv_server_inventory_details)
        btnReconcileAssignments = findViewById(R.id.btn_reconcile_assignments)
        btnIssueAssignmentQr = findViewById(R.id.btn_issue_assignment_qr)
        tvClusterSnapshotSummary = findViewById(R.id.tv_cluster_snapshot_summary)

        toggleAdminTabs.check(R.id.btn_tab_servers)

        btnProbeServerNode.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showProbeServerDialog()
        }

        btnReconcileAssignments.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            vm.reconcileAssignments()
        }

        btnIssueAssignmentQr.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showSelectAssignmentForInvitation()
        }

        btnAddUser.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showCreateUserDialog()
        }
        btnAssignUserDocument.setOnClickListener { showSelectUserForAssignment() }

        headerAuditLog.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            isAuditLogExpanded = !isAuditLogExpanded
            val historyEmpty = vm.commandHistory.value.isEmpty()
            rvCommandHistory.visibility = if (isAuditLogExpanded && !historyEmpty) View.VISIBLE else View.GONE
            tvEmptyHistory.visibility = if (isAuditLogExpanded && historyEmpty) View.VISIBLE else View.GONE
            iconAuditChevron.animate().rotation(if (isAuditLogExpanded) 0f else -90f).setDuration(200).start()
        }

        btnManageSshKey.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showManageSshKeyDialog()
        }

        btnAddServerNode.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showAddServerNodeDialog()
        }

        tvDeviceFingerprint.text = "Отпечаток: ${vm.fingerprint}"
    }

    private fun setupTopBar() {
        findViewById<View>(R.id.btn_back).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            onBackPressedDispatcher.onBackPressed()
        }
        findViewById<View>(R.id.btn_refresh).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            vm.refreshClusterSnapshot()
            refreshLocalProfiles()
        }
    }

    private fun setupChannelSelector() {
        toggleChannel.isSaveFromParentEnabled = false
        toggleChannel.check(when (vm.selectedChannel.value) {
            AdminChannel.ACTIVE_TRANSPORT -> R.id.btn_channel_active
            AdminChannel.DOCUMENT -> R.id.btn_channel_doc
            AdminChannel.SSH_VDS -> R.id.btn_channel_vds
            AdminChannel.SSH_NETCRAZE -> R.id.btn_channel_netcraze
            else -> R.id.btn_channel_auto
        })
        toggleChannel.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_channel_auto -> vm.selectChannel(AdminChannel.AUTO)
                R.id.btn_channel_active -> vm.selectChannel(AdminChannel.ACTIVE_TRANSPORT)
                R.id.btn_channel_doc -> vm.selectChannel(AdminChannel.DOCUMENT)
                R.id.btn_channel_vds -> vm.selectChannel(AdminChannel.SSH_VDS)
                R.id.btn_channel_netcraze -> vm.selectChannel(AdminChannel.SSH_NETCRAZE)
            }
        }
    }

    private fun setupAdapters() {
        profilesAdapter = AdminProfilesAdapter(
            onInviteClicked = { profile ->
                showPromptRecipientKeyDialog(profile)
            },
            onToggleSuspendClicked = { profile ->
                if (profile.isSuspended) {
                    vm.resumeProfile(profile.id)
                } else {
                    vm.suspendProfile(profile.id)
                }
            },
            onRevokeClicked = { profile ->
                showConfirmRevokeProfileDialog(profile)
            },
            onAuthSyncClicked = { profile ->
                val serverId = vm.selectedServerNodeId.value
                val targetServer = vm.serverNodes.value.firstOrNull { it.id == serverId } ?: vm.serverNodes.value.firstOrNull()
                val intent = android.content.Intent(this, RemoteAuthActivity::class.java).apply {
                    putExtra(RemoteAuthActivity.EXTRA_TARGET_ID, targetServer?.name ?: targetServer?.id ?: "vds89")
                    putExtra(RemoteAuthActivity.EXTRA_PROFILE_ID, profile.id)
                }
                startActivity(intent)
            }
        )
        rvManagedProfiles.layoutManager = LinearLayoutManager(this)
        rvManagedProfiles.adapter = profilesAdapter

        commandsAdapter = AdminCommandsAdapter()
        rvCommandHistory.layoutManager = LinearLayoutManager(this)
        rvCommandHistory.adapter = commandsAdapter

        assignmentsAdapter = AdminAssignmentsAdapter(
            onIssueQrClicked = { assignment ->
                vm.issueAssignmentInvitation(assignment.id)
            },
            onRevokeClicked = { assignment ->
                confirmRevokeAssignment(assignment.id)
            },
            onItemClicked = { assignment ->
                showAssignmentActionDialog(assignment)
            }
        )
        rvUserAssignments.layoutManager = LinearLayoutManager(this)
        rvUserAssignments.adapter = assignmentsAdapter

        serverNodesAdapter = AdminServerNodesAdapter(
            onPingClicked = { node ->
                findViewById<View>(R.id.btn_refresh).performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                vm.probeServer(node)
            },
            onManageClicked = { node ->
                findViewById<View>(R.id.btn_refresh).performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                showServerNodeDetailsDialog(node)
            }
        )
        rvServerNodes.layoutManager = LinearLayoutManager(this)
        rvServerNodes.adapter = serverNodesAdapter

        btnAddProfile.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showCreateUserDialog()
        }

        btnRevokeAdminRights.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showConfirmRevokeRightsDialog()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    vm.role.collect { role ->
                        bindRoleState(role)
                    }
                }
                launch {
                    vm.managedProfiles.collect { profiles ->
                        profilesAdapter.submitList(profiles)
                        tvEmptyProfiles.text = if (profiles.isEmpty() && vm.role.value == AdminRole.OWNER && vm.serverNodes.value.isNotEmpty()) {
                            "Профиль владельца отсутствует (profile missing). Выполните повторную синхронизацию сервера."
                        } else {
                            getString(R.string.admin_empty_profiles)
                        }
                        tvEmptyProfiles.visibility = if (profiles.isEmpty()) View.VISIBLE else View.GONE
                        rvManagedProfiles.visibility = if (profiles.isEmpty()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    vm.commandHistory.collect { history ->
                        commandsAdapter.submitList(history)
                        tvEmptyHistory.visibility = if (isAuditLogExpanded && history.isEmpty()) View.VISIBLE else View.GONE
                        rvCommandHistory.visibility = if (isAuditLogExpanded && history.isNotEmpty()) View.VISIBLE else View.GONE
                        iconAuditChevron.rotation = if (isAuditLogExpanded) 0f else -90f
                    }
                }
                launch {
                    vm.lastPairingCode.collect { code ->
                        if (!code.isNullOrBlank()) {
                            tvPairingCode.text = code
                            layoutPendingInfo.visibility = View.VISIBLE
                        } else {
                            layoutPendingInfo.visibility = View.GONE
                        }
                    }
                }
                launch {
                    vm.uiEvents.collect { event ->
                        when (event) {
                            is AdminUiEvent.ShowInstallPlan -> MaterialAlertDialogBuilder(this@AdminActivity)
                                .setTitle("План установки: ${event.node.name}")
                                .setMessage(event.summary)
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Установить") { _, _ -> vm.installServer(event.node, event.operationId, event.documentUrl) }
                                .show()
                            is AdminUiEvent.ShowToast -> Toast.makeText(this@AdminActivity, event.message, Toast.LENGTH_SHORT).show()
                            is AdminUiEvent.ShowInviteDialog -> showInviteDialog(event.profile, event.inviteLink)
                            is AdminUiEvent.ShowAssignmentInviteDialog -> showAssignmentInviteDialog(event.assignmentId, event.inviteLink)
                            is AdminUiEvent.ShowAccessOperation -> showAccessOperation(event.operation)
                        }
                    }
                }
                launch {
                    vm.accessOperation.collect { operation ->
                        if (!restoredOperationShown && operation?.state == AccessOperationState.UNKNOWN) {
                            restoredOperationShown = true
                            showAccessOperation(operation)
                        }
                    }
                }
                launch {
                    vm.hasAdminSshKey.collect { hasKey ->
                        tvSshKeyStatus.text = if (hasKey) "SSH-ключ: настроен" else "SSH-ключ: не настроен"
                    }
                }
                launch {
                    vm.serverNodes.collect { nodes ->
                        renderOverviewCounts()
                        renderPeople()
                        findViewById<MaterialButton>(R.id.btn_tab_servers).text = "🖥 Серверы (${nodes.size})"
                        renderServerNodes()
                        updateServerClientsCount()


                        val probedNode = nodes.firstOrNull { it.inventory != null }
                        if (probedNode != null) {
                            val inv = probedNode.inventory!!
                            if (inv.probeError != null) {
                                tvServerInventoryDetails.text = "Ошибка опроса [${probedNode.name}]: ${inv.probeError}"
                            } else {
                                tvServerInventoryDetails.text = buildString {
                                    appendLine("Узел [${probedNode.name}]: ${inv.osName} ${inv.osVersion} (${inv.arch})")
                                    appendLine("CPU: ${inv.cpuCores} cores | RAM: ${inv.ramFreeMb}/${inv.ramTotalMb} MB")
                                    appendLine("Диск: ${inv.diskFreeMb}/${inv.diskTotalMb} MB | Init: ${inv.initSystem}")
                                    append("Пакеты: ${inv.packageManager} | Docker: ${if (inv.hasDocker) "Да" else "Нет"}")
                                }
                            }
                        } else {
                            tvServerInventoryDetails.text = "Опрос сервера: не проводился (нажмите «Опрос»)"
                        }

                        findViewById<View>(R.id.btn_channel_vds).visibility =
                            if (nodes.any { it.nodeType == ServerNodeType.VDS || it.nodeType == ServerNodeType.CUSTOM }) View.VISIBLE else View.GONE
                        findViewById<View>(R.id.btn_channel_netcraze).visibility =
                            if (nodes.any { it.nodeType == ServerNodeType.ROUTER_KEENETIC || it.nodeType == ServerNodeType.ROUTER_OPENWRT }) View.VISIBLE else View.GONE
                        val channel = vm.selectedChannel.value
                        if ((channel == AdminChannel.SSH_VDS && nodes.none { it.nodeType == ServerNodeType.VDS || it.nodeType == ServerNodeType.CUSTOM }) ||
                            (channel == AdminChannel.SSH_NETCRAZE && nodes.none { it.nodeType == ServerNodeType.ROUTER_KEENETIC || it.nodeType == ServerNodeType.ROUTER_OPENWRT })) {
                            toggleChannel.check(R.id.btn_channel_doc)
                        }
                    }
                }
                launch {
                    vm.clusterSnapshot.collect { snapshot ->
                        if (snapshot != null) {
                            val health = adminClusterHealth(snapshot)
                            if (health.confirmed) {
                                cardClusterHealth.setCardBackgroundColor(android.graphics.Color.parseColor("#1A22C55E"))
                                cardClusterHealth.strokeColor = android.graphics.Color.parseColor("#3322C55E")
                                iconClusterHealth.setImageResource(R.drawable.ic_check)
                                iconClusterHealth.setColorFilter(ContextCompat.getColor(this@AdminActivity, R.color.colorSuccess))
                                tvClusterSnapshotSummary.setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.colorSuccess))
                                tvClusterSnapshotSummary.text = health.message
                            } else {
                                cardClusterHealth.setCardBackgroundColor(android.graphics.Color.parseColor("#1AF59E0B"))
                                cardClusterHealth.strokeColor = android.graphics.Color.parseColor("#33F59E0B")
                                iconClusterHealth.setImageResource(R.drawable.ic_refresh)
                                iconClusterHealth.setColorFilter(ContextCompat.getColor(this@AdminActivity, R.color.state_connecting))
                                tvClusterSnapshotSummary.setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.state_connecting))
                                tvClusterSnapshotSummary.text = health.message
                            }
                        } else {
                            cardClusterHealth.setCardBackgroundColor(android.graphics.Color.parseColor("#14FFFFFF"))
                            cardClusterHealth.strokeColor = android.graphics.Color.parseColor("#22FFFFFF")
                            iconClusterHealth.setImageResource(R.drawable.ic_refresh)
                            iconClusterHealth.setColorFilter(ContextCompat.getColor(this@AdminActivity, R.color.text_tertiary))
                            tvClusterSnapshotSummary.setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.text_tertiary))
                            tvClusterSnapshotSummary.text = vm.clusterSnapshotStatus.value
                        }
                    }
                }
                launch {
                    vm.clusterSnapshotStatus.collect { status ->
                        if (vm.clusterSnapshot.value == null ||
                            vm.liveAccess.value[vm.currentServerNode()?.id ?: "legacy"]?.state != AdminLiveAccess.State.READY) {
                            tvClusterSnapshotSummary.text = status
                        }
                    }
                }
                launch {
                    vm.serverProfilesStatus.collect { tvServerProfilesStatus.text = it }
                }
                launch {
                    vm.serverProfiles.collect { profiles ->
                        tvServerProfiles.text = profiles.joinToString("\n") {
                            "• ${it.name} · ${it.status} · ревизия ${it.revision}"
                        }
                    }
                }
                launch {
                    vm.usersStatus.collect { tvUsersStatus.text = it }
                }
                launch {
                    vm.users.collect { users ->
                        renderPeople()
                        tvUsers.text = users.joinToString("\n") { user ->
                            val document = if (vm.hasPendingUserDocument(user.id))
                                " · документ ожидает ключ устройства" else ""
                            "• ${user.name} · ${user.status}$document"
                        }
                        assignmentsAdapter.setUserNames(users.associate { it.id to it.name })
                        findViewById<MaterialButton>(R.id.btn_tab_users).text = "👥 Пользователи (${users.size})"
                    }
                }
                launch {
                    vm.assignments.collect { assignments ->
                        renderPeople()
                        assignmentsAdapter.submitList(assignments)
                        val isEmpty = assignments.isEmpty()
                        tvEmptyAssignments.visibility = if (isEmpty) View.VISIBLE else View.GONE
                        rvUserAssignments.visibility = if (isEmpty) View.GONE else View.VISIBLE
                        updateServerClientsCount()
                    }
                }
                launch {
                    vm.assignmentsStatus.collect { status ->
                        if (vm.assignments.value.isEmpty()) {
                            tvEmptyAssignments.text = status
                        }
                    }
                }
                launch {
                    vm.selectedChannel.collect { channel ->
                        updateCreateAvailability()
                        findViewById<MaterialButton>(R.id.adminChannelSummary).text = "Канал управления: " + when (channel) {
                            AdminChannel.AUTO -> "автоматически"
                            AdminChannel.ACTIVE_TRANSPORT -> "текущее подключение"
                            AdminChannel.DOCUMENT -> "через документ"
                            else -> "SSH"
                        }
                    }
                }
                launch { vm.liveAccess.collect { updateCreateAvailability() } }
                launch { vm.selectedServerNodeId.collect { updateCreateAvailability() } }
            }
        }
    }

    private fun showAccessOperation(operation: AccessOperationRecord) {
        val title = when (operation.state) {
            AccessOperationState.CONFIRMED -> "Доступ настроен"
            AccessOperationState.PARTIAL -> "Доступ настроен частично"
            AccessOperationState.FAILED -> "Не удалось настроить доступ"
            AccessOperationState.UNKNOWN -> "Результат неизвестен"
            AccessOperationState.SUBMITTING -> "Настройка доступа выполняется"
            AccessOperationState.IDLE -> "Настройка доступа"
        }
        val rows = operation.routes.joinToString("\n") { route ->
            val label = "${route.serverId} · ${route.transport}"
            if (route.reason.isNullOrBlank()) "$label: ${route.state}" else "$label: ${route.state} — ${route.reason}"
        }
        showMessage(title, rows.ifBlank { operation.error ?: "Нет данных" })
    }

    private fun showMessage(title: String, body: String) {
        val dialog = MaterialAlertDialogBuilder(this).setTitle(title).setMessage(body).setPositiveButton("Закрыть", null).create()
        dialogs.show(dialog, "message", Bundle().apply { putString("title", title); putString("body", body) })
    }

    private fun bindRoleState(role: AdminRole) {
        if (role != AdminRole.OWNER && role != AdminRole.OPERATOR && role != AdminRole.VIEWER) {
            Toast.makeText(this, "Доступ закрыт: требуются права OWNER или OPERATOR", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, AdminSetupActivity::class.java))
            finish()
            return
        }
        profilesAdapter.canRevoke = role == AdminRole.OWNER
        when (role) {
            AdminRole.NONE -> {
                btnActionPrimary.visibility = View.VISIBLE
                tvRoleTitle.text = getString(R.string.admin_role_none)
                tvRoleDesc.text = getString(R.string.admin_role_none_desc)
                iconRoleStatus.setImageResource(R.drawable.ic_lock)
                iconRoleStatus.setColorFilter(ContextCompat.getColor(this, R.color.text_tertiary))

                btnActionPrimary.text = getString(R.string.admin_btn_request_rights)
                btnActionPrimary.setOnClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                    startActivity(Intent(this, AdminSetupActivity::class.java))
                }

                btnActionSecondary.visibility = View.GONE

                btnAddProfile.visibility = View.GONE
                btnRevokeAdminRights.visibility = View.GONE
                layoutPendingInfo.visibility = View.GONE
            }
            AdminRole.PENDING -> {
                tvRoleTitle.text = getString(R.string.admin_role_pending)
                tvRoleDesc.text = getString(R.string.admin_role_pending_desc)
                iconRoleStatus.setImageResource(R.drawable.ic_lock)
                iconRoleStatus.setColorFilter(ContextCompat.getColor(this, R.color.state_connecting))

                layoutPendingInfo.visibility = View.VISIBLE

                btnActionPrimary.visibility = View.VISIBLE
                btnActionPrimary.text = "Копировать подписанный запрос"
                btnActionPrimary.setOnClickListener { copyPendingJoinRequest() }
                btnActionSecondary.visibility = View.VISIBLE
                btnActionSecondary.text = "Импортировать подтверждение"
                btnActionSecondary.setOnClickListener { showImportGrantDialog() }
                btnAddProfile.visibility = View.GONE
                btnRevokeAdminRights.visibility = View.GONE
            }
            AdminRole.OWNER -> {
                btnActionPrimary.visibility = View.VISIBLE
                tvRoleTitle.text = "Владелец"
                tvRoleDesc.text = "Полный доступ к выдаче, отзывам и узлам"
                iconRoleStatus.setImageResource(R.drawable.ic_shield)
                iconRoleStatus.setColorFilter(ContextCompat.getColor(this, R.color.m3_primary))

                btnActionPrimary.text = "Добавить пользователя"
                btnActionPrimary.setOnClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                    showCreateUserDialog()
                }
                btnActionSecondary.visibility = View.VISIBLE
                btnActionSecondary.text = "Пригласить администратора"
                btnActionSecondary.setOnClickListener { showAdminDelegationActions() }
                btnAddProfile.visibility = View.VISIBLE
                btnRevokeAdminRights.visibility = View.VISIBLE
                layoutPendingInfo.visibility = View.GONE
            }
            AdminRole.OPERATOR -> {
                btnActionPrimary.visibility = View.VISIBLE
                tvRoleTitle.text = "Администратор"
                tvRoleDesc.text = "Управление назначенными профилями и выдача приглашений"
                iconRoleStatus.setImageResource(R.drawable.ic_shield)
                iconRoleStatus.setColorFilter(ContextCompat.getColor(this, R.color.m3_primary))

                btnActionPrimary.text = "Добавить пользователя"
                btnActionPrimary.setOnClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                    showCreateUserDialog()
                }
                btnActionSecondary.visibility = View.GONE
                btnAddProfile.visibility = View.VISIBLE
                btnRevokeAdminRights.visibility = View.VISIBLE
                layoutPendingInfo.visibility = View.GONE
            }
            AdminRole.VIEWER -> {
                tvRoleTitle.text = getString(R.string.admin_role_viewer)
                tvRoleDesc.text = "Просмотр состояния профилей и журнала аудита"
                iconRoleStatus.setImageResource(R.drawable.ic_lock)
                iconRoleStatus.setColorFilter(ContextCompat.getColor(this, R.color.text_secondary))

                btnActionPrimary.visibility = View.GONE
                btnActionSecondary.visibility = View.GONE
                btnAddProfile.visibility = View.GONE
                btnRevokeAdminRights.visibility = View.VISIBLE
                layoutPendingInfo.visibility = View.GONE
            }
        }
        if (role == AdminRole.OWNER || role == AdminRole.OPERATOR) btnActionPrimary.visibility = View.GONE
        updateCreateAvailability()
    }

    private fun updateCreateAvailability() {
        if (!::btnAddProfile.isInitialized || !::btnActionPrimary.isInitialized) return
        val supported = vm.canPerform("create-user")
        val canRead = vm.canOpenPeople()
        val nav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.adminNavigation)
        nav.menu.findItem(R.id.nav_people).isEnabled = canRead
        nav.menu.findItem(R.id.nav_people).setIcon(if (canRead) R.drawable.ic_person else R.drawable.ic_lock)
        nav.findViewById<View>(R.id.nav_people)?.alpha = if (canRead) 1f else 0.38f
        findViewById<View>(R.id.btn_tab_users).isEnabled = canRead
        findViewById<View>(R.id.adminUsersMetric).isEnabled = canRead
        findViewById<View>(R.id.adminUsersMetric).alpha = if (canRead) 1f else 0.38f
        val node = vm.currentServerNode()
        val live = vm.liveAccess.value[node?.id ?: "legacy"]
        val explanation = when (live?.state) {
            AdminLiveAccess.State.CHECKING -> "Проверяем соединение. Сохранённые данные доступны для просмотра."
            AdminLiveAccess.State.READY -> if (vm.mutationBlockReason() != null)
                "Административные права сохранены. ${vm.mutationBlockReason()}"
                else if (supported) "Управление пользователями доступно"
                else if (canRead) "Доступен только просмотр пользователей"
                else "Роль не разрешает просмотр пользователей"
            AdminLiveAccess.State.DENIED -> "Сервер не подтвердил административные права. Получите управление по SSH или примите приглашение администратора."
            AdminLiveAccess.State.UNAVAILABLE -> "Нет соединения с сервером. Сохранённый допуск не изменён; доступны последние данные. Для изменений восстановите соединение и нажмите «Обновить»."
            null -> "Доступ к управлению ещё не подтверждён"
        }
        findViewById<TextView>(R.id.adminAccessExplanation).text = explanation
        val needsAdmission = live?.state == AdminLiveAccess.State.DENIED ||
            vm.currentServerAccess().state != AdminAccessState.ACTIVE
        findViewById<View>(R.id.adminAcquireSsh).visibility = if (needsAdmission && node != null) View.VISIBLE else View.GONE
        if (!canRead && live?.state != AdminLiveAccess.State.CHECKING) {
            if (adminTab == R.id.nav_people) nav.selectedItemId = R.id.nav_overview
            dialogs.dismiss("person")
            dialogs.dismiss("showCreateUserDialog")
        }
        btnAddProfile.isEnabled = supported
        btnQuickInvite.isEnabled = supported
        btnAddUser.isEnabled = supported
        findViewById<View>(R.id.overviewInvite).isEnabled = supported
        listOf(btnAddProfile, btnQuickInvite, btnAddUser, findViewById<MaterialButton>(R.id.overviewInvite)).forEach {
            it.alpha = if (supported) 1f else 0.38f
        }
        findViewById<MaterialButton>(R.id.overviewInvite).apply {
            text = if (supported) "Добавить пользователя" else when (live?.state) {
                AdminLiveAccess.State.CHECKING -> "Проверяем доступ…"
                AdminLiveAccess.State.DENIED -> "Нет прав на добавление пользователя"
                AdminLiveAccess.State.READY -> "Добавление пользователя недоступно"
                AdminLiveAccess.State.UNAVAILABLE -> "Нет связи для добавления пользователя"
                else -> "Доступ к пользователям не подтверждён"
            }
            icon = if (supported) null else ContextCompat.getDrawable(this@AdminActivity, R.drawable.ic_lock)
            contentDescription = if (supported) "Добавить пользователя" else "$text. $explanation"
        }
        renderOverviewCounts()
        if (vm.role.value == AdminRole.OWNER || vm.role.value == AdminRole.OPERATOR) {
            btnActionPrimary.isEnabled = supported
            btnActionPrimary.text = if (supported) "Добавить пользователя"
                else vm.mutationBlockReason() ?: "Недостаточно прав на выбранном сервере"
        }
    }

    private fun showAdminDelegationActions() {
        val actions = arrayOf("Создать приглашение для устройства", "Одобрить запрос устройства", "Завершить передачу владения", "Управлять администраторами")
        MaterialAlertDialogBuilder(this).setTitle("Доступ администратора")
            .setItems(actions) { _, which -> when (which) { 0 -> showAdminInvitationTemplateDialog(); 1 -> showApproveAdminRequestDialog(); 2 -> showCompleteOwnerTransferDialog(); else -> showAdministratorsDialog() } }
            .create().let { dialogs.show(it, "showAdminDelegationActions", Bundle()) }
    }

    private fun showAdministratorsDialog(selectedNode: ServerNode? = null) {
        val nodes = vm.serverNodes.value
        if (nodes.isEmpty()) { Toast.makeText(this, "Нет сервера для списка администраторов", Toast.LENGTH_LONG).show(); return }
        val node = selectedNode ?: nodes.firstOrNull { it.id == vm.selectedServerNodeId.value } ?: nodes.singleOrNull()
        if (node == null) {
            MaterialAlertDialogBuilder(this).setTitle("Выберите сервер").setItems(nodes.map { it.name }.toTypedArray()) { _, index -> showAdministratorsDialog(nodes[index]) }.create().let { dialogs.show(it, "admins_choose_server") }
            return
        }
        val repo = AdminRepository.getInstance(applicationContext)
        dialogOperation.run { AdministratorsLoaded(node.id, repo.listAdministrators(AdminChannel.DOCUMENT, node).getOrThrow()) }
    }

    private fun showAdministratorList(nodeId: String, admins: List<AdministratorRecord>) {
        if (admins.isEmpty()) { Toast.makeText(this, "Администраторов нет", Toast.LENGTH_LONG).show(); return }
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Администраторы")
            .setItems(admins.map { "${it.role} · ${it.deviceId}\n${it.status}" }.toTypedArray()) { _, index ->
                val admin = admins[index]; showRevokeAdministrator(nodeId, admin.deviceId, admin.role.toString())
            }.create()
        dialogs.show(dialog, "admins", Bundle().apply { putString("node", nodeId); putString("admins", kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(AdministratorRecord.serializer()), admins)) })
    }

    private fun showRevokeAdministrator(nodeId: String, deviceId: String, role: String) {
        val node = vm.serverNodes.value.firstOrNull { it.id == nodeId } ?: return
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Отозвать доступ?")
            .setMessage("$role\n$deviceId").setNegativeButton("Отмена", null)
            .setPositiveButton("Отозвать") { _, _ ->
                val repo = AdminRepository.getInstance(applicationContext)
                dialogOperation.run { repo.revokeAdministrator(deviceId, node).getOrThrow(); AdminDialogOutcome("Доступ отозван") }
            }.create()
        dialogs.show(dialog, "revoke_admin", Bundle().apply { putString("node", nodeId); putString("device", deviceId); putString("role", role) })
    }

    private fun showCompleteOwnerTransferDialog(initialAck: String = "") {
        val nodes = vm.serverNodes.value
        if (nodes.isEmpty()) { Toast.makeText(this, "Нет сервера для завершения передачи", Toast.LENGTH_LONG).show(); return }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 8, 28, 0) }
        val ack = EditText(this).apply { hint = "Ссылка admin-transfer-ack"; minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; setText(initialAck) }
        val server = Spinner(this).apply { adapter = ArrayAdapter(this@AdminActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Выберите сервер") + nodes.map { it.name }); setSelection(nodes.indexOfFirst { it.id == vm.selectedServerNodeId.value }.let { if (it >= 0) it + 1 else if (nodes.size == 1) 1 else 0 }) }
        container.addView(ack); container.addView(TextView(this).apply { text = "Сервер" }); container.addView(server)
        MaterialAlertDialogBuilder(this).setTitle("Завершить передачу владения").setMessage("Выполняйте это только после получения подтверждения от нового владельца.")
            .setView(container).setNegativeButton("Отмена", null).setPositiveButton("Завершить") { _, _ ->
                if (server.selectedItemPosition == 0) { Toast.makeText(this, "Выберите сервер", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val node = nodes[server.selectedItemPosition - 1]
                val text = ack.text.toString().trim()
                val repo = AdminRepository.getInstance(applicationContext)
                dialogOperation.run { repo.completeOwnerTransfer(text, node).getOrThrow(); AdminDialogOutcome("Передача владения завершена") }

            }.create().let { dialogs.show(it, "showCompleteOwnerTransferDialog", Bundle()) }
    }

    private fun showAdminInvitationTemplateDialog() {
        val nodes = vm.serverNodes.value
        if (nodes.isEmpty()) { Toast.makeText(this, "Сначала добавьте и проверьте сервер", Toast.LENGTH_LONG).show(); return }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 8, 28, 0) }
        val role = Spinner(this).apply {
            adapter = ArrayAdapter(this@AdminActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Оператор", "Наблюдатель"))
        }
        val server = Spinner(this).apply { adapter = ArrayAdapter(this@AdminActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Выберите сервер") + nodes.map { it.name }); setSelection(nodes.indexOfFirst { it.id == vm.selectedServerNodeId.value }.let { if (it >= 0) it + 1 else if (nodes.size == 1) 1 else 0 }) }
        container.addView(TextView(this).apply { text = "Роль получателя" }); container.addView(role)
        container.addView(TextView(this).apply { text = "Сервер" }); container.addView(server)
        MaterialAlertDialogBuilder(this).setTitle("Приглашение администратора").setMessage("Ссылка будет одноразовой. Получатель подтвердит устройство, после чего вы одобрите запрос.")
            .setView(container).setNegativeButton("Отмена", null).setPositiveButton("Создать ссылку") { _, _ ->
                val selectedRole = if (role.selectedItemPosition == 0) AdminRole.OPERATOR else AdminRole.VIEWER
                if (server.selectedItemPosition == 0) { Toast.makeText(this, "Выберите сервер", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val node = nodes[server.selectedItemPosition - 1]
                val repo = AdminRepository.getInstance(applicationContext)
                dialogOperation.run {
                    val (result, link) = repo.issueAdminInvitationTemplate(selectedRole, node, AdminChannel.DOCUMENT)
                    if (result is AdminOpResult.Failure) error(result.error)
                    AdminDialogOutcome(link = link, title = "Приглашение LibreRoute")
                }

            }.create().let { dialogs.show(it, "showAdminInvitationTemplateDialog", Bundle()) }
    }

    private fun showApproveAdminRequestDialog() {
        val nodes = vm.serverNodes.value
        if (nodes.isEmpty()) { Toast.makeText(this, "Нет сервера для подтверждения", Toast.LENGTH_LONG).show(); return }
        val container = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 8, 28, 0) }
        val request = EditText(this).apply { hint = "Ссылка libreroute://admin-request"; minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        val role = Spinner(this).apply { adapter = ArrayAdapter(this@AdminActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Оператор", "Наблюдатель", "Владелец (передача)")) }
        val server = Spinner(this).apply { adapter = ArrayAdapter(this@AdminActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Выберите сервер") + nodes.map { it.name }); setSelection(nodes.indexOfFirst { it.id == vm.selectedServerNodeId.value }.let { if (it >= 0) it + 1 else if (nodes.size == 1) 1 else 0 }) }
        val transfer = MaterialCheckBox(this).apply { text = "Передать владение после подтверждения получателем"; visibility = View.GONE }
        role.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { transfer.visibility = if (position == 2) View.VISIBLE else View.GONE }
        })
        container.addView(request); container.addView(TextView(this).apply { text = "Роль" }); container.addView(role); container.addView(transfer); container.addView(TextView(this).apply { text = "Сервер" }); container.addView(server)
        MaterialAlertDialogBuilder(this).setTitle("Одобрить запрос устройства").setView(container).setNegativeButton("Отмена", null).setPositiveButton("Одобрить") { _, _ ->
            val selectedRole = when (role.selectedItemPosition) { 0 -> AdminRole.OPERATOR; 1 -> AdminRole.VIEWER; else -> AdminRole.OWNER }
            if (server.selectedItemPosition == 0) { Toast.makeText(this, "Выберите сервер", Toast.LENGTH_LONG).show(); return@setPositiveButton }
            val node = nodes[server.selectedItemPosition - 1]
            val text = request.text.toString().trim()
            val transferOwner = transfer.isChecked
            val repo = AdminRepository.getInstance(applicationContext)
            dialogOperation.run {
                val (result, link) = repo.approveAdminRequest(text, selectedRole, node, AdminChannel.DOCUMENT, transferOwner)
                if (result is AdminOpResult.Failure) error(result.error)
                AdminDialogOutcome(link = link, title = "Подтверждение LibreRoute")
            }

        }.create().let { dialogs.show(it, "showApproveAdminRequestDialog", Bundle()) }
    }

    private fun shareAdminLink(link: String, title: String) {
        InvitationShareDialogFragment.show(supportFragmentManager, title, link)
    }

    private fun copyPendingJoinRequest() {
        val request = vm.pendingJoinRequestJson() ?: return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("LibreRoute signed admin request", request))
        Toast.makeText(this, "Подписанный запрос скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun showImportGrantDialog() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val grantInput = EditText(this).apply {
            hint = "JSON подписанного grant из SSH"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
        }
        val fingerprintInput = EditText(this).apply {
            hint = "SHA256: отпечаток из доверенной SSH-сессии"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        container.addView(grantInput)
        container.addView(fingerprintInput)
        AlertDialog.Builder(this)
            .setTitle("Подтверждение администратора")
            .setMessage("Сверьте отпечаток ключа с отдельной доверенной SSH-сессией к Netcraze.")
            .setView(container)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Проверить") { _, _ ->
                vm.importBootstrapGrant(grantInput.text.toString(), fingerprintInput.text.toString())
            }
            .create().let { dialogs.show(it, "showImportGrantDialog", Bundle()) }
    }

    private fun showPromptRecipientKeyDialog(profile: ManagedProfile) {
        val input = EditText(this).apply {
            hint = "Публичный ключ получателя (Base64)"
            setSingleLine(false)
            maxLines = 4
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        if (clipText != null && clipText.length in 40..256 && !clipText.contains(" ")) {
            input.setText(clipText)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Выдача приглашения: ${profile.name}")
            .setMessage("Для сквозного шифрования введите открытый ключ устройства получателя (из его профиля LibreRoute):")
            .setView(android.widget.FrameLayout(this).apply {
                val padding = (16 * resources.displayMetrics.density).toInt()
                setPadding(padding, padding / 2, padding, padding / 2)
                addView(input)
            })
            .setPositiveButton("Выдать") { _, _ ->
                val recipientKey = input.text.toString().trim()
                if (recipientKey.isBlank()) {
                    Toast.makeText(this, "Публичный ключ не может быть пустым", Toast.LENGTH_SHORT).show()
                } else {
                    vm.issueInvite(profile, recipientKey)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create().let { dialogs.show(it, "showPromptRecipientKeyDialog", Bundle().apply { putString("id", profile.id) }) }
    }

    private fun showInviteDialog(profile: ManagedProfile, inviteLink: String) {
        InvitationShareDialogFragment.show(supportFragmentManager,
            getString(R.string.invite_profile_label, profile.name), inviteLink)
    }

    private fun showCreateUserDialog() {
        if (!vm.canPerform("create-user")) return
        val padding = (20 * resources.displayMetrics.density).toInt()
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val name = TextInputEditText(this).apply {
            hint = "Имя пользователя"
            setSingleLine(true)
        }
        val document = TextInputEditText(this).apply {
            hint = "Ссылка на документ Яндекса (необязательно)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        form.addView(name)
        form.addView(document)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Добавить пользователя")
            .setMessage("Пользователь будет создан на сервере. Серверы и доступные протоколы настраиваются в его карточке.")
            .setView(form)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Добавить", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val userName = name.text?.toString()?.trim().orEmpty()
                val documentUrl = document.text?.toString()?.trim().orEmpty()
                if (userName.isBlank()) {
                    name.error = "Введите имя пользователя"
                    return@setOnClickListener
                }
                val validUrl = runCatching {
                    val url = java.net.URI(documentUrl)
                    url.scheme == "https" && url.userInfo == null && url.port == -1 &&
                        !url.path.isNullOrBlank() &&
                        (url.host == "yandex.ru" || url.host?.endsWith(".yandex.ru") == true)
                }.getOrDefault(false)
                if (documentUrl.isNotEmpty() && !validUrl) {
                    document.error = "Нужен HTTPS адрес документа Яндекса"
                    return@setOnClickListener
                }
                vm.createUser(userName, documentUrl)
                dialog.dismiss()
            }
        }
        dialogs.show(dialog, "showCreateUserDialog", Bundle())
    }

    private fun showSelectUserForAssignment() {
        val users = vm.users.value.filter { it.isActive }
        if (users.isEmpty()) {
            Toast.makeText(this, "Сначала добавьте пользователя", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this).setTitle("Настроить доступ пользователя")
            .setItems(users.map { it.name }.toTypedArray()) { _, index -> showPersonDetails(users[index]) }.create().let { dialogs.show(it, "showSelectUserForAssignment", Bundle()) }
    }

    private fun showAssignDocumentDialog(user: AdminUser) {
        val snapshot = vm.clusterSnapshot.value
        if (snapshot == null) {
            vm.refreshClusterSnapshot()
            Toast.makeText(this, "Дождитесь ответа сервера с доступными протоколами", Toast.LENGTH_LONG).show()
            return
        }
        val form = UserAccessForm(this, snapshot, user, vm.serverNodes.value,
            vm.assignments.value.filter { it.userId == user.id }.map { it.serverId }.toSet(),
            AdminRepository.getInstance(this).getPendingUserDocument(user.id).orEmpty(),
            onCreateLink = { routes ->
                val automaticJazz = routes.singleOrNull()?.takeIf {
                    it.transport == "jazz" && it.allocation_mode == "automatic" && it.client_channel_schema == 2
                }
                if (automaticJazz != null) vm.issuePortableJazzInvitation(user, automaticJazz.server_id)
                else vm.configureUserAccess(user, routes, null)
                dialogs.dismiss("access")
            },
            onAssignDevice = { routes, key ->
                vm.configureUserAccess(user, routes, key)
                dialogs.dismiss("access")
            },
            onChannelGroup = { serverId -> ChannelGroupInvitationDialogFragment.show(supportFragmentManager, user.id, serverId) })
        dialogs.show(form.dialog, "access", Bundle().apply { putString("id", user.id) })
    }

    private fun showServerAuthDialog() {
        val nodes = vm.serverNodes.value
        if (nodes.isEmpty()) {
            Toast.makeText(this, "Сначала добавьте SSH-узел", Toast.LENGTH_SHORT).show()
            return
        }
        fun open(node: ServerNode) {
            val profileId = vm.controlProfileForServer(node)
            if (profileId.isNullOrBlank()) {
                Toast.makeText(this, "Нет подтверждённых прав для управляющего документа узла", Toast.LENGTH_LONG).show()
                return
            }
            startActivity(android.content.Intent(this, RemoteAuthActivity::class.java).apply {
                putExtra(RemoteAuthActivity.EXTRA_TARGET_ID, node.id)
                putExtra(RemoteAuthActivity.EXTRA_PROFILE_ID, profileId)
            })
        }
        if (nodes.size == 1) open(nodes.first())
        else MaterialAlertDialogBuilder(this).setTitle("Авторизация Яндекса на сервере")
            .setItems(nodes.map { it.name }.toTypedArray()) { _, index -> open(nodes[index]) }.create().let { dialogs.show(it, "showServerAuthDialog", Bundle()) }
    }

    private fun showInstallServerDialog() {
        val nodes = vm.serverNodes.value
        if (nodes.isEmpty()) {
            Toast.makeText(this, "Сначала добавьте сервер", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this).setTitle("Настройка сервера")
            .setItems(nodes.map { it.name }.toTypedArray()) { _, index -> openServerSetup(nodes[index]) }.create().let { dialogs.show(it, "showInstallServerDialog", Bundle()) }
    }

    private fun openServerSetup(node: ServerNode) {
        startActivity(Intent(this, ServerSetupActivity::class.java).putExtra("server_id", node.id))
    }

    private fun showProbeServerDialog() {
        val nodes = vm.serverNodes.value
        if (nodes.isEmpty()) {
            Toast.makeText(this, "Сначала добавьте хотя бы один SSH-узел", Toast.LENGTH_SHORT).show()
            return
        }
        if (nodes.size == 1) {
            vm.probeServer(nodes.first())
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Опрос характеристик сервера")
            .setItems(nodes.map { "${it.name} (${it.host}:${it.port})" }.toTypedArray()) { _, index ->
                vm.probeServer(nodes[index])
            }
            .create().let { dialogs.show(it, "showProbeServerDialog", Bundle()) }
    }

    private fun showSelectAssignmentForInvitation() {
        val assignments = vm.assignments.value
        if (assignments.isEmpty()) {
            Toast.makeText(this, "Назначений пока нет. Сначала создайте пользователя и назначьте документ.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Выберите назначение")
            .setItems(assignments.map { assignment ->
                val userName = vm.users.value.firstOrNull { it.id == assignment.userId }?.name
                    ?: assignment.userId
                val revInfo = "рев. ${assignment.appliedRevision}"
                "$userName → ${assignment.transport} (${assignment.serverId}) · ${assignment.status} [$revInfo]"
            }.toTypedArray()) { _, index ->
                showAssignmentActionDialog(assignments[index])
            }
            .create().let { dialogs.show(it, "showSelectAssignmentForInvitation", Bundle()) }
    }

    private fun showAssignmentActionDialog(assignment: AdminAssignment) {
        val userName = vm.users.value.firstOrNull { it.id == assignment.userId }?.name ?: assignment.userId
        val options = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()

        if (assignment.status == AdminAssignment.STATUS_READY ||
            assignment.status == AdminAssignment.STATUS_INVITED ||
            assignment.status == AdminAssignment.STATUS_ACTIVE) {
            options.add("Сформировать приглашение (QR / ссылка)")
            actions.add { vm.issueAssignmentInvitation(assignment.id) }
        }

        if (assignment.status != AdminAssignment.STATUS_REVOKED) {
            options.add("Отозвать назначение")
            actions.add {
                confirmRevokeAssignment(assignment.id)
            }
        }

        options.add("Сверить с сервером")
        actions.add { vm.reconcileAssignments() }

        MaterialAlertDialogBuilder(this)
            .setTitle("Назначение: $userName")
            .setItems(options.toTypedArray()) { _, which ->
                actions[which].invoke()
            }
            .setNegativeButton("Отмена", null)
            .create().let { dialogs.show(it, "showAssignmentActionDialog", Bundle().apply { putString("id", assignment.id) }) }
    }

    private fun showAssignmentInviteDialog(assignmentId: String, inviteLink: String) {
        InvitationShareDialogFragment.show(supportFragmentManager,
            getString(R.string.invite_assignment_label, assignmentId.take(8)), inviteLink)
    }

    private fun showConfirmRevokeProfileDialog(profile: ManagedProfile) {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.dialog_title).text = getString(R.string.admin_confirm_revoke_title)
        view.findViewById<TextView>(R.id.dialog_message).text =
            "«${profile.name}»\n${getString(R.string.admin_confirm_revoke_message)}"

        val btnDelete = view.findViewById<TextView>(R.id.btn_delete)
        btnDelete.text = getString(R.string.admin_btn_revoke)

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        btnDelete.setOnClickListener {
            dialog.dismiss()
            vm.revokeProfile(profile.id)
        }

        dialogs.show(dialog, "showConfirmRevokeProfileDialog", Bundle().apply { putString("id", profile.id) })
        val width = (resources.displayMetrics.widthPixels * 0.88).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showConfirmRevokeRightsDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
        dialog.setContentView(view)

        view.findViewById<TextView>(R.id.dialog_title).text = getString(R.string.admin_confirm_revoke_rights_title)
        view.findViewById<TextView>(R.id.dialog_message).text = getString(R.string.admin_confirm_revoke_rights_message)

        val btnDelete = view.findViewById<TextView>(R.id.btn_delete)
        btnDelete.text = "Отозвать"

        view.findViewById<View>(R.id.btn_cancel).setOnClickListener { dialog.dismiss() }
        btnDelete.setOnClickListener {
            dialog.dismiss()
            vm.revokeAdminRights()
        }

        dialogs.show(dialog, "showConfirmRevokeRightsDialog", Bundle())
        val width = (resources.displayMetrics.widthPixels * 0.88).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showManageSshKeyDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = layoutInflater.inflate(R.layout.dialog_manage_ssh_key, null)
        dialog.setContentView(view)

        val layoutKeyExists = view.findViewById<View>(R.id.layout_key_exists)
        val layoutKeyMissing = view.findViewById<View>(R.id.layout_key_missing)
        val tvPubkey = view.findViewById<TextView>(R.id.tv_ssh_public_key_text)
        val btnCopy = view.findViewById<View>(R.id.btn_copy_ssh_pubkey)
        val btnGenerate = view.findViewById<View>(R.id.btn_generate_ssh_key)
        val btnImport = view.findViewById<View>(R.id.btn_import_ssh_key)
        val btnClose = view.findViewById<View>(R.id.btn_close_ssh_key_dialog)

        fun updateDialogState() {
            val pubKey = vm.adminSshPublicKey.value
            val hasKey = vm.hasAdminSshKey.value
            if (hasKey && !pubKey.isNullOrBlank()) {
                layoutKeyExists.visibility = View.VISIBLE
                layoutKeyMissing.visibility = View.GONE
                tvPubkey.text = pubKey
            } else {
                layoutKeyExists.visibility = View.GONE
                layoutKeyMissing.visibility = View.VISIBLE
            }
        }
        updateDialogState()

        btnCopy.setOnClickListener {
            val pub = vm.adminSshPublicKey.value.orEmpty()
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("OpenSSH Public Key", pub))
            Toast.makeText(this, "Открытый SSH-ключ скопирован в буфер", Toast.LENGTH_SHORT).show()
        }

        btnGenerate.setOnClickListener {
            vm.generateSshKeyPair()
            dialog.dismiss()
        }

        btnImport.setOnClickListener {
            dialog.dismiss()
            showImportSshKeyDialog()
        }

        btnClose.setOnClickListener { dialog.dismiss() }

        dialogs.show(dialog, "showManageSshKeyDialog", Bundle())
        val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun showImportSshKeyDialog() {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val keyInput = EditText(this).apply {
            hint = "-----BEGIN OPENSSH/RSA PRIVATE KEY-----\n..."
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 5
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 12f
        }
        val passInput = EditText(this).apply {
            hint = "Passphrase (если ключ зашифрован)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        container.addView(keyInput)
        container.addView(passInput)

        AlertDialog.Builder(this)
            .setTitle("Импорт приватного SSH-ключа")
            .setMessage("Вставьте текст приватного ключа в формате OpenSSH или PEM.")
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Импортировать") { _, _ ->
                val pem = keyInput.text.toString().trim()
                val pass = passInput.text.toString().trim().takeIf { it.isNotEmpty() }
                if (pem.isNotEmpty()) {
                    vm.importSshPrivateKey(pem, pass)
                } else {
                    Toast.makeText(this, "Ключ не может быть пустым", Toast.LENGTH_SHORT).show()
                }
            }
            .create().let { dialogs.show(it, "showImportSshKeyDialog", Bundle()) }
    }

    private fun showAddServerNodeDialog() {
        startActivity(Intent(this, AdminSetupActivity::class.java).putExtra("add_server", true))
    }

    private fun updateServerClientsCount() {
        if (!::serverNodesAdapter.isInitialized) return
        val assignments = vm.assignments.value
        val counts = assignments.filter { it.status != AdminAssignment.STATUS_REVOKED }
            .groupBy { it.serverId }.mapValues { (_, assigned) -> assigned.map { it.userId }.distinct().size }
        serverNodesAdapter.setServerClientsCount(counts)
    }

    private fun showServerNodeDetailsDialog(node: ServerNode) {
        val screen = io.github.libreroute.ui.CalmDetailDialog(this, "Детали сервера")
        screen.content.addView(io.github.libreroute.ui.CalmViews.row(this, node.name, node.status.displayName))
        screen.content.addView(io.github.libreroute.ui.CalmViews.row(this, "Ядро сервера", coreVersionSummary(node.coreObservation)))
        val assignments = vm.assignments.value.filter { it.serverId == node.id }
        screen.content.addView(io.github.libreroute.ui.CalmViews.row(this, "Назначенные пользователи", if (assignments.isEmpty()) "Нет назначений" else assignments.map { a -> vm.users.value.firstOrNull { it.id == a.userId }?.name ?: a.userId }.distinct().joinToString("\n")))
        screen.action("Проверить") { vm.probeServer(node); screen.dismiss() }
        screen.action("Открыть авторизацию") {
            val profileId = vm.controlProfileForServer(node)
            if (profileId.isNullOrBlank()) {
                Toast.makeText(this, "Нет прав для управляющего документа узла", Toast.LENGTH_LONG).show()
            } else {
                screen.dismiss()
                startActivity(Intent(this, RemoteAuthActivity::class.java).apply {
                    putExtra(RemoteAuthActivity.EXTRA_TARGET_ID, node.id)
                    putExtra(RemoteAuthActivity.EXTRA_PROFILE_ID, profileId)
                })
            }
        }
        screen.action("Настроить сервер") { screen.dismiss(); openServerSetup(node) }
        screen.action("Технические сведения") {
            val inv = node.inventory
            val message = buildString {
                appendLine("Адрес: ${node.host}:${node.port}")
                if (inv == null) append("Опрос ещё не проводился")
                else {
                    appendLine("Система: ${inv.osName} ${inv.osVersion}")
                    appendLine("Процессор: ${inv.cpuCores} · ${inv.arch}")
                    appendLine("Память: ${inv.ramFreeMb}/${inv.ramTotalMb} МБ свободно")
                    append("Диск: ${inv.diskFreeMb}/${inv.diskTotalMb} МБ свободно")
                }
            }
            showMessage("Технические сведения", message)
        }
        screen.action("Удалить сервер", destructive = true) {
            confirmDeleteServer(node.id)
        }
        dialogs.show(screen, "server", Bundle().apply { putString("id", node.id) })
    }

    private fun confirmDeleteUserFromServer(id: String, targetId: String? = null, authorityId: String? = null) {
        val user = vm.users.value.firstOrNull { it.id == id } ?: return
        val authority = authorityId ?: vm.currentServerNode()?.id ?: return
        val snapshot = vm.clusterSnapshot.value ?: return
        val servers = (snapshot.distinctServerIds + vm.assignments.value.filter { it.userId == id }.map { it.serverId })
            .filter { it !in user.removedServerIds }.sorted()
        if (targetId == null && servers.size > 1) {
            val labels = servers.map { sid -> "${vm.serverNodes.value.firstOrNull { it.id == sid }?.name ?: sid} ($sid)" }
            val dialog = MaterialAlertDialogBuilder(this).setTitle("Удалить доступ: выберите сервер")
                .setItems(labels.toTypedArray()) { _, index -> confirmDeleteUserFromServer(id, servers[index], authority) }
                .setNegativeButton("Отмена", null).create()
            dialogs.show(dialog, "delete_user_server", Bundle().apply { putString("id", id); putString("authority", authority) })
            return
        }
        val server = targetId ?: servers.singleOrNull() ?: return
        if (server !in servers) return
        val name = vm.serverNodes.value.firstOrNull { it.id == server }?.name ?: server
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Удалить пользователя с сервера?")
            .setMessage("Удалить доступ «${user.name}» только к серверу «$name» ($server)?\n\nМаршруты и приглашения этого сервера будут отозваны. Приглашение сразу на несколько серверов потребуется выдать заново. Уже полученные доступы к другим серверам сохранятся.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить с сервера") { _, _ ->
                vm.deleteUserFromServer(id, server, authority); dialogs.dismiss("person")
            }.create()
        dialogs.show(dialog, "delete_user_server", Bundle().apply {
            putString("id", id); putString("server", server); putString("authority", authority)
        })
    }

    private fun confirmSuspendUser(id: String) {
        val user = vm.users.value.firstOrNull { it.id == id } ?: return
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Приостановить доступ?")
            .setMessage("Пользователь «${user.name}» потеряет доступ к назначенным маршрутам.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Приостановить") { _, _ -> vm.suspendUser(id); dialogs.dismiss("person") }.create()
        dialogs.show(dialog, "suspend_user", Bundle().apply { putString("id", id) })
    }

    private fun confirmRevokeAssignment(id: String) {
        val assignment = vm.assignments.value.firstOrNull { it.id == id } ?: return
        val userName = vm.users.value.firstOrNull { it.id == assignment.userId }?.name ?: assignment.userId
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Отозвать доступ?")
            .setMessage("Отозвать маршрут ${assignment.transport} для пользователя $userName на сервере ${assignment.serverId}?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Отозвать") { _, _ -> vm.revokeAssignment(id) }.create()
        dialogs.show(dialog, "revoke_assignment", Bundle().apply { putString("id", id) })
    }

    private fun confirmDeleteServer(id: String) {
        val node = vm.serverNodes.value.firstOrNull { it.id == id } ?: return
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Удалить сервер?")
            .setMessage("Удалить «${node.name}» из списка серверов?")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ -> vm.removeServerNode(id); dialogs.dismiss("server") }.create()
        dialogs.show(dialog, "delete_server", Bundle().apply { putString("id", id) })
    }
}
