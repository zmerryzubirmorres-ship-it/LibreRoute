package io.github.libreroute.util

import android.content.Context
import io.github.libreroute.data.ZapretProfileState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ZapretProfilePreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("libreroute_zapret_profiles", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): ZapretProfileState {
        val stored = prefs.getString("state", null)
            ?: return BundledRoutingDefaults.load(appContext).profiles ?: ZapretProfileState()
        return json.decodeFromString<ZapretProfileState>(stored).also { it.validate() }
    }

    fun store(state: ZapretProfileState) {
        state.validate()
        check(prefs.edit().putString("state", json.encodeToString(state)).commit()) { "Не удалось сохранить профили zapret" }
    }
}
