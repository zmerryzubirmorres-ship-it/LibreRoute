package io.github.p1neapplexpress.openflux.ui

import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.textfield.TextInputLayout
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.event.AppEvent
import io.github.p1neapplexpress.openflux.util.performAppHaptics
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
        val encryptionKeyContainer = view.findViewById<TextInputLayout>(R.id.encryptionKeyContainer)
        val transportLabel = view.findViewById<TextView>(R.id.selectedTransport)
        val docUrl = view.findViewById<TextView>(R.id.documentUrl)
        val maxToken = view.findViewById<TextView>(R.id.maxToken)
        val maxUid = view.findViewById<TextView>(R.id.maxUserId)
        val name = view.findViewById<TextView>(R.id.name)
        val encryptionKey = view.findViewById<TextView>(R.id.encryptionKey)
        val extraParamsContainer = view.findViewById<TextInputLayout>(R.id.extraParamsContainer)
        val extraParams = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.extraParams)
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

        fun updateTransportUi(type: TransportType) {
            transport = type
            when (type) {
                TransportType.yandex -> {
                    transportIcon.setImageResource(R.drawable.yandex_docs)
                    transportLabel.text = getString(R.string.yandex_docs_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    yandexContainer.hint = getString(R.string.document_url)
                }
                TransportType.vyandex -> {
                    transportIcon.setImageResource(R.drawable.volga)
                    transportLabel.text = getString(R.string.vyandex_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    yandexContainer.hint = getString(R.string.document_url)
                }
                TransportType.max -> {
                    transportIcon.setImageResource(R.drawable.max_msg)
                    transportLabel.text = getString(R.string.max_messenger_backend)
                    maxContainer.isVisible = true
                    yandexContainer.isVisible = false
                }
                TransportType.cups -> {
                    transportIcon.setImageResource(R.drawable.ic_cups)
                    transportLabel.text = getString(R.string.cups_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    yandexContainer.hint = getString(R.string.cups_url_hint)
                }
                TransportType.mailru -> {
                    transportIcon.setImageResource(R.drawable.ic_mailru)
                    transportLabel.text = getString(R.string.mailru_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    yandexContainer.hint = getString(R.string.mailru_url_hint)
                }
                TransportType.gdocs -> {
                    transportIcon.setImageResource(R.drawable.ic_gdocs)
                    transportLabel.text = getString(R.string.gdocs_backend)
                    maxContainer.isVisible = false
                    yandexContainer.isVisible = true
                    yandexContainer.hint = getString(R.string.gdocs_url_hint)
                }
            }
            yandexContainer.clearError()
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
                TransportType.yandex, TransportType.vyandex -> {
                    val urlsVal = argValue(t.transportConnPayload, "--urls")
                    val displayUrl = if (urlsVal.isNotEmpty()) urlsVal.split(",").joinToString("\n") else argValue(t.transportConnPayload, "--url")
                    docUrl.setText(displayUrl)
                }
                TransportType.max -> {
                    maxToken.setText(argValue(t.transportConnPayload, "--maxToken"))
                    maxUid.setText(argValue(t.transportConnPayload, "--maxUid"))
                }
                TransportType.cups, TransportType.mailru, TransportType.gdocs -> {
                    docUrl.setText(argValue(t.transportConnPayload, "--url"))
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
                extraParams = extraParams.text?.toString().orEmpty()
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
        sheetView.findViewById<View>(R.id.check_gdocs)?.isVisible = current == TransportType.gdocs

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
        bindOption(R.id.picker_gdocs, TransportType.gdocs)

        dialog.show()
    }

    private fun argValue(payload: List<String>, key: String): String {
        val idx = payload.indexOf(key)
        return if (idx >= 0 && idx + 1 < payload.size) payload[idx + 1] else ""
    }

    override fun onNewEvent(ev: AppEvent) = Unit
}
