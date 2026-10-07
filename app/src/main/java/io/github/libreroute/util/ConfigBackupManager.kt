package io.github.libreroute.util

import android.content.Context
import android.net.Uri
import io.github.libreroute.BuildConfig
import io.github.libreroute.data.Tunnel
import io.github.libreroute.data.TunnelRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

object ConfigBackupManager {

    private const val TAG = "ConfigBackupManager"

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    @Serializable
    data class LibreRouteBackup(
        val version: Int = 2,
        val timestamp: Long = System.currentTimeMillis(),
        val appVersion: String = BuildConfig.VERSION_NAME,
        val tunnels: List<Tunnel> = emptyList(),
        val splitTunnelMode: String? = null,
        val splitEnabled: Boolean? = null,
        val bypassApps: List<String> = emptyList(),
        val proxyApps: List<String> = emptyList(),
        val domainMode: Int? = null,
        val domains: List<String> = emptyList(),
        val ipRules: List<String> = emptyList(),
    )

    suspend fun exportBackup(context: Context, uri: Uri, password: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val repo = TunnelRepository(context)
            val rawTunnels = repo.load()
            val tunnels = rawTunnels.map { TunnelLinkParser.prepareForExport(context, it) }
            val splitPrefs = SplitTunnelPreferences(context)
            val domainPrefs = DomainRulesPreferences(context)

            val backup = LibreRouteBackup(
                version = 2,
                timestamp = System.currentTimeMillis(),
                appVersion = BuildConfig.VERSION_NAME,
                tunnels = tunnels,
                splitTunnelMode = splitPrefs.mode,
                splitEnabled = splitPrefs.isEnabled,
                bypassApps = splitPrefs.bypassApps.toList(),
                proxyApps = splitPrefs.proxyApps.toList(),
                domainMode = domainPrefs.mode,
                domains = domainPrefs.domains.toList(),
                ipRules = IpRulesPreferences(context).rules.toList(),
            )

            val jsonString = BackupEncryption.encrypt(json.encodeToString(backup), password)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(jsonString)
                }
            } ?: throw IllegalStateException("Failed to open output stream")

            tunnels.size
        }
    }

    suspend fun peekBackup(context: Context, uri: Uri, password: String = ""): Result<LibreRouteBackup> = withContext(Dispatchers.IO) {
        runCatching {
            val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    val buffer = CharArray(4096); val text = StringBuilder()
                    while (true) { val count = reader.read(buffer); if (count < 0) break
                        require(text.length + count <= 32 * 1024 * 1024) { "Резервная копия слишком большая" }; text.append(buffer, 0, count) }
                    text.toString()
                }
            } ?: throw IllegalStateException("Failed to open input stream")

            val plaintext = if (BackupEncryption.isEncrypted(content)) BackupEncryption.decrypt(content, password) else content
            val backup = json.decodeFromString<LibreRouteBackup>(plaintext)
            require(backup.version in 1..2) { "Неподдерживаемая версия данных резервной копии" }
            if (backup.tunnels.isEmpty() && backup.bypassApps.isEmpty() && backup.proxyApps.isEmpty() && backup.domains.isEmpty() && backup.ipRules.isEmpty()) {
                throw IllegalArgumentException("Backup file is empty or invalid")
            }
            backup
        }
    }

    suspend fun applyBackup(context: Context, backup: LibreRouteBackup): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val repo = TunnelRepository(context)
            require(backup.version in 1..2 && backup.tunnels.size <= 10_000) { "Неподдерживаемая резервная копия" }
            require(backup.ipRules.all(IpRulesPreferences::isValid)) { "Некорректные IP-правила в резервной копии" }
            require(backup.domains.all(DomainRulesPreferences::isValidDomain)) { "Некорректные домены в резервной копии" }
            require(backup.domainMode == null || backup.domainMode in 0..1) { "Некорректный режим доменных правил" }
            require(backup.splitTunnelMode == null || backup.splitTunnelMode in setOf("bypass", "proxy")) { "Некорректный режим разделения приложений" }
            require(backup.tunnels.all { it.name.isNotBlank() && it.transportConnPayload.size <= 512 }) { "Некорректный маршрут в резервной копии" }
            val current = repo.load()
            val portable = current.map { TunnelLinkParser.prepareForExport(context, it) }
            val merged = BackupMerge.merge(portable, backup.tunnels) { System.currentTimeMillis() * 1000L + kotlin.random.Random.nextLong(1000L) }
            val added = merged.drop(portable.size).map { TunnelLinkParser.ensureLocalKeyFile(context, it) }
            val importedCount = added.size
            repo.save(current + added)

            // Restore app split tunneling
            val splitPrefs = SplitTunnelPreferences(context)
            backup.splitTunnelMode?.let { splitPrefs.mode = it }
            backup.splitEnabled?.let { splitPrefs.isEnabled = it }
            if (backup.version >= 2 || backup.bypassApps.isNotEmpty()) splitPrefs.bypassApps = backup.bypassApps.toSet()
            if (backup.version >= 2 || backup.proxyApps.isNotEmpty()) splitPrefs.proxyApps = backup.proxyApps.toSet()

            // Restore domain rules
            val domainPrefs = DomainRulesPreferences(context)
            backup.domainMode?.let { domainPrefs.mode = it }
            if (backup.version >= 2) domainPrefs.domains = backup.domains.toSet()
            else if (backup.domains.isNotEmpty()) domainPrefs.addPreset(backup.domains.toSet())
            if (backup.version >= 2 && !IpRulesPreferences(context).update(backup.ipRules.joinToString("\n"))) {
                throw IllegalArgumentException("Invalid IP rules in backup")
            }

            importedCount
        }
    }

    suspend fun importBackup(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        val peek = peekBackup(context, uri)
        peek.fold(
            onSuccess = { backup -> applyBackup(context, backup) },
            onFailure = { Result.failure(it) }
        )
    }
}
