package io.github.p1neapplexpress.openflux.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.animation.OvershootInterpolator
import android.widget.PopupWindow
import com.google.android.material.bottomsheet.BottomSheetDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.event.EventBus
import io.github.p1neapplexpress.openflux.util.performAppHaptics
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

class LogsFragment : BaseFragment() {

    companion object {
        private const val MAX_LINES = 1500
        private const val FLUSH_INTERVAL_MS = 150L
    }

    enum class LogCategory {
        ALL, ERROR, WARN, INFO, DEBUG
    }

    data class LogItem(
        val message: String,
        val timestamp: Long,
        val category: LogCategory
    )

    private lateinit var textView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var tvLineCount: TextView
    private lateinit var btnLogsMore: ImageView
    private lateinit var btnConnectionLogLevel: com.google.android.material.button.MaterialButton

    private var activeLogLevelPopup: PopupWindow? = null
    private var activeMoreMenuDialog: BottomSheetDialog? = null

    private var autoScroll = true
    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val allLogItems = mutableListOf<LogItem>()
    private val pending = ConcurrentLinkedQueue<Pair<LogItem, Long>>()
    private var renderedLines = 0
    private var flushScheduled = false

    override fun onNewEvent(ev: AppEvent) = Unit

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_logs, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<MaterialToolbar>(R.id.logs_top_bar)?.setNavigationOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        view.findViewById<View>(R.id.btn_back)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        textView = view.findViewById(R.id.logs)
        scrollView = view.findViewById(R.id.log_scroll)
        tvLineCount = view.findViewById(R.id.tv_line_count)
        btnLogsMore = view.findViewById(R.id.btn_logs_more)
        btnConnectionLogLevel = view.findViewById(R.id.btn_connection_log_level)

        val appSettings = io.github.p1neapplexpress.openflux.util.AppSettings(requireContext())

        fun updateLogLevelButton() {
            val current = io.github.p1neapplexpress.openflux.service.NativeProcessSupervisor.activeLogLevel.ifBlank {
                appSettings.connectionLogLevel
            }
            btnConnectionLogLevel.text = current
        }
        updateLogLevelButton()

