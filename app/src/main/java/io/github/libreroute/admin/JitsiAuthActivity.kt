package io.github.libreroute.admin

import android.annotation.SuppressLint
import android.content.Intent
import android.content.ClipboardManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import io.github.libreroute.util.JitsiTokenStore
import io.github.libreroute.util.JitsiBrowserHandoff
import java.net.URI

/** Browser handoff for Jitsi moderator login. The room JWT is returned in the URL fragment. */
class JitsiAuthActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ROOM_URL = "extra_room_url"

        internal fun roomUrlOrNull(raw: String?): String? = runCatching {
            val uri = URI(raw.orEmpty().trim())
            require(uri.scheme.equals("https", true))
            require(uri.userInfo == null && !uri.host.isNullOrBlank())
            val parts = uri.path.orEmpty().trim('/').split('/')
            val host = uri.host.lowercase()
            val isJaas = host == "8x8.vc" || host.endsWith(".8x8.vc")
            if (isJaas) {
                require(parts.size == 2 && parts.all { it.isNotBlank() })
            } else {
                require(parts.size == 1 && parts[0].isNotBlank())
            }
            require(uri.query == null)
            URI("https", uri.authority, uri.path.trimEnd('/'), null, null).toASCIIString()
        }.getOrNull()

        internal fun tokenFromUrl(raw: String?): String? = runCatching {
            val uri = URI(raw.orEmpty())
            JitsiTokenStore.fromFragment(uri.rawFragment) ?: JitsiTokenStore.fromQuery(uri.rawQuery)
        }.getOrNull()
    }

    private lateinit var roomUrl: String
    private lateinit var status: TextView
    private lateinit var browser: WebView
    private var completed = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        roomUrl = roomUrlOrNull(intent.getStringExtra(EXTRA_ROOM_URL)).orEmpty()
        if (roomUrl.isBlank()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 12)
        }
        status = TextView(this).apply {
            text = "Jitsi: open the room as host. If the provider returns a JWT, it will be saved; otherwise keep the host browser open."
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 12)
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        browser = WebView(this)
        root.addView(browser, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "Открыть внешним браузером"
            setOnClickListener {
                status.text = "После входа скопируйте ссылку комнаты, вернитесь сюда и нажмите «Вставить ссылку»."
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(roomUrl))) }
                    .onFailure { status.text = "Не найден браузер для открытия комнаты" }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "Вставить ссылку"
            setOnClickListener { importClipboardLink() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "Отмена"
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)

        browser.settings.javaScriptEnabled = true
        browser.settings.domStorageEnabled = true
        browser.settings.cacheMode = WebSettings.LOAD_DEFAULT
        browser.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        browser.settings.allowFileAccess = false
        browser.settings.allowContentAccess = false
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, true)
        browser.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.scheme != "https"
            }

            override fun onPageFinished(view: WebView, url: String) {
                captureToken(url)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                captureToken(url)
            }
        }
        browser.loadUrl(roomUrl)
    }

    private fun captureToken(url: String?) {
        if (completed) return
        val page = runCatching { URI(url.orEmpty()) }.getOrNull() ?: return
        if (!JitsiBrowserHandoff.sameRoom(roomUrl, url.orEmpty())) return
        val token = tokenFromUrl(url) ?: return
        if (JitsiTokenStore.isExpired(token) || token.length !in 32..8192) {
            status.text = "Токен Jitsi истёк. Войдите в комнату ещё раз."
            return
        }
        JitsiTokenStore(this).put(roomUrl, token)
        completed = true
        status.text = "Вход Jitsi подтверждён. Возвращаемся к подключению…"
        setResult(RESULT_OK)
        finish()
    }

    private fun importClipboardLink() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val shared = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
        val handoff = JitsiBrowserHandoff.parseSharedText(shared)
        if (handoff != null) {
            if (!JitsiBrowserHandoff.sameRoom(roomUrl, handoff.roomUrl)) {
                status.text = "Ссылка относится к другой комнате Jitsi."
                return
            }
            if (JitsiTokenStore.isExpired(handoff.token)) {
                status.text = "Токен Jitsi истёк. Войдите в комнату ещё раз."
                return
            }
            JitsiTokenStore(this).put(roomUrl, handoff.token)
            status.text = "JWT Jitsi получен. Возвращаемся к подключению…"
        } else {
            val plainRoom = roomUrlOrNull(shared)
            if (plainRoom == null || !JitsiBrowserHandoff.sameRoom(roomUrl, plainRoom)) {
                status.text = "В буфере нет ссылки этой комнаты. Скопируйте адрес комнаты из браузера."
                return
            }
            status.text = "Комната подтверждена без JWT. Оставьте браузер с организатором открытым."
        }
        completed = true
        setResult(RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        if (::browser.isInitialized) {
            browser.stopLoading()
            browser.destroy()
        }
        super.onDestroy()
    }
}


