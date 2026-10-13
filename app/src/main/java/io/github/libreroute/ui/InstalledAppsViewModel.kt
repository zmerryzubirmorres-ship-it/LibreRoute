package io.github.libreroute.ui

import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.libreroute.util.AppRoutingPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One inventory per screen lifetime, including rotation while enumeration is in progress. */
class InstalledAppsViewModel(application: Application) : AndroidViewModel(application) {
    data class App(val key: String, val name: String, val uid: Int, val system: Boolean,
                   val launchable: Boolean, val icon: Drawable?)
    data class State(val apps: List<App>? = null, val loading: Boolean = true, val failed: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var started = false

    fun ensureLoaded(allIcons: Boolean = false) {
        if (started) return
        started = true
        viewModelScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val appContext = getApplication<Application>()
                    val pm = appContext.packageManager
                    val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                    }, 0).map { it.activityInfo.packageName }.toSet()
                    val assigned = if (allIcons) emptySet() else
                        AppRoutingPreferences(appContext).load().rules.map { it.packageName }.toSet()
                    pm.getInstalledApplications(0).filter { it.packageName != appContext.packageName }.map { info ->
                        ensureActive()
                        val system = info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                        val launches = info.packageName in launchable
                        val icon = if (allIcons || !system || launches || info.packageName in assigned)
                            runCatching { pm.getApplicationIcon(info) }.getOrNull() else null
                        App(info.packageName, runCatching { pm.getApplicationLabel(info).toString() }
                            .getOrDefault(info.packageName), info.uid, system, launches, icon)
                    }.sortedBy { it.name.lowercase(java.util.Locale.ROOT) }
                }
                mutable.value = State(apps = apps, loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = State(loading = false, failed = true)
            }
        }
    }
}
