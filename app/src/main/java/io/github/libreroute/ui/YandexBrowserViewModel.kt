package io.github.libreroute.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.libreroute.admin.InstallationLog
import io.github.libreroute.service.Ipv4WebViewProxy
import io.github.libreroute.service.YandexBrowserProbe
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class YandexBrowserViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    data class State(val running: Boolean = false, val saved: Boolean = false, val message: String? = null)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    internal val proxy = runCatching { Ipv4WebViewProxy() }.getOrNull()
    @Volatile private var process: Process? = null
    @Volatile private var closed = false
    var retryNotBefore: Long
        get() = saved["retry_not_before"] ?: 0L
        set(value) { saved["retry_not_before"] = value }

    fun verify(documentUrl: String, handoff: JSONObject) {
        if (mutable.value.running || mutable.value.saved) return
        mutable.value = State(running = true, message = "Проверяем соединение с Яндексом…")
        val app = getApplication<Application>()
        val dir = File(app.filesDir, "yandex-probe-${java.util.UUID.randomUUID()}")
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    dir.mkdirs()
                    val candidate = File(dir, YandexCaptchaActivity.COOKIE_FILE)
                    candidate.writeText(handoff.toString())
                    android.system.Os.chmod(candidate.absolutePath, 0x180)
                    YandexBrowserProbe.run(app, documentUrl, candidate) { process = it; if (closed) it?.destroyForcibly() }
                }
                if (!outcome.optBoolean("ok")) {
                    val reason = outcome.optString("reason")
                    val message = when (reason) {
                        "captcha" -> "Яндекс снова запросил проверку. Обновите страницу и завершите её"
                        "session" -> "Яндекс не принял сессию. Обновите документ и повторите проверку"
                        "rate_limit" -> {
                            val seconds = outcome.optLong("retry_after_seconds").coerceIn(30, Int.MAX_VALUE.toLong())
                            retryNotBefore = android.os.SystemClock.elapsedRealtime() + seconds * 1000
                            "Яндекс ограничил запросы. Повторите через $seconds с"
                        }
                        else -> "Не удалось проверить соединение. Проверьте сеть и повторите попытку"
                    }
                    InstallationLog.append(app, "client_yandex_probe_failed", "reason=${reason.takeIf { it in setOf("captcha", "session", "rate_limit", "network") } ?: "network"}")
                    mutable.value = State(message = message)
                } else {
                    withContext(Dispatchers.IO) {
                        for (name in listOf(YandexCaptchaActivity.COOKIE_FILE, "yandex_volga_session.json")) {
                            val tmp = File(app.filesDir, "$name.new")
                            File(dir, name).copyTo(tmp, overwrite = true)
                            android.system.Os.chmod(tmp.absolutePath, 0x180)
                        }
                        for (name in listOf(YandexCaptchaActivity.COOKIE_FILE, "yandex_volga_session.json")) {
                            check(File(app.filesDir, "$name.new").renameTo(app.getFileStreamPath(name)))
                        }
                    }
                    InstallationLog.append(app, "client_yandex_browser_saved", "cookies_present=true native_probe_passed=true")
                    mutable.value = State(saved = true)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.value = State(message = "Не удалось сохранить авторизацию. Повторите проверку") }
            finally { withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }; process = null }
        }
    }

    override fun onCleared() { closed = true; process?.destroyForcibly(); proxy?.close() }
}
