package io.github.libreroute

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.libreroute.event.EventBus
import io.github.libreroute.util.AppSettings
import io.github.libreroute.util.FileLogger
import io.github.libreroute.util.Logx
import io.github.libreroute.util.ThemePreferences

class LibreRouteApp : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
        Logx.init(BuildConfig.DEBUG)
        ThemePreferences(this).applyTheme()

        val savedLogs = FileLogger.readRecentLines(this, 1000)
        if (savedLogs.isNotEmpty()) {
            EventBus.initFromSavedLogs(savedLogs)
        }

        val lang = AppSettings(this).appLanguage
        val locales = when (lang) {
            "ru" -> LocaleListCompat.forLanguageTags("ru")
            "en" -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
        io.github.libreroute.admin.RouteSyncWorker.schedule(this)
    }
}
