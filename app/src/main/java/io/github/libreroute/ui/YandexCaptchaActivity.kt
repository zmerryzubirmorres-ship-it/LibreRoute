package io.github.libreroute.ui

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
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.libreroute.R
import io.github.libreroute.service.Ipv4WebViewProxy
import io.github.libreroute.event.YandexAuthReason
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Human-operated Yandex challenge; no CAPTCHA solver or challenge URL is logged. */
class YandexCaptchaActivity : AppCompatActivity() {
    private val retainedBrowser by viewModels<RetainedWebViewModel>()
    private lateinit var browser: WebView
    private lateinit var chrome: BrowserChrome
    private lateinit var status: TextView
    private var documentUrl: String = ""
    private var reason = YandexAuthReason.CAPTCHA
    private val vm by viewModels<YandexBrowserViewModel>()
    private val verifying get() = vm.state.value.running
    private var retryNotBefore: Long
        get() = vm.retryNotBefore
        set(value) { vm.retryNotBefore = value }
    private var ipv4Proxy: Ipv4WebViewProxy? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        retryNotBefore = savedInstanceState?.getLong("retry_not_before") ?: 0L
        val candidate = intent.getStringExtra(EXTRA_DOCUMENT_URL).orEmpty()
        if (!isAllowed(candidate)) {
            finish()
            return
        }
        documentUrl = candidate
        io.github.libreroute.admin.InstallationLog.append(this, "client_yandex_webview_open", "user_requested=true")
        reason = runCatching {
            YandexAuthReason.valueOf(intent.getStringExtra(EXTRA_REASON).orEmpty())
        }.getOrDefault(YandexAuthReason.CAPTCHA)

