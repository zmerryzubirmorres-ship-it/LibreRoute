package io.github.libreroute.ui

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import io.github.libreroute.R

/** The prepared message and its position stay open across Activity recreation. */
class AdminContactDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val text = requireArguments().getString("message").orEmpty()
        return CalmDetailDialog(requireContext(), getString(R.string.calm_message_prepared)).apply {
            text(text)
            action(getString(R.string.calm_share_admin)) {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                runCatching { startActivity(Intent.createChooser(send, getString(R.string.calm_share_admin))) }
                    .onFailure { Toast.makeText(context, "Скопируйте текст и передайте его администратору", Toast.LENGTH_LONG).show() }
            }
            action(getString(R.string.calm_copy_message), secondary = true) {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("LibreRoute", text))
                Toast.makeText(context, R.string.calm_message_copied, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
    }
}
