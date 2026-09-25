package io.github.p1neapplexpress.openflux.ui

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.app.Activity.RESULT_OK
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.transition.ChangeBounds
import androidx.transition.Fade
import androidx.transition.TransitionManager
import androidx.transition.TransitionSet
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.github.g00fy2.quickie.QRResult
import io.github.g00fy2.quickie.ScanQRCode
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.util.TunnelErrorClassifier
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.data.TunnelHealth
import io.github.p1neapplexpress.openflux.data.TunnelState
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.ui.widget.AuroraView
import io.github.p1neapplexpress.openflux.ui.widget.PulseRingsView
import io.github.p1neapplexpress.openflux.ui.widget.SpeedSparklineView
import io.github.p1neapplexpress.openflux.util.QrDecoder
import io.github.p1neapplexpress.openflux.util.ThemePreferences
import io.github.p1neapplexpress.openflux.util.TunnelLinkParser
import io.github.p1neapplexpress.openflux.util.toUptimeHms
import io.github.p1neapplexpress.openflux.util.performAppHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale

class TunnelsFragment : BaseFragment() {

    private val vm: TunnelsViewModel by activityViewModels()

    private lateinit var aurora: AuroraView
    private lateinit var pulseRings: PulseRingsView
    private lateinit var ringOuter: View
    private lateinit var ringMid: View
    private lateinit var centerContainer: View
    private lateinit var connectButton: View
    private lateinit var powerIcon: ImageView
    private lateinit var configSelector: View
    private lateinit var configDot: View
    private lateinit var tunnelName: TextView
    private lateinit var chevron: ImageView
    private lateinit var configPing: TextView
    private lateinit var statusText: TextView
    private lateinit var uptimeContainer: View
    private lateinit var uptimeText: TextView
    private lateinit var speedText: TextView
    private lateinit var speedSparkline: SpeedSparklineView
    private val binding get() = this
    private lateinit var memoryContainer: View
    private lateinit var memoryText: TextView
    private lateinit var appSettings: AppSettings

