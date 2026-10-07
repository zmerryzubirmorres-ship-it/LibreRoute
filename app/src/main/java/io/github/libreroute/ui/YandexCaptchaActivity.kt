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
import androidx.lifecycle.lifecycleScope
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
    private lateinit var browser: WebView
    private lateinit var status: TextView
    private var documentUrl: String = ""
    private var reason = YandexAuthReason.CAPTCHA
    private var verifying = false
    private var retryNotBefore = 0L
    @Volatile private var probeProcess: Process? = null
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
        findViewById<android.view.View>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_return_server).apply { text = "Вернуться в приложение"; setOnClickListener { finish() } }
        val address = findViewById<android.widget.EditText>(R.id.browser_address).apply { isEnabled = true; setText(documentUrl) }
        val pageError = findViewById<TextView>(R.id.tv_error_view)
        val progress = findViewById<android.widget.ProgressBar>(R.id.progress_bar)
        val retryPage = findViewById<Button>(R.id.btn_retry_page)
        browser = findViewById(R.id.web_view)
        // WebView itself has no IPv4-only setting. Install a loopback CONNECT
        // proxy before the first navigation; it resolves every destination to
        // an IPv4 address and leaves TLS/cookies end-to-end in Chromium.
        ipv4Proxy = runCatching { Ipv4WebViewProxy() }.getOrNull()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        fun openAddress() {
            val entered = address.text.toString().trim()
            val url = if (entered.startsWith("https://")) entered else "https://$entered"
            if (!isAllowed(url)) {
                address.error = "Для этой проверки нужен HTTPS-адрес Яндекса"
                return
            }
            address.clearFocus()
            browser.loadUrl(url)
        }
        findViewById<Button>(R.id.browser_go).apply { isEnabled = true; setOnClickListener { openAddress() } }
        address.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_GO) { openAddress(); true } else false
        }
        findViewById<android.view.View>(R.id.btn_reload).setOnClickListener { browser.reload() }
        retryPage.setOnClickListener { openAddress() }
        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.settings.allowFileAccess = false
        browser.settings.allowContentAccess = false
        browser.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        browser.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress.visibility = if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
                progress.progress = newProgress
            }
        }
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !isAllowed(request.url.toString())
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                pageError.visibility = android.view.View.GONE
                retryPage.visibility = android.view.View.GONE
            }
            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    pageError.text = "Не удалось загрузить страницу. Проверьте сеть и повторите попытку"
                    pageError.visibility = android.view.View.VISIBLE
                    retryPage.visibility = android.view.View.VISIBLE
                    retryPage.isEnabled = true
                }
            }
            override fun onPageFinished(view: WebView, url: String) {
                if (!address.hasFocus()) address.setText(url)
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
        val proxy = ipv4Proxy
        if (proxy == null) {
            status.text = "Не удалось включить IPv4 для проверки. Проверьте сеть"
        } else {
            proxy.install { installed ->
                if (!installed) {
                    status.text = "Не удалось включить IPv4 для проверки. Проверьте сеть"
                } else if (!isFinishing && !isDestroyed) {
                    browser.loadUrl(documentUrl)
                }
            }
        }
    }

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
        if (verifying || isFinishing || isDestroyed) return
        verifying = true
        findViewById<Button>(R.id.btn_send_auth).isEnabled = false
        status.text = "Проверяем соединение с Яндексом…"
        val candidateDir = File(filesDir, "yandex-probe-${java.util.UUID.randomUUID()}")
        lifecycleScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    check(candidateDir.mkdir())
                    val candidate = File(candidateDir, COOKIE_FILE)
                    candidate.writeText(handoff.toString(), Charsets.UTF_8)
                    android.system.Os.chmod(candidate.absolutePath, 0x180)
                    io.github.libreroute.service.YandexBrowserProbe.run(this@YandexCaptchaActivity, documentUrl, candidate) {
                        probeProcess = it
                        if (isFinishing || isDestroyed) it?.destroyForcibly()
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                if (!outcome.optBoolean("ok")) {
                    status.text = when (outcome.optString("reason")) {
                        "captcha" -> "Яндекс снова запросил проверку. Обновите страницу и завершите её"
                        "session" -> "Яндекс не принял сессию. Обновите документ и повторите проверку"
                        "rate_limit" -> {
                            val seconds = outcome.optLong("retry_after_seconds").coerceAtLeast(30)
                            retryNotBefore = android.os.SystemClock.elapsedRealtime() + seconds.coerceAtMost(Int.MAX_VALUE.toLong()) * 1000
                            "Яндекс ограничил запросы. Повторите через $seconds с"
                        }
                        else -> "Не удалось проверить соединение. Проверьте сеть и повторите попытку"
                    }
                    io.github.libreroute.admin.InstallationLog.append(this@YandexCaptchaActivity, "client_yandex_probe_failed", "reason=${outcome.optString("reason").takeIf { it in setOf("captcha", "session", "rate_limit", "network") } ?: "network"}")
                    return@launch
                }
                // Copy to temporary siblings before replacing the active files.
                // The probe's cache is bound to the exact promoted cookie bytes.
                val cookieTarget = getFileStreamPath(COOKIE_FILE)
                val cookieTmp = File(filesDir, "$COOKIE_FILE.new")
                File(candidateDir, COOKIE_FILE).copyTo(cookieTmp, overwrite = true)
                android.system.Os.chmod(cookieTmp.absolutePath, 0x180)
                val cacheName = "yandex_volga_session.json"
                val cacheTmp = File(filesDir, "$cacheName.new")
                File(candidateDir, cacheName).copyTo(cacheTmp, overwrite = true)
                android.system.Os.chmod(cacheTmp.absolutePath, 0x180)
                check(cookieTmp.renameTo(cookieTarget))
                check(cacheTmp.renameTo(getFileStreamPath(cacheName)))
                io.github.libreroute.admin.InstallationLog.append(this@YandexCaptchaActivity, "client_yandex_browser_saved", "cookies_present=true native_probe_passed=true")
                setResult(Activity.RESULT_OK)
                finish()
            } catch (_: Exception) {
                if (!isFinishing && !isDestroyed) status.text = getString(R.string.yandex_webview_save_failed)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    // This directory was created by this activity with a fixed parent.
                    candidateDir.deleteRecursively()
                }
                verifying = false
                if (!isFinishing && !isDestroyed) findViewById<Button>(R.id.btn_send_auth).isEnabled = true
            }
        }
    }

    override fun onDestroy() {
        ipv4Proxy?.close()
        ipv4Proxy = null
        probeProcess?.let { if (it.isAlive) it.destroyForcibly() }
        if (::browser.isInitialized) {
            browser.stopLoading()
            browser.destroy()
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLong("retry_not_before", retryNotBefore)
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
