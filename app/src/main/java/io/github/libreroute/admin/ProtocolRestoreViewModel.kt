package io.github.libreroute.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.libreroute.data.Tunnel
import io.github.libreroute.data.TunnelRepository
import java.util.concurrent.ConcurrentHashMap

/** Retains the automatic inventory/restoration request across screen rotation. */
class ProtocolRestoreViewModel(application: Application) : AndroidViewModel(application) {
    data class State(val running: Boolean = false, val outcome: String = "")
    private val mutableState = MutableStateFlow(State())
    val state = mutableState.asStateFlow()
    private var checkedServer: String? = null
    data class LocalState(val node: ServerNode?, val permissions: Set<String>, val blockReason: String?,
                          val ready: Set<String>, val tunnels: List<Tunnel>)
    private val mutableLocal = MutableStateFlow<LocalState?>(null)
    val local = mutableLocal.asStateFlow()
    private var localJob: kotlinx.coroutines.Job? = null

    fun cached(serverId: String) = local.value?.takeIf { it.node?.id == serverId } ?: cachedLocal[serverId]

    fun loadLocal(serverId: String) {
        localJob?.cancel()
        localJob = viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                val repository = AdminRepository.getInstance(getApplication())
                val permissions = setOf("list", "install", "get-protocol-configurations", "get-jazz-room-pool")
                    .filter { repository.canPerform(serverId, it) }.toSet()
                val tunnels = TunnelRepository(getApplication()).load()
                LocalState(repository.getServerNode(serverId), permissions,
                    repository.serverMutationBlockReason(serverId),
                    repository.readyProtocolTransports(serverId, tunnels), tunnels)
            }
            cachedLocal[serverId] = snapshot
            mutableLocal.value = snapshot
        }
    }

    fun restore(node: ServerNode, force: Boolean = false) {
        if (state.value.running || (!force && checkedServer == node.id)) return
        checkedServer = node.id
        mutableState.value = State(true, "Проверяем установленные протоколы…")
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val repository = AdminRepository.getInstance(getApplication())
                if (force) repository.restoreServerProtocols(node)
                else repository.restoreServerProtocolsAutomatically(node)
            }
            mutableState.value = State(outcome = result.fold(
                onSuccess = { if (it.isEmpty()) "Установленных подключений для этого устройства не найдено"
                    else "Настройки подключений загружены: ${it.size}. Для проверки связи выберите подключение и нажмите «Подключить»." },
                onFailure = { it.message ?: "Не удалось восстановить подключения. Повторите проверку." }))
        }
    }

    companion object {
        // Display data only; every administrative command checks its grant afresh.
        private val cachedLocal = ConcurrentHashMap<String, LocalState>()
    }
}