        btnConnectionLogLevel.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showLogLevelPopup(it, appSettings)
        }

        btnLogsMore.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showMoreMenu(it)
        }
        if (appSettings.autoClearLogs) {
            val logPrefs = requireContext().getSharedPreferences("fluxon_logs_meta", Context.MODE_PRIVATE)
            val lastLogTime = logPrefs.getLong("last_log_ts", 0L)
            val now = System.currentTimeMillis()
            if (lastLogTime > 0 && (now - lastLogTime) > 24 * 60 * 60 * 1000L) {
                EventBus.clearLogHistory()
                pending.clear()
                allLogItems.clear()
                renderedLines = 0
                textView.text = ""
            }
            logPrefs.edit().putLong("last_log_ts", now).apply()
        }

        // Initialize history from ring buffer
        val history = EventBus.getLogEntries()
        if (history.isNotEmpty()) {
            for (entry in history) {
                entry.message.split('\n').forEach { subline ->
                    if (subline.isNotBlank()) {
                        val item = LogItem(subline, entry.timestamp, detectCategory(subline))
                        allLogItems.add(item)
                    }
                }
            }
            if (allLogItems.size > MAX_LINES) {
                val excess = allLogItems.size - MAX_LINES
                repeat(excess) { allLogItems.removeAt(0) }
            }
            rebuildFilteredLogs()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                EventBus.events.collect { ev ->
                    if (ev is AppEvent.LogMessage) enqueue(ev.message)
                }
            }
        }
    }

    private fun detectCategory(line: String): LogCategory = when {
        line.contains("[E]") || line.contains("error", ignoreCase = true) ||
                line.contains("panic:", ignoreCase = true) || line.contains("Failed to start", ignoreCase = true) -> LogCategory.ERROR
        line.contains("[W]") || line.contains("warning", ignoreCase = true) -> LogCategory.WARN
        line.contains("[I]") || line.contains("[S]") || line.contains("[ROUTER]") ||
                line.contains("PROXY") || line.contains("DIRECT") || line.contains("gVisor") -> LogCategory.INFO
        line.contains("[D]") || line.contains("debug", ignoreCase = true) ||
                line.startsWith("<- ") || line.startsWith("-> ") ||
                line.contains("bytes - TCP") || line.contains("bytes - UDP") -> LogCategory.DEBUG
        else -> LogCategory.INFO
    }

    private fun rebuildFilteredLogs() {
        pending.clear()
        renderedLines = 0
        textView.text = ""

        val toAppend = SpannableStringBuilder()
        for (item in allLogItems) {
            renderLogLine(toAppend, item.message, item.timestamp)
        }
        if (toAppend.isNotEmpty()) {
            textView.text = toAppend
            if (autoScroll) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }
        updateLineCountBadge()
    }

    private fun updateLineCountBadge() {
        if (renderedLines > 0) {
            tvLineCount.visibility = View.VISIBLE
            tvLineCount.text = getString(R.string.logs_lines_count, renderedLines)
        } else {
            tvLineCount.visibility = View.GONE
        }
    }


    private fun enqueue(message: String) {
        val now = System.currentTimeMillis()
        message.split('\n').forEach { raw ->
            if (raw.isNotEmpty()) {
                val item = LogItem(raw, now, detectCategory(raw))
                allLogItems.add(item)
                if (allLogItems.size > MAX_LINES) {
                    allLogItems.removeAt(0)
                }
                pending.offer(Pair(item, now))
            }
        }

        if (!flushScheduled && !pending.isEmpty()) {
            flushScheduled = true
            textView.postDelayed({ flushPending(); flushScheduled = false }, FLUSH_INTERVAL_MS)
        }
    }

    private fun renderLogLine(toAppend: SpannableStringBuilder, line: String, timestamp: Long) {
        if (renderedLines > 0 || toAppend.isNotEmpty()) toAppend.append('\n')

        val lineNo = (renderedLines + 1).toString().padStart(4)
        val base = toAppend.length
        toAppend.append(lineNo).append(' ')
        val isNight = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

        val timeColor = if (isNight) 0xFF94A3B8.toInt() else 0xFF64748B.toInt()
        val lineNoColor = if (isNight) 0xFF64748B.toInt() else 0xFF94A3B8.toInt()
        toAppend.setSpan(ForegroundColorSpan(lineNoColor), base, base + 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val timePrefix = "[${ts.format(Date(timestamp))}] "
        val tsStart = toAppend.length
        toAppend.append(timePrefix)
        toAppend.setSpan(ForegroundColorSpan(timeColor), tsStart, tsStart + timePrefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        val msgStart = toAppend.length
        toAppend.append(line)

        val tagSpan = when {
            line.contains("[E]") || line.contains("error", ignoreCase = true) || line.contains("panic:", ignoreCase = true) ->
                ForegroundColorSpan(if (isNight) 0xFFEF4444.toInt() else 0xFFDC2626.toInt())
            line.contains("[W]") || line.contains("warning", ignoreCase = true) ->
                ForegroundColorSpan(if (isNight) 0xFFF59E0B.toInt() else 0xFFD97706.toInt())
            line.contains("DIRECT") ->
                ForegroundColorSpan(if (isNight) 0xFF38BDF8.toInt() else 0xFF0284C7.toInt())
            line.contains("[I]") || line.contains("[S]") || line.contains("[ROUTER]") ||
                    line.contains("PROXY") || line.contains("gVisor") ->
                ForegroundColorSpan(if (isNight) 0xFF10B981.toInt() else 0xFF059669.toInt())
            line.contains("[D]") || line.contains("debug", ignoreCase = true) ->
                ForegroundColorSpan(if (isNight) 0xFF94A3B8.toInt() else 0xFF64748B.toInt())
            else -> null
        }
        if (tagSpan != null) {
            toAppend.setSpan(tagSpan, msgStart, msgStart + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        renderedLines++
    }

    private fun flushPending(drainAll: Boolean = false) {
        if (pending.isEmpty()) return

        val toAppend = SpannableStringBuilder()
        var appended = 0
        val maxBatch = if (drainAll) Int.MAX_VALUE else 64
        while (appended < maxBatch) {
            val item = pending.poll() ?: break
            renderLogLine(toAppend, item.first.message, item.second)
            appended++
        }

        if (toAppend.isNotEmpty()) {
            textView.append(toAppend)
            updateLineCountBadge()
            if (autoScroll) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
        }

        if (!pending.isEmpty() && !flushScheduled) {
            flushScheduled = true
            textView.postDelayed({ flushPending(); flushScheduled = false }, FLUSH_INTERVAL_MS)
        }
    }

    override fun onDestroyView() {
        activeLogLevelPopup?.dismiss()
        activeLogLevelPopup = null
        activeMoreMenuDialog?.dismiss()
        activeMoreMenuDialog = null
        textView.handler?.removeCallbacksAndMessages(null)
        pending.clear()
        super.onDestroyView()
    }

    private fun showLogLevelPopup(anchor: View, appSettings: io.github.p1neapplexpress.openflux.util.AppSettings) {
        activeLogLevelPopup?.dismiss()
        val inflater = LayoutInflater.from(requireContext())
        val menuView = inflater.inflate(R.layout.popup_log_level_menu, null)

        val popup = PopupWindow(
            menuView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            elevation = 12f
            isOutsideTouchable = true
            isFocusable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        activeLogLevelPopup = popup

        val current = io.github.p1neapplexpress.openflux.service.NativeProcessSupervisor.activeLogLevel.ifBlank {
            appSettings.connectionLogLevel
        }

        menuView.findViewById<View>(R.id.check_level_debug)?.isVisible = (current == "DEBUG")
        menuView.findViewById<View>(R.id.check_level_info)?.isVisible = (current == "INFO")
        menuView.findViewById<View>(R.id.check_level_warn)?.isVisible = (current == "WARN")
        menuView.findViewById<View>(R.id.check_level_error)?.isVisible = (current == "ERROR")

        fun selectLevel(selected: String) {
            popup.dismiss()
            appSettings.connectionLogLevel = selected
            io.github.p1neapplexpress.openflux.service.NativeProcessSupervisor.activeLogLevel = selected
            io.github.p1neapplexpress.openflux.util.Logx.setVerbose(selected == "DEBUG")
            btnConnectionLogLevel.text = selected
            EventBus.dispatch(AppEvent.LogMessage("[I] ${getString(R.string.connection_log_level_changed, selected)}"))
            Toast.makeText(
                requireContext(),
                getString(R.string.connection_log_level_changed, selected),
                Toast.LENGTH_SHORT
            ).show()
        }

        menuView.findViewById<View>(R.id.option_level_debug)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            selectLevel("DEBUG")
        }
        menuView.findViewById<View>(R.id.option_level_info)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            selectLevel("INFO")
        }
        menuView.findViewById<View>(R.id.option_level_warn)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            selectLevel("WARN")
        }
        menuView.findViewById<View>(R.id.option_level_error)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            selectLevel("ERROR")
        }

        menuView.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val menuWidth = menuView.measuredWidth
        val density = resources.displayMetrics.density

        val xOff = -(menuWidth - anchor.width)
        val yOff = (4 * density).toInt()

        menuView.alpha = 0f
        menuView.scaleX = 0.95f
        menuView.scaleY = 0.95f

        popup.showAsDropDown(anchor, xOff, yOff)

        menuView.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(160)
            .setInterpolator(OvershootInterpolator(1.1f))
            .start()
    }

    private fun showMoreMenu(anchor: View) {
        activeMoreMenuDialog?.dismiss()
        val dialog = BottomSheetDialog(requireContext())
        val menuView = layoutInflater.inflate(R.layout.bottom_sheet_logs_more, null)
        dialog.setContentView(menuView)
        activeMoreMenuDialog = dialog

        val checkAutoScroll = menuView.findViewById<View>(R.id.check_logs_autoscroll)
        checkAutoScroll?.isVisible = autoScroll

        menuView.findViewById<View>(R.id.menu_logs_autoscroll)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            autoScroll = !autoScroll
            checkAutoScroll?.isVisible = autoScroll
            dialog.dismiss()
            if (autoScroll) {
                scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
                Toast.makeText(requireContext(), R.string.autoscroll_enabled, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), R.string.autoscroll_disabled, Toast.LENGTH_SHORT).show()
            }
        }

        menuView.findViewById<View>(R.id.menu_logs_share)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            flushPending(drainAll = true)
            showShareDialog()
        }

        menuView.findViewById<View>(R.id.menu_logs_clear)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            EventBus.clearLogHistory()
            pending.clear()
            allLogItems.clear()
            renderedLines = 0
            textView.text = ""
            updateLineCountBadge()
            Toast.makeText(requireContext(), R.string.logs_cleared, Toast.LENGTH_SHORT).show()
        }

        dialog.show()
    }

    private fun showShareDialog() {
        val text = textView.text.toString()
        if (text.isBlank()) {
            Toast.makeText(requireContext(), R.string.share_logs_empty, Toast.LENGTH_SHORT).show()
            return
        }

        val items = arrayOf(
            getString(R.string.share_logs_as_file),
            getString(R.string.share_logs_copy_text)
        )

        com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.share_logs_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> shareAsFile(text)
                    1 -> copyToClipboard(text)
                }
            }
            .show()
    }

    private fun shareAsFile(text: String) {
        try {
            val logsDir = File(requireContext().cacheDir, "logs").apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val logFile = File(logsDir, "fluxon_logs_${timestamp}.txt")
            logFile.writeText(text)

            val uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                logFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Fluxon Logs")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_logs_chooser_title)))
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.share_logs_error, e.localizedMessage ?: "Unknown error"),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = ClipData.newPlainText("Fluxon Logs", text)
        clipboard?.setPrimaryClip(clip)
        Toast.makeText(requireContext(), R.string.logs_copied, Toast.LENGTH_SHORT).show()
    }
}
