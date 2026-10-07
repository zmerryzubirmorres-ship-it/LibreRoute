package io.github.libreroute.util

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import io.github.libreroute.BuildConfig
import io.github.libreroute.R
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object AppUpdateChecker {

    private const val TAG = "AppUpdateChecker"
    private const val PREFS_NAME = "libreroute_update_checker"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L // 24 hours
    private const val UPDATE_DIR = "updates"

    private val versionPattern = Regex("^[vV]?(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?(?:[-+][0-9A-Za-z.-]+)?$")

    fun projectUrlOrNull(): String? = BuildConfig.UPDATE_REPOSITORY
        .takeIf { it.isNotBlank() }
        ?.let { "https://github.com/$it" }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Serializable
    data class GitHubRelease(
        val tag_name: String = "",
        val name: String? = null,
        val html_url: String = "",
        val body: String? = null,
        val assets: List<GitHubAsset> = emptyList(),
    )

    @Serializable
    data class GitHubAsset(
        val name: String = "",
        val browser_download_url: String = "",
    )

    fun checkForUpdate(
        activity: Activity,
        force: Boolean = false,
        onResult: ((hasUpdate: Boolean, versionOrError: String) -> Unit)? = null
    ) {
        val repository = BuildConfig.UPDATE_REPOSITORY
        if (repository.isBlank()) {
            if (force) onResult?.invoke(false, activity.getString(R.string.update_source_not_configured))
            return
        }
        if (BuildConfig.DEBUG && !force) return

        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastCheck = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        val now = System.currentTimeMillis()

        if (!force) {
            val appSettings = AppSettings(activity)
            if (!appSettings.autoUpdateCheck) {
                return
            }
            if ((now - lastCheck) < CHECK_INTERVAL_MS) {
                return
            }
        }

        (activity as? LifecycleOwner)?.lifecycleScope?.launch(Dispatchers.IO) {
            try {
                val url = URL("https://api.github.com/repos/$repository/releases/latest")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000
                    readTimeout = 7000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "LibreRoute-Android/${BuildConfig.VERSION_NAME}")
                }

                if (conn.responseCode != 200) {
                    val code = conn.responseCode
                    Logx.d(TAG, "GitHub releases API returned HTTP $code")
                    conn.disconnect()
                    withContext(Dispatchers.Main) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onResult?.invoke(false, "HTTP $code")
                        }
                    }
                    return@launch
                }

                val responseBody = BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
                conn.disconnect()
                val release = json.decodeFromString<GitHubRelease>(responseBody)

                prefs.edit().putLong(KEY_LAST_CHECK_MS, now).apply()

                val remoteTag = release.tag_name
                val currentVersion = BuildConfig.VERSION_NAME

                if (isNewerVersion(remoteTag, currentVersion)) {
                    Logx.i(TAG, "Update available: $remoteTag (current: $currentVersion)")
                    val projectUrl = projectUrlOrNull()!!
                    val apkAsset = release.assets.firstOrNull {
                        it.name.endsWith(".apk", ignoreCase = true) &&
                            !it.name.contains("debug", ignoreCase = true) &&
                            it.browser_download_url.startsWith("$projectUrl/releases/download/")
                    }
                    // Only offer the in-app installer when the release contains an APK.
                    // Falling back to the release page would download HTML and produce a
                    // confusing installer error.
                    val downloadUrl = apkAsset?.browser_download_url

                    withContext(Dispatchers.Main) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onResult?.invoke(true, remoteTag)
                            try {
                                showUpdateDialog(activity, release, downloadUrl)
                            } catch (e: Exception) {
                                Logx.d(TAG, "Could not show update dialog: ${e.message}")
                            }
                        }
                    }
                } else {
                    Logx.d(TAG, "App is up to date: current=$currentVersion, remote=$remoteTag")
                    withContext(Dispatchers.Main) {
                        if (!activity.isFinishing && !activity.isDestroyed) {
                            onResult?.invoke(false, remoteTag)
                        }
                    }
                }
            } catch (e: Exception) {
                Logx.d(TAG, "Failed to check for updates: ${e.message}")
                withContext(Dispatchers.Main) {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        onResult?.invoke(false, e.message ?: "Network error")
                    }
                }
            }
        }
    }

    fun showTestUpdateDialog(activity: Activity) {
        val releaseUrl = projectUrlOrNull()?.plus("/releases")
        val testRelease = GitHubRelease(
            tag_name = "v1.2.0",
            name = "LibreRoute v1.2.0 (Тестовый релиз)",
            html_url = releaseUrl.orEmpty(),
            body = "1. **Тестирование системы обновлений**:\n   - Уведомление в приложении работает корректно!\n2. **Улучшения интерфейса**:\n   - Проверка всех функций приложения.\n   - Быстрый отклик и стабильность.",
        )
        showUpdateDialog(activity, testRelease, releaseUrl)
    }

    fun resetLastCheckTime(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_LAST_CHECK_MS).apply()
    }

    private fun versionParts(tag: String): List<Long>? {
        val match = versionPattern.matchEntire(tag.trim()) ?: return null
        return (1..3).map { index ->
            match.groupValues[index].ifEmpty { "0" }.toLongOrNull() ?: return null
        }
    }

    fun isVersionTag(tag: String): Boolean = versionParts(tag) != null

    fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
        val remoteParts = versionParts(remoteTag) ?: return false
        val currentParts = versionParts(currentVersion) ?: return false
        for (i in 0..2) {
            val r = remoteParts[i]
            val c = currentParts[i]
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }

    private fun showUpdateDialog(activity: Activity, release: GitHubRelease, downloadUrl: String?) {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val view = activity.layoutInflater.inflate(R.layout.dialog_update_available, null)
        dialog.setContentView(view)

        val versionText = String.format(
            activity.getString(R.string.update_version_format),
            release.tag_name,
            BuildConfig.VERSION_NAME
        )
        view.findViewById<TextView>(R.id.update_version_info).text = versionText

        val notesView = view.findViewById<TextView>(R.id.update_notes)
        val body = release.body?.trim().orEmpty()
        if (body.isNotEmpty()) {
            notesView.text = renderMarkdown(body)
        } else {
            notesView.text = activity.getString(R.string.update_no_changelog)
        }

        val downloadButton = view.findViewById<View>(R.id.btn_download_update)
        downloadButton.apply {
            visibility = if (downloadUrl == null) View.GONE else View.VISIBLE
            if (downloadUrl != null) {
                setOnClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (!downloadButton.isEnabled) return@setOnClickListener
                    downloadButton.isEnabled = false
                    (downloadButton as? TextView)?.text = activity.getString(R.string.update_downloading)
                    val scope = (activity as? LifecycleOwner)?.lifecycleScope
                        ?: return@setOnClickListener
                    scope.launch {
                        try {
                            val apk = withContext(Dispatchers.IO) {
                                downloadApk(activity, downloadUrl, release.tag_name) { progress ->
                                    // Keep the dialog responsive while the file is downloaded.
                                    activity.runOnUiThread {
                                        (downloadButton as? TextView)?.text = activity.getString(
                                            R.string.update_downloading_percent,
                                            progress
                                        )
                                    }
                                }
                            }
                            val uri = FileProvider.getUriForFile(
                                activity,
                                "${activity.packageName}.fileprovider",
                                apk
                            )
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, "application/vnd.android.package-archive")
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            dialog.dismiss()
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            Logx.e(TAG, "Failed to download or launch update", e)
                            downloadButton.isEnabled = true
                            (downloadButton as? TextView)?.text = activity.getString(R.string.action_update_download)
                            Toast.makeText(
                                activity,
                                activity.getString(R.string.update_download_error),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
        }

        view.findViewById<View>(R.id.btn_update_later).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }

        dialog.show()

        val width = (activity.resources.displayMetrics.widthPixels * 0.88).toInt()
        val maxNotesHeight = (activity.resources.displayMetrics.heightPixels * 0.36).toInt()
        val notesScroll = view.findViewById<android.widget.ScrollView>(R.id.update_notes_scroll)
        notesScroll.post {
            if (notesScroll.height > maxNotesHeight) {
                notesScroll.layoutParams = notesScroll.layoutParams.apply {
                    height = maxNotesHeight
                }
            }
        }
        dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** Downloads the release APK into app-private storage and returns an atomic, complete file. */
    private fun downloadApk(
        context: Context,
        downloadUrl: String,
        releaseTag: String,
        onProgress: (Int) -> Unit
    ): File {
        val updateDir = File(context.filesDir, UPDATE_DIR).apply { mkdirs() }
        val safeTag = releaseTag.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val apkFile = File(updateDir, "LibreRoute-$safeTag.apk")
        val tempFile = File(updateDir, "$safeTag.apk.part")
        val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.android.package-archive")
            setRequestProperty("User-Agent", "LibreRoute-Android/${BuildConfig.VERSION_NAME}")
        }
        try {
            val response = connection.responseCode
            if (response !in 200..299) error("HTTP $response")
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastProgress = -1
            connection.inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            val progress = ((downloaded * 100L) / total).toInt().coerceIn(0, 100)
                            if (progress != lastProgress) {
                                lastProgress = progress
                                onProgress(progress)
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (!tempFile.isFile || tempFile.length() == 0L) error("Empty APK")
            if (apkFile.exists() && !apkFile.delete()) error("Cannot replace old APK")
            if (!tempFile.renameTo(apkFile)) error("Cannot finalize APK")
            return apkFile
        } finally {
            connection.disconnect()
            if (tempFile.exists()) tempFile.delete()
        }
    }

    /**
     * Lightweight GitHub Markdown → SpannableString renderer.
     * Supports: ### headers, **bold**, *italic*, `code`, - bullets, blank line spacing.
     */
    private fun renderMarkdown(raw: String): CharSequence {
        val sb = SpannableStringBuilder()
        val lines = raw.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                // H3 ###
                trimmed.startsWith("### ") -> {
                    if (sb.isNotEmpty()) sb.append("\n")
                    val text = trimmed.removePrefix("### ")
                    val start = sb.length
                    sb.append(renderInline(text))
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.1f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append("\n")
                }
                // H2 ##
                trimmed.startsWith("## ") -> {
                    if (sb.isNotEmpty()) sb.append("\n")
                    val text = trimmed.removePrefix("## ")
                    val start = sb.length
                    sb.append(renderInline(text))
                    sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(1.15f), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.append("\n")
                }
                // Bullet - or *
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    val text = trimmed.drop(2)
                    sb.append("• ")
                    sb.append(renderInline(text))
                    sb.append("\n")
                }
                // Blank line → spacing
                trimmed.isEmpty() -> {
                    if (sb.isNotEmpty() && !sb.endsWith("\n\n")) sb.append("\n")
                }
                // Normal paragraph
                else -> {
                    sb.append(renderInline(trimmed))
                    sb.append("\n")
                }
            }
            i++
        }
        // Trim trailing newlines
        while (sb.endsWith("\n")) sb.delete(sb.length - 1, sb.length)
        return sb
    }

    /** Renders inline Markdown: **bold**, *italic*, `code` within a line. */
    private fun renderInline(text: String): SpannableStringBuilder {
        val sb = SpannableStringBuilder()
        val len = text.length
        var pos = 0
        while (pos < len) {
            when {
                // **bold**
                text.startsWith("**", pos) -> {
                    val end = text.indexOf("**", pos + 2)
                    if (end > pos + 2) {
                        val start = sb.length
                        sb.append(text.substring(pos + 2, end))
                        sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        pos = end + 2
                    } else { sb.append(text[pos]); pos++ }
                }
                // *italic*
                text.startsWith("*", pos) && !text.startsWith("**", pos) -> {
                    val end = text.indexOf("*", pos + 1)
                    if (end > pos + 1) {
                        val start = sb.length
                        sb.append(text.substring(pos + 1, end))
                        sb.setSpan(StyleSpan(Typeface.ITALIC), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        pos = end + 1
                    } else { sb.append(text[pos]); pos++ }
                }
                // `code`
                text[pos] == '`' -> {
                    val end = text.indexOf('`', pos + 1)
                    if (end > pos + 1) {
                        val start = sb.length
                        sb.append(text.substring(pos + 1, end))
                        sb.setSpan(TypefaceSpan("monospace"), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        pos = end + 1
                    } else { sb.append(text[pos]); pos++ }
                }
                else -> { sb.append(text[pos]); pos++ }
            }
        }
        return sb
    }
}
