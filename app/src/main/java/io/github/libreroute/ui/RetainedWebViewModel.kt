package io.github.libreroute.ui

import android.app.Activity
import android.app.Application
import android.content.MutableContextWrapper
import android.view.ViewGroup
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel

/** Keeps the live page, DOM drafts and navigation across configuration changes. */
class RetainedWebViewModel(app: Application) : AndroidViewModel(app) {
    private val browserContext = MutableContextWrapper(app)
    private var browser: WebView? = null

    fun obtain(activity: Activity): WebView {
        browserContext.baseContext = activity
        return (browser ?: WebView(browserContext).also { browser = it }).also {
            (it.parent as? ViewGroup)?.removeView(it)
        }
    }

    fun replace(activity: Activity, placeholder: WebView): WebView {
        val parent = placeholder.parent as ViewGroup
        val position = parent.indexOfChild(placeholder)
        val params = placeholder.layoutParams
        val retained = obtain(activity).apply { id = placeholder.id; visibility = placeholder.visibility }
        parent.removeView(placeholder)
        placeholder.destroy()
        parent.addView(retained, position, params)
        return retained
    }

    fun detach(activity: Activity) {
        if (browserContext.baseContext !== activity) return
        browser?.let { (it.parent as? ViewGroup)?.removeView(it) }
        browserContext.baseContext = getApplication<Application>()
    }

    override fun onCleared() {
        browser?.apply {
            (parent as? ViewGroup)?.removeView(this)
            stopLoading()
            webChromeClient = null
            webViewClient = android.webkit.WebViewClient()
            destroy()
        }
        browser = null
        browserContext.baseContext = getApplication<Application>()
        super.onCleared()
    }
}
