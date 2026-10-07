package io.github.libreroute.ui

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.CheckBox
import android.widget.Spinner
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputLayout
import io.github.libreroute.R
import io.github.libreroute.data.TransportType
import io.github.libreroute.data.Tunnel
import io.github.libreroute.event.AppEvent
import io.github.libreroute.util.performAppHaptics
import io.github.libreroute.util.ServiceRouteEndpoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

class AddTunFragment : BaseFragment() {

    companion object {
        private const val ARG_EDIT_JSON = "edit_json"

        fun new() = AddTunFragment()

        fun edit(tunnel: Tunnel): AddTunFragment = AddTunFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_EDIT_JSON, Json.encodeToString(Tunnel.serializer(), tunnel))
            }
        }
    }

    private val vm: TunnelsViewModel by activityViewModels()
    private val addEditVm: AddEditTunnelViewModel by viewModels()
    private var transport = TransportType.yandex
    private var editing: Tunnel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = arguments?.getString(ARG_EDIT_JSON)
        if (raw != null) {
            editing = runCatching { Json.decodeFromString(Tunnel.serializer(), raw) }.getOrNull()
        }
        addEditVm.initWithTunnel(editing)
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?) =
        i.inflate(R.layout.fragment_add_tun, c, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val btnBack = view.findViewById<View>(R.id.btn_back)
        val toolbarTitle = view.findViewById<TextView>(R.id.toolbar_title)
        val nameContainer = view.findViewById<TextInputLayout>(R.id.nameContainer)
        val transportLayout = view.findViewById<View>(R.id.select_transport_layout)
        val transportIcon = view.findViewById<ImageView>(R.id.transport_icon)
        val maxContainer = view.findViewById<View>(R.id.maxContainer)
        val maxTokenContainer = view.findViewById<TextInputLayout>(R.id.maxTokenContainer)
        val maxUserIdContainer = view.findViewById<TextInputLayout>(R.id.maxUserIdContainer)
        val yandexContainer = view.findViewById<TextInputLayout>(R.id.yandexUrlContainer)
        val mqttContainer = view.findViewById<View>(R.id.mqttContainer)
        val mqttBroker = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.mqttBroker)
        val mqttTopic = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.mqttTopic)
        val mqttClientId = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.mqttClientId)
        val mqttBrokerContainer = view.findViewById<TextInputLayout>(R.id.mqttBrokerContainer)
        val mqttTopicContainer = view.findViewById<TextInputLayout>(R.id.mqttTopicContainer)
        val mqttClientIdContainer = view.findViewById<TextInputLayout>(R.id.mqttClientIdContainer)
        val encryptionKeyContainer = view.findViewById<TextInputLayout>(R.id.encryptionKeyContainer)
        val transportLabel = view.findViewById<TextView>(R.id.selectedTransport)
        val docUrl = view.findViewById<TextView>(R.id.documentUrl)
        val maxToken = view.findViewById<TextView>(R.id.maxToken)
        val maxUid = view.findViewById<TextView>(R.id.maxUserId)
        val name = view.findViewById<TextView>(R.id.name)
        val encryptionKey = view.findViewById<TextView>(R.id.encryptionKey)
        val extraParamsContainer = view.findViewById<TextInputLayout>(R.id.extraParamsContainer)
        val extraParams = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.extraParams)
        val sessionSection = view.findViewById<View>(R.id.session_section)
        val sessionCheck = view.findViewById<CheckBox>(R.id.session_negotiate)
        val sessionOptions = view.findViewById<View>(R.id.session_options)
        val batchSpinner = view.findViewById<Spinner>(R.id.session_batch)
        val lingerSpinner = view.findViewById<Spinner>(R.id.session_linger)
        val compressionSpinner = view.findViewById<Spinner>(R.id.session_compression)
        // Service secrets and raw CLI/session controls are generated internally.
        view.findViewById<View>(R.id.security_section).isVisible = false
        view.findViewById<View>(R.id.security_section_title).isVisible = false
        view.findViewById<View>(R.id.extra_section).isVisible = false
        view.findViewById<View>(R.id.extra_section_title).isVisible = false
        // The client id is an implementation detail. Generate it with the profile id;
        // exposing it here made manual MQTT setup needlessly fragile.
        mqttClientIdContainer.isVisible = false
        sessionSection.isVisible = false
        sessionCheck.isVisible = false
        val batchValues = listOf(8192, 16384, 32768)
        val lingerValues = listOf(2, 5, 10)
        val compressionValues = listOf("zstd-auto", "none")
        batchSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, batchValues.map { "$it B" })
        lingerSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, lingerValues.map { "$it ms" })
        compressionSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, compressionValues)
        sessionCheck.setOnCheckedChangeListener { _, checked -> sessionOptions.isVisible = checked }
        val save = view.findViewById<Button>(R.id.saveButton)

        btnBack.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        fun TextInputLayout.clearError() {
            error = null
            isErrorEnabled = false
        }

        // Clear errors on typing
        name.doAfterTextChanged { nameContainer.clearError() }
        docUrl.doAfterTextChanged { yandexContainer.clearError() }
        maxToken.doAfterTextChanged { maxTokenContainer.clearError() }
        maxUid.doAfterTextChanged { maxUserIdContainer.clearError() }
        encryptionKey.doAfterTextChanged { encryptionKeyContainer.clearError() }
        extraParams.doAfterTextChanged { extraParamsContainer.clearError() }
        mqttBroker.doAfterTextChanged { mqttBrokerContainer.clearError() }
        mqttTopic.doAfterTextChanged { mqttTopicContainer.clearError() }
        mqttClientId.doAfterTextChanged { mqttClientIdContainer.clearError() }

        fun updateTransportUi(type: TransportType) {
            transport = type
            encryptionKeyContainer.helperText = null
            when (type) {
                TransportType.directDpi -> {
                    transportIcon.setImageResource(R.drawable.ic_split_tunnel)
                    transportLabel.text = "Прямой обход DPI · zapret"
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = false
                    mqttContainer.isVisible = false
                }
                TransportType.mqtt, TransportType.jitsi -> {
                    transportIcon.setImageResource(R.drawable.ic_split_tunnel)
                    transportLabel.text = getString(if (type == TransportType.mqtt) R.string.mqtt_backend else R.string.jitsi_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = type == TransportType.jitsi
                    mqttContainer.isVisible = type == TransportType.mqtt
                    yandexContainer.hint = getString(R.string.jitsi_url_hint)
                    if (type == TransportType.mqtt && mqttBroker.text.isNullOrBlank()) {
                        mqttBroker.setText(ServiceRouteEndpoint.DEFAULT_MQTT_BROKER)
                    }
                    encryptionKeyContainer.helperText = getString(R.string.service_key_required)
                }
                TransportType.yandex -> {
                    transportIcon.setImageResource(R.drawable.yandex_docs)
                    transportLabel.text = getString(R.string.yandex_docs_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    mqttContainer.isVisible = false
                    yandexContainer.hint = getString(R.string.document_url)
                }
                TransportType.vyandex -> {
                    transportIcon.setImageResource(R.drawable.volga)
                    transportLabel.text = getString(R.string.vyandex_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    mqttContainer.isVisible = false
                    yandexContainer.hint = getString(R.string.document_url)
                }
                TransportType.max -> {
                    transportIcon.setImageResource(R.drawable.max_msg)
                    transportLabel.text = getString(R.string.max_messenger_backend)
                    maxContainer.isVisible = true
                    yandexContainer.isVisible = false
                    mqttContainer.isVisible = false
                }
                TransportType.cups -> {
                    transportIcon.setImageResource(R.drawable.ic_cups)
                    transportLabel.text = getString(R.string.cups_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    mqttContainer.isVisible = false
                    yandexContainer.hint = getString(R.string.cups_url_hint)
                }
                TransportType.mailru -> {
                    transportIcon.setImageResource(R.drawable.ic_mailru)
                    transportLabel.text = getString(R.string.mailru_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    mqttContainer.isVisible = false
                    yandexContainer.hint = getString(R.string.mailru_url_hint)
                }
            }
            // Negotiated sessions are an internal transport capability, never a manual
            // MQTT or Volga setting.
            sessionSection?.isVisible = false
            sessionCheck.isVisible = false
            sessionCheck.isChecked = false
            yandexContainer.clearError()
            mqttBrokerContainer.clearError()
            mqttTopicContainer.clearError()
            mqttClientIdContainer.clearError()
            maxTokenContainer.clearError()
            maxUserIdContainer.clearError()
        }

        // ─── Заполнение при редактировании ───
        editing?.let { t ->
            toolbarTitle.text = getString(R.string.edit_config)
            name.setText(t.name)
            val initialTransport = TransportType.from(t.transportType)
            updateTransportUi(initialTransport)

            when (initialTransport) {
                TransportType.directDpi -> Unit
                TransportType.yandex, TransportType.vyandex -> {
                    val urlsVal = argValue(t.transportConnPayload, "--urls")
                    val displayUrl = if (urlsVal.isNotEmpty()) urlsVal.split(",").joinToString("\n") else argValue(t.transportConnPayload, "--url")
                    docUrl.setText(displayUrl)
                }
                TransportType.max -> {
                    maxToken.setText(argValue(t.transportConnPayload, "--maxToken"))
                    maxUid.setText(argValue(t.transportConnPayload, "--maxUid"))
                }
                TransportType.cups, TransportType.mailru, TransportType.jitsi -> {
                    docUrl.setText(argValue(t.transportConnPayload, "--url"))
                }
                TransportType.mqtt -> {
                    val parsed = ServiceRouteEndpoint.parseMqttUrl(argValue(t.transportConnPayload, "--url"))
                    mqttBroker.setText(argValue(t.transportConnPayload, "--mqtt-broker").ifBlank { parsed?.broker ?: ServiceRouteEndpoint.DEFAULT_MQTT_BROKER })
                    mqttTopic.setText(argValue(t.transportConnPayload, "--mqtt-topic").ifBlank { parsed?.topic.orEmpty() })
                    mqttClientId.setText(argValue(t.transportConnPayload, "--mqtt-client-id"))
                }
            }

            val keyFromProp = t.encryptionKey?.trim()
            if (!keyFromProp.isNullOrEmpty()) {
                encryptionKey.setText(keyFromProp)
            } else {
                val keyPath = argValue(t.transportConnPayload, "--encryption-key-file")
                if (keyPath.isNotEmpty()) {
                    try {
                        val kf = File(keyPath)
                        if (kf.exists()) {
                            encryptionKey.setText(kf.readText().trim())
                        }
                    } catch (_: Exception) {}
                }
            }

            val extra = AddEditTunnelViewModel.extractExtraParams(t.transportConnPayload)
            if (extra.isNotEmpty()) {
                extraParams.setText(extra)
            }
            val isNegotiated = t.transportConnPayload.any { it == "--session-negotiate" || it == "--session-negotiate=true" }
            sessionCheck.isChecked = isNegotiated
            sessionOptions.isVisible = isNegotiated && initialTransport in setOf(TransportType.vyandex, TransportType.mqtt, TransportType.jitsi)
            fun value(flag: String, fallback: String): String {
                val eqPrefix = "$flag="
                for (item in t.transportConnPayload) {
                    if (item.startsWith(eqPrefix, ignoreCase = true)) {
                        return item.substring(eqPrefix.length).trim('"', '\'')
                    }
                }
                val idx = t.transportConnPayload.indexOfFirst { it.equals(flag, ignoreCase = true) }
                return if (idx >= 0 && idx + 1 < t.transportConnPayload.size) t.transportConnPayload[idx + 1].trim('"', '\'') else fallback
            }
            val curBatch = value("--session-batch-bytes", "16384").toIntOrNull() ?: 16384
            val batchIdx = batchValues.indexOf(curBatch)
            batchSpinner.setSelection(if (batchIdx >= 0) batchIdx else batchValues.indexOf(16384).coerceAtLeast(0))

            val curLinger = value("--session-linger-ms", "5").toIntOrNull() ?: 5
            val lingerIdx = lingerValues.indexOf(curLinger)
            lingerSpinner.setSelection(if (lingerIdx >= 0) lingerIdx else lingerValues.indexOf(5).coerceAtLeast(0))

            val curComp = value("--session-compression", "zstd-auto")
            val compIdx = compressionValues.indexOf(curComp)
            compressionSpinner.setSelection(if (compIdx >= 0) compIdx else 0)

            save.text = getString(R.string.save)
        } ?: run {
            toolbarTitle.text = getString(R.string.enter_manually)
            updateTransportUi(TransportType.yandex)
        }

        transportLayout.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            showTransportBottomSheet(transport) { selected ->
                updateTransportUi(selected)
                addEditVm.setTransport(selected)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    addEditVm.formState.collect { state ->
                        save.isEnabled = !state.isSaving
                    }
                }
                launch {
                    addEditVm.events.collect { event ->
                        when (event) {
                            is AddEditEvent.Saved -> {
                                if (event.oldTunnel != null) {
                                    vm.updateTunnel(event.oldTunnel, event.newTunnel)
                                    Toast.makeText(requireContext(), R.string.config_saved, Toast.LENGTH_SHORT).show()
                                } else {
                                    vm.addTunnel(event.newTunnel)
                                }
                                requireActivity().onBackPressedDispatcher.onBackPressed()
                            }
                            is AddEditEvent.ValidationError -> {
                                when (event.field) {
                                    AddEditEvent.FieldError.NAME -> {
                                        nameContainer.error = getString(event.messageRes)
                                        name.requestFocus()
                                    }
                                    AddEditEvent.FieldError.URL -> {
                                        yandexContainer.error = getString(event.messageRes)
                                        docUrl.requestFocus()
                                    }
                                    AddEditEvent.FieldError.TOKEN -> {
                                        maxTokenContainer.error = getString(event.messageRes)
                                        maxToken.requestFocus()
                                    }
                                    AddEditEvent.FieldError.UID -> {
                                        maxUserIdContainer.error = getString(event.messageRes)
                                        maxUid.requestFocus()
                                    }
                                    AddEditEvent.FieldError.KEY -> {
                                        encryptionKeyContainer.error = getString(event.messageRes)
                                        encryptionKey.requestFocus()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        save.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            addEditVm.validateAndSave(
                name = name.text?.toString().orEmpty(),
                docUrl = docUrl.text?.toString().orEmpty(),
                maxToken = maxToken.text?.toString().orEmpty(),
                maxUid = maxUid.text?.toString().orEmpty(),
                encryptionKey = encryptionKey.text?.toString().orEmpty(),
                extraParams = extraParams.text?.toString().orEmpty(),
                mqttBroker = mqttBroker.text?.toString().orEmpty(),
                mqttTopic = mqttTopic.text?.toString().orEmpty(),
                mqttClientId = mqttClientId.text?.toString().orEmpty(),
                sessionNegotiate = sessionCheck.isChecked,
                sessionBatchBytes = batchValues[batchSpinner.selectedItemPosition.coerceIn(0, batchValues.size - 1)],
                sessionLingerMs = lingerValues[lingerSpinner.selectedItemPosition.coerceIn(0, lingerValues.size - 1)],
                sessionCompression = compressionValues[compressionSpinner.selectedItemPosition.coerceIn(0, compressionValues.size - 1)]
            )
        }
    }

    private fun showTransportBottomSheet(
        current: TransportType,
        onSelect: (TransportType) -> Unit
    ) {
        val dialog = BottomSheetDialog(requireContext())
        val sheetView = layoutInflater.inflate(R.layout.bottom_sheet_transport_picker, null)
        dialog.setContentView(sheetView)

        sheetView.findViewById<View>(R.id.check_yandex)?.isVisible = current == TransportType.yandex
        sheetView.findViewById<View>(R.id.check_vyandex)?.isVisible = current == TransportType.vyandex
        sheetView.findViewById<View>(R.id.check_max)?.isVisible = current == TransportType.max
        sheetView.findViewById<View>(R.id.check_cups)?.isVisible = current == TransportType.cups
        sheetView.findViewById<View>(R.id.check_mailru)?.isVisible = current == TransportType.mailru

        fun bindOption(viewId: Int, type: TransportType) {
            sheetView.findViewById<View>(viewId)?.setOnClickListener {
                it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
                onSelect(type)
                dialog.dismiss()
            }
        }

        bindOption(R.id.picker_yandex, TransportType.yandex)
        bindOption(R.id.picker_vyandex, TransportType.vyandex)
        bindOption(R.id.picker_max, TransportType.max)
        bindOption(R.id.picker_cups, TransportType.cups)
        bindOption(R.id.picker_mailru, TransportType.mailru)
        bindOption(R.id.picker_mqtt, TransportType.mqtt)
        bindOption(R.id.picker_jitsi, TransportType.jitsi)
        sheetView.findViewById<View>(R.id.check_mqtt)?.isVisible = current == TransportType.mqtt
        sheetView.findViewById<View>(R.id.check_jitsi)?.isVisible = current == TransportType.jitsi

        dialog.show()
    }

    private fun argValue(payload: List<String>, key: String): String {
        val eqPrefix = "$key="
        payload.firstOrNull { it.startsWith(eqPrefix, ignoreCase = true) }?.let {
            return it.substring(eqPrefix.length).trim('"', '\'')
        }
        val idx = payload.indexOf(key)
        return if (idx >= 0 && idx + 1 < payload.size) payload[idx + 1] else ""
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
