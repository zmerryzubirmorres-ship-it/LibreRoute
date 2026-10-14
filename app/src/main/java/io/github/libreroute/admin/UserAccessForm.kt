package io.github.libreroute.admin

import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.libreroute.R
import io.github.libreroute.data.TransportType
import io.github.libreroute.ui.CalmDetailDialog
import io.github.libreroute.ui.CalmViews
import io.github.libreroute.ui.DialogScreens

/** View state is restored by DialogScreens; building the form never issues access. */
internal class UserAccessForm(
    private val context: Context,
    private val snapshot: AdminClusterSnapshot,
    user: AdminUser,
    nodes: List<ServerNode>,
    assignedServers: Set<String>,
    pendingDocument: String,
    onCreateLink: (List<EnrollmentRoute>) -> Unit,
    onAssignDevice: (List<EnrollmentRoute>, String) -> Unit,
    onChannelGroup: (String) -> Unit,
) {
    val dialog = CalmDetailDialog(context, "Доступ пользователя")
    private val selectedServers = mutableSetOf<String>()
    private val selectedProtocols = mutableSetOf<Pair<String, String>>()
    private val fields = mutableMapOf<Pair<String, String>, TextInputLayout>()
    private val serverNames = snapshot.distinctServerIds.associateWith { id -> nodes.firstOrNull { it.id == id }?.name ?: id }
    private val summary = label("", 13).apply {
        isSaveEnabled = false; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val error = label("", 13).apply { visibility = View.GONE; isSaveEnabled = false; setTextColor(color(R.color.m3_error)) }
    private val create = CalmViews.action(context, "Создать ссылку") {
        routes()?.let(onCreateLink)
    }

    init {
        dialog.content.addView(CalmViews.row(context, user.name, "Выберите, как пользователь будет подключаться. После создания можно отправить ссылку или показать QR-код."))
        dialog.content.addView(label("1. Выберите подключения", 19, heading = true))
        dialog.content.addView(label("Отметьте сервер и нужные способы подключения. Можно выбрать несколько.", 14))
        snapshot.distinctServerIds.sorted().forEach { serverId ->
            val methods = snapshot.methodsFor(serverId).filter {
                it.transport in setOf("vyandex", "jazz", "jitsi") && TransportType.isAvailable(it.transport)
            }.sortedBy { when (it.transport) { "jazz" -> 0; "vyandex" -> 1; else -> 2 } }
            val serverContent = column(12)
            val card = MaterialCardView(context).apply {
                radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
                setCardBackgroundColor(color(R.color.m3_surface_container_lowest))
                addView(serverContent)
            }
            val options = column(4)
            fun showServer(checked: Boolean) {
                if (checked) selectedServers.add(serverId) else selectedServers.remove(serverId)
                options.visibility = if (checked) View.VISIBLE else View.GONE
                card.strokeColor = color(if (checked) R.color.m3_primary else R.color.m3_outline_variant)
                updateSummary()
            }
            val server = MaterialCheckBox(context).apply {
                id = DialogScreens.stateId("server:$serverId")
                text = serverNames.getValue(serverId); textSize = 17f
                isEnabled = methods.isNotEmpty()
                isChecked = isEnabled && (snapshot.distinctServerIds.size == 1 || serverId in assignedServers)
                setOnCheckedChangeListener { _, checked -> showServer(checked) }
            }
            serverContent.addView(server)
            if (methods.isEmpty()) serverContent.addView(label("Нет доступных подключений. Сначала настройте протоколы на сервере.", 13))
            methods.forEach { method ->
                val key = serverId to method.transport
                val needsLink = method.endpointRequired && !method.automaticAllocation
                val inputLayout = if (needsLink) TextInputLayout(context).apply inputLayout@ {
                    id = DialogScreens.stateId("endpoint-layout:$serverId:${method.transport}")
                    boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                    hint = when (method.transport) {
                        "vyandex" -> "Ссылка на документ Яндекса"
                        "jazz" -> "Ссылка на комнату с паролем"
                        else -> "Ссылка на комнату Jitsi"
                    }
                    addView(TextInputEditText(context).apply {
                        id = DialogScreens.stateId("endpoint:$serverId:${method.transport}")
                        inputType = InputType.TYPE_CLASS_TEXT or if (method.transport == "jazz")
                            InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI
                        setSingleLine(true)
                        if (method.transport == "vyandex" && snapshot.distinctServerIds.size == 1) setText(pendingDocument)
                        doAfterTextChanged { this@inputLayout.error = null; clearError() }
                    })
                    if (method.transport == "jazz") endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
                } else null
                val protocol = MaterialCheckBox(context).apply {
                    id = DialogScreens.stateId("protocol:$serverId:${method.transport}")
                    text = protocolLabel(method.transport)
                    isChecked = method.transport == "jazz" && method.automaticAllocation
                    if (isChecked) selectedProtocols.add(key)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedProtocols.add(key) else selectedProtocols.remove(key)
                        inputLayout?.visibility = if (checked) View.VISIBLE else View.GONE
                        updateSummary()
                    }
                }
                options.addView(protocol)
                options.addView(label(when {
                    method.transport == "jazz" && method.automaticAllocation -> "Комната создаётся автоматически. Ссылку на комнату вводить не нужно."
                    method.transport == "vyandex" -> "Укажите отдельный документ Яндекса для этого пользователя."
                    method.transport == "jazz" -> "Понадобится отдельная гостевая комната с паролем."
                    else -> "Укажите комнату, которую откроет организатор."
                }, 13).apply { setPadding(dp(16), 0, dp(8), dp(8)) })
                inputLayout?.let {
                    it.visibility = if (protocol.isChecked) View.VISIBLE else View.GONE
                    options.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
                    fields[key] = it
                }
            }
            serverContent.addView(options)
            dialog.content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            showServer(server.isChecked)
        }
        if (snapshot.distinctServerIds.isEmpty()) {
            dialog.content.addView(label("Сервер пока не сообщил доступные подключения. Вернитесь в список серверов и обновите сведения.", 14))
        }

        val advanced = column(0).apply { visibility = View.GONE }
        dialog.content.addView(MaterialSwitch(context).apply {
            id = DialogScreens.stateId("access-advanced")
            text = "Дополнительно"; setPadding(0, dp(16), 0, dp(8))
            setOnCheckedChangeListener { _, checked -> advanced.visibility = if (checked) View.VISIBLE else View.GONE }
        })
        advanced.addView(label("Объединённые каналы", 16, heading = true))
        advanced.addView(label("Несколько комнат Jazz или документов Яндекса будут работать как одно подключение. Для этого выберите один сервер.", 13))
        advanced.addView(CalmViews.action(context, "Настроить объединённые каналы", secondary = true) {
            if (selectedServers.size != 1) { showError("Выберите один сервер для объединённых каналов"); return@action }
            val serverId = selectedServers.single()
            if (snapshot.coreInfo?.capabilities?.contains(ChannelGroupPlan.ENROLLMENT_CAPABILITY) != true ||
                snapshot.methodsFor(serverId).none { it.transport == "channel-group" }) {
                showError("Обновите ядро выбранного сервера: выдача объединённых каналов пока недоступна")
                return@action
            }
            onChannelGroup(serverId)
        })
        advanced.addView(label("Устройство уже зарегистрировано", 16, heading = true))
        advanced.addView(label("Если у вас есть публичный ключ телефона, можно назначить выбранные подключения сразу этому устройству.", 13))
        val publicKeyLayout = TextInputLayout(context).apply {
            hint = "Публичный ключ устройства"; boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        }
        val publicKey = TextInputEditText(context).apply {
            id = DialogScreens.stateId("access-device-key")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2; maxLines = 4
            doAfterTextChanged { publicKeyLayout.error = null; clearError() }
        }
        publicKeyLayout.addView(publicKey); advanced.addView(publicKeyLayout)
        advanced.addView(CalmViews.action(context, "Назначить доступ по ключу", secondary = true) {
            val key = publicKey.text?.toString()?.trim().orEmpty()
            if (key.isEmpty()) { publicKeyLayout.error = "Введите публичный ключ устройства"; publicKey.requestFocus(); return@action }
            routes()?.let { onAssignDevice(it, key) }
        })
        dialog.content.addView(advanced)
        dialog.footer.apply {
            visibility = View.VISIBLE
            addView(label("2. Создайте ссылку", 17, heading = true))
            addView(summary); addView(error); addView(create)
        }
        updateSummary()
    }

    private fun routes(): List<EnrollmentRoute>? {
        clearError()
        fields.values.forEach { it.error = null }
        val links = fields.mapValues { it.value.editText?.text?.toString()?.trim().orEmpty() }
        fields.forEach { (key, layout) ->
            if (key.first in selectedServers && key in selectedProtocols) {
                val value = links.getValue(key)
                if (value.isEmpty() || !AutomaticAccessPlan.validLink(key.second, value)) {
                    layout.error = if (value.isEmpty()) "Добавьте ссылку для ${protocolLabel(key.second)}" else "Проверьте ссылку на документ или комнату"
                    layout.editText?.requestFocus()
                    dialog.scrollView.post { layout.requestRectangleOnScreen(android.graphics.Rect(0, 0, layout.width, layout.height), false) }
                    showError("Заполните ссылку для выбранного подключения")
                    return null
                }
            }
        }
        return runCatching { AutomaticAccessPlan.build(snapshot, selectedServers, links, selectedProtocols) }
            .getOrElse { showError(it.message ?: "Проверьте выбранные подключения"); null }
    }

    private fun updateSummary() {
        clearError()
        val selected = selectedProtocols.filter { it.first in selectedServers }
        summary.text = when {
            selectedServers.isEmpty() -> "Выберите хотя бы один сервер"
            selectedServers.any { id -> selected.none { it.first == id } } -> "Отметьте хотя бы один способ подключения для каждого выбранного сервера"
            else -> selected.groupBy { it.first }.entries.joinToString("\n") { (id, methods) ->
                "${serverNames.getValue(id)} · ${methods.joinToString { protocolLabel(it.second) }}"
            }
        }
        create.isEnabled = selectedServers.isNotEmpty() && selectedServers.all { id -> selected.any { it.first == id } }
    }

    private fun clearError() { error.visibility = View.GONE; error.text = "" }
    private fun showError(message: String) { error.text = message; error.visibility = View.VISIBLE }
    private fun dp(value: Int) = CalmViews.dp(context, value)
    private fun color(value: Int) = ContextCompat.getColor(context, value)
    private fun column(padding: Int) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun label(value: String, size: Int, heading: Boolean = false) = TextView(context).apply {
        text = value; textSize = size.toFloat()
        setTextColor(color(if (heading) R.color.text_primary else R.color.text_secondary))
        setPadding(0, dp(4), 0, dp(6))
        if (heading) { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); androidx.core.view.ViewCompat.setAccessibilityHeading(this, true) }
    }

    private fun protocolLabel(transport: String) = when (transport) {
        "vyandex" -> "Яндекс"; "jazz" -> "СберДжаз"; "jitsi" -> "Jitsi"; else -> transport
    }
}
