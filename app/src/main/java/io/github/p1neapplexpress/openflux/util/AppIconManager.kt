package io.github.p1neapplexpress.openflux.util

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.github.p1neapplexpress.openflux.R

object AppIconManager {
    const val ICON_DARK = "dark"
    const val ICON_LIGHT = "light"

    const val ALIAS_DARK = "io.github.p1neapplexpress.openflux.ui.MainActivityDark"
    const val ALIAS_LIGHT = "io.github.p1neapplexpress.openflux.ui.MainActivityLight"
    const val ALIAS_DEFAULT = "io.github.p1neapplexpress.openflux.ui.MainActivityDefault"

    fun getCurrentIcon(context: Context): String {
        val appSettings = AppSettings(context)
        val icon = appSettings.appIcon
        return if (icon == ICON_LIGHT) ICON_LIGHT else ICON_DARK
    }

    fun setAppIcon(context: Context, iconKey: String) {
        val pm = context.packageManager
        val appSettings = AppSettings(context)
        val targetKey = if (iconKey == ICON_LIGHT) ICON_LIGHT else ICON_DARK
        appSettings.appIcon = targetKey

        val aliases = mapOf(
            ICON_DARK to ComponentName(context, ALIAS_DARK),
            ICON_LIGHT to ComponentName(context, ALIAS_LIGHT)
        )

        for ((key, component) in aliases) {
            val newState = if (key == targetKey) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                pm.setComponentEnabledSetting(
                    component,
                    newState,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {}
        }

        // Keep legacy alias disabled to prevent duplicate launcher icons
        try {
            pm.setComponentEnabledSetting(
                ComponentName(context, ALIAS_DEFAULT),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP
            )
        } catch (_: Exception) {}
    }
}
