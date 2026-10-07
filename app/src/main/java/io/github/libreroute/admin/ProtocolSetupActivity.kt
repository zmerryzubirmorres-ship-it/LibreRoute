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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import io.github.libreroute.data.TunnelRepository
import io.github.libreroute.ui.CalmViews
import io.github.libreroute.util.JitsiBrowserHandoff
import io.github.libreroute.util.JitsiTokenStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One resumable operation per server transport. */
class ProtocolSetupActivity : AppCompatActivity() {
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val endpointInputs = mutableMapOf<String, EditText>()
    private val repository by lazy { AdminRepository.getInstance(this) }
    private val nodeId by lazy { intent.getStringExtra(EXTRA_SERVER_ID).orEmpty() }
    private var pendingAuthProtocol: String? = null
    private var pendingAuthEndpoint: String = ""
    /** A plain room handoff is valid when the provider admits guests after the host. */
    private var confirmedJitsiRoom: String = ""
    private var busy = false
    private var lastOutcome = ""
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
        pendingAuthProtocol = savedInstanceState?.getString("pending_auth_protocol")
        pendingAuthEndpoint = savedInstanceState?.getString("pending_auth_endpoint").orEmpty()
        confirmedJitsiRoom = savedInstanceState?.getString("confirmed_jitsi_room").orEmpty()
        lastOutcome = savedInstanceState?.getString("last_outcome").orEmpty()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(MaterialButton(this).apply {
            text = "‹"; contentDescription = "Назад"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(56.dp(), 56.dp()))
        header.addView(TextView(this).apply { text = "Протоколы сервера"; textSize = 22f })
        root.addView(header)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16.dp(), 0, 16.dp(), 24.dp()) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root); render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_auth_protocol", pendingAuthProtocol)
        outState.putString("pending_auth_endpoint", pendingAuthEndpoint)
        outState.putString("confirmed_jitsi_room", confirmedJitsiRoom)
        outState.putString("last_outcome", lastOutcome)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() { super.onResume(); if (::content.isInitialized) render() }

    private fun render() {
        content.removeAllViews(); endpointInputs.clear()
        val node = repository.getServerNode(nodeId) ?: run { finish(); return }
        content.addView(CalmViews.row(this, node.name, node.host))
        content.addView(CalmViews.row(this, "Ядро сервера", coreVersionSummary(node.coreObservation)))
        content.addView(CalmViews.row(this, "Сетевые каналы", "Настройте каналы туннелирования. Клиент сразу подключится к доступному каналу или выберет наиболее подходящий."))
        protocolCard(node, "vyandex", "Yandex Volga", "Документный транспорт. Если Яндекс запросит вход или CAPTCHA, откроется WebView.", true)
        protocolCard(node, "mqtt", "MQTT WSS", "Шифрованный WebSocket. Адрес, topic и ключ сервер настроит автоматически.", false)
        protocolCard(node, "jitsi", "Jitsi Meet", "WebRTC-канал. Ссылка комнаты необязательна.", false, true)
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
        }, LinearLayout.LayoutParams(-1, 56.dp()))
    }

    private fun protocolCard(node: ServerNode, id: String, title: String, description: String, endpointRequired: Boolean, jitsi: Boolean = false) {
        val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12.dp(), 12.dp(), 12.dp(), 12.dp()); setBackgroundResource(io.github.libreroute.R.drawable.bg_bottom_sheet) }
        val operation = repository.latestProtocolInstallation(node.id, id)
        val installed = repository.isProtocolReady(node.id, id)
        val serverHasProtocol = node.inventory?.installedProtocols?.any { it.equals(id, true) } == true
        val state = when (operation?.stage) { ProtocolInstallationOperation.NEEDS_ACTION -> "Требуется проверка"; ProtocolInstallationOperation.UNKNOWN -> "Проверяем результат"; ProtocolInstallationOperation.FAILED -> "Не настроен"; else -> if (installed) "Активен" else if (serverHasProtocol) "Доступен к настройке" else "Доступен к установке" }
        card.addView(CalmViews.row(this, "$title · $state", description))
        if (endpointRequired || jitsi) {
            val input = EditText(this).apply { hint = if (jitsi) "HTTPS-ссылка комнаты (необязательно)" else "HTTPS-ссылка документа Yandex"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; operation?.endpoint?.takeIf { it.isNotBlank() }?.let(::setText) }
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
        card.addView(MaterialButton(this).apply {
            isAllCaps = false
            text = when {
                id == "vyandex" -> "Проверить и активировать"
                id == "jitsi" -> "Подключить комнату"
                installed -> "Настроить"
                else -> "Установить"
            }
            setOnClickListener { install(node, id, endpointInputs[id]?.text?.toString()?.trim().orEmpty()) }
        }, LinearLayout.LayoutParams(-1, 52.dp()))
        content.addView(card, LinearLayout.LayoutParams(-1, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12.dp() })
    }

    private fun install(node: ServerNode, protocol: String, endpoint: String) {
        if (busy) return
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
        busy = true
        status.text = "Настраиваем ${protocolName(protocol)}…"
        InstallationLog.append(this, "protocol_prepare", "server=${node.host} protocol=$protocol")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { repository.installProtocol(node, protocol, endpoint, AdminChannel.DIRECT_SSH) }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Exception) { AdminOpResult.Failure("Не удалось завершить настройку протокола. Повторите проверку результата.", e) }
            }
            val operation = repository.latestProtocolInstallation(node.id, protocol)
            InstallationLog.append(this@ProtocolSetupActivity, "protocol_result",
                "server=${node.host} protocol=$protocol operation_id=${operation?.operationId.orEmpty()} stage=${operation?.stage.orEmpty()} result=${result.javaClass.simpleName} error=${(result as? AdminOpResult.Failure)?.error.orEmpty()}")
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
                            }.show()
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
                            }.show()
                    }
                }
            }
            lastOutcome = status.text.toString()
            busy = false
            render()
        }
    }

    private fun confirmClear() {
        AlertDialog.Builder(this).setTitle("Очистить протоколы?").setMessage("Будут удалены только worker-протоколы и пользовательские конфигурации. SSH и единственный профиль администратора сохранятся.").setNegativeButton("Отмена", null).setPositiveButton("Очистить") { _, _ -> clearProtocols() }.show()
    }

    private fun clearProtocols() {
        if (busy) return
        val node = repository.getServerNode(nodeId) ?: return
        busy = true
        status.text = "Очищаем протоколы…"
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.clearServerProtocols(node.id, node.toSshHostConfig()) }
            status.text = when (result) { is AdminOpResult.Success -> result.message; is AdminOpResult.Failure -> "Не удалось очистить протоколы: ${result.error}" }
            lastOutcome = status.text.toString()
            busy = false
            render()
        }
    }

    private fun generatedEndpoint(node: ServerNode, transport: String): String {
        val tunnel = runCatching { TunnelRepository(this).load().firstOrNull {
            it.transportType.equals(transport, true) && it.name.contains(node.name, ignoreCase = true)
        } }.getOrNull() ?: return ""
        val args = tunnel.transportConnPayload
        val index = args.indexOf("--url")
        return if (index >= 0) args.getOrNull(index + 1).orEmpty() else ""
    }

    private fun protocolName(id: String) = when (id) { "vyandex" -> "Yandex Volga"; "mqtt" -> "MQTT WSS"; "jitsi" -> "Jitsi Meet"; else -> id }
    private fun Int.dp(): Int = (this * resources.displayMetrics.density).toInt()
    companion object { const val EXTRA_SERVER_ID = "server_id" }
}
