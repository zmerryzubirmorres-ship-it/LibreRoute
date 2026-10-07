package io.github.libreroute.admin

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.OnBackPressedCallback
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.libreroute.R
import io.github.libreroute.util.Logx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI
import io.github.libreroute.util.JitsiTokenStore

/**
 * RemoteAuthActivity provides an isolated browser context for user authentication (SSO / CAPTCHA),
 * tunnelled through the selected server node (e.g. VDS89) via fail-closed SSH proxy,
 * and securely exports the resulting cookies (including HttpOnly) as an encrypted AuthBundle.
 */
class RemoteAuthActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "RemoteAuthActivity"
        const val EXTRA_TARGET_ID = "extra_target_id"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_DOC_URL = "extra_doc_url"

        private val ALLOWED_HOSTS = listOf(
            "yandex.ru",
            "passport.yandex.ru",
            "disk.yandex.ru",
            "docs.yandex.ru",
            "volga.yandex.ru",
            "127.0.0.1",
            "localhost"
        )

        fun isAllowedHost(host: String?): Boolean {
            if (host.isNullOrBlank()) return false
            val clean = host.lowercase().trim()
            return ALLOWED_HOSTS.any { clean == it || clean.endsWith(".$it") }
        }

        fun browserUrl(input: String): String? = runCatching {
            val value = input.trim()
            require(value.isNotEmpty())
            val url = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value) &&
                !Regex("^[^/:]+:[0-9]+(?:/|$)").containsMatchIn(value)) value else "https://$value"
            val uri = URI(url)
            require(uri.scheme.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank())
            require(uri.rawUserInfo == null && (uri.port == -1 || uri.port in 1..65535))
            uri.toASCIIString()
        }.getOrNull()

        fun maskDocUrl(url: String?): String {
            if (url.isNullOrBlank()) return ""
            return try {
                val uri = URI(url)
                val path = uri.path.orEmpty()
                val maskedPath = if (path.length > 8) path.take(4) + "..." + path.takeLast(4) else path
                "${uri.scheme}://${uri.host}$maskedPath"
            } catch (_: Exception) {
                if (url.length > 15) url.take(15) + "..." else url
            }
        }
    }

    private lateinit var btnBack: ImageView
    private lateinit var btnReload: ImageView
    private lateinit var tvTargetInfo: TextView
    private lateinit var tvTunnelBadge: TextView
    private lateinit var tvSessionStatus: TextView
    private lateinit var tvIncidentBanner: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var webView: WebView
    private lateinit var tvErrorView: TextView
    private lateinit var btnManualFallback: Button
    private lateinit var btnSendAuth: Button
    private lateinit var btnRetryPage: Button
    private lateinit var addressInput: EditText
    private lateinit var btnGo: Button
    private var tunnelReady = false

    private lateinit var adminRepo: AdminRepository
    private lateinit var proxyManager: WebViewTunnelProxyManager

    private var targetServer: ServerNode? = null
    private var targetProfileId: String = ""
    private var activeSession: AuthSession? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_auth)

        btnBack = findViewById(R.id.btn_back)
        btnReload = findViewById(R.id.btn_reload)
        tvTargetInfo = findViewById(R.id.tv_target_info)
        tvTunnelBadge = findViewById(R.id.tv_tunnel_badge)
        tvSessionStatus = findViewById(R.id.tv_session_status)
        tvIncidentBanner = findViewById(R.id.tv_incident_banner)
        progressBar = findViewById(R.id.progress_bar)
        webView = findViewById(R.id.web_view)
        tvErrorView = findViewById(R.id.tv_error_view)
        btnManualFallback = findViewById(R.id.btn_manual_fallback)
        btnSendAuth = findViewById(R.id.btn_send_auth)
        btnRetryPage = findViewById(R.id.btn_retry_page)
        addressInput = findViewById(R.id.browser_address)
        btnGo = findViewById(R.id.browser_go)
        findViewById<View>(R.id.btn_return_server).setOnClickListener { finish() }
        btnReload.isEnabled = false
        btnRetryPage.isEnabled = false
        btnSendAuth.isEnabled = false

        adminRepo = AdminRepository.getInstance(this)
        proxyManager = WebViewTunnelProxyManager(this)

        val targetId = intent.getStringExtra(EXTRA_TARGET_ID).orEmpty()
        targetProfileId = intent.getStringExtra(EXTRA_PROFILE_ID).orEmpty()
        val docUrl = intent.getStringExtra(EXTRA_DOC_URL).orEmpty()

        val servers = adminRepo.serverNodesFlow.value
        targetServer = if (targetId.isNotBlank()) {
            servers.firstOrNull { it.id == targetId || it.name == targetId }
        } else {
            servers.firstOrNull()
        }
        if (targetProfileId.isBlank()) {
            targetProfileId = targetServer?.let { server ->
                adminRepo.managedProfilesFlow.value.firstOrNull {
                    it.profileType == ManagedProfile.PROFILE_TYPE_CONTROL &&
                        it.serverId == server.id && it.role == AdminRole.OWNER
                }?.id
            }.orEmpty()
        }

        if (targetServer == null) {
            val missingTarget = if (targetId.isNotBlank()) targetId else "не указан"
            tvTargetInfo.text = "Ошибка: Целевой сервер '$missingTarget' не найден"
            tvSessionStatus.text = "Для работы выберите настроенный SSH-сервер в панели администрирования."
            tvTunnelBadge.text = "Туннель заблокирован"
            tvTunnelBadge.setTextColor(0xFFFF4444.toInt())
            btnSendAuth.isEnabled = false
            btnManualFallback.isEnabled = false
            btnReload.isEnabled = false
            btnBack.setOnClickListener { finish() }
            return
        }

        val docMasked = if (docUrl.isNotBlank()) " | Док: ${maskDocUrl(docUrl)}" else ""
        tvTargetInfo.text = "Узел: ${targetServer?.name} | Профиль: $targetProfileId$docMasked"

        btnBack.setOnClickListener { navigateBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        btnReload.setOnClickListener { if (tunnelReady) webView.reload() }
        btnRetryPage.setOnClickListener { if (tunnelReady) openAddress(addressInput.text.toString()) }
        btnGo.setOnClickListener { openAddress(addressInput.text.toString()) }
        addressInput.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_GO) {
                openAddress(addressInput.text.toString())
                true
            } else false
        }

        setupWebView()
        btnSendAuth.setOnClickListener { captureAndSendAuth() }
        btnManualFallback.setOnClickListener { showManualFallbackDialog() }

        initializeRemoteSession()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress in 1..99) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                val host = uri.host
                if (!tunnelReady || browserUrl(uri.toString()) == null) {
                    Toast.makeText(
                        this@RemoteAuthActivity,
                        "Этот адрес нельзя открыть через туннель",
                        Toast.LENGTH_SHORT
                    ).show()
                    Logx.w(TAG, "Blocked unsupported browser navigation")
                    return true
                }
                return false
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (request?.isForMainFrame == true) {
                    tvErrorView.text = "Ошибка загрузки: ${error?.description ?: "сбой сети"}"
                    tvErrorView.visibility = View.VISIBLE
                    btnRetryPage.isEnabled = tunnelReady
                    btnRetryPage.visibility = View.VISIBLE
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Jitsi's Firebase page returns a short-lived JWT in the room hash.
                // Keep it encrypted on-device; it is later added to the native Jitsi
                // focus request. Google cookies themselves are never exported here.
                val page = runCatching { URI(url.orEmpty()) }.getOrNull()
                if (page?.host?.equals("meet.jit.si", true) == true) {
                    JitsiTokenStore.fromFragment(page.rawFragment)?.let { token ->
                        JitsiTokenStore(this@RemoteAuthActivity).put(url.orEmpty().substringBefore('#'), token)
                        tvSessionStatus.text = "Вход Jitsi подтверждён. Можно подключать маршрут."
                    }
                }
                if (!addressInput.hasFocus()) addressInput.setText(url.orEmpty())
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                tvErrorView.visibility = View.GONE
                btnRetryPage.isEnabled = false
                btnRetryPage.visibility = View.GONE
                if (!addressInput.hasFocus()) addressInput.setText(url.orEmpty())
            }
        }
    }

    private fun initializeRemoteSession() {
        val server = targetServer
        if (server == null) {
            tvSessionStatus.text = "Ошибка: не выбран целевой сервер"
            return
        }

        lifecycleScope.launch {
            tvSessionStatus.text = "Инициализация сессии на сервере..."
            tvTunnelBadge.text = "Туннель: запуск SSH-прокси..."
            tvTunnelBadge.setTextColor(0xFFFFCC00.toInt())

            // 1. Begin auth session on target server
            val sessionResult = withContext(Dispatchers.IO) {
                adminRepo.beginAuthSession(
                    targetId = server.name,
                    profileId = targetProfileId,
                    channel = AdminChannel.DIRECT_SSH,
                    serverNode = server,
                    documentUrl = intent.getStringExtra(EXTRA_DOC_URL).orEmpty()
                )
            }

            val session = sessionResult.getOrElse { err ->
                tvSessionStatus.text = "Ошибка создания сессии: ${err.message}"
                Logx.e(TAG, "beginAuthSession failed: ${err.message}", err)
                return@launch
            }
            activeSession = session
            InstallationLog.append(this@RemoteAuthActivity, "auth_session", "server=${server.host} document_present=${session.documentUrl.isNotBlank()}")
            if (session.documentUrl.isBlank()) {
                tvSessionStatus.text = "Сервер не указал документ для проверки авторизации. Обновите серверное ядро и откройте авторизацию заново."
                return@launch
            }
            tvSessionStatus.text = "Сессия авторизации готова"

            // 2. Start SSH proxy loopback
            proxyManager.startProxy(
                serverNode = server,
                onReady = { port ->
                    runOnUiThread {
                        setTunnelReady(true)
                        tvTunnelBadge.text = "Туннель подключён"
                        tvTunnelBadge.setTextColor(0xFF00FF88.toInt())

                        // Check active incidents for this profile
                        checkIncidents(session.profileId)

                        // Keep solved CAPTCHA when reopening the same browser context.
                        // Clear cookies when switching server or profile.
                        val cookieManager = CookieManager.getInstance()
                        val browserPrefs = getSharedPreferences("remote_browser_context", MODE_PRIVATE)
                        val contextKey = "${server.id}:$targetProfileId"
                        val loadInitialPage = {
                            runOnUiThread {
                                browserPrefs.edit().putString("context", contextKey).apply()
                                // A protocol setup may pass a new Yandex
                                // document while the auth session is anchored
                                // to the existing OWNER control profile. Open
                                // the requested document first so CAPTCHA and
                                // cookie state are collected for that setup.
                                val initialUrl = intent.getStringExtra(EXTRA_DOC_URL)?.takeIf { it.isNotBlank() }
                                    ?: session.documentUrl.takeIf { it.isNotBlank() }
                                    ?: "https://passport.yandex.ru/auth"
                                openAddress(initialUrl)
                            }
                        }
                        if (browserPrefs.getString("context", null) == contextKey) {
                            loadInitialPage()
                        } else {
                            cookieManager.removeAllCookies { loadInitialPage() }
                        }
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        setTunnelReady(false)
                        tvTunnelBadge.text = "Ошибка туннеля"
                        tvTunnelBadge.setTextColor(0xFFFF4444.toInt())
                        tvSessionStatus.text = "Сбой SSH-прокси: $err"
                        Toast.makeText(this@RemoteAuthActivity, err, Toast.LENGTH_LONG).show()
                    }
                },
                onTerminated = { exitCode ->
                    runOnUiThread {
                        setTunnelReady(false)
                        webView.stopLoading()
                        webView.visibility = View.GONE
                        tvErrorView.text = "SSH-туннель был неожиданно прерван (код $exitCode). Трафик заблокирован (Fail-Closed)."
                        tvErrorView.visibility = View.VISIBLE
                        tvTunnelBadge.text = "Туннель разорван!"
                        tvTunnelBadge.setTextColor(0xFFFF4444.toInt())
                        btnSendAuth.isEnabled = false
                        btnReload.isEnabled = false
                    }
                }
            )
        }
    }

    private fun setTunnelReady(ready: Boolean) {
        tunnelReady = ready
        addressInput.isEnabled = ready
        btnGo.isEnabled = ready
        btnReload.isEnabled = ready
        btnRetryPage.isEnabled = false
        btnSendAuth.isEnabled = ready
        if (!ready) webView.stopLoading()
    }

    private fun openAddress(input: String) {
        if (!tunnelReady) return
        val url = browserUrl(input)
        if (url == null) {
            Toast.makeText(this, "Введите адрес сайта HTTP или HTTPS", Toast.LENGTH_SHORT).show()
            return
        }
        addressInput.setText(url)
        addressInput.clearFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(addressInput.windowToken, 0)
        tvErrorView.visibility = View.GONE
        webView.loadUrl(url)
    }

    private fun navigateBack() {
        if (tunnelReady && webView.canGoBack()) webView.goBack() else finish()
    }

    private fun checkIncidents(profileId: String) {
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                adminRepo.listAuthIncidents(profileId, AdminChannel.DIRECT_SSH, targetServer)
            }
            val incidents = res.getOrNull().orEmpty()
            val openIncidents = incidents.filter { it.isOpen }
            if (openIncidents.isNotEmpty()) {
                val first = openIncidents.first()
                tvIncidentBanner.text = "Инцидент [${first.code}]: ${first.description}\nДействие: ${first.actionRequired}"
                tvIncidentBanner.visibility = View.VISIBLE
            } else {
                tvIncidentBanner.visibility = View.GONE
            }
        }
    }

    private fun captureAndSendAuth() {
        val session = activeSession
        if (session == null) {
            Toast.makeText(this, "Сессия авторизации не инициализирована", Toast.LENGTH_SHORT).show()
            return
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()

        val originsMap = mutableMapOf<String, String>()
        val checkOrigins = session.allowedOrigins.ifEmpty {
            listOf(
                "https://disk.yandex.ru/",
                "https://docs.yandex.ru/",
                "https://passport.yandex.ru/",
                "https://volga.yandex.ru/",
                "https://yandex.ru/"
            )
        }

        var foundAny = false
        for (origin in checkOrigins) {
            val c = cookieManager.getCookie(origin)
            if (!c.isNullOrBlank()) {
                originsMap[origin] = c
                if (c.contains("Session_id", ignoreCase = true)) {
                    foundAny = true
                }
            }
        }

        if (originsMap.isEmpty()) {
            Toast.makeText(this, "Cookies не найдены в WebView. Сначала выполните вход в аккаунт.", Toast.LENGTH_LONG).show()
            return
        }

        if (!foundAny) {
            AlertDialog.Builder(this)
                .setTitle("Сессионный токен не обнаружен")
                .setMessage("В сохранённых cookies не найден параметр 'Session_id'. Авторизация может быть неполной. Отправить всё равно?")
                .setPositiveButton("Отправить") { _, _ ->
                    confirmAndDispatch(session, originsMap)
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }

        confirmAndDispatch(session, originsMap)
    }

    private fun confirmAndDispatch(session: AuthSession, origins: Map<String, String>) {
        val message = "Целевой узел: ${session.targetId}\n" +
                "Профиль: ${session.profileId}\n" +
                "Поколение: ${session.currentGeneration + 1}\n" +
                "Источники: ${origins.keys.joinToString("\n")}\n\n" +
                "Передать зашифрованные данные авторизации на сервер?"

        AlertDialog.Builder(this)
            .setTitle("Подтверждение отправки")
            .setMessage(message)
            .setPositiveButton("Передать") { _, _ ->
                dispatchAuthBundle(session, origins)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun dispatchAuthBundle(session: AuthSession, origins: Map<String, String>) {
        btnSendAuth.isEnabled = false
        btnSendAuth.text = "Передача и проверка..."

        val bundle = AuthBundlePlaintext(
            version = 1,
            targetId = session.targetId,
            profileId = session.profileId,
            authSessionId = session.id,
            generation = session.currentGeneration + 1,
            nonce = session.nonce,
            userAgent = webView.settings.userAgentString,
            origins = origins
        )
        val finalBundle = bundle.copy(contentHash = bundle.computeContentHash())

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                adminRepo.importAuthBundle(
                    authSessionId = session.id,
                    serverPub = session.serverPub,
                    bundle = finalBundle,
                    channel = AdminChannel.DIRECT_SSH,
                    serverNode = targetServer
                )
            }

            btnSendAuth.isEnabled = tunnelReady
            btnSendAuth.text = "Передать авторизацию"

            result.fold(
                onSuccess = {
                    InstallationLog.append(this@RemoteAuthActivity, "auth_import_success", "server=${targetServer?.host.orEmpty()} generation=${finalBundle.generation} probe_passed=true")
                    tvSessionStatus.text = "Авторизация успешно применена и проверена! (Gen ${finalBundle.generation})"
                    tvIncidentBanner.visibility = View.GONE
                    AlertDialog.Builder(this@RemoteAuthActivity)
                        .setTitle("Авторизация обновлена")
                        .setMessage("Сервер подтвердил успешное применение cookies поколения ${finalBundle.generation} и положительный результат проверочного зонда.")
                        .setPositiveButton("OK") { _, _ ->
                            // Let the caller resume its durable operation with
                            // the same protocol/endpoint after the cookie
                            // handoff has been accepted by the server.
                            setResult(RESULT_OK)
                            finish()
                        }
                        .show()
                },
                  onFailure = { err ->
                      InstallationLog.append(this@RemoteAuthActivity, "auth_import_error", "server=${targetServer?.host.orEmpty()} message=${err.message.orEmpty()}")
                    tvSessionStatus.text = "Ошибка применения: ${err.message}"
                    Logx.e(TAG, "importAuthBundle failed: ${err.message}", err)
                    AlertDialog.Builder(this@RemoteAuthActivity)
                        .setTitle("Сбой применения авторизации")
                        .setMessage("Сервер отклонил авторизацию или зонд завершился ошибкой:\n${err.message}\n\nПроверьте капчу/логин и попробуйте снова.")
                        .setPositiveButton("Понятно", null)
                        .show()
                    checkIncidents(session.profileId)
                }
            )
        }
    }

    private fun showManualFallbackDialog() {
        val session = activeSession ?: run {
            Toast.makeText(this, "Сессия не готова", Toast.LENGTH_SHORT).show()
            return
        }

        val originsList = session.allowedOrigins.ifEmpty {
            listOf(
                "https://passport.yandex.ru/",
                "https://disk.yandex.ru/",
                "https://docs.yandex.ru/"
            )
        }

        val context = this
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val inputMap = mutableMapOf<String, EditText>()
        for (origin in originsList) {
            val label = TextView(context).apply {
                text = origin
                textSize = 12f
                setPadding(0, 16, 0, 4)
            }
            val input = EditText(context).apply {
                hint = "Session_id=...; yandexuid=..."
                textSize = 14f
                maxLines = 4
            }
            layout.addView(label)
            layout.addView(input)
            inputMap[origin] = input
        }

        val scrollView = ScrollView(context).apply {
            addView(layout)
        }

        AlertDialog.Builder(this)
            .setTitle("Ручной импорт cookies (Fallback)")
            .setMessage("Введите cookies отдельно для каждого источника:")
            .setView(scrollView)
            .setPositiveButton("Применить") { _, _ ->
                val manualOrigins = mutableMapOf<String, String>()
                for ((origin, editText) in inputMap) {
                    val text = editText.text.toString().trim()
                    if (text.isNotBlank()) {
                        manualOrigins[origin] = text
                    }
                }
                if (manualOrigins.isEmpty()) {
                    Toast.makeText(this, "Не введено ни одного значения cookies", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                tvSessionStatus.text = "Режим: Ручной fallback импорт"
                confirmAndDispatch(session, manualOrigins)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        webView.stopLoading()
        webView.clearHistory()
        webView.destroy()
        proxyManager.stopProxy()
    }
}
