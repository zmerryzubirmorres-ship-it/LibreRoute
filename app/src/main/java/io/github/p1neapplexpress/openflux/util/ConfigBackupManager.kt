package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.net.Uri
import io.github.p1neapplexpress.openflux.BuildConfig
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelRepository
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
    data class FluxonBackup(
        val version: Int = 1,
        val timestamp: Long = System.currentTimeMillis(),
        val appVersion: String = BuildConfig.VERSION_NAME,
        val tunnels: List<Tunnel> = emptyList(),
        val splitTunnelMode: String? = null,
        val bypassApps: List<String> = emptyList(),
        val proxyApps: List<String> = emptyList(),
        val domainMode: Int? = null,
        val domains: List<String> = emptyList(),
    )

    suspend fun exportBackup(context: Context, uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val repo = TunnelRepository(context)
            val rawTunnels = repo.load()
            val tunnels = rawTunnels.map { TunnelLinkParser.prepareForExport(context, it) }
            val splitPrefs = SplitTunnelPreferences(context)
            val domainPrefs = DomainRulesPreferences(context)

            val backup = FluxonBackup(
                version = 1,
                timestamp = System.currentTimeMillis(),
                appVersion = BuildConfig.VERSION_NAME,
                tunnels = tunnels,
                splitTunnelMode = splitPrefs.mode,
                bypassApps = splitPrefs.bypassApps.toList(),
                proxyApps = splitPrefs.proxyApps.toList(),
                domainMode = domainPrefs.mode,
                domains = domainPrefs.domains.toList(),
            )

            val jsonString = json.encodeToString(backup)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os, Charsets.UTF_8).use { writer ->
                    writer.write(jsonString)
                }
            } ?: throw IllegalStateException("Failed to open output stream")

            tunnels.size
        }
    }

    suspend fun peekBackup(context: Context, uri: Uri): Result<FluxonBackup> = withContext(Dispatchers.IO) {
        runCatching {
            val content = context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8)).use { reader ->
                    reader.readText()
                }
            } ?: throw IllegalStateException("Failed to open input stream")

            val backup = json.decodeFromString<FluxonBackup>(content)
            if (backup.tunnels.isEmpty() && backup.bypassApps.isEmpty() && backup.proxyApps.isEmpty() && backup.domains.isEmpty()) {
                throw IllegalArgumentException("Backup file is empty or invalid")
            }
            backup
        }
    }

    suspend fun applyBackup(context: Context, backup: FluxonBackup): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val repo = TunnelRepository(context)
            val currentTunnels = repo.load().toMutableList()

            var importedCount = 0
            val usedIds = currentTunnels.map { it.id }.toMutableSet()
            for (rawTunnel in backup.tunnels) {
                val idResolved = if (usedIds.contains(rawTunnel.id) && currentTunnels.none { it.id == rawTunnel.id && it.name == rawTunnel.name }) {
                    var newId = System.currentTimeMillis() * 1000L + kotlin.random.Random.nextLong(1000L)
                    while (usedIds.contains(newId)) { newId++ }
                    rawTunnel.copy(id = newId)
                } else rawTunnel

                // Recreate local key file on disk with private 0600 permissions and update --encryption-key-file payload
                val tunnel = TunnelLinkParser.ensureLocalKeyFile(context, idResolved)
                usedIds.add(tunnel.id)
                val existingIndex = currentTunnels.indexOfFirst { it.name == tunnel.name }
                if (existingIndex >= 0) {
                    currentTunnels[existingIndex] = tunnel
                } else {
                    currentTunnels.add(tunnel)
                }
                importedCount++
            }
            repo.save(currentTunnels)

            // Restore app split tunneling
            val splitPrefs = SplitTunnelPreferences(context)
            backup.splitTunnelMode?.let { splitPrefs.mode = it }
            if (backup.bypassApps.isNotEmpty()) {
                splitPrefs.bypassApps = backup.bypassApps.toSet()
            }
            if (backup.proxyApps.isNotEmpty()) {
                splitPrefs.proxyApps = backup.proxyApps.toSet()
            }

            // Restore domain rules
            val domainPrefs = DomainRulesPreferences(context)
            backup.domainMode?.let { domainPrefs.mode = it }
            if (backup.domains.isNotEmpty()) {
                domainPrefs.addPreset(backup.domains.toSet())
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