        setContentView(R.layout.activity_remote_auth)
        status = findViewById(R.id.tv_session_status)
        status.text = getString(if (reason == YandexAuthReason.CAPTCHA) R.string.yandex_webview_captcha_intro else R.string.yandex_webview_session_intro)
        findViewById<TextView>(R.id.browser_title).text = "Проверка подключения"
        findViewById<TextView>(R.id.tv_target_info).text = "Завершите проверку на странице"
        findViewById<TextView>(R.id.tv_tunnel_badge).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.btn_manual_fallback).visibility = android.view.View.GONE
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { navigateBack() }
        findViewById<Button>(R.id.btn_return_server).apply { text = "Вернуться в приложение"; setOnClickListener { finish() } }
        val address = findViewById<android.widget.EditText>(R.id.browser_address).apply { isEnabled = true; setText(documentUrl) }
        chrome = BrowserChrome(this, savedInstanceState)
        val pageError = findViewById<TextView>(R.id.tv_error_view)
        val retryPage = findViewById<Button>(R.id.btn_retry_page)
        browser = retainedBrowser.replace(this, findViewById(R.id.web_view))
        // WebView itself has no IPv4-only setting. Install a loopback CONNECT
        // proxy before the first navigation; it resolves every destination to
        // an IPv4 address and leaves TLS/cookies end-to-end in Chromium.
        ipv4Proxy = vm.proxy
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        fun openAddress() {
            val entered = address.text.toString().trim()
            val url = if (entered.startsWith("https://")) entered else "https://$entered"
            if (!isAllowed(url)) {
                address.error = "Для этой проверки нужен HTTPS-адрес Яндекса"
                return
            }
            chrome.navigate(url)
            browser.loadUrl(url)
        }
        findViewById<Button>(R.id.browser_go).apply { isEnabled = true; setOnClickListener { openAddress() } }
        address.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { openAddress(); true } else false
        }
        chrome.bindReload(browser)
        retryPage.setOnClickListener { openAddress() }
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.settings.allowFileAccess = false
        browser.settings.allowContentAccess = false
        browser.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        browser.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                chrome.progressChanged(newProgress)
            }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !isAllowed(request.url.toString())
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                chrome.pageStarted(url)
                pageError.visibility = android.view.View.GONE
                retryPage.visibility = android.view.View.GONE
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    chrome.pageFailed()
                    pageError.text = "Не удалось загрузить страницу. Проверьте сеть и повторите попытку"
                    pageError.visibility = android.view.View.VISIBLE
                    retryPage.visibility = android.view.View.VISIBLE
                    retryPage.isEnabled = true
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                chrome.pageFinished(url)
                if (verifying) return
                status.text = if (url.contains("showcaptcha", ignoreCase = true)) {
                    "Завершите проверку на странице, затем дождитесь открытия документа"
                } else {
                    "Если документ открылся, нажмите «Повторить подключение»"
                }
            }
        }
        findViewById<Button>(R.id.btn_send_auth).apply {
            text = "Повторить подключение"
            setOnClickListener { verifyAndSave() }
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    findViewById<Button>(R.id.btn_send_auth).isEnabled = !state.running
                    state.message?.let { status.text = it }
                    if (state.saved) { setResult(Activity.RESULT_OK); finish() }
                }
            }
        }
        val browserState = savedInstanceState?.getBundle("browser")
        val proxy = ipv4Proxy
        if (proxy == null) {
            status.text = "Не удалось включить IPv4 для проверки. Проверьте сеть"
        } else {
            proxy.install { installed ->
                if (isFinishing || isDestroyed) return@install
                if (!installed) {
                    status.text = "Не удалось включить IPv4 для проверки. Проверьте сеть"
                } else if (!isFinishing && !isDestroyed) {
                    if (browser.url == null && (browserState == null || browser.restoreState(browserState) == null)) browser.loadUrl(documentUrl)
                }
            }
        }
    }

    private fun navigateBack() { if (::browser.isInitialized && browser.canGoBack()) browser.goBack() else finish() }

    private fun verifyAndSave() {
        if (verifying) return
        val retrySeconds = (retryNotBefore - android.os.SystemClock.elapsedRealtime() + 999) / 1000
        if (retrySeconds > 0) {
            status.text = "Яндекс ограничил запросы. Повторите через $retrySeconds с"
            return
        }
        val current = browser.url.orEmpty()
        if (!isAllowed(current) || current.contains("showcaptcha", ignoreCase = true)) {
            status.text = if (current.contains("showcaptcha", ignoreCase = true)) {
                getString(R.string.yandex_webview_captcha_pending)
            } else {
                getString(R.string.yandex_webview_document_pending)
            }
            return
        }

        val manager = CookieManager.getInstance()
        manager.flush()
        val origins = (listOf(documentUrl, current) + listOf(
            "https://docs.yandex.ru/",
            "https://disk.yandex.ru/",
            "https://volga.yandex.ru/",
            "https://yandex.ru/"
        )).filter { isAllowed(it) }.distinct()
        val cookies = JSONObject()
        var hasCaptchaToken = false
        for (origin in origins) {
            val c = manager.getCookie(origin)
            if (!c.isNullOrBlank()) {
                val parts = c.split(";").map { it.trim() }
                val seen = mutableSetOf<String>()
                val unique = mutableListOf<String>()
                for (part in parts) {
                    val name = part.substringBefore("=").trim()
                    if (name.isNotEmpty() && seen.add(name)) {
                        unique.add(part)
                    }
                }
                if (unique.isNotEmpty()) {
                    cookies.put(origin, unique.joinToString("; "))
                }
                if (c.contains("spravka", ignoreCase = true)) {
                    hasCaptchaToken = true
                }
            }
        }

        browser.evaluateJavascript("document.querySelector('#client-config') !== null") { result ->
            val hasClientConfig = (result == "true")
            if (!hasClientConfig && !hasCaptchaToken) {
                status.text = getString(R.string.yandex_webview_captcha_pending)
                return@evaluateJavascript
            }
            try {
                io.github.libreroute.admin.InstallationLog.append(
                    this,
                    "client_yandex_verify",
                    "document_ready=$hasClientConfig captcha_url=false origins=${cookies.length()} has_captcha_token=$hasCaptchaToken"
                )
                if (cookies.length() == 0) {
                    status.text = getString(R.string.yandex_webview_no_cookies)
                    return@evaluateJavascript
                }
                val handoff = JSONObject().put("user_agent", browser.settings.userAgentString)
                    .put("cookies", cookies)
                if (intent.getStringExtra(EXTRA_TRANSPORT) == "yandex") {
                    // The legacy editor uses a different authentication protocol.
                    openFileOutput(COOKIE_FILE, MODE_PRIVATE).use { it.write(handoff.toString().toByteArray(Charsets.UTF_8)) }
                    android.system.Os.chmod(getFileStreamPath(COOKIE_FILE).absolutePath, 0x180)
                    deleteFile("yandex_volga_session.json")
                    io.github.libreroute.admin.InstallationLog.append(this, "client_yandex_browser_saved", "cookies_present=true native_probe_passed=false")
                    setResult(Activity.RESULT_OK)
                    finish()
                } else {
                    verifyCandidate(handoff)
                }
            } catch (_: Exception) {
                status.text = getString(R.string.yandex_webview_save_failed)
            }
        }
    }

    private fun verifyCandidate(handoff: JSONObject) {
        if (!isFinishing && !isDestroyed) vm.verify(documentUrl, handoff)
    }

    override fun onDestroy() {
        ipv4Proxy = null
        if (::browser.isInitialized) {
            retainedBrowser.detach(this)
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::chrome.isInitialized) chrome.saveState(outState)
        outState.putLong("retry_not_before", retryNotBefore)
        if (::browser.isInitialized) outState.putBundle("browser", Bundle().also { browser.saveState(it) })
        super.onSaveInstanceState(outState)
    }

    companion object {
        const val EXTRA_DOCUMENT_URL = "document_url"
        const val EXTRA_REASON = "auth_reason"
        const val EXTRA_TRANSPORT = "transport_type"
        const val COOKIE_FILE = "yandex_webview_cookies.json"

        private fun isAllowed(raw: String): Boolean = runCatching {
            val uri = android.net.Uri.parse(raw)
            val host = uri.host?.lowercase().orEmpty()
            uri.scheme == "https" && (host == "yandex.ru" || host.endsWith(".yandex.ru"))
        }.getOrDefault(false)
    }
}
