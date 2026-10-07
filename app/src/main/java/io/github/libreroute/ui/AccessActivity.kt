package io.github.libreroute.ui

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import com.google.android.material.button.MaterialButton
import io.github.libreroute.R
import io.github.libreroute.admin.AdminOpResult
import io.github.libreroute.admin.AdminRepository
import io.github.libreroute.data.Tunnel
import io.github.libreroute.data.TunnelRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class AccessReason { EXPIRED, USED, REVOKED, NOT_FOR_DEVICE, INVALID_SIGNATURE, NETWORK, UNKNOWN }

data class AccessState(
    val loading: Boolean = true,
    val error: String? = null,
    val routes: List<Tunnel> = emptyList(),
    val reason: AccessReason? = null,
    val retryable: Boolean = true,
    val invitation: String = ""
)

class AccessViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val mutableState = MutableStateFlow(AccessState(invitation = savedState.get<String>("access_invitation").orEmpty()))
    val state = mutableState.asStateFlow()
    private var started = false
    fun retry() {
        val previous = mutableState.value
        if (previous.loading || !previous.retryable || previous.invitation.isBlank()) return
        started = false
        redeem(previous.invitation)
    }
    fun redeem(link: String) {
        if (started) return
        val invitation = link.trim()
        savedState["access_invitation"] = invitation
        started = true
        mutableState.value = AccessState(loading = true, invitation = invitation)
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val repo = AdminRepository.getInstance(getApplication())
                    val (outcome, tunnel) = repo.redeemInvitation(invitation)
                    if (outcome is AdminOpResult.Failure || tunnel == null) {
                        val message = (outcome as? AdminOpResult.Failure)?.error ?: "Не удалось проверить приглашение"
                        val reason = classifyReason(message)
                        AccessState(loading = false, error = message, reason = reason,
                            retryable = reason == AccessReason.NETWORK || reason == AccessReason.UNKNOWN, invitation = invitation)
                    } else {
                        val routes = repo.getLastRedeemedTunnels().ifEmpty { listOf(tunnel) }
                        val storage = TunnelRepository(getApplication())
                        // The verified redemption already saves the managed routes.
                        // Read back prepared IDs and key paths; never import them twice.
                        val stored = storage.load()
                        val prepared = routes.mapNotNull { route -> stored.firstOrNull {
                            it.id == route.id || (it.name == route.name && it.encryptionKey == route.encryptionKey)
                        } }
                        check(prepared.size == routes.size) { "Маршруты не сохранились" }
                        storage.setSelectedId(prepared.first().id)
                        AccessState(loading = false, routes = prepared)
                    }
                }
                mutableState.value = result.copy(invitation = invitation)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                mutableState.value = AccessState(loading = false, error = "Не удалось завершить проверку. Проверьте сеть и попробуйте ещё раз", reason = AccessReason.NETWORK, retryable = true, invitation = invitation)
            }
        }
    }

    private fun classifyReason(message: String): AccessReason {
        val lower = message.lowercase()
        return when {
            lower.contains("expired") || lower.contains("просроч") || lower.contains("истёк") -> AccessReason.EXPIRED
            lower.contains("already redeemed") || lower.contains("уже погаш") || lower.contains("использован") -> AccessReason.USED
            lower.contains("revoked") || lower.contains("отозван") -> AccessReason.REVOKED
            lower.contains("recipient") || lower.contains("device") || lower.contains("устройств") -> AccessReason.NOT_FOR_DEVICE
            lower.contains("signature") || lower.contains("подпис") || lower.contains("ключ") -> AccessReason.INVALID_SIGNATURE
            lower.contains("network") || lower.contains("сеть") || lower.contains("передать") -> AccessReason.NETWORK
            else -> AccessReason.UNKNOWN
        }
    }
}

/** Validation, success, expired and invalid invitation states share one native screen. */
class AccessActivity : AppCompatActivity() {
    private val vm: AccessViewModel by viewModels()
    private lateinit var content: LinearLayout
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageButton(this).apply {
            setImageResource(R.drawable.ic_arrow_back)
            contentDescription = "Назад"
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(CalmViews.dp(this, 56), CalmViews.dp(this, 56)))
        header.addView(TextView(this).apply {
            text = "Добавление доступа"
            textSize = 24f
            setTextColor(androidx.core.content.ContextCompat.getColor(this@AccessActivity, R.color.text_primary))
        })
        root.addView(header)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(CalmViews.dp(context, 16), CalmViews.dp(context, 24), CalmViews.dp(context, 16), CalmViews.dp(context, 24))
        }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        vm.redeem(vm.state.value.invitation.ifBlank { intent.getStringExtra("invitation").orEmpty() })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { vm.state.collect { render(it) } }
        }
    }
    private fun render(state: AccessState) {
        content.removeAllViews()
        if (state.loading) {
            content.addView(CalmViews.row(this, "Проверяем приглашение", "Дождитесь ответа сервера"))
            content.addView(ProgressBar(this))
            return
        }
        val error = state.error
        if (error != null) {
            val title = when (state.reason) {
                AccessReason.EXPIRED -> "Приглашение просрочено"
                AccessReason.USED -> "Приглашение уже использовано"
                AccessReason.REVOKED -> "Доступ отозван"
                AccessReason.NOT_FOR_DEVICE -> "Приглашение не для этого устройства"
                AccessReason.INVALID_SIGNATURE -> "Не удалось подтвердить приглашение"
                AccessReason.NETWORK -> "Сеть недоступна"
                else -> "Не удалось добавить доступ"
            }
            content.addView(CalmViews.row(this, title, state.error ?: "Нет данных"))
            if (state.reason == AccessReason.EXPIRED || state.reason == AccessReason.USED || state.reason == AccessReason.REVOKED) {
                button(if (state.reason == AccessReason.REVOKED) "Связаться с администратором" else "Запросить новое приглашение") {
                    AdminContact.show(this, when (state.reason) {
                        AccessReason.EXPIRED -> AdminContact.Reason.INVITATION_EXPIRED
                        AccessReason.USED -> AdminContact.Reason.INVITATION_USED
                        else -> AdminContact.Reason.ACCESS_REVOKED
                    })
                }
            }
            if (state.retryable) button(if (state.reason == AccessReason.UNKNOWN) "Проверить результат" else "Повторить проверку") { vm.retry() }
            button("Ввести другую ссылку") { setResult(RESULT_CANCELED, Intent().putExtra("retry_invitation", true)); finish() }
            return
        }
        content.addView(CalmViews.row(this, "Доступ добавлен", "Сохранено маршрутов: ${state.routes.size}"))
        state.routes.forEach { content.addView(CalmViews.row(this, it.name, "Готов к подключению")) }
        button("Подключить") {
            setResult(RESULT_OK, Intent().putExtra("connect", true)); finish()
        }
        button("Перейти к маршрутам") {
            setResult(RESULT_OK, Intent().putExtra("connect", false)); finish()
        }
    }
    private fun button(label: String, action: () -> Unit) {
        content.addView(MaterialButton(this).apply {
            text = label; isAllCaps = false; gravity = Gravity.CENTER
            setOnClickListener { action() }
        }, LinearLayout.LayoutParams(-1, CalmViews.dp(this, 56)))
    }
}
