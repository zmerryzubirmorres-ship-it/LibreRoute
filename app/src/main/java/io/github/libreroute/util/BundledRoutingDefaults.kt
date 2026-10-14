package io.github.libreroute.util

import android.content.Context
import io.github.libreroute.data.RoutingDefaults
import kotlinx.serialization.json.Json

/** Used only when the corresponding preference has never been saved. */
internal object BundledRoutingDefaults {
    @Volatile private var cached: RoutingDefaults? = null

    fun load(context: Context): RoutingDefaults = cached ?: synchronized(this) {
        cached ?: context.applicationContext.assets.open("routing-defaults.json").bufferedReader().use {
            Json.decodeFromString<RoutingDefaults>(it.readText()).also { defaults ->
                defaults.validate()
                cached = defaults
            }
        }
    }
}
