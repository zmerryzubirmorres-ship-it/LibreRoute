package io.github.libreroute.admin

import android.app.Dialog
import android.text.InputType
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.core.content.FileProvider
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
import io.github.libreroute.util.QrGenerator
import io.github.libreroute.util.ThemePreferences
import io.github.libreroute.util.performAppHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class AdminActivity : AppCompatActivity() {

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
    private var restoredOperationShown = false
    private var adminTab = R.id.nav_overview

    private fun setupCalmAdminNavigation(saved: Bundle?) {
        val nav = findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(R.id.adminNavigation)
        nav.setOnItemSelectedListener {
            adminTab = it.itemId
            findViewById<View>(R.id.adminOverview).visibility = if (adminTab == R.id.nav_overview) View.VISIBLE else View.GONE
            layoutTabUsers.visibility = if (adminTab == R.id.nav_people) View.VISIBLE else View.GONE
            layoutTabServers.visibility = if (adminTab == R.id.nav_servers) View.VISIBLE else View.GONE
            true
        }
        nav.selectedItemId = saved?.getInt("admin_tab", R.id.nav_overview) ?: R.id.nav_overview
        findViewById<View>(R.id.adminReturnUser).setOnClickListener { finish() }
        findViewById<View>(R.id.overviewInvite).setOnClickListener { showCreateUserDialog() }
        findViewById<View>(R.id.overviewAddServer).setOnClickListener { showAddServerNodeDialog() }
        findViewById<View>(R.id.adminAdvancedToggle).setOnClickListener {
            val advanced = findViewById<View>(R.id.adminOverviewAdvanced)
            advanced.visibility = if (advanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("admin_tab", adminTab)
    }

    private fun renderOverviewCounts() {
        val count = findViewById<TextView>(R.id.adminOverviewCounts)
        val nodes = vm.serverNodes.value
        count.text = "Люди: ${vm.users.value.size} · Серверы: ${nodes.size}\n" +
            when {
                nodes.isEmpty() -> "Добавьте первый сервер, затем пригласите человека"
                nodes.any { it.status == ServerNodeStatus.OFFLINE } -> "Есть недоступные серверы. Откройте раздел «Серверы»"
                nodes.any { it.status == ServerNodeStatus.UNKNOWN } -> "Часть серверов ещё не проверена"
                else -> "Состояние серверов доступно в разделе «Серверы»"
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
        filtered.forEach { user ->
            val status = when {
                user.isRevoked -> "Доступ отозван"
                user.isSuspended -> "Доступ приостановлен"
                vm.hasPendingUserDocument(user.id) -> "Ожидаем ключ устройства"
                else -> "Активен"
            }
            rows.addView(io.github.libreroute.ui.CalmViews.row(this, user.name, status) { showPersonDetails(user) })
        }
        findViewById<TextView>(R.id.tv_people_filter_status).text = if (filtered.isEmpty()) {
            if (vm.users.value.isEmpty()) "Пользователи ещё не загружены" else "По заданному фильтру ничего не найдено"
        } else "Показано: ${filtered.size} из ${vm.users.value.size}"
        renderOverviewCounts()
    }

    private fun setupPeopleSearch() {
        peopleSearch = findViewById(R.id.adminPeopleSearch)
        findViewById<MaterialButtonToggleGroup>(R.id.peopleFilters).check(R.id.btn_people_all)
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

    private fun protocolLabel(transport: String): String = when (transport) {
        "vyandex" -> "Документ Яндекса"
        "mqtt" -> "MQTT · WSS"
        "jitsi" -> "Jitsi Meet"
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
        fun render() {
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
            if (assignments.isEmpty()) screen.text("Доступ ещё не назначен. Настройте серверы и выдайте приглашение")
            assignments.forEach { assignment ->
                val name = vm.serverNodes.value.firstOrNull { it.id == assignment.serverId }?.name ?: assignment.serverId
                screen.content.addView(io.github.libreroute.ui.CalmViews.row(this, name,
                    "${protocolLabel(assignment.transport)} · ${assignmentStatusLabel(assignment.status)}") { showAssignmentActionDialog(assignment) })
            }
            if (user.isActive) screen.action("Настроить доступ") { screen.dismiss(); showAssignDocumentDialog(user) }
            if (user.isActive) screen.action("Приостановить доступ", destructive = true) {
                MaterialAlertDialogBuilder(this).setTitle("Приостановить доступ?")
                    .setMessage("Пользователь «${user.name}» потеряет доступ к назначенным маршрутам.")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Приостановить") { _, _ -> vm.suspendUser(user.id); screen.dismiss() }.show()
            }
        }
        render()
        screen.show()
        val updates = lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(vm.clusterSnapshot, vm.assignments, vm.serverNodes, vm.clusterSnapshotStatus) { _, _, _, _ -> Unit }
                .collect { if (screen.isShowing) render() }
        }
        screen.setOnDismissListener { updates.cancel() }
        vm.refreshClusterSnapshot()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        setupPeopleSearch()
        setupTopBar()
        setupChannelSelector()
        setupAdapters()
        setupCalmAdminNavigation(savedInstanceState)
        observeViewModel()
        vm.refreshServerProfiles()
        vm.refreshUsers()
        vm.refreshAssignments()
        intent.getStringExtra("admin_transfer_ack")?.let { showCompleteOwnerTransferDialog(it) }
    }

    override fun onResume() {
        super.onResume()
        refreshLocalProfiles()
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
        toggleAdminTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_tab_servers -> {
                    layoutTabServers.visibility = View.VISIBLE
                    layoutTabUsers.visibility = View.GONE
                }
                R.id.btn_tab_users -> {
                    layoutTabServers.visibility = View.GONE
                    layoutTabUsers.visibility = View.VISIBLE
                    vm.refreshUsers()
                    vm.refreshAssignments()
                    vm.refreshClusterSnapshot()
                }
            }
        }

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
            finish()
        }
        findViewById<View>(R.id.btn_refresh).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            vm.refreshServerProfiles()
            vm.refreshUsers()
            vm.refreshAssignments()
            refreshLocalProfiles()
        }
    }

    private fun setupChannelSelector() {
        toggleChannel.check(R.id.btn_channel_doc)
        toggleChannel.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_channel_doc -> vm.selectChannel(AdminChannel.DOCUMENT)
                R.id.btn_channel_vds -> vm.selectChannel(AdminChannel.SSH_VDS)
                R.id.btn_channel_netcraze -> vm.selectChannel(AdminChannel.SSH_NETCRAZE)
            }
            vm.refreshAssignments()
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
                val userName = vm.users.value.firstOrNull { it.id == assignment.userId }?.name ?: assignment.userId
                MaterialAlertDialogBuilder(this)
                    .setTitle("Отозвать доступ?")
                    .setMessage("Отозвать маршрут ${assignment.transport} для пользователя $userName на сервере ${assignment.serverId}?")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Отозвать") { _, _ ->
                        vm.revokeAssignment(assignment.id)
                    }
                    .show()
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
                        tvEmptyHistory.visibility = if (history.isEmpty()) View.VISIBLE else View.GONE
                        rvCommandHistory.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
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
                        findViewById<MaterialButton>(R.id.btn_tab_servers).text = "🖥 Серверы (${nodes.size})"
                        val activeCount = nodes.count { it.status == ServerNodeStatus.ONLINE }
                        tvClusterServersSummary.text = if (nodes.isEmpty()) "0 подключено" else "$activeCount/${nodes.size} подключено"
                        tvEmptyServers.visibility = if (nodes.isEmpty()) View.VISIBLE else View.GONE
                        rvServerNodes.visibility = if (nodes.isEmpty()) View.GONE else View.VISIBLE
                        serverNodesAdapter.submitList(nodes)
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
                            val allReady = snapshot.isComplete && snapshot.perServerStatus.isNotEmpty() && snapshot.perServerStatus.values.all { it.isApplied && it.isReady }
                            if (allReady) {
                                cardClusterHealth.setCardBackgroundColor(android.graphics.Color.parseColor("#1A22C55E"))
                                cardClusterHealth.strokeColor = android.graphics.Color.parseColor("#3322C55E")
                                iconClusterHealth.setImageResource(R.drawable.ic_check)
                                iconClusterHealth.setColorFilter(ContextCompat.getColor(this@AdminActivity, R.color.colorSuccess))
                                tvClusterSnapshotSummary.setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.colorSuccess))
                                tvClusterSnapshotSummary.text = "Снимок кластера: Ревизия #${snapshot.revision} (Все узлы согласованы ✓)"
                            } else {
                                cardClusterHealth.setCardBackgroundColor(android.graphics.Color.parseColor("#1AF59E0B"))
                                cardClusterHealth.strokeColor = android.graphics.Color.parseColor("#33F59E0B")
                                iconClusterHealth.setImageResource(R.drawable.ic_refresh)
                                iconClusterHealth.setColorFilter(ContextCompat.getColor(this@AdminActivity, R.color.state_connecting))
                                tvClusterSnapshotSummary.setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.state_connecting))
                                tvClusterSnapshotSummary.text = "Снимок кластера: Ревизия #${snapshot.revision} (ожидает синхронизации узлов)"
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
                        if (vm.clusterSnapshot.value == null) {
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
                    vm.selectedChannel.collect { updateCreateAvailability() }
                }
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
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(rows.ifBlank { operation.error ?: "Нет данных" })
            .setPositiveButton("Закрыть", null)
            .show()
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
        updateCreateAvailability()
    }

    private fun updateCreateAvailability() {
        if (!::btnAddProfile.isInitialized || !::btnActionPrimary.isInitialized) return
        val supported = vm.canPerform("create-user")
        btnAddProfile.isEnabled = supported
        if (vm.role.value == AdminRole.OWNER || vm.role.value == AdminRole.OPERATOR) {
            btnActionPrimary.isEnabled = supported
            btnActionPrimary.text = if (supported) "Добавить пользователя"
                else "Недостаточно прав на выбранном сервере"
        }
    }

    private fun showAdminDelegationActions() {
        val actions = arrayOf("Создать приглашение для устройства", "Одобрить запрос устройства", "Завершить передачу владения", "Управлять администраторами")
        MaterialAlertDialogBuilder(this).setTitle("Доступ администратора")
            .setItems(actions) { _, which -> when (which) { 0 -> showAdminInvitationTemplateDialog(); 1 -> showApproveAdminRequestDialog(); 2 -> showCompleteOwnerTransferDialog(); else -> showAdministratorsDialog() } }
            .show()
    }

    private fun showAdministratorsDialog(selectedNode: ServerNode? = null) {
        val nodes = vm.serverNodes.value
        if (nodes.isEmpty()) { Toast.makeText(this, "Нет сервера для списка администраторов", Toast.LENGTH_LONG).show(); return }
        val node = selectedNode ?: nodes.firstOrNull { it.id == vm.selectedServerNodeId.value } ?: nodes.singleOrNull()
        if (node == null) {
            MaterialAlertDialogBuilder(this).setTitle("Выберите сервер").setItems(nodes.map { it.name }.toTypedArray()) { _, index -> showAdministratorsDialog(nodes[index]) }.show()
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { AdminRepository.getInstance(this@AdminActivity).listAdministrators(AdminChannel.DOCUMENT, node) }
            result.onFailure { Toast.makeText(this@AdminActivity, it.message ?: "Не удалось загрузить список", Toast.LENGTH_LONG).show() }
                .onSuccess { admins ->
                    val labels = admins.map { "${it.role} · ${it.deviceId}\n${it.status}" }.toTypedArray()
                    if (labels.isEmpty()) { Toast.makeText(this@AdminActivity, "Администраторов нет", Toast.LENGTH_LONG).show(); return@onSuccess }
                    MaterialAlertDialogBuilder(this@AdminActivity).setTitle("Администраторы")
                        .setItems(labels) { _, index ->
                            val admin = admins[index]
                            MaterialAlertDialogBuilder(this@AdminActivity).setTitle("Отозвать доступ?")
                                .setMessage("${admin.role}\n${admin.deviceId}").setNegativeButton("Отмена", null)
                                .setPositiveButton("Отозвать") { _, _ -> lifecycleScope.launch {
                                    val revoke = withContext(Dispatchers.IO) { AdminRepository.getInstance(this@AdminActivity).revokeAdministrator(admin.deviceId, node) }
                                    revoke.onSuccess { Toast.makeText(this@AdminActivity, "Доступ отозван", Toast.LENGTH_LONG).show() }
                                        .onFailure { Toast.makeText(this@AdminActivity, it.message ?: "Не удалось отозвать доступ", Toast.LENGTH_LONG).show() }
                                } }.show()
                        }.show()
                }
        }
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
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) { AdminRepository.getInstance(this@AdminActivity).completeOwnerTransfer(ack.text.toString().trim(), node) }
                    result.onSuccess { Toast.makeText(this@AdminActivity, "Передача владения завершена", Toast.LENGTH_LONG).show() }
                        .onFailure { Toast.makeText(this@AdminActivity, it.message ?: "Подтверждение не принято", Toast.LENGTH_LONG).show() }
                }
            }.show()
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
                lifecycleScope.launch {
                    val (result, link) = withContext(Dispatchers.IO) { AdminRepository.getInstance(this@AdminActivity).issueAdminInvitationTemplate(selectedRole, node, AdminChannel.DOCUMENT) }
                    if (result is AdminOpResult.Failure) Toast.makeText(this@AdminActivity, result.error, Toast.LENGTH_LONG).show()
                    else if (!link.isNullOrBlank()) shareAdminLink(link, "Приглашение LibreRoute")
                }
            }.show()
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
            lifecycleScope.launch {
                val (result, link) = withContext(Dispatchers.IO) { AdminRepository.getInstance(this@AdminActivity).approveAdminRequest(request.text.toString().trim(), selectedRole, node, AdminChannel.DOCUMENT, transfer.isChecked) }
                if (result is AdminOpResult.Failure) Toast.makeText(this@AdminActivity, result.error, Toast.LENGTH_LONG).show()
                else if (!link.isNullOrBlank()) shareAdminLink(link, "Подтверждение LibreRoute")
            }
        }.show()
    }

    private fun shareAdminLink(link: String, title: String) {
        shareInvite(title, link, QrGenerator.generateQrBitmap(link, 480))
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
            .show()
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
            .show()
    }

    private fun showInviteDialog(profile: ManagedProfile, inviteLink: String) {
        val qrBitmap = QrGenerator.generateQrBitmap(inviteLink, 480)

        val dialog = Dialog(this, R.style.Theme_SoxMax)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = layoutInflater.inflate(R.layout.dialog_issue_invitation, null)
        val scroll = android.widget.ScrollView(this).apply { addView(view) }
        dialog.setContentView(scroll)

        view.findViewById<TextView>(R.id.tv_invite_profile_name).text = "Для профиля: ${profile.name}"
        val imgQr = view.findViewById<ImageView>(R.id.img_invite_qr)
        if (qrBitmap != null) {
            imgQr.setImageBitmap(qrBitmap)
        }
        val tvLink = view.findViewById<TextView>(R.id.tv_invite_link)
        tvLink.text = "Приглашение готово · нажмите, чтобы показать ссылку"
        tvLink.setOnClickListener { tvLink.text = if (tvLink.text == inviteLink) "Приглашение готово · нажмите, чтобы показать ссылку" else inviteLink }

        view.findViewById<View>(R.id.btn_close_invite).setOnClickListener { dialog.dismiss() }

        view.findViewById<View>(R.id.btn_copy_invite).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LibreRoute Invite", inviteLink))
            Toast.makeText(this, "Приглашение скопировано", Toast.LENGTH_SHORT).show()
        }

        view.findViewById<View>(R.id.btn_share_invite).setOnClickListener {
            shareInvite(profile.name, inviteLink, qrBitmap)
        }

        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun showCreateUserDialog() {
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
        dialog.show()
    }

    private fun showSelectUserForAssignment() {
        val users = vm.users.value.filter { it.isActive }
        if (users.isEmpty()) {
            Toast.makeText(this, "Сначала добавьте пользователя", Toast.LENGTH_LONG).show()
            return
        }
        MaterialAlertDialogBuilder(this).setTitle("Настроить доступ пользователя")
            .setItems(users.map { it.name }.toTypedArray()) { _, index -> showPersonDetails(users[index]) }.show()
    }

    private fun showAssignDocumentDialog(user: AdminUser) {
        val snapshot = vm.clusterSnapshot.value
        if (snapshot == null) {
            vm.refreshClusterSnapshot()
            Toast.makeText(this, "Дождитесь ответа сервера с доступными протоколами", Toast.LENGTH_LONG).show()
            return
        }
        val screen = io.github.libreroute.ui.CalmDetailDialog(this, "Доступ: ${user.name}")
        screen.text("Выберите серверы для приглашения или добавления маршрутов устройству. Доступные протоколы настраиваются автоматически. Ссылку на документ или комнату можно добавить позже; MQTT-канал создаст сервер.")
        val selectedServers = mutableSetOf<String>()
        val fields = mutableMapOf<Pair<String, String>, TextInputEditText>()
        val assignedServers = vm.assignments.value.filter { it.userId == user.id }.map { it.serverId }.toSet()
        snapshot.distinctServerIds.sorted().forEach { serverId ->
            val methods = snapshot.methodsFor(serverId).filter { it.transport in setOf("vyandex", "mqtt", "jitsi") }
            val name = vm.serverNodes.value.firstOrNull { it.id == serverId }?.name ?: serverId
            val options = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val checkbox = MaterialCheckBox(this).apply {
                text = name
                isEnabled = methods.isNotEmpty()
                isChecked = isEnabled && (snapshot.distinctServerIds.size == 1 || serverId in assignedServers)
                if (isChecked) selectedServers.add(serverId)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedServers.add(serverId) else selectedServers.remove(serverId)
                    options.visibility = if (checked) View.VISIBLE else View.GONE
                }
            }
            screen.content.addView(checkbox)
            options.visibility = if (checkbox.isChecked) View.VISIBLE else View.GONE
            options.addView(TextView(this).apply {
                text = methods.joinToString(" · ") { protocolLabel(it.transport) }.ifEmpty { "Протоколы не подтверждены" }
                setTextColor(ContextCompat.getColor(this@AdminActivity, R.color.text_secondary))
            })
            methods.filter { it.endpointRequired }.forEach { method ->
                val input = TextInputEditText(this).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setSingleLine(true)
                    if (method.transport == "vyandex" && snapshot.distinctServerIds.size == 1)
                        setText(AdminRepository.getInstance(this@AdminActivity).getPendingUserDocument(user.id).orEmpty())
                }
                options.addView(TextInputLayout(this).apply {
                    hint = when (method.transport) {
                        "vyandex" -> "Ссылка на отдельный документ"
                        "jitsi" -> "Ссылка на комнату организатора"
                        else -> "Ссылка на отдельный канал"
                    }
                    addView(input)
                })
                fields[serverId to method.transport] = input
            }
            screen.content.addView(options)
        }
        fun routes(): List<EnrollmentRoute>? {
            val links = fields.mapValues { it.value.text?.toString()?.trim().orEmpty() }
            fields.values.forEach { it.error = null }
            for ((key, value) in links) {
                if (key.first !in selectedServers || value.isEmpty()) continue
                if (!AutomaticAccessPlan.validLink(key.second, value)) {
                    fields[key]?.error = "Проверьте ссылку на отдельный документ или комнату"
                    return null
                }
            }
            return runCatching { AutomaticAccessPlan.build(snapshot, selectedServers, links) }.getOrElse {
                Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                null
            }
        }
        screen.action("Создать приглашение QR для нового телефона") {
            val plan = routes() ?: return@action
            vm.configureUserAccess(user, plan, null)
            screen.dismiss()
        }
        val key = TextInputEditText(this).apply {
            hint = "Публичный ключ уже известного устройства"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
        }
        screen.text("Для уже зарегистрированного телефона можно назначить доступ по его публичному ключу")
        screen.content.addView(key)
        screen.action("Назначить устройству", secondary = true) {
            val publicKey = key.text?.toString()?.trim().orEmpty()
            if (publicKey.isEmpty()) { key.error = "Введите публичный ключ или создайте QR выше"; return@action }
            val plan = routes() ?: return@action
            vm.configureUserAccess(user, plan, publicKey)
            screen.dismiss()
        }
        screen.show()
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
            .setItems(nodes.map { it.name }.toTypedArray()) { _, index -> open(nodes[index]) }.show()
    }

    private fun showInstallServerDialog() {
        val nodes = vm.serverNodes.value
        if (nodes.isEmpty()) {
            Toast.makeText(this, "Сначала добавьте сервер", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this).setTitle("Настройка сервера")
            .setItems(nodes.map { it.name }.toTypedArray()) { _, index -> openServerSetup(nodes[index]) }.show()
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
            .show()
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
            .show()
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
                MaterialAlertDialogBuilder(this)
                    .setTitle("Отозвать доступ?")
                    .setMessage("Отозвать маршрут ${assignment.transport} для пользователя $userName на сервере ${assignment.serverId}?")
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Отозвать") { _, _ ->
                        vm.revokeAssignment(assignment.id)
                    }
                    .show()
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
            .show()
    }

    private fun showAssignmentInviteDialog(assignmentId: String, inviteLink: String) {
        val qrBitmap = QrGenerator.generateQrBitmap(inviteLink, 480)
        val dialog = Dialog(this, R.style.Theme_SoxMax)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val view = layoutInflater.inflate(R.layout.dialog_issue_invitation, null)
        val scroll = android.widget.ScrollView(this).apply { addView(view) }
        dialog.setContentView(scroll)

        view.findViewById<TextView>(R.id.tv_invite_profile_name).text = "Назначение: ${assignmentId.take(8)}"
        val imgQr = view.findViewById<ImageView>(R.id.img_invite_qr)
        if (qrBitmap != null) {
            imgQr.setImageBitmap(qrBitmap)
        }
        val tvLink = view.findViewById<TextView>(R.id.tv_invite_link)
        tvLink.text = "Приглашение готово · нажмите, чтобы показать ссылку"
        tvLink.setOnClickListener { tvLink.text = if (tvLink.text == inviteLink) "Приглашение готово · нажмите, чтобы показать ссылку" else inviteLink }

        view.findViewById<View>(R.id.btn_close_invite).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btn_copy_invite).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LibreRoute Invite", inviteLink))
            Toast.makeText(this, "Приглашение скопировано", Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btn_share_invite).setOnClickListener {
            shareInvite("Доступ LibreRoute", inviteLink, qrBitmap)
        }
        dialog.show()
        val width = (resources.displayMetrics.widthPixels * 0.92).toInt()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun shareInvite(profileName: String, inviteLink: String, qrBitmap: Bitmap?) {
        val appContext = applicationContext
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "LibreRoute: $profileName")
                    putExtra(Intent.EXTRA_TEXT, inviteLink)
                }

                if (qrBitmap != null) {
                    val imagesDir = File(appContext.cacheDir, "shared_images").apply { mkdirs() }
                    val imageFile = File(imagesDir, "invite_${System.currentTimeMillis()}.png")
                    FileOutputStream(imageFile).use { fos ->
                        qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    val uri = FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        imageFile
                    )
                    intent.type = "image/png"
                    intent.putExtra(Intent.EXTRA_STREAM, uri)
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                val chooser = Intent.createChooser(intent, "Поделиться приглашением").apply {
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    appContext.startActivity(chooser)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, "Ошибка отправки: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
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

        dialog.show()
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

        dialog.show()
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

        dialog.show()
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
            .show()
    }

    private fun showAddServerNodeDialog() {
        startActivity(Intent(this, AdminSetupActivity::class.java).putExtra("add_server", true))
    }

    private fun updateServerClientsCount() {
        if (!::serverNodesAdapter.isInitialized) return
        val assignments = vm.assignments.value
        val counts = mutableMapOf<String, Int>()
        for (a in assignments) {
            if (a.status != AdminAssignment.STATUS_REVOKED) {
                counts[a.serverId] = (counts[a.serverId] ?: 0) + 1
            }
        }
        serverNodesAdapter.setServerClientsCount(counts)
    }

    private fun showServerNodeDetailsDialog(node: ServerNode) {
        val screen = io.github.libreroute.ui.CalmDetailDialog(this, "Детали сервера")
        screen.content.addView(io.github.libreroute.ui.CalmViews.row(this, node.name, node.status.displayName))
        screen.content.addView(io.github.libreroute.ui.CalmViews.row(this, "Ядро сервера", coreVersionSummary(node.coreObservation)))
        val assignments = vm.assignments.value.filter { it.serverId == node.id }
        screen.content.addView(io.github.libreroute.ui.CalmViews.row(this, "Назначенные люди", if (assignments.isEmpty()) "Нет назначений" else assignments.map { a -> vm.users.value.firstOrNull { it.id == a.userId }?.name ?: a.userId }.distinct().joinToString("\n")))
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
            MaterialAlertDialogBuilder(this).setTitle("Технические сведения").setMessage(message).setPositiveButton("Закрыть", null).show()
        }
        screen.action("Удалить сервер", destructive = true) {
            MaterialAlertDialogBuilder(this).setTitle("Удалить сервер?")
                .setMessage("Удалить «${node.name}» из списка серверов?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить") { _, _ -> vm.removeServerNode(node.id); screen.dismiss() }.show()
        }
        screen.show()
    }
}
