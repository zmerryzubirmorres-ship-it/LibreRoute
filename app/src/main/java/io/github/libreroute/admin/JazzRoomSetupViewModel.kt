package io.github.libreroute.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Keeps guest creation/install running through Activity recreation. Secrets stay in the repository. */
class JazzRoomSetupViewModel(application: Application) : AndroidViewModel(application) {
    data class State(val running: Boolean = false, val outcome: String = "")
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()

    fun createAndInstall(node: ServerNode) {
        if (state.value.running) return
        mutableState.value = State(true, "Создаём или восстанавливаем комнату СберДжаза и настраиваем канал…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try { AdminRepository.getInstance(getApplication()).createAndInstallJazz(node) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    AdminOpResult.Failure("Не удалось завершить настройку СберДжаза. Повторите проверку результата.")
                }
            }
            val message = when (result) {
                is AdminOpResult.Success -> result.message
                is AdminOpResult.Failure -> result.error
            }
            InstallationLog.append(getApplication(), "jazz_room_setup", "result=${result.javaClass.simpleName}")
            mutableState.value = State(outcome = message)
        }
    }
}
