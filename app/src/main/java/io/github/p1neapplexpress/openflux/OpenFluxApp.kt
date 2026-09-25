package io.github.p1neapplexpress.openflux

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.Logx
import io.github.p1neapplexpress.openflux.util.ThemePreferences

class OpenFluxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logx.init(BuildConfig.DEBUG)
        ThemePreferences(this).applyTheme()

        val lang = AppSettings(this).appLanguage
        val locales = when (lang) {
            "ru" -> LocaleListCompat.forLanguageTags("ru")
            "en" -> LocaleListCompat.forLanguageTags("en")
            else -> LocaleListCompat.getEmptyLocaleList()
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }
}

