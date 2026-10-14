package io.github.libreroute.ui

import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.libreroute.R

/** Browser controls live outside provider HTML; redirects never overwrite an address draft. */
class BrowserChrome(private val activity: AppCompatActivity, savedState: Bundle?) {
    private val address = activity.findViewById<EditText>(R.id.browser_address)
    private val reload = activity.findViewById<ImageView>(R.id.btn_reload)
    private val progress = activity.findViewById<ProgressBar>(R.id.progress_bar)
    private var loading = savedState?.getBoolean("browser_loading") == true
    private var editing = savedState?.getBoolean("browser_address_editing") == true

    init {
        savedState?.getString("browser_address_draft")?.let(address::setText)
        progress.progress = savedState?.getInt("browser_progress") ?: 0
        setLoading(loading)
        if (savedState?.containsKey("browser_page_error_visibility") == true) {
            activity.findViewById<TextView>(R.id.tv_error_view)?.apply {
                text = savedState.getString("browser_page_error")
                visibility = savedState.getInt("browser_page_error_visibility")
            }
            activity.findViewById<View>(R.id.btn_retry_page)?.apply {
                visibility = savedState.getInt("browser_retry_visibility", View.GONE)
                isEnabled = savedState.getBoolean("browser_retry_enabled", false)
            }
            activity.findViewById<TextView>(R.id.tv_session_status)?.text = savedState.getString("browser_session_status")
        }
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
        outState.putBoolean("browser_loading", loading)
        outState.putInt("browser_progress", progress.progress)
        activity.findViewById<TextView>(R.id.tv_error_view)?.let {
            outState.putString("browser_page_error", it.text.toString())
            outState.putInt("browser_page_error_visibility", it.visibility)
        }
        activity.findViewById<View>(R.id.btn_retry_page)?.let {
            outState.putInt("browser_retry_visibility", it.visibility)
            outState.putBoolean("browser_retry_enabled", it.isEnabled)
        }
        activity.findViewById<TextView>(R.id.tv_session_status)?.let { outState.putString("browser_session_status", it.text.toString()) }
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
