package io.github.libreroute.util

import android.content.Context
import io.github.libreroute.data.AppRoutingState
import io.github.libreroute.data.ZapretProfileState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AppRoutingPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("libreroute_app_routing", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun load(): AppRoutingState = prefs.getString("state", null)?.let { json.decodeFromString<AppRoutingState>(it) } ?: AppRoutingState()
    fun store(state: AppRoutingState, profiles: ZapretProfileState) {
        state.validate(profiles)
        check(prefs.edit().putString("state", json.encodeToString(state)).commit()) { "Не удалось сохранить правила приложений" }
    }
}
