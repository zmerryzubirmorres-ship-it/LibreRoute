package io.github.libreroute.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import androidx.appcompat.app.AppCompatActivity
import io.github.libreroute.R

/** Browser controls live outside provider HTML; redirects never overwrite an address draft. */
class BrowserChrome(private val activity: AppCompatActivity, savedState: Bundle?) {
    private val address = activity.findViewById<EditText>(R.id.browser_address)
    private val reload = activity.findViewById<ImageView>(R.id.btn_reload)
    private val progress = activity.findViewById<ProgressBar>(R.id.progress_bar)
    private var loading = false
    private var editing = savedState?.getBoolean("browser_address_editing") == true

    init {
        if (editing) address.setText(savedState?.getString("browser_address_draft").orEmpty())
        address.setOnFocusChangeListener { _, focused -> editing = focused }
    }

    fun bindReload(browser: WebView, canNavigate: () -> Boolean = { true }) {
        reload.setOnClickListener {
            if (canNavigate()) {
                if (loading) { browser.stopLoading(); setLoading(false) } else browser.reload()
            }
        }
    }

    fun pageStarted(url: String?) { updateAddress(url); setLoading(true) }
    fun pageFinished(url: String?) { updateAddress(url); setLoading(false) }
    fun pageFailed() { setLoading(false) }

    fun progressChanged(value: Int) {
        progress.progress = value
        if (value >= 100) setLoading(false)
    }

    fun navigate(url: String) {
        editing = false
        address.setText(url)
        address.error = null
        address.clearFocus()
        (activity.getSystemService(AppCompatActivity.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(address.windowToken, 0)
    }

    fun saveState(outState: Bundle) {
        outState.putBoolean("browser_address_editing", editing || address.hasFocus())
        outState.putString("browser_address_draft", address.text.toString())
    }

    private fun updateAddress(url: String?) {
        if (!editing && !address.hasFocus() && !url.isNullOrBlank()) address.setText(url)
    }

    private fun setLoading(value: Boolean) {
        loading = value
        progress.visibility = if (value) View.VISIBLE else View.INVISIBLE
        reload.setImageResource(if (value) R.drawable.ic_close else R.drawable.ic_refresh)
        reload.contentDescription = activity.getString(if (value) R.string.browser_stop else R.string.browser_reload)
    }
}
