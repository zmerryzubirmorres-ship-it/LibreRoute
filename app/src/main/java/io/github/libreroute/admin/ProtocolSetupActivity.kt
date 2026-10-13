package io.github.libreroute.admin

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModelProvider
import com.google.android.material.button.MaterialButton
import io.github.libreroute.ui.CalmViews
import io.github.libreroute.util.JitsiBrowserHandoff
import io.github.libreroute.util.JitsiTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One resumable operation per server transport. */
class ProtocolSetupActivity : AppCompatActivity() {
    private val scrollPosition by lazy { io.github.libreroute.ui.ScreenScrollPosition(this, content.parent as ScrollView) }
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val endpointInputs = mutableMapOf<String, EditText>()
    private val repository by lazy { AdminRepository.getInstance(this) }
    private val nodeId by lazy { intent.getStringExtra(EXTRA_SERVER_ID).orEmpty() }
    private var pendingAuthProtocol: String? = null
    private var pendingAuthEndpoint: String = ""
    /** A plain room handoff is valid when the provider admits guests after the host. */
    private var confirmedJitsiRoom: String = ""
    private val operation by viewModels<io.github.libreroute.ui.ScreenOperationViewModel>()
    private val busy get() = operation.state.value.running
    private data class ProtocolOutcome(val protocol: String, val endpoint: String, val result: AdminOpResult)
    private val dialogs by lazy { io.github.libreroute.ui.DialogScreens(this) { key, args ->
        if (key == "group") repository.getServerNode(nodeId)?.let(::showChannelGroupDialog)
        if (key == "clear") confirmClear()
        if (key == "yandex_choose") showGroupDocuments(args.getStringArrayList("documents").orEmpty())
        if (key == "auth_request") handleProtocolOutcome(ProtocolOutcome(args.getString("protocol").orEmpty(), args.getString("endpoint").orEmpty(), AdminOpResult.Failure(args.getString("error").orEmpty())))
    } }
    private var lastOutcome = ""
    private var channelGroupDialog: AlertDialog? = null
    private var localSnapshot: ProtocolRestoreViewModel.LocalState? = null
    private val endpointDrafts = mutableMapOf<String, String>()
    private val jazzSetup by lazy { ViewModelProvider(this)[JazzRoomSetupViewModel::class.java] }
    private val protocolRestore by lazy { ViewModelProvider(this)[ProtocolRestoreViewModel::class.java] }
    private val groupSetup by lazy { ViewModelProvider(this)[ChannelGroupSetupViewModel::class.java] }
    private fun isBusy() = busy || jazzSetup.state.value.running || groupSetup.state.value.running
    private val groupAuthLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        lastOutcome = if (result.resultCode == RESULT_OK) "Документ Яндекса проверен. Можно проверить следующий документ или настроить объединение." else "Проверка документа не завершена. Повторите её после входа или CAPTCHA."
        if (::status.isInitialized) status.text = lastOutcome
        repository.getServerNode(nodeId)?.let { if (!isBusy()) showChannelGroupDialog(it) }
    }
    private val authLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val protocol = pendingAuthProtocol
        pendingAuthProtocol = null
        if (result.resultCode == RESULT_OK && protocol != null) {
            if (protocol == "jitsi" && JitsiTokenStore(this).get(pendingAuthEndpoint) == null) {
                // The provider may not issue a JWT at all. In that mode the host
                // browser is the moderator and must remain open while the worker joins.
                confirmedJitsiRoom = pendingAuthEndpoint
            }
            val node = repository.getServerNode(nodeId)
            if (node != null) install(node, protocol, pendingAuthEndpoint)
        } else {
            lastOutcome = if (protocol == "jitsi") "Вход в Jitsi не завершён. Откройте мастер повторно." else "Проверка Yandex отменена. Повторите её после входа или CAPTCHA."
            if (::status.isInitialized) status.text = lastOutcome
        }
        pendingAuthEndpoint = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("group_draft")?.let { groupSetup.draft = it }
        pendingAuthProtocol = savedInstanceState?.getString("pending_auth_protocol")
        pendingAuthEndpoint = savedInstanceState?.getString("pending_auth_endpoint").orEmpty()
        confirmedJitsiRoom = savedInstanceState?.getString("confirmed_jitsi_room").orEmpty()
        lastOutcome = savedInstanceState?.getString("last_outcome").orEmpty()
        for (id in listOf("vyandex", "jazz", "jitsi")) {
            savedInstanceState?.getString("endpoint_$id")?.let { endpointDrafts[id] = it }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(MaterialButton(this).apply {
            text = "‹"; contentDescription = "Назад"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(56.dp(), 56.dp()))
        header.addView(TextView(this).apply { text = "Протоколы сервера"; textSize = 22f })
        root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp(), 0, 16.dp(), 24.dp()) }
        root.addView(ScrollView(this).apply { id = io.github.libreroute.ui.DialogScreens.stateId("protocol-setup-scroll") }.apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        status = TextView(this).apply { text = "Загружаем сохранённые настройки…" }
        content.addView(status)
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                operation.state.collect { state ->
                    state.result?.let { result ->
                        operation.consumed()
                        result.onSuccess { value ->
                            if (value is ProtocolOutcome) handleProtocolOutcome(value)
                            else lastOutcome = value.toString()
                        }.onFailure { lastOutcome = it.message ?: "Операция не завершена. Проверьте результат." }
                        render()
                    }
                    refreshSetupEnabled()
                }
            }
        }
        localSnapshot = protocolRestore.cached(nodeId)
        localSnapshot?.let { renderSnapshot() }
        lifecycleScope.launch {
            protocolRestore.local.collect { snapshot ->
                if (snapshot != null) {
                    localSnapshot = snapshot
                    if (snapshot.node == null || "list" !in snapshot.permissions) { finish(); return@collect }
                    renderSnapshot()
                    if ("get-protocol-configurations" in snapshot.permissions) protocolRestore.restore(snapshot.node)
                }
            }
        }
        lifecycleScope.launch {
            protocolRestore.state.collect { state ->
                if (state.outcome.isNotBlank()) {
                    lastOutcome = state.outcome
                    if (!state.running) render() else status.text = lastOutcome
                }
                refreshSetupEnabled()
            }
        }
        lifecycleScope.launch {
            jazzSetup.state.collect { state ->
                if (state.outcome.isNotBlank()) {
                    lastOutcome = state.outcome
                    status.text = lastOutcome
                    if (!state.running) render()
                }
                refreshSetupEnabled()
            }
        }
        lifecycleScope.launch {
            groupSetup.state.collect { state ->
                if (state.outcome.isNotBlank()) {
                    lastOutcome = state.outcome
                    if (::status.isInitialized) status.text = lastOutcome
                    if (!state.running) render()
                }
                refreshSetupEnabled()
            }
        }
        dialogs.restore()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("group_draft", groupSetup.draft)
        outState.putString("pending_auth_protocol", pendingAuthProtocol)
        outState.putString("pending_auth_endpoint", pendingAuthEndpoint)
        outState.putString("confirmed_jitsi_room", confirmedJitsiRoom)
        outState.putString("last_outcome", lastOutcome)
        endpointInputs.forEach { (id, input) -> endpointDrafts[id] = input.text.toString() }
        endpointDrafts.forEach { (id, value) -> outState.putString("endpoint_$id", value) }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized) render() }

    private fun render() {
        if (::status.isInitialized) status.text = lastOutcome
        if (::content.isInitialized) refreshSetupEnabled()
        protocolRestore.loadLocal(nodeId)
    }

    private fun renderSnapshot() {
        val snapshot = localSnapshot ?: return
        scrollPosition.beforeRebuild()
        endpointInputs.forEach { (id, input) -> endpointDrafts[id] = input.text.toString() }
        content.removeAllViews(); endpointInputs.clear()
        val node = snapshot.node ?: run { finish(); return }
        content.addView(MaterialButton(this).apply {
            text = "Объединить каналы"; isAllCaps = false; tag = "local"
            setOnClickListener { if (!isBusy()) showChannelGroupDialog(node) }
        })
        snapshot.blockReason?.let { content.addView(CalmViews.row(this, "Изменения недоступны", it)) }
        content.addView(MaterialButton(this).apply {
            text = "Проверить соединение"; tag = "local"; isAllCaps = false
            setOnClickListener {
                if (isBusy()) return@setOnClickListener
                val repo = repository
                lastOutcome = "Проверяем соединение с сервером…"
                status.text = lastOutcome
                operation.run { repo.refreshServerCore(node).getOrThrow(); "Соединение с сервером подтверждено" }
            }
        })
        content.addView(CalmViews.row(this, node.name, node.host))
        if ("get-protocol-configurations" in snapshot.permissions) content.addView(MaterialButton(this).apply {
            text = "Восстановить подключения"; tag = "local"; isAllCaps = false
            setOnClickListener { if (!isBusy()) protocolRestore.restore(node, force = true) }
        })
        content.addView(CalmViews.row(this, "Ядро сервера", coreVersionSummary(node.coreObservation)))
        if ("get-jazz-room-pool" in snapshot.permissions) content.addView(MaterialButton(this).apply {
            text="СберДжаз · размещение подключений"; isAllCaps=false
            setOnClickListener { startActivity(Intent(this@ProtocolSetupActivity,JazzRoomPolicyActivity::class.java).putExtra(EXTRA_SERVER_ID,nodeId)) }
        },LinearLayout.LayoutParams(-1,56.dp()))
        if ("install" !in snapshot.permissions) { status=TextView(this).apply { text="Управление протоколами ограничено ролью администратора" }; content.addView(status); return }
        content.addView(CalmViews.row(this, "Сетевые каналы", "Настройте каналы туннелирования. Клиент сразу подключится к доступному каналу или выберет наиболее подходящий."))
        protocolCard(node, "vyandex", "Yandex Volga", "Документный транспорт. Если Яндекс запросит вход или CAPTCHA, откроется WebView.", true)
        protocolCard(node, "jitsi", "Jitsi Meet", "WebRTC-канал. Ссылка комнаты необязательна.", false, true)
        protocolCard(node, "jazz", "СберДжаз", "Создайте гостевую комнату автоматически или вставьте полную ссылку с паролем.", true)
        content.addView(CalmViews.row(this, "UDP-маршрутизация", "Проверяется автоматически. При недоступности приложение использует TCP."))
        status = TextView(this).apply { textSize = 14f; setTextColor(resources.getColor(io.github.libreroute.R.color.text_secondary, theme)); setPadding(0, 12.dp(), 0, 0) }
        status.text = lastOutcome
        content.addView(status)
        content.addView(MaterialButton(this).apply { text = "Очистить сервер от установленных протоколов"; isAllCaps = false; setOnClickListener { confirmClear() } }, LinearLayout.LayoutParams(-1, 56.dp()))
        content.addView(MaterialButton(this).apply {
            text = "\u0417\u0430\u0432\u0435\u0440\u0448\u0438\u0442\u044c \u0438 \u043f\u0435\u0440\u0435\u0439\u0442\u0438 \u043a \u043f\u043e\u0434\u043a\u043b\u044e\u0447\u0435\u043d\u0438\u044e"
            isAllCaps = false
            setOnClickListener {
                startActivity(Intent(this@ProtocolSetupActivity, io.github.libreroute.ui.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }
            tag = "local"
        }, LinearLayout.LayoutParams(-1, 56.dp()))
        refreshSetupEnabled()
    }

    private fun showChannelGroupDialog(node: ServerNode) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24.dp(), 0, 24.dp(), 0) }
        box.addView(TextView(this).apply { text = "Вставьте от 2 до 4 ссылок, каждую с новой строки. Можно сочетать отдельные комнаты Jazz и документы Яндекса. Они будут работать как одно подключение." })
        val input = EditText(this).apply {
            hint = "Ссылки на каналы"; minLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(groupSetup.draft)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { groupSetup.draft = s?.toString().orEmpty() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        box.addView(input)
        val dialog = AlertDialog.Builder(this).setTitle("Объединить каналы").setView(box)
            .setNegativeButton("Отмена", null).setNeutralButton("Проверить Яндекс", null).setPositiveButton("Настроить", null).create()
        channelGroupDialog = dialog
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val documents = input.text.toString().lineSequence().map(String::trim).filter(String::isNotBlank)
                    .filter { runCatching { endpointIdentity("vyandex", it) }.isSuccess }.distinct().toList()
                if (documents.isEmpty()) { input.error = "Сначала вставьте ссылки на документы Яндекса"; return@setOnClickListener }
                showGroupDocuments(documents)
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val result = runCatching {
                    val urls = input.text.toString().lineSequence().map(String::trim).filter(String::isNotBlank).toList()
                    require(urls.size in 2..4) { "Добавьте от 2 до 4 ссылок" }
                    ChannelGroupPlan(lanes = urls.map { raw ->
                        val host = java.net.URI(raw).host?.lowercase().orEmpty()
                        val provider = if (host in setOf("salutejazz.ru", "jazz.sber.ru")) "jazz" else "vyandex"
                        GroupLaneRequest(provider, raw)
                    }).also(ChannelGroupPlan::validate)
                }
                result.onSuccess { plan -> groupSetup.install(node, plan); dialog.dismiss() }
                    .onFailure { input.error = it.message }
            }
        }
        dialogs.show(dialog, "group", Bundle())
    }

    private fun showGroupDocuments(documents: List<String>) {
        val dialog = AlertDialog.Builder(this).setTitle("Проверить Яндекс")
            .setItems(documents.indices.map { "Документ ${it + 1}" }.toTypedArray()) { _, index ->
                channelGroupDialog?.dismiss()
                groupAuthLauncher.launch(Intent(this, io.github.libreroute.ui.YandexCaptchaActivity::class.java)
                    .putExtra(io.github.libreroute.ui.YandexCaptchaActivity.EXTRA_DOCUMENT_URL, documents[index])
                    .putExtra(io.github.libreroute.ui.YandexCaptchaActivity.EXTRA_REASON, io.github.libreroute.event.YandexAuthReason.CAPTCHA.name))
            }.setNegativeButton("Отмена", null).create()
        dialogs.show(dialog, "yandex_choose", Bundle().apply { putStringArrayList("documents", ArrayList(documents)) })
    }

    private fun refreshSetupEnabled() {
        val blockReason = localSnapshot?.blockReason
        fun update(view: View) {
            if (view is MaterialButton || view is EditText) view.isEnabled = !isBusy() &&
                (view.tag == "local" || view is EditText || blockReason == null)
            if (view is MaterialButton && view.text == "Восстановить подключения" && protocolRestore.state.value.running)
                view.isEnabled = false
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) update(view.getChildAt(i))
        }
        update(content)
    }

    private fun protocolCard(node: ServerNode, id: String, title: String, description: String, endpointRequired: Boolean, jitsi: Boolean = false) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp()); setBackgroundResource(io.github.libreroute.R.drawable.bg_bottom_sheet) }
        val operation = repository.latestProtocolInstallation(node.id, id)
        val installed = id in localSnapshot?.ready.orEmpty()
        val serverHasProtocol = node.inventory?.installedProtocols?.any { it.equals(id, true) } == true
        val state = when (operation?.stage) { ProtocolInstallationOperation.NEEDS_ACTION -> "Требуется проверка"; ProtocolInstallationOperation.UNKNOWN -> "Проверяем результат"; ProtocolInstallationOperation.FAILED -> "Не настроен"; else -> if (installed) "Активен" else if (serverHasProtocol) "Доступен к настройке" else "Доступен к установке" }
        card.addView(CalmViews.row(this, "$title · $state", description))
        if (endpointRequired || jitsi) {
            val input = EditText(this).apply {
                hint = when { id == "jazz" -> "Гостевая ссылка СберДжаза с паролем"; jitsi -> "HTTPS-ссылка комнаты (необязательно)"; else -> "HTTPS-ссылка документа Yandex" }
                inputType = InputType.TYPE_CLASS_TEXT or if (id == "jazz") InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
                (endpointDrafts[id] ?: operation?.endpoint?.takeIf { it.isNotBlank() }
                    ?: generatedEndpoint(node, id).takeIf { it.isNotBlank() })?.let(::setText)
            }
            endpointInputs[id] = input; card.addView(input)
        }
        if (id == "mqtt" && installed) {
            val endpoint = generatedEndpoint(node, id)
            if (endpoint.isNotBlank()) {
                val endpointRow = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(10.dp(), 8.dp(), 10.dp(), 8.dp())
                    setBackgroundResource(io.github.libreroute.R.drawable.bg_bottom_sheet)
                }
                endpointRow.addView(TextView(this).apply {
                    text = "Адрес endpoint"
                    textSize = 11f
                    setTextColor(resources.getColor(io.github.libreroute.R.color.text_secondary, theme))
                })
                endpointRow.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    addView(TextView(this@ProtocolSetupActivity).apply {
                        text = endpoint
                        textSize = 12f
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                        setTextColor(resources.getColor(io.github.libreroute.R.color.text_primary, theme))
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(MaterialButton(this@ProtocolSetupActivity).apply {
                        text = "Копия"
                        tag = "local"
                        isAllCaps = false
                        minHeight = 36.dp()
                        setOnClickListener {
                            val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("LibreRoute endpoint", endpoint))
                            Toast.makeText(this@ProtocolSetupActivity, "Endpoint скопирован", Toast.LENGTH_SHORT).show()
                        }
                    }, LinearLayout.LayoutParams(-2, 44.dp()))
                })
                card.addView(endpointRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 8.dp() })
            }
        }
        if (id == "jazz" && !installed) {
            card.addView(MaterialButton(this).apply {
                isAllCaps = false
                text = if (operation?.endpoint?.isNotBlank() == true) "Продолжить настройку комнаты" else "Создать комнату"
                setOnClickListener {
                    if (!isBusy()) jazzSetup.createAndInstall(node)
                }
            }, LinearLayout.LayoutParams(-1, 52.dp()))
        }
        card.addView(MaterialButton(this).apply {
            isAllCaps = false
            text = when {
                id == "vyandex" -> "Проверить и активировать"
                id == "jitsi" || id == "jazz" -> "Подключить комнату"
                installed -> "Настроить"
                else -> "Установить"
            }
            setOnClickListener { install(node, id, endpointInputs[id]?.text?.toString()?.trim().orEmpty()) }
        }, LinearLayout.LayoutParams(-1, 52.dp()))
        content.addView(card, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp() })
    }

    private fun install(node: ServerNode, protocol: String, endpoint: String) {
        if (isBusy()) return
        if (protocol == "jazz" && !io.github.libreroute.util.ServiceRouteEndpoint.isValid(io.github.libreroute.data.TransportType.jazz, endpoint)) {
            Toast.makeText(this, "Укажите полную HTTPS-ссылку СберДжаза: /call/комната/пароль", Toast.LENGTH_LONG).show()
            return
        }
        val jitsiToken = if (protocol == "jitsi") JitsiTokenStore(this).get(endpoint) else null
        val plainRoomConfirmed = protocol == "jitsi" && confirmedJitsiRoom.isNotBlank() &&
            JitsiBrowserHandoff.sameRoom(confirmedJitsiRoom, endpoint)
        if (protocol == "jitsi" && jitsiToken == null && !plainRoomConfirmed) {
            val room = endpoint.ifBlank {
                pendingAuthEndpoint.takeIf { it.isNotBlank() }
                    ?: "https://meet.jit.si/libreroute-${java.util.UUID.randomUUID()}"
            }
            if (JitsiAuthActivity.roomUrlOrNull(room) == null) {
                Toast.makeText(this, "Укажите HTTPS-ссылку комнаты Jitsi", Toast.LENGTH_LONG).show()
                return
            }
            pendingAuthProtocol = protocol
            pendingAuthEndpoint = room
            endpointInputs[protocol]?.setText(room)
            authLauncher.launch(Intent(this, JitsiAuthActivity::class.java)
                .putExtra(JitsiAuthActivity.EXTRA_ROOM_URL, room))
            return
        }
        if (protocol == "vyandex" && endpoint.isBlank()) { Toast.makeText(this, "Укажите ссылку на HTTPS-документ Yandex", Toast.LENGTH_LONG).show(); return }
        if (protocol == "jitsi" && endpoint.isNotBlank() && !endpoint.startsWith("https://")) { Toast.makeText(this, "Ссылка комнаты должна начинаться с https://", Toast.LENGTH_LONG).show(); return }
        lastOutcome = "Настраиваем ${protocolName(protocol)}…"
        status.text = lastOutcome
        val repo = repository
        val app = applicationContext
        InstallationLog.append(app, "protocol_prepare", "server=${node.host} protocol=$protocol")
        operation.run {
            val result = repo.installProtocol(node, protocol, endpoint, AdminChannel.AUTO)
            val record = repo.latestProtocolInstallation(node.id, protocol)
            InstallationLog.append(app, "protocol_result", "server=${node.host} protocol=$protocol operation_id=${record?.operationId.orEmpty()} stage=${record?.stage.orEmpty()} result=${result.javaClass.simpleName}")
            ProtocolOutcome(protocol, endpoint, result)
        }
    }

    private fun handleProtocolOutcome(value: ProtocolOutcome) {
        val node = repository.getServerNode(nodeId) ?: return
        val protocol = value.protocol
        val endpoint = value.endpoint
        val result = value.result
            when (result) {
                is AdminOpResult.Success -> status.text = result.message
                is AdminOpResult.Failure -> {
                    status.text = result.error
                    if (protocol == "vyandex" && requiresYandexBrowserAuth(result.error)) {
                        AlertDialog.Builder(this@ProtocolSetupActivity)
                            .setTitle("Нужна проверка Yandex")
                            .setMessage("Это не ошибка установки. Завершите вход или CAPTCHA в WebView, после возврата приложение повторит проверку этого протокола.")
                            .setNegativeButton("Позже", null)
                            .setPositiveButton("Открыть WebView") { _, _ ->
                                pendingAuthProtocol = protocol
                                pendingAuthEndpoint = endpoint
                                val controlProfileId = repository.managedProfilesFlow.value.firstOrNull {
                                    it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL &&
                                        it.serverId == node.id && it.role == AdminRole.OWNER
                                }?.id.orEmpty()
                                authLauncher.launch(Intent(this@ProtocolSetupActivity, RemoteAuthActivity::class.java)
                                    .putExtra(RemoteAuthActivity.EXTRA_TARGET_ID, node.id)
                                    .putExtra(RemoteAuthActivity.EXTRA_PROFILE_ID, controlProfileId)
                                    .putExtra(RemoteAuthActivity.EXTRA_DOC_URL, endpoint))
                            }.create().let { dialogs.show(it, "auth_request", Bundle().apply { putString("protocol", protocol); putString("endpoint", endpoint); putString("error", result.error) }) }
                    } else if (protocol == "jitsi" && endpoint.isNotBlank() &&
                        (result.error.contains("moderator", true) || result.error.contains("not ready", true) || result.error.contains("room", true))) {
                        AlertDialog.Builder(this@ProtocolSetupActivity)
                            .setTitle("Нужно открыть комнату Jitsi")
                            .setMessage("Комната ещё не готова или ожидает организатора. Откройте ссылку в браузере, затем вернитесь в приложение и повторите настройку.")
                            .setNegativeButton("Позже", null)
                            .setPositiveButton("Открыть комнату") { _, _ ->
                                pendingAuthProtocol = protocol
                                pendingAuthEndpoint = endpoint
                                runCatching {
                                    authLauncher.launch(Intent(this@ProtocolSetupActivity, JitsiAuthActivity::class.java)
                                        .putExtra(JitsiAuthActivity.EXTRA_ROOM_URL, endpoint))
                                }.onFailure { status.text = "Не найден браузер для открытия комнаты" }
                            }.create().let { dialogs.show(it, "auth_request", Bundle().apply { putString("protocol", protocol); putString("endpoint", endpoint); putString("error", result.error) }) }
                    }
                }
            }
        lastOutcome = status.text.toString()
        render()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Очистить протоколы?").setMessage("Будут удалены только worker-протоколы и пользовательские конфигурации. SSH и единственный профиль администратора сохранятся.").setNegativeButton("Отмена", null).setPositiveButton("Очистить") { _, _ -> clearProtocols() }.create().let { dialogs.show(it, "clear", Bundle()) }
    }

    private fun clearProtocols() {
        if (isBusy()) return
        val node = repository.getServerNode(nodeId) ?: return
        lastOutcome = "Очищаем протоколы…"
        status.text = lastOutcome
        val repo = repository
        operation.run {
            when (val result = repo.clearServerProtocols(node.id, node.toSshHostConfig())) {
                is AdminOpResult.Success -> result.message
                is AdminOpResult.Failure -> "Не удалось очистить протоколы: ${result.error}"
            }
        }
    }

    private fun generatedEndpoint(node: ServerNode, transport: String): String {
        val tunnel = localSnapshot?.tunnels?.firstOrNull {
            it.transportType.equals(transport, true) && it.serverId == node.id
        } ?: return ""
        val args = tunnel.transportConnPayload
        val index = args.indexOf("--url")
        return if (index >= 0) args.getOrNull(index + 1).orEmpty() else ""
    }

    private fun protocolName(id: String) = when (id) { "vyandex" -> "Yandex Volga"; "mqtt" -> "MQTT WSS"; "jitsi" -> "Jitsi Meet"; "jazz" -> "СберДжаз"; else -> id }
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    companion object { const val EXTRA_SERVER_ID = "server_id" }
}
