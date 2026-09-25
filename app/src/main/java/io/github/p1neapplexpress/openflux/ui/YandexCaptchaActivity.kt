package io.github.p1neapplexpress.openflux.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.event.YandexAuthReason
import org.json.JSONObject

/** Human-operated Yandex challenge; no CAPTCHA solver or challenge URL is logged. */
class YandexCaptchaActivity : AppCompatActivity() {
    private lateinit var browser: WebView
    private lateinit var status: TextView
    private var documentUrl: String = ""
    private var reason = YandexAuthReason.CAPTCHA

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val candidate = intent.getStringExtra(EXTRA_DOCUMENT_URL).orEmpty()
        if (!isAllowed(candidate)) {
            finish()
            return
        }
        documentUrl = candidate
        reason = runCatching {
            YandexAuthReason.valueOf(intent.getStringExtra(EXTRA_REASON).orEmpty())
        }.getOrDefault(YandexAuthReason.CAPTCHA)

        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply {
            text = getString(if (reason == YandexAuthReason.CAPTCHA) {
                R.string.yandex_webview_captcha_intro
            } else {
                R.string.yandex_webview_session_intro
            })
            setPadding(24, 20, 24, 16)
        }
        layout.addView(status)
        browser = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && !isAllowed(request.url.toString())

                override fun onPageFinished(view: WebView, url: String) {
                    status.text = if (url.contains("showcaptcha", ignoreCase = true)) {
                        "Решите CAPTCHA на странице Яндекса, затем дождитесь открытия документа."
                    } else {
                        "Если документ открылся, нажмите «Повторить подключение»."
                    }
                }
            }
            loadUrl(documentUrl)
        }
        layout.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        val retry = Button(this).apply {
            text = "Повторить подключение"
            setOnClickListener { verifyAndSave() }
        }
        layout.addView(retry)
        setContentView(layout)
    }

    private fun verifyAndSave() {
        val current = browser.url.orEmpty()
        if (!isAllowed(current) || current.contains("showcaptcha", ignoreCase = true)) {
            status.text = if (current.contains("showcaptcha", ignoreCase = true)) {
                getString(R.string.yandex_webview_captcha_pending)
            } else {
                getString(R.string.yandex_webview_document_pending)
            }
            return
        }
        browser.evaluateJavascript("document.querySelector('#client-config') !== null") { result ->
            if (result != "true") {
                status.text = "Документ пока не открылся. Дождитесь загрузки после проверки."
                return@evaluateJavascript
            }
            try {
                val manager = CookieManager.getInstance()
                manager.flush()
                val origins = listOf("https://disk.yandex.ru/", "https://docs.yandex.ru/", "https://yandex.ru/")
                val cookies = JSONObject()
                for (origin in origins) {
                    manager.getCookie(origin)?.let { cookies.put(origin, it) }
                }
                if (cookies.length() == 0) {
                    status.text = "Яндекс не сохранил cookies проверки. Повторите её в окне браузера."
                    return@evaluateJavascript
                }
                val handoff = JSONObject().put("user_agent", browser.settings.userAgentString)
                    .put("cookies", cookies)
                openFileOutput(COOKIE_FILE, MODE_PRIVATE).use {
                    it.write(handoff.toString().toByteArray(Charsets.UTF_8))
                }
                android.system.Os.chmod(getFileStreamPath(COOKIE_FILE).absolutePath, 0x180) // 0600
                setResult(Activity.RESULT_OK)
                finish()
            } catch (_: Exception) {
                deleteFile(COOKIE_FILE)
                status.text = "Не удалось сохранить результат проверки. Попробуйте ещё раз."
            }
        }
    }

    override fun onDestroy() {
        if (::browser.isInitialized) {
            browser.stopLoading()
            browser.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_DOCUMENT_URL = "document_url"
        const val EXTRA_REASON = "auth_reason"
        const val COOKIE_FILE = "yandex_webview_cookies.json"

        private fun isAllowed(raw: String): Boolean = runCatching {
            val uri = android.net.Uri.parse(raw)
            val host = uri.host?.lowercase().orEmpty()
            uri.scheme == "https" && (host == "yandex.ru" || host.endsWith(".yandex.ru"))
        }.getOrDefault(false)
    }
}
