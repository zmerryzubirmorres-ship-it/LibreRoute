package io.github.libreroute.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps the same durable operation and UI state during screen rotation. */
class ChannelGroupSetupViewModel(application: Application) : AndroidViewModel(application) {
    data class State(val running: Boolean = false, val outcome: String = "")
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    var draft: String = ""
    fun install(node: ServerNode, plan: ChannelGroupPlan) {
        if (mutable.value.running) return
        mutable.value = State(true, "Настраиваем объединение каналов…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { AdminRepository.getInstance(getApplication()).installChannelGroup(node, plan) }
                    .getOrElse { AdminOpResult.Failure(it.message ?: "Не удалось настроить каналы", it) }
            }
            mutable.value = State(false, when (result) {
                is AdminOpResult.Success -> result.message
                is AdminOpResult.Failure -> result.error
            })
        }
    }
}

@kotlinx.serialization.Serializable
internal data class PendingChannelGroup(
    val plan: ChannelGroupPlan,
    val profileId: String,
    val operationId: String,
    val stage: String = "submitting"
)
