package io.github.libreroute.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One operation per screen; work must capture repositories/values, never UI. */
class ScreenOperationViewModel : ViewModel() {
    data class State(val running: Boolean = false, val result: Result<Any>? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()

    fun run(work: suspend () -> Any) {
        if (mutable.value.running || mutable.value.result != null) return
        mutable.value = State(running = true)
        viewModelScope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) { work() })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Result.failure(error)
            }
            mutable.value = State(result = result)
        }
    }

    /** Consume once, from a STARTED owner, after rendering the result. */
    fun consumed() { if (!mutable.value.running) mutable.value = State() }
}
