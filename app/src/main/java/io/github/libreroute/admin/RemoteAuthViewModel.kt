package io.github.libreroute.admin

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Session and fail-closed proxy belong to the auth flow, not its current view. */
class RemoteAuthViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val session: AuthSession? = null, val ready: Boolean = false, val error: String? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private val proxy = WebViewTunnelProxyManager(app)
    private var initialized = false
    private var closed = false

    fun initialize(server: ServerNode, profileId: String, documentUrl: String) {
        if (initialized) return
        initialized = true
        viewModelScope.launch {
            val previous = saved.get<String>("session")?.let { runCatching { Json.decodeFromString(AuthSession.serializer(), it) }.getOrNull() }
            if (previous == null && saved.get<Boolean>("session_started") == true) {
                mutable.value = State(error = "Создание сессии было прервано. Вернитесь к серверу и откройте авторизацию заново.")
                return@launch
            }
            saved["session_started"] = true
            val session = try {
                previous ?: withContext(Dispatchers.IO) {
                    AdminRepository.getInstance(getApplication()).beginAuthSession(server.name, profileId, AdminChannel.DIRECT_SSH, server, documentUrl).getOrThrow()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = State(error = error.message ?: "Ошибка создания сессии"); return@launch }
            saved["session"] = Json.encodeToString(AuthSession.serializer(), session)
            mutable.value = State(session = session)
            if (session.documentUrl.isBlank()) {
                mutable.value = State(session, error = "Сервер не указал документ для проверки авторизации. Обновите серверное ядро и откройте авторизацию заново.")
                return@launch
            }
            proxy.startProxy(server,
                onReady = { viewModelScope.launch { if (!closed) mutable.value = State(session, ready = true) } },
                onError = { error -> viewModelScope.launch { if (!closed) mutable.value = State(session, error = error) } },
                onTerminated = { viewModelScope.launch { if (!closed) mutable.value = State(session, error = "Туннель разорван. Откройте авторизацию заново.") } },
            )
        }
    }

    override fun onCleared() { closed = true; proxy.stopProxy() }
}
