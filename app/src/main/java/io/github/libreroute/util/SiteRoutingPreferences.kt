package io.github.libreroute.util

import android.content.Context
import io.github.libreroute.data.SiteRoutingState
import io.github.libreroute.data.ZapretProfileState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SiteRoutingPreferences(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = context.applicationContext.getSharedPreferences("libreroute_site_routing", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): SiteRoutingState = prefs.getString("state", null)?.let {
        json.decodeFromString<SiteRoutingState>(it)
    } ?: BundledRoutingDefaults.load(appContext).sites

    fun store(state: SiteRoutingState, profiles: ZapretProfileState) {
        state.validate(profiles)
        check(prefs.edit().putString("state", json.encodeToString(state)).commit()) { "Не удалось сохранить правила сайтов" }
    }
}
