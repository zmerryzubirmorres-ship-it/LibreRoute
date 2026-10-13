package io.github.libreroute.admin

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import io.github.libreroute.R
import io.github.libreroute.util.QrGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The same invitation is retained through rotation; rendering never issues a new one. */
class InvitationShareDialogFragment : DialogFragment() {
    private var linkVisible = false
    private var qrBitmap: Bitmap? = null

    companion object {
        private const val TAG = "invitation-share"
        fun show(manager: FragmentManager, subtitle: String, link: String) {
            if (manager.isStateSaved || manager.findFragmentByTag(TAG) != null) return
            InvitationShareDialogFragment().apply {
                arguments = Bundle().apply { putString("subtitle", subtitle); putString("link", link) }
            }.show(manager, TAG)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = invitationDialog()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_invitation_share, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val link = requireArguments().getString("link").orEmpty()
        linkVisible = savedInstanceState?.getBoolean("linkVisible") ?: false
        view.findViewById<TextView>(R.id.tv_invite_profile_name).text = requireArguments().getString("subtitle")
        val linkText = view.findViewById<TextView>(R.id.tv_invite_link)
        val reveal = view.findViewById<TextView>(R.id.btn_show_invite)
        fun updateLink() {
            linkText.text = if (linkVisible) link else ""
            linkText.isVisible = linkVisible
            reveal.setText(if (linkVisible) R.string.invite_hide_link else R.string.invite_show_link)
        }
        updateLink()
        reveal.setOnClickListener { linkVisible = !linkVisible; updateLink() }
        view.findViewById<View>(R.id.btn_close_invite).setOnClickListener { dismiss() }
        view.findViewById<View>(R.id.btn_copy_invite).setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("LibreRoute Invite", link))
            Toast.makeText(context, R.string.invite_copied, Toast.LENGTH_SHORT).show()
        }
        view.findViewById<View>(R.id.btn_share_invite).setOnClickListener { share(link) }
        val image = view.findViewById<ImageView>(R.id.img_invite_qr)
        image.setOnClickListener {
            if (qrBitmap != null && childFragmentManager.findFragmentByTag("invitation-qr-full") == null) {
                InvitationQrDialogFragment().apply {
                    arguments = Bundle().apply { putString("link", link) }
                }.show(childFragmentManager, "invitation-qr-full")
            }
        }
        image.post {
            if (this.view !== view) return@post
            val size = (image.width - image.paddingLeft - image.paddingRight).coerceIn(256, 2048)
            viewLifecycleOwner.lifecycleScope.launch {
                qrBitmap = withContext(Dispatchers.Default) { QrGenerator.generateQrBitmap(link, size) }
                val status = view.findViewById<TextView>(R.id.tv_invite_qr_status)
                image.isVisible = qrBitmap != null
                if (qrBitmap != null) image.setQrBitmap(requireNotNull(qrBitmap))
                status.setText(if (qrBitmap != null) R.string.invite_qr_enlarge else R.string.invite_qr_failed)
            }
        }
    }

    override fun onStart() { super.onStart(); sizeInvitationWindow(false) }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("linkVisible", linkVisible)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() { qrBitmap = null; super.onDestroyView() }

    private fun share(link: String) {
        val context = requireContext()
        val bitmap = qrBitmap
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val intent = withContext(Dispatchers.IO) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "LibreRoute")
                        putExtra(Intent.EXTRA_TEXT, link)
                        if (bitmap != null) {
                            val directory = File(context.cacheDir, "shared_images").apply { mkdirs() }
                            val file = File.createTempFile("invite_", ".png", directory)
                            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            clipData = ClipData.newUri(context.contentResolver, "LibreRoute QR", uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                }
                startActivity(Intent.createChooser(intent, getString(R.string.invite_share_title)))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                Toast.makeText(context, R.string.invite_share_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

class InvitationQrDialogFragment : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog = invitationDialog()
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.dialog_invitation_qr, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.findViewById<View>(R.id.invite_qr_full_close).setOnClickListener { dismiss() }
        val image = view.findViewById<ImageView>(R.id.invite_qr_full_image)
        image.post {
            if (this.view !== view) return@post
            val size = (minOf(image.width, image.height) - image.paddingLeft - image.paddingRight).coerceIn(256, 2048)
            viewLifecycleOwner.lifecycleScope.launch {
                val bitmap = withContext(Dispatchers.Default) {
                    QrGenerator.generateQrBitmap(requireArguments().getString("link").orEmpty(), size)
                }
                image.isVisible = bitmap != null
                view.findViewById<View>(R.id.invite_qr_full_error).isVisible = bitmap == null
                if (bitmap != null) image.setQrBitmap(bitmap)
            }
        }
    }
    override fun onStart() { super.onStart(); sizeInvitationWindow(true) }
}

private fun DialogFragment.invitationDialog() = Dialog(requireContext(), R.style.Theme_SoxMax).apply {
    requestWindowFeature(Window.FEATURE_NO_TITLE)
    window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
}

private fun DialogFragment.sizeInvitationWindow(full: Boolean) {
    val metrics = resources.displayMetrics
    val width = if (full) metrics.widthPixels else minOf((metrics.widthPixels * 0.94).toInt(), (560 * metrics.density).toInt())
    val height = if (full) (metrics.heightPixels * 0.96).toInt() else ViewGroup.LayoutParams.WRAP_CONTENT
    dialog?.window?.setLayout(width, height)
}

private fun ImageView.setQrBitmap(bitmap: Bitmap) {
    setImageDrawable(BitmapDrawable(resources, bitmap).apply { isFilterBitmap = false })
}