    private var rotationAnim: ObjectAnimator? = null
    private var breathAnim: ObjectAnimator? = null
    private var currentVisualState: TunnelState? = null
    private var isInitialStateBinding = true
    private var popup: PopupWindow? = null
    private var configSheetDialog: BottomSheetDialog? = null

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) vm.startCurrent()
        else Toast.makeText(requireContext(), R.string.vpn_permission_required, Toast.LENGTH_LONG).show()
    }

    private val qrScanner = registerForActivityResult(ScanQRCode()) { result ->
        val raw = (result as? QRResult.QRSuccess)?.content?.rawValue
            ?: return@registerForActivityResult
        val tunnel = TunnelLinkParser.parse(raw, requireContext())
        if (tunnel != null) {
            vm.addTunnel(tunnel)
            Toast.makeText(requireContext(), R.string.config_saved, Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), R.string.qr_scan_failed, Toast.LENGTH_LONG).show()
        }
    }

    private val pickQrImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val decoded = QrDecoder.decodeFromUri(ctx, uri)
            withContext(Dispatchers.Main) {
                val tunnel = if (decoded != null) TunnelLinkParser.parse(decoded, ctx) else null
                if (tunnel != null) {
                    vm.addTunnel(tunnel)
                    Toast.makeText(ctx, R.string.config_saved, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, R.string.qr_scan_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showAddQrBottomSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_add_qr, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.option_scan_camera).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            qrScanner.launch(null)
        }

        sheetView.findViewById<View>(R.id.option_pick_gallery).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            pickQrImage.launch("image/*")
        }

        sheetView.findViewById<View>(R.id.option_import_link).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            showImportLinkDialog()
        }

        sheetView.findViewById<View>(R.id.option_manual)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_bottom,
                    R.anim.fade_out,
                    R.anim.fade_in,
                    R.anim.slide_out_bottom
                )
                .replace(R.id.main, AddTunFragment.new())
                .addToBackStack("manual")
                .commit()
        }

        dialog.show()
    }

    private fun showImportLinkDialog() {
        val context = requireContext()
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_import_link, null)
        dialog.setContentView(view)

        val inputEdit = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.input_link_value)
        val btnCancel = view.findViewById<View>(R.id.btn_cancel_import)
        val btnConfirm = view.findViewById<View>(R.id.btn_confirm_import)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clipText = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()
        clipText?.let {
            if (it.startsWith("openflux://", ignoreCase = true) || it.startsWith("{") || it.startsWith("vless://") || it.startsWith("vmess://") || it.startsWith("http")) {
                inputEdit.setText(it)
                inputEdit.setSelection(it.length)
            }
        }

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnConfirm.setOnClickListener {
            val text = inputEdit.text?.toString()?.trim()
            val tunnel = TunnelLinkParser.parse(text, context)
            if (tunnel != null) {
                dialog.dismiss()
                vm.addTunnel(tunnel)
                Toast.makeText(context, R.string.config_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, R.string.import_link_invalid, Toast.LENGTH_LONG).show()
            }
        }

        dialog.show()
        inputEdit.requestFocus()
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_tunnels, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        aurora = view.findViewById(R.id.aurora)
        pulseRings = view.findViewById(R.id.pulseRings)
        ringOuter = view.findViewById(R.id.ringOuter)
        ringMid = view.findViewById(R.id.ringMid)
        centerContainer = view.findViewById(R.id.center)
        connectButton = view.findViewById(R.id.connectButton)
        powerIcon = view.findViewById(R.id.powerIcon)
        configSelector = view.findViewById(R.id.configSelector)
        configDot = view.findViewById(R.id.configDot)
        tunnelName = view.findViewById(R.id.tunnelName)
        chevron = view.findViewById(R.id.chevron)
        configPing = view.findViewById(R.id.configPing)
        statusText = view.findViewById(R.id.statusText)
        uptimeContainer = view.findViewById(R.id.uptimeContainer)
        uptimeText = view.findViewById(R.id.uptimeText)
        speedText = view.findViewById(R.id.speedText)
        speedSparkline = view.findViewById(R.id.speedSparkline)
        memoryContainer = view.findViewById(R.id.memoryContainer)
        memoryText = view.findViewById(R.id.memoryText)
        appSettings = AppSettings(requireContext())
        updateMemoryUsage()

        connectButton.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            when (vm.active.value) {
                is TunnelState.Running, is TunnelState.Reconnecting, is TunnelState.WaitingForNetwork, is TunnelState.Connecting -> vm.stop()
                is TunnelState.Idle, is TunnelState.Error -> requestVpnAndStart()
                else -> Unit
            }
        }

        configSelector.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showConfigBottomSheet()
        }

        configDot.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            vm.checkSelectedHealth(force = true)
        }

        view.findViewById<View>(R.id.btnSettings)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }

        view.findViewById<View>(R.id.btnLogs)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            startActivity(Intent(requireContext(), LogsActivity::class.java))
        }
        if (vm.active.value is TunnelState.Running) {
            val transY = getRunningStatusTranslationY()
            uptimeContainer.alpha = 1f
            uptimeContainer.translationY = transY
            statusText.translationY = transY
            uptimeText.visibility = View.VISIBLE
            uptimeText.alpha = 1f
        } else if (vm.active.value is TunnelState.Idle) {
            val transY = getIdleStatusTranslationY()
            statusText.translationY = transY
            uptimeContainer.alpha = 0f
            uptimeContainer.translationY = transY + (10f * resources.displayMetrics.density)
            uptimeText.visibility = View.GONE
            uptimeText.alpha = 0f
        }

        view.doOnLayout {
            if (vm.active.value is TunnelState.Idle || currentVisualState is TunnelState.Idle) {
                val transY = getIdleStatusTranslationY()
                statusText.translationY = transY
                uptimeContainer.translationY = transY + (10f * resources.displayMetrics.density)
            }
        }
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (vm.active.value is TunnelState.Idle || currentVisualState is TunnelState.Idle) {
                val transY = getIdleStatusTranslationY()
                statusText.translationY = transY
                uptimeContainer.translationY = transY + (10f * resources.displayMetrics.density)
            }
        }

        view.findViewById<View>(R.id.addButton).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showAddQrBottomSheet()
        }

        observe()
    }

    override fun onResume() {
        super.onResume()
        vm.checkSelectedHealth(force = false)
        updateMemoryUsage()
        updateConfigPing()
        updateSpeedGraphVisibility()
        ensureAnimations(vm.active.value)
    }

    private fun requestVpnAndStart() {
        checkBatteryOptimizationAndProceed {
            val intent = VpnService.prepare(requireActivity())
            if (intent != null) vpnPermission.launch(intent) else vm.startCurrent()
        }
    }

    private fun checkBatteryOptimizationAndProceed(onProceed: () -> Unit) {
        val ctx = context ?: run { onProceed(); return }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val isIgnored = pm?.isIgnoringBatteryOptimizations(ctx.packageName) == true
            if (!isIgnored && !appSettings.batteryOptPrompted) {
                appSettings.batteryOptPrompted = true
                MaterialAlertDialogBuilder(ctx)
                    .setTitle(R.string.battery_opt_prompt_title)
                    .setMessage(R.string.battery_opt_prompt_message)
                    .setPositiveButton(R.string.battery_opt_prompt_action) { _, _ ->
                        try {
                            val intent = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${ctx.packageName}")
                            }
                            startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                            } catch (_: Exception) {}
                        }
                        onProceed()
                    }
                    .setNegativeButton(R.string.battery_opt_prompt_later) { _, _ ->
                        onProceed()
                    }
                    .show()
                return
            }
        }
        onProceed()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.syncRunningServiceState()
                launch { vm.active.collect { applyState(it); updateConfigPing(); updateSpeedGraphVisibility() } }
                launch { vm.uptimeSeconds.collect { renderUptime(it) } }
                launch { vm.selected.collect { renderSelected(it) } }
                launch {
                    combine(vm.rxSpeed, vm.txSpeed) { rx, tx -> Pair(rx, tx) }
                        .collect { (rx, tx) -> renderSpeed(rx, tx) }
                }
                launch { vm.tunnelHealth.collect { renderHealth(it) } }
                launch { vm.pingMap.collect { updateConfigPing() } }
            }
        }
    }

    private fun animateButtonPosition(durationMs: Long = 380L) {
        val root = view as? ViewGroup ?: return
        if (!isAdded || isInitialStateBinding || !::centerContainer.isInitialized) return
        val transition = TransitionSet().apply {
            ordering = TransitionSet.ORDERING_TOGETHER
            addTransition(ChangeBounds().apply {
                duration = durationMs
                interpolator = DecelerateInterpolator()
                addTarget(centerContainer)
            })
        }
        TransitionManager.beginDelayedTransition(root, transition)
    }

    private fun updateSpeedGraphVisibility() {
        if (!::speedSparkline.isInitialized || !::appSettings.isInitialized) return
        val isRunning = vm.active.value is TunnelState.Running
        val show = appSettings.showSpeedGraph && isRunning
        if (speedSparkline.isVisible != show) {
            animateButtonPosition(380L)
            speedSparkline.isVisible = show
        }
    }

    private fun renderSpeed(rxBytesSec: Long, txBytesSec: Long) {
        speedText.text = "↓ ${formatSpeed(rxBytesSec)}   ↑ ${formatSpeed(txBytesSec)}"
        if (::speedSparkline.isInitialized && ::appSettings.isInitialized) {
            updateSpeedGraphVisibility()
            if (speedSparkline.isVisible) {
                speedSparkline.addSample(rxBytesSec, txBytesSec)
            }
        }
    }

    private fun formatSpeed(bytesPerSecond: Long): String = when {
        bytesPerSecond < 1024 -> "$bytesPerSecond B/s"
        bytesPerSecond < 1024 * 1024 -> String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0)
        else -> String.format(Locale.US, "%.1f MB/s", bytesPerSecond / (1024.0 * 1024.0))
    }

    private fun renderSelected(tunnel: Tunnel?) {
        tunnelName.text = tunnel?.name ?: getString(R.string.no_configs)
        renderHealth(vm.tunnelHealth.value)
        updateConfigPing()
    }

    private fun updateConfigPing() {
        if (!::configPing.isInitialized || !::configSelector.isInitialized) return
        val isRunning = vm.active.value is TunnelState.Running
        val showPing = appSettings.showPingInMainMenu
        val selectedId = vm.selectedTunnelId
        val ping = selectedId?.let { vm.getTunnelPing(it) }
        val shouldShow = isRunning && showPing && ping != null && ping > 0

        if (configPing.isVisible != shouldShow) {
            val selectorGroup = configSelector as? ViewGroup
            if (selectorGroup != null && isAdded && !isInitialStateBinding) {
                val transition = TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER
                    addTransition(ChangeBounds().apply {
                        duration = 240L
                        interpolator = DecelerateInterpolator()
                    })
                    addTransition(Fade().apply {
                        duration = 200L
                    })
                }
                TransitionManager.beginDelayedTransition(selectorGroup, transition)
            }
            configPing.isVisible = shouldShow
        }

        if (shouldShow) {
            val p = ping ?: return
            configPing.text = getString(R.string.ping_ms_format, p)
            val colorRes = when {
                p < 150 -> R.color.state_running
                p < 350 -> R.color.state_connecting
                else -> R.color.state_error
            }
            configPing.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
        }
    }

    private fun renderHealth(health: TunnelHealth) {
        if (vm.selected.value == null) {
            configDot.background?.setTint(
                ContextCompat.getColor(requireContext(), R.color.state_error)
            )
            return
        }
        val colorRes = when (health) {
            TunnelHealth.AVAILABLE -> R.color.state_running
            TunnelHealth.UNAVAILABLE -> R.color.state_error
            TunnelHealth.CHECKING -> R.color.state_connecting
            TunnelHealth.UNKNOWN -> R.color.state_idle
        }
        configDot.background?.setTint(
            ContextCompat.getColor(requireContext(), colorRes)
        )
        renderRunningHealth(health)
    }

    private fun renderRunningHealth(health: TunnelHealth) {
        if (vm.active.value !is TunnelState.Running || !::statusText.isInitialized || !isAdded) return
        val (label, colorRes) = when (health) {
            TunnelHealth.AVAILABLE -> R.string.running to R.color.state_running
            TunnelHealth.UNAVAILABLE -> R.string.tunnel_traffic_unavailable to R.color.state_error
            TunnelHealth.CHECKING, TunnelHealth.UNKNOWN ->
                R.string.checking_tunnel_traffic to R.color.state_connecting
        }
        val color = ContextCompat.getColor(requireContext(), colorRes)
        statusText.setText(label)
        statusText.setTextColor(color)
        aurora.setStateColor(color)
        pulseRings.setColor(color)
    }

    

    private fun showConfigBottomSheet() {
        vm.probeAllTunnels()

        val dialog = BottomSheetDialog(requireContext())
        configSheetDialog = dialog
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_configs, null)
        dialog.setContentView(sheetView)

        val subtitleView = sheetView.findViewById<TextView>(R.id.configs_sheet_subtitle)
        val btnClose = sheetView.findViewById<View>(R.id.btn_close_configs)
        val searchContainer = sheetView.findViewById<TextInputLayout>(R.id.search_configs_container)
        val searchInput = sheetView.findViewById<TextInputEditText>(R.id.search_configs_input)
        val configsRecycler = sheetView.findViewById<RecyclerView>(R.id.configs_recycler)
        val emptyState = sheetView.findViewById<View>(R.id.configs_empty_state)
        val btnAddBottom = sheetView.findViewById<View>(R.id.btn_add_config_bottom)

        val onAddClick = View.OnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            showAddQrBottomSheet()
        }
        btnAddBottom.setOnClickListener(onAddClick)
        btnClose.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
        }

        val adapter = ConfigsAdapter(
            onSelect = { tunnel ->
                vm.selectTunnel(tunnel)
                dialog.dismiss()
            },
            onMore = { anchor, tunnel ->
                showItemContextMenu(anchor, tunnel)
            }
        )
        configsRecycler.layoutManager = LinearLayoutManager(requireContext())
        configsRecycler.adapter = adapter

        var currentFilter = ""

        fun populateList(filterQuery: String = currentFilter) {
            currentFilter = filterQuery
            val allTunnels = vm.tunnels.value.map { it.tunnel }
            subtitleView.text = getString(R.string.total_configs_format, allTunnels.size)

            searchContainer.isVisible = allTunnels.size >= 4

            val filtered = if (filterQuery.isBlank()) {
                allTunnels
            } else {
                allTunnels.filter {
                    it.name.contains(filterQuery, ignoreCase = true) ||
                    it.transportType.contains(filterQuery, ignoreCase = true)
                }
            }

            if (filtered.isEmpty()) {
                emptyState.isVisible = true
                adapter.submitList(emptyList())
                return
            } else {
                emptyState.isVisible = false
            }

            val selectedId = vm.selectedTunnelId
            val healthMap = vm.healthMap.value
            val items = filtered.map { tunnel ->
                TunnelConfigItem(
                    tunnel = tunnel,
                    isSelected = tunnel.id == selectedId,
                    health = healthMap[tunnel.id] ?: vm.getTunnelHealth(tunnel.id)
                )
            }
            adapter.submitList(items)
        }

        populateList()

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                populateList(s?.toString()?.trim() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        chevron.animate().rotation(180f).setDuration(220L).setInterpolator(DecelerateInterpolator()).start()

        val sheetCollectorsJob = viewLifecycleOwner.lifecycleScope.launch {
            launch {
                vm.healthMap.collect {
                    populateList()
                }
            }
            launch {
                vm.tunnels.collect {
                    populateList()
                }
            }
        }

        dialog.setOnDismissListener {
            sheetCollectorsJob.cancel()
            chevron.animate().rotation(0f).setDuration(180L).setInterpolator(DecelerateInterpolator()).start()
            configSheetDialog = null
        }

        dialog.show()
    }

    data class TunnelConfigItem(
        val tunnel: Tunnel,
        val isSelected: Boolean,
        val health: TunnelHealth
    )

    private class TunnelConfigDiffCallback : DiffUtil.ItemCallback<TunnelConfigItem>() {
        override fun areItemsTheSame(oldItem: TunnelConfigItem, newItem: TunnelConfigItem): Boolean =
            oldItem.tunnel.id == newItem.tunnel.id

        override fun areContentsTheSame(oldItem: TunnelConfigItem, newItem: TunnelConfigItem): Boolean =
            oldItem == newItem
    }

    private class ConfigsAdapter(
        private val onSelect: (Tunnel) -> Unit,
        private val onMore: (View, Tunnel) -> Unit
    ) : ListAdapter<TunnelConfigItem, ConfigsAdapter.ConfigViewHolder>(TunnelConfigDiffCallback()) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConfigViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_config_card, parent, false) as MaterialCardView
            return ConfigViewHolder(v)
        }

        override fun onBindViewHolder(holder: ConfigViewHolder, position: Int) {
            holder.bind(getItem(position))
        }

        inner class ConfigViewHolder(private val card: MaterialCardView) : RecyclerView.ViewHolder(card) {
            private val iconView = card.findViewById<ImageView>(R.id.config_transport_icon)
            private val nameView = card.findViewById<TextView>(R.id.config_name)
            private val transportLabel = card.findViewById<TextView>(R.id.config_transport_label)
            private val dot = card.findViewById<View>(R.id.config_status_dot)
            private val checkIcon = card.findViewById<ImageView>(R.id.config_check_icon)
            private val btnMore = card.findViewById<ImageView>(R.id.btn_config_more)

            fun bind(item: TunnelConfigItem) {
                val tunnel = item.tunnel
                nameView.text = tunnel.name

                when (TransportType.from(tunnel.transportType)) {
                    TransportType.yandex -> {
                        iconView.imageTintList = null
                        iconView.setImageResource(R.drawable.yandex_docs)
                        transportLabel.text = card.context.getString(R.string.yandex_docs_backend)
                    }
                    TransportType.vyandex -> {
                        iconView.imageTintList = null
                        iconView.setImageResource(R.drawable.volga)
                        transportLabel.text = card.context.getString(R.string.vyandex_backend)
                    }
                    TransportType.max -> {
                        iconView.setImageResource(R.drawable.max_msg)
                        iconView.imageTintList = ContextCompat.getColorStateList(card.context, R.color.text_primary)
                        transportLabel.text = card.context.getString(R.string.max_messenger_backend)
                    }
                    TransportType.cups -> {
                        iconView.imageTintList = null
                        iconView.setImageResource(R.drawable.ic_cups)
                        transportLabel.text = card.context.getString(R.string.cups_backend)
                    }
                    TransportType.mailru -> {
                        iconView.imageTintList = null
                        iconView.setImageResource(R.drawable.ic_mailru)
                        transportLabel.text = card.context.getString(R.string.mailru_backend)
                    }
                    TransportType.gdocs -> {
                        iconView.imageTintList = null
                        iconView.setImageResource(R.drawable.ic_gdocs)
                        transportLabel.text = card.context.getString(R.string.gdocs_backend)
                    }
                }

                val density = card.resources.displayMetrics.density
                if (item.isSelected) {
                    card.setStrokeColor(ContextCompat.getColor(card.context, R.color.m3_primary))
                    card.strokeWidth = (1.5f * density).toInt()
                    card.setCardBackgroundColor(ContextCompat.getColor(card.context, R.color.m3_surface_container_high))
                    checkIcon.isVisible = true
                    nameView.setTypeface(null, Typeface.BOLD)
                } else {
                    card.setStrokeColor(ContextCompat.getColor(card.context, R.color.m3_outline_variant))
                    card.strokeWidth = (1f * density).toInt()
                    card.setCardBackgroundColor(ContextCompat.getColor(card.context, R.color.m3_surface_container))
                    checkIcon.isVisible = false
                    nameView.setTypeface(null, Typeface.NORMAL)
                }

                val colorRes = when (item.health) {
                    TunnelHealth.AVAILABLE -> R.color.state_running
                    TunnelHealth.UNAVAILABLE -> R.color.state_error
                    TunnelHealth.CHECKING -> R.color.state_connecting
                    TunnelHealth.UNKNOWN -> R.color.state_idle
                }
                dot.background?.setTint(ContextCompat.getColor(card.context, colorRes))

                card.setOnClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                    onSelect(tunnel)
                }
                btnMore.setOnClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                    onMore(it, tunnel)
                }
                card.setOnLongClickListener {
                    it.performAppHaptics(HapticFeedbackConstants.LONG_PRESS)
                    onMore(btnMore, tunnel)
                    true
                }
            }
        }
    }

    private fun showItemContextMenu(anchor: View, tunnel: Tunnel) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottom_sheet_config_options, null)
        dialog.setContentView(view)

        val nameView = view.findViewById<TextView>(R.id.sheet_config_name)
        val subView = view.findViewById<TextView>(R.id.sheet_config_subtitle)
        val iconView = view.findViewById<ImageView>(R.id.sheet_config_icon)

        nameView.text = tunnel.name
        when (TransportType.from(tunnel.transportType)) {
            TransportType.yandex -> {
                iconView.imageTintList = null
                iconView.setImageResource(R.drawable.yandex_docs)
                subView.text = getString(R.string.yandex_docs_backend)
            }
            TransportType.vyandex -> {
                iconView.imageTintList = null
                iconView.setImageResource(R.drawable.volga)
                subView.text = getString(R.string.vyandex_backend)
            }
            TransportType.max -> {
                iconView.setImageResource(R.drawable.max_msg)
                iconView.imageTintList = ContextCompat.getColorStateList(requireContext(), R.color.text_primary)
                subView.text = getString(R.string.max_messenger_backend)
            }
            TransportType.cups -> {
                iconView.imageTintList = null
                iconView.setImageResource(R.drawable.ic_cups)
                subView.text = getString(R.string.cups_backend)
            }
            TransportType.mailru -> {
                iconView.imageTintList = null
                iconView.setImageResource(R.drawable.ic_mailru)
                subView.text = getString(R.string.mailru_backend)
            }
            TransportType.gdocs -> {
                iconView.imageTintList = null
                iconView.setImageResource(R.drawable.ic_gdocs)
                subView.text = getString(R.string.gdocs_backend)
            }
        }

        view.findViewById<View>(R.id.menu_edit).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            configSheetDialog?.dismiss()
            parentFragmentManager.beginTransaction()
                .setCustomAnimations(
                    R.anim.slide_in_bottom,
                    R.anim.fade_out,
                    R.anim.fade_in,
                    R.anim.slide_out_bottom
                )
                .replace(R.id.main, AddTunFragment.edit(tunnel))
                .addToBackStack("edit")
                .commit()
        }

        view.findViewById<View>(R.id.menu_share_qr)?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            configSheetDialog?.dismiss()
            QrShareDialog.show(requireContext(), tunnel)
        }

        view.findViewById<View>(R.id.menu_delete).setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dialog.dismiss()
            confirmDelete(tunnel)
        }

        dialog.show()
    }

    private fun confirmDelete(tunnel: Tunnel) {
        val active = vm.active.value
        if (active.isActive && active.tunnel == tunnel) {
            Toast.makeText(requireContext(), R.string.cannot_delete_active, Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.delete_config_title)
            .setMessage("«${tunnel.name}»\n${getString(R.string.delete_config_msg)}")
            .setPositiveButton(R.string.action_delete) { dialog, _ ->
                vm.removeTunnel(tunnel)
                configSheetDialog?.dismiss()
                popup?.dismiss()
                Toast.makeText(requireContext(), R.string.config_deleted, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun showErrorDialog(rawOrTitle: String, detail: String? = null) {
        val title: String
        val msg: String
        if (!detail.isNullOrBlank()) {
            title = rawOrTitle
            msg = detail
        } else {
            val classified = TunnelErrorClassifier.classify(rawOrTitle)
            title = classified.title
            msg = classified.message
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton(R.string.retry) { dialog, _ ->
                dialog.dismiss()
                requestVpnAndStart()
            }
            .setNeutralButton(R.string.view_logs) { dialog, _ ->
                dialog.dismiss()
                startActivity(Intent(requireContext(), LogsActivity::class.java))
            }
            .setNegativeButton(R.string.cancel) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }

    private fun applyState(state: TunnelState) {
        val isFirst = isInitialStateBinding
        isInitialStateBinding = false

        if (state !is TunnelState.Running) {
            if (::speedSparkline.isInitialized) {
                speedSparkline.reset()
            }
        }

        if (state == currentVisualState && !isFirst) {
            ensureAnimations(state)
            return
        }
        currentVisualState = state

        if (!isFirst) {
            animateButtonPosition(380L)
        }

        val color = state.color
        aurora.setStateColor(color)

        if (isFirst) {
            when (state) {
                is TunnelState.Idle -> {
                    statusText.text = getString(R.string.tap_to_connect)
                    statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                    statusText.alpha = 1f
                    val transY = getIdleStatusTranslationY()
                    statusText.translationY = transY
                    aurora.setIntensity(0.4f)
                    pulseRings.stop()
                    stopRotation()
                    startBreath()
                    powerIcon.scaleX = 1f
                    powerIcon.scaleY = 1f
                    powerIcon.alpha = 0.92f
                    connectButton.scaleX = 1f
                    connectButton.scaleY = 1f
                    uptimeContainer.animate().cancel()
                    uptimeContainer.alpha = 0f
                    uptimeContainer.translationY = transY + (10f * resources.displayMetrics.density)
                    uptimeText.visibility = View.GONE
                    uptimeText.alpha = 0f
                }
                is TunnelState.Connecting,
                is TunnelState.StartingTransport,
                is TunnelState.StartingTun2Socks -> {
                    val label = when (state) {
                        is TunnelState.Connecting -> getString(R.string.connecting)
                        is TunnelState.StartingTransport -> getString(R.string.starting_transport)
                        is TunnelState.StartingTun2Socks -> getString(R.string.starting_tsocks)
                    }
                    statusText.text = label
                    statusText.setTextColor(color)
                    statusText.alpha = 1f
                    statusText.translationY = 22f * resources.displayMetrics.density
                    aurora.setIntensity(0.75f)
                    startRotation()
                    pulseRings.setColor(color)
                    pulseRings.start(color, intervalMs = 1800L)
                    stopBreath()
                    powerIcon.scaleX = 0.94f
                    powerIcon.scaleY = 0.94f
                    powerIcon.alpha = 0.7f
                    connectButton.scaleX = 1f
                    connectButton.scaleY = 1f
                    uptimeContainer.animate().cancel()
                    uptimeContainer.alpha = 0f
                    uptimeContainer.translationY = 32f * resources.displayMetrics.density
                    uptimeText.visibility = View.GONE
                    uptimeText.alpha = 0f
                }
                is TunnelState.Reconnecting -> {
                    statusText.text = getString(R.string.reconnecting)
                    statusText.setTextColor(color)
                    statusText.alpha = 1f
                    statusText.translationY = 22f * resources.displayMetrics.density
                    aurora.setIntensity(0.75f)
                    startRotation()
                    pulseRings.setColor(color)
                    pulseRings.start(color, intervalMs = 1800L)
                    stopBreath()
                    powerIcon.scaleX = 0.94f
                    powerIcon.scaleY = 0.94f
                    powerIcon.alpha = 0.7f
                    connectButton.scaleX = 1f
                    connectButton.scaleY = 1f
                    uptimeContainer.animate().cancel()
                    uptimeContainer.alpha = 0f
                    uptimeContainer.translationY = 32f * resources.displayMetrics.density
                    uptimeText.visibility = View.GONE
                    uptimeText.alpha = 0f
                }
                is TunnelState.WaitingForNetwork -> {
                    statusText.text = getString(R.string.waiting_for_network)
                    statusText.setTextColor(color)
                    statusText.alpha = 1f
                    statusText.translationY = 22f * resources.displayMetrics.density
                    aurora.setIntensity(0.6f)
                    stopRotation()
                    pulseRings.stop()
                    startBreath()
                    powerIcon.scaleX = 0.94f
                    powerIcon.scaleY = 0.94f
                    powerIcon.alpha = 0.7f
                    connectButton.scaleX = 1f
                    connectButton.scaleY = 1f
                    uptimeContainer.animate().cancel()
                    uptimeContainer.alpha = 0f
                    uptimeContainer.translationY = 32f * resources.displayMetrics.density
                    uptimeText.visibility = View.GONE
                    uptimeText.alpha = 0f
                }
                is TunnelState.Running -> {
                    statusText.text = getString(R.string.running)
                    statusText.setTextColor(color)
                    statusText.alpha = 1f
                    statusText.translationY = 22f * resources.displayMetrics.density
                    aurora.setIntensity(1f)
                    stopRotation()
                    pulseRings.setColor(color)
                    pulseRings.start(color, intervalMs = 1400L)
                    startBreath()
                    powerIcon.scaleX = 1.05f
                    powerIcon.scaleY = 1.05f
                    powerIcon.alpha = 1f
                    connectButton.scaleX = 1f
                    connectButton.scaleY = 1f
                    uptimeContainer.animate().cancel()
                    uptimeContainer.alpha = 1f
                    uptimeContainer.translationY = 22f * resources.displayMetrics.density
                    uptimeText.visibility = View.VISIBLE
                    uptimeText.alpha = 1f
                    renderRunningHealth(vm.tunnelHealth.value)
                }
                is TunnelState.Error -> {
                    statusText.text = state.message
                    statusText.setTextColor(color)
                    statusText.alpha = 1f
                    statusText.translationY = 22f * resources.displayMetrics.density
                    aurora.setIntensity(0.9f)
                    pulseRings.stop()
                    stopRotation()
                    stopBreath()
                    powerIcon.scaleX = 1f
                    powerIcon.scaleY = 1f
                    powerIcon.alpha = 1f
                    connectButton.scaleX = 1f
                    connectButton.scaleY = 1f
                    uptimeContainer.animate().cancel()
                    uptimeContainer.alpha = 0f
                    uptimeContainer.translationY = 32f * resources.displayMetrics.density
                    uptimeText.visibility = View.GONE
                    uptimeText.alpha = 0f
                }
            }
            return
        }

        when (state) {
            is TunnelState.Idle -> {
                statusText.text = getString(R.string.tap_to_connect)
                statusText.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_primary))
                crossFadeStatus()
                animateStatusPosition(down = true)
                aurora.setIntensity(0.4f)
                pulseRings.stop()
                stopRotation()
                startBreath()
                animateIcon(scale = 1f, alpha = 0.92f)
                hideUptime()
            }

            is TunnelState.Connecting,
            is TunnelState.StartingTransport,
            is TunnelState.StartingTun2Socks -> {
                val label = when (state) {
                    is TunnelState.Connecting -> getString(R.string.connecting)
                    is TunnelState.StartingTransport -> getString(R.string.starting_transport)
                    is TunnelState.StartingTun2Socks -> getString(R.string.starting_tsocks)
                }
                statusText.text = label
                statusText.setTextColor(color)
                crossFadeStatus()
                animateStatusPosition(down = false)
                aurora.setIntensity(0.75f)
                startRotation()
                pulseRings.setColor(color)
                pulseRings.start(color, intervalMs = 1800L)
                stopBreath()
                animateIcon(scale = 0.94f, alpha = 0.7f)
                hideUptime()
            }

            is TunnelState.Reconnecting -> {
                statusText.text = getString(R.string.reconnecting)
                statusText.setTextColor(color)
                crossFadeStatus()
                animateStatusPosition(down = false)
                aurora.setIntensity(0.75f)
                startRotation()
                pulseRings.setColor(color)
                pulseRings.start(color, intervalMs = 1800L)
                stopBreath()
                animateIcon(scale = 0.94f, alpha = 0.7f)
                hideUptime()
            }

            is TunnelState.WaitingForNetwork -> {
                statusText.text = getString(R.string.waiting_for_network)
                statusText.setTextColor(color)
                crossFadeStatus()
                animateStatusPosition(down = false)
                aurora.setIntensity(0.6f)
                stopRotation()
                pulseRings.stop()
                startBreath()
                animateIcon(scale = 0.94f, alpha = 0.7f)
                hideUptime()
            }

            is TunnelState.Running -> {
                uptimeText.text = "00:00"
                val feedback = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.LONG_PRESS
                }
                connectButton.performAppHaptics(feedback)
                statusText.text = getString(R.string.running)
                statusText.setTextColor(color)
                crossFadeStatus()
                animateStatusPosition(down = false)
                aurora.setIntensity(1f)
                stopRotation()
                pulseRings.setColor(color)
                pulseRings.start(color, intervalMs = 1400L)
                startBreath()
                animateIcon(scale = 1.08f, alpha = 1f)
                popButton()
                showUptime()
                renderRunningHealth(vm.tunnelHealth.value)
            }

            is TunnelState.Error -> {
                val feedback = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.REJECT
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                }
                connectButton.performAppHaptics(feedback)
                showErrorDialog(state.message, state.detail)
                statusText.text = state.message
                statusText.setTextColor(color)
                crossFadeStatus()
                animateStatusPosition(down = false)
                aurora.setIntensity(0.9f)
                pulseRings.stop()
                stopRotation()
                stopBreath()
                animateIcon(scale = 1f, alpha = 1f)
                shake()
                hideUptime()
            }
        }
    }

    private fun getRunningStatusTranslationY(): Float {
        return 22f * resources.displayMetrics.density
    }

    private fun getIdleStatusTranslationY(): Float {
        val density = resources.displayMetrics.density
        if (!::centerContainer.isInitialized || !::statusText.isInitialized || !::configSelector.isInitialized) {
            return 80f * density
        }
        val centerTop = centerContainer.top.toFloat()
        val configBottom = configSelector.bottom.toFloat()
        val statusTop = statusText.top.toFloat()
        val statusHeight = statusText.height.toFloat().takeIf { it > 0 } ?: (32f * density)

        if (centerTop <= 0f || configBottom <= 0f) {
            val screenHeight = resources.displayMetrics.heightPixels.toFloat()
            return (screenHeight * 0.11f).coerceIn(60f * density, 130f * density)
        }

        val availableSpace = centerTop - configBottom
        val marginFromButton = (availableSpace * 0.26f).coerceIn(20f * density, 72f * density)
        val targetTop = centerTop - marginFromButton - statusHeight
        val calculatedTransY = targetTop - statusTop

        val minTransY = 36f * density
        val maxTransY = (centerTop - statusTop - statusHeight - 16f * density).coerceAtLeast(minTransY)
        return calculatedTransY.coerceIn(minTransY, maxTransY)
    }

    private fun animateStatusPosition(down: Boolean) {
        val targetY = if (down) getIdleStatusTranslationY() else getRunningStatusTranslationY()
        statusText.animate()
            .translationY(targetY)
            .setDuration(320L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun renderUptime(seconds: Long) {
        if (seconds < 0L || currentVisualState !is TunnelState.Running) {
            hideUptime()
            return
        }
        val text = seconds.toUptimeHms()
        if (uptimeText.text != text) {
            uptimeText.text = text
        }
        showUptime()
    }

    private fun crossFadeStatus() {
        statusText.animate().cancel()
        statusText.alpha = 0.6f
        statusText.animate().alpha(1f).setDuration(260L)
            .setInterpolator(DecelerateInterpolator()).start()
    }

    private fun animateIcon(scale: Float, alpha: Float) {
        powerIcon.animate().cancel()
        powerIcon.animate().scaleX(scale).scaleY(scale).alpha(alpha)
            .setDuration(360L).setInterpolator(OvershootInterpolator(1.4f)).start()
    }

    private fun popButton() {
        connectButton.animate().cancel()
        connectButton.animate()
            .scaleX(1.06f)
            .scaleY(1.06f)
            .setDuration(180L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                connectButton.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(300L)
                    .setInterpolator(OvershootInterpolator(2.0f))
                    .start()
            }
            .start()
    }

    private fun shake() {
        val props = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -14f, 14f, -10f, 10f, -4f, 4f, 0f)
        ObjectAnimator.ofPropertyValuesHolder(connectButton, props).apply {
            duration = 520L
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun ensureAnimations(state: TunnelState) {
        val color = state.color
        aurora.setStateColor(color)
        when (state) {
            is TunnelState.Idle -> {
                aurora.setIntensity(0.4f)
                pulseRings.stop()
                stopRotation()
                startBreath()
            }
            is TunnelState.Connecting,
            is TunnelState.StartingTransport,
            is TunnelState.StartingTun2Socks -> {
                aurora.setIntensity(0.75f)
                startRotation()
                pulseRings.setColor(color)
                pulseRings.start(color, intervalMs = 1800L)
                stopBreath()
            }
            is TunnelState.Reconnecting -> {
                aurora.setIntensity(0.75f)
                startRotation()
                pulseRings.setColor(color)
                pulseRings.start(color, intervalMs = 1800L)
                stopBreath()
            }
            is TunnelState.WaitingForNetwork -> {
                aurora.setIntensity(0.6f)
                stopRotation()
                pulseRings.stop()
                startBreath()
            }
            is TunnelState.Running -> {
                aurora.setIntensity(1f)
                stopRotation()
                pulseRings.setColor(color)
                pulseRings.start(color, intervalMs = 1400L)
                startBreath()
            }
            is TunnelState.Error -> {
                aurora.setIntensity(0.9f)
                pulseRings.stop()
                stopRotation()
                stopBreath()
            }
        }
    }

    private fun startRotation() {
        if (ringOuter.visibility == View.GONE) return
        if (rotationAnim?.isRunning == true) return
        rotationAnim = ObjectAnimator.ofFloat(ringOuter, View.ROTATION, 0f, 360f).apply {
            duration = 4200L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopRotation() {
        rotationAnim?.cancel()
        rotationAnim = null
        ringOuter.rotation = 0f
    }

    private fun startBreath() {
        if (ringOuter.visibility == View.GONE) return
        if (breathAnim?.isRunning == true) return
        breathAnim = ObjectAnimator.ofPropertyValuesHolder(
            ringOuter,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.02f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.02f),
        ).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun stopBreath() {
        breathAnim?.cancel()
        breathAnim = null
        ringOuter.scaleX = 1f
        ringOuter.scaleY = 1f
    }

    private fun showUptime() {
        if (!uptimeText.isVisible) {
            val btnGroup = connectButton as? ViewGroup
            if (btnGroup != null && isAdded && !isInitialStateBinding) {
                val transition = TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER
                    addTransition(ChangeBounds().apply {
                        duration = 350L
                        interpolator = DecelerateInterpolator()
                    })
                    addTransition(Fade(Fade.IN).apply {
                        duration = 280L
                        addTarget(uptimeText)
                    })
                }
                TransitionManager.beginDelayedTransition(btnGroup, transition)
            }
            uptimeText.alpha = 1f
            uptimeText.visibility = View.VISIBLE
        }
        if (uptimeContainer.alpha > 0.05f) return
        val targetY = getRunningStatusTranslationY()
        uptimeContainer.translationY = targetY + (10f * resources.displayMetrics.density)
        uptimeContainer.animate().cancel()
        uptimeContainer.animate()
            .alpha(1f)
            .translationY(targetY)
            .setDuration(350L)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun hideUptime() {
        uptimeText.text = "00:00"
        if (uptimeText.isVisible) {
            val btnGroup = connectButton as? ViewGroup
            if (btnGroup != null && isAdded && !isInitialStateBinding) {
                val transition = TransitionSet().apply {
                    ordering = TransitionSet.ORDERING_TOGETHER
                    addTransition(ChangeBounds().apply {
                        duration = 320L
                        interpolator = DecelerateInterpolator()
                    })
                    addTransition(Fade(Fade.OUT).apply {
                        duration = 200L
                        addTarget(uptimeText)
                    })
                }
                TransitionManager.beginDelayedTransition(btnGroup, transition)
            }
            uptimeText.visibility = View.GONE
        }
        if (uptimeContainer.alpha < 0.05f) return
        val targetY = statusText.translationY + (10f * resources.displayMetrics.density)
        uptimeContainer.animate().cancel()
        uptimeContainer.animate()
            .alpha(0f)
            .translationY(targetY)
            .setDuration(200L)
            .start()
    }

    private fun updateMemoryUsage() {
        if (!::appSettings.isInitialized || !::memoryContainer.isInitialized || !::memoryText.isInitialized) return
        if (appSettings.showMemoryUsage) {
            memoryContainer.visibility = View.VISIBLE
            val runtime = Runtime.getRuntime()
            val usedMemInMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
            memoryText.text = getString(R.string.memory_usage_format, usedMemInMB)
        } else {
            memoryContainer.visibility = View.GONE
        }
    }

    override fun onDestroyView() {
        rotationAnim?.cancel()
        breathAnim?.cancel()
        pulseRings.stop()
        popup?.dismiss()
        popup = null
        currentVisualState = null
        isInitialStateBinding = true
        super.onDestroyView()
    }

    override fun onNewEvent(ev: AppEvent) = Unit

    companion object {
        fun new() = TunnelsFragment()
    }
}
