package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.content.SharedPreferences

class DomainRulesPreferences(context: Context) {

    companion object {
        private const val PREFS_NAME = "fluxon_domain_rules"
        private const val KEY_MODE = "domain_mode"
        private const val KEY_DOMAINS = "domains"

        const val MODE_BYPASS = 0
        const val MODE_PROXY = 1

        private val cache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

        private fun loadAssetPreset(context: Context, filename: String, fallback: Set<String>): Set<String> {
            val cached = cache[filename]
            if (cached != null) return cached
            val loaded = runCatching {
                context.assets.open("presets/$filename").bufferedReader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toSet()
                }
            }.getOrDefault(fallback)
            if (loaded.isNotEmpty()) {
                cache[filename] = loaded
            }
            return loaded
        }

        /**
         * Российские сервисы, банки, госуслуги и доменные зоны
         * Источники: UnRKN/ru-blocklist и itdoginfo/allow-domains (Russia outside)
         */
        val PRESET_RU_DOMAINS = setOf(
            "ru", "рф", "su",
            "yandex.ru", "ya.ru", "dzen.ru", "mail.ru", "rambler.ru",
            "vk.com", "vk.me", "userapi.com", "ok.ru",
            "gosuslugi.ru", "mos.ru", "nalog.gov.ru", "cbr.ru", "pochta.ru", "emias.info",
            "sberbank.ru", "sber.ru", "tbank.ru", "tinkoff.ru", "vtb.ru", "alfabank.ru",
            "gazprombank.ru", "raiffeisen.ru", "sovcombank.ru", "open.ru", "rshb.ru", "nspk.ru",
            "avito.ru", "ozon.ru", "wildberries.ru", "market.yandex.ru", "cdek.ru", "samokat.ru", "kuper.ru",
            "auto.ru", "cian.ru", "domclick.ru", "2gis.ru", "2ip.ru",
            "mts.ru", "megafon.ru", "beeline.ru", "t2.ru", "rostelecom.ru",
            "kinopoisk.ru", "rutube.ru", "smotrim.ru", "1tv.ru", "ntv.ru", "habr.com"
        )

        /**
         * Популярные заблокированные сервисы (соцсети, медиа, торренты)
         * Источники: itdoginfo/allow-domains (Russia inside) и UnRKN
         */
        val PRESET_BLOCKED_DOMAINS = setOf(
            "instagram.com", "cdninstagram.com", "threads.net",
            "facebook.com", "fbcdn.net", "meta.com",
            "twitter.com", "x.com", "t.co", "twimg.com",
            "linkedin.com", "licdn.com",
            "rutracker.org", "flibusta.is", "kinozal.tv", "nnmclub.to", "hdrezka.me", "rezka.ag", "anilibria.tv",
            "meduza.io", "bbc.com", "dw.com", "svoboda.org", "theins.ru", "zona.media",
            "notion.so", "canva.com", "spotify.com", "medium.com", "adguard-vpn.com"
        )

        /**
         * YouTube и видеосервисы Google
         * Источник: itdoginfo/allow-domains (youtube.lst) и v2fly
         */
        val PRESET_YOUTUBE_DOMAINS = setOf(
            "youtube.com", "ytimg.com", "googlevideo.com", "ggpht.com",
            "youtu.be", "youtubekids.com", "youtube-nocookie.com",
            "youtubei.googleapis.com", "yt.be", "wide-youtube.l.google.com", "ytimg.l.google.com"
        )

        /**
         * Discord и сопутствующие голосовые/медиа серверы
         * Источник: itdoginfo/allow-domains (discord.lst) и v2fly
         */
        val PRESET_DISCORD_DOMAINS = setOf(
            "discord.com", "discord.gg", "discordapp.com", "discordapp.net",
            "discord.media", "dis.gd", "discordstatus.com", "discord.co"
        )

        /**
         * Зарубежные нейросети и AI платформы
         * Источник: v2fly community & allow-domains
         */
        val PRESET_AI_DOMAINS = setOf(
            "openai.com", "chatgpt.com", "oaistatic.com", "oaiusercontent.com",
            "anthropic.com", "claude.ai", "midjourney.com", "perplexity.ai",
            "copilot.microsoft.com"
        )

        fun getPresetRu(context: Context): Set<String> =
            loadAssetPreset(context, "preset_ru.txt", PRESET_RU_DOMAINS)

        fun getPresetBlocked(context: Context): Set<String> =
            loadAssetPreset(context, "preset_blocked.txt", PRESET_BLOCKED_DOMAINS)

        fun getPresetYoutube(context: Context): Set<String> =
            loadAssetPreset(context, "preset_youtube.txt", PRESET_YOUTUBE_DOMAINS)

        fun getPresetDiscord(context: Context): Set<String> =
            loadAssetPreset(context, "preset_discord.txt", PRESET_DISCORD_DOMAINS)

        fun getPresetAi(context: Context): Set<String> =
            loadAssetPreset(context, "preset_ai.txt", PRESET_AI_DOMAINS)
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var mode: Int
        get() = prefs.getInt(KEY_MODE, MODE_BYPASS)
        set(value) = prefs.edit().putInt(KEY_MODE, value).apply()

    var domains: Set<String>
        get() = prefs.getStringSet(KEY_DOMAINS, emptySet())?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_DOMAINS, value).apply()

    fun addDomain(domain: String): Boolean {
        val clean = domain.trim().lowercase().removePrefix("https://").removePrefix("http://").trimEnd('/')
        if (clean.isBlank()) return false
        val updated = domains.toMutableSet()
        val added = updated.add(clean)
        if (added) domains = updated
        return added
    }

    fun removeDomain(domain: String): Boolean {
        val updated = domains.toMutableSet()
        val removed = updated.remove(domain)
        if (removed) domains = updated
        return removed
    }

    fun addPreset(preset: Set<String>) {
        val updated = domains.toMutableSet()
        updated.addAll(preset)
        domains = updated
    }

    fun clearAll() {
        domains = emptySet()
    }

    fun hasActiveRules(): Boolean = domains.isNotEmpty()

    fun getModeString(): String = if (mode == MODE_PROXY) "proxy" else "bypass"

    fun getRulesFilePath(context: Context): String =
        java.io.File(context.filesDir, "domain_rules.txt").absolutePath

    fun writeRulesFile(context: Context): java.io.File {
        val file = java.io.File(context.filesDir, "domain_rules.txt")
        val activeList = domains
        file.bufferedWriter().use { writer ->
            for (d in activeList) {
                val clean = d.trim().lowercase()
                if (clean.isNotEmpty()) {
                    writer.write(clean)
                    writer.newLine()
                }
            }
        }
        return file
    }
}
