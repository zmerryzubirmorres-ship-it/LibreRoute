package io.github.p1neapplexpress.openflux.ui

import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.util.AppSettings
import io.github.p1neapplexpress.openflux.util.performAppHaptics

class MtuBottomSheetDialog(
    context: Context,
    private val currentMtu: Int,
    private val onMtuSelected: (Int) -> Unit
) : BottomSheetDialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.bottom_sheet_mtu)

        val input = findViewById<TextInputEditText>(R.id.input_mtu_value) ?: return
        val btnSave = findViewById<MaterialButton>(R.id.btn_mtu_save)
        val btnCancel = findViewById<MaterialButton>(R.id.btn_mtu_cancel)

        input.setText(currentMtu.toString())
        input.setSelection(input.text?.length ?: 0)

        btnSave?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            val text = input.text?.toString()?.trim()
            val value = text?.toIntOrNull() ?: currentMtu
            val clamped = value.coerceIn(AppSettings.MIN_MTU, AppSettings.MAX_MTU)
            onMtuSelected(clamped)
            dismiss()
        }

        btnCancel?.setOnClickListener {
            it.performAppHaptics(HapticFeedbackConstants.VIRTUAL_KEY)
            dismiss()
        }
    }
}
