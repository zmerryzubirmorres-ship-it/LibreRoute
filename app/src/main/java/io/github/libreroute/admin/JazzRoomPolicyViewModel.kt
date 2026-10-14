package io.github.libreroute.admin

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class JazzRoomPolicyViewModel(private val saved: SavedStateHandle) : ViewModel() {
    data class State(val running: Boolean = false, val projection: JazzPoolProjection? = null, val error: String? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    var mode: String?
        get() = saved["mode"]
        set(value) { saved["mode"] = value }
    var pairs: String?
        get() = saved["pairs"]
        set(value) { saved["pairs"] = value }
    var rooms: String?
        get() = saved["rooms"]
        set(value) { saved["rooms"] = value }
    var validationMessage: String?
        get() = saved["validation_message"]
        set(value) { saved["validation_message"] = value }
    var initialized = false

    fun run(node: ServerNode, commitDraft: Boolean, action: (ServerNode) -> Result<JazzPoolProjection>) {
        if (mutable.value.running) return
        initialized = true
        validationMessage = null
        mutable.value = mutable.value.copy(running = true, error = null)
        viewModelScope.launch {
            val result = try { withContext(Dispatchers.IO) { action(node) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
            result.onSuccess {
                if (commitDraft) { mode = null; pairs = null; rooms = null }
                mutable.value = State(projection = it)
            }.onFailure {
                mutable.value = mutable.value.copy(running = false, error = it.message ?: "Ответ сервера не получен")
            }
        }
    }
}
