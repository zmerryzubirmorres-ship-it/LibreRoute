package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatDelegate

class ThemePreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "openflux_theme"
        private const val KEY_THEME_MODE = "theme_mode"

        const val THEME_SYSTEM = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        const val THEME_DARK = AppCompatDelegate.MODE_NIGHT_YES
        const val THEME_LIGHT = AppCompatDelegate.MODE_NIGHT_NO
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var themeMode: Int
        get() = prefs.getInt(KEY_THEME_MODE, THEME_DARK)
        set(value) = prefs.edit().putInt(KEY_THEME_MODE, value).apply()

    fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(themeMode)
    }
}
