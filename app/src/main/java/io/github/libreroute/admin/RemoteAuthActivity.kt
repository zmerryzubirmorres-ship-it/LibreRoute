package io.github.libreroute.admin

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
    private lateinit var webView: WebView
    private lateinit var tvErrorView: TextView
    private lateinit var btnManualFallback: Button
    private lateinit var btnSendAuth: Button
    private lateinit var btnRetryPage: Button
    private lateinit var addressInput: EditText
    private lateinit var chrome: io.github.libreroute.ui.BrowserChrome
    private lateinit var btnGo: Button
    private var tunnelReady = false

    private lateinit var adminRepo: AdminRepository
    private val vm by viewModels<RemoteAuthViewModel>()
    private val sending by viewModels<io.github.libreroute.ui.ScreenOperationViewModel>()
    private var savedBrowser: Bundle? = null
    private var browserLoaded = false
    private val dialogs by lazy { io.github.libreroute.ui.DialogScreens(this) { key, args ->
        when (key) {
            "manual" -> showManualFallbackDialog()
            "confirm" -> activeSession?.let { session ->
                val origins = args.getBundle("origins") ?: Bundle()
                confirmAndDispatch(session, origins.keySet().associateWith { origins.getString(it).orEmpty() })
            }
            "applied" -> showAuthApplied(args.getLong("generation"))
        }
    } }

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
        webView = findViewById(R.id.web_view)
        tvErrorView = findViewById(R.id.tv_error_view)
        btnManualFallback = findViewById(R.id.btn_manual_fallback)
        btnSendAuth = findViewById(R.id.btn_send_auth)
        btnRetryPage = findViewById(R.id.btn_retry_page)
        addressInput = findViewById(R.id.browser_address)
        chrome = io.github.libreroute.ui.BrowserChrome(this, savedInstanceState)
        btnGo = findViewById(R.id.browser_go)
        findViewById<View>(R.id.btn_return_server).setOnClickListener { finish() }
        btnReload.isEnabled = false
        btnRetryPage.isEnabled = false
        btnSendAuth.isEnabled = false

        adminRepo = AdminRepository.getInstance(this)
        savedBrowser = savedInstanceState?.getBundle("browser")

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
            tvTunnelBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.state_error))
            tvErrorView.text = "Сначала выберите сервер в разделе администрирования"
            tvErrorView.visibility = View.VISIBLE
            btnSendAuth.isEnabled = false
            btnManualFallback.isEnabled = false
            btnReload.isEnabled = false
            btnBack.setOnClickListener { finish() }
            return
        }

        tvTargetInfo.text = "Сервер: ${targetServer?.name}"

        btnBack.setOnClickListener { navigateBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = navigateBack()
        })
        chrome.bindReload(webView) { tunnelReady }
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
                chrome.progressChanged(newProgress)
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
                    chrome.pageFailed()
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
                chrome.pageFinished(url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                tvErrorView.visibility = View.GONE
                btnRetryPage.isEnabled = false
                btnRetryPage.visibility = View.GONE
                chrome.pageStarted(url)
            }
        }
    }

    private fun initializeRemoteSession() {
        val server = targetServer ?: return
        vm.initialize(server, targetProfileId, intent.getStringExtra(EXTRA_DOC_URL).orEmpty())
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                vm.state.collect { state ->
                    activeSession = state.session
                    setTunnelReady(state.ready)
                    tvTunnelBadge.text = if (state.ready) "Туннель подключён" else "Туннель: ожидание подключения"
                    tvTunnelBadge.setTextColor(androidx.core.content.ContextCompat.getColor(this@RemoteAuthActivity,
                        if (state.ready) R.color.state_running else R.color.state_connecting))
                    if (state.error != null) { tvSessionStatus.text = state.error; webView.stopLoading() }
                    if (state.ready && !browserLoaded) {
                        browserLoaded = true
                        val session = state.session ?: return@collect
                        checkIncidents(session.profileId)
                        val cookieManager = CookieManager.getInstance()
                        val prefs = getSharedPreferences("remote_browser_context", MODE_PRIVATE)
                        val contextKey = "${server.id}:$targetProfileId"
                        val sameContext = prefs.getString("context", null) == contextKey
                        val loadPage = {
                            runOnUiThread {
                                if (!isFinishing && !isDestroyed) {
                                    prefs.edit().putString("context", contextKey).apply()
                                    val history = savedBrowser.takeIf { sameContext }
                                    savedBrowser = null
                                    if (history == null || webView.restoreState(history) == null) {
                                        openAddress(intent.getStringExtra(EXTRA_DOC_URL)?.takeIf { it.isNotBlank() } ?: session.documentUrl)
                                    }
                                    dialogs.restore()
                                }
                            }
                        }
                        if (sameContext) loadPage() else cookieManager.removeAllCookies { loadPage() }
                    }
                }
            }
        }
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                sending.state.collect { state ->
                    btnSendAuth.isEnabled = tunnelReady && !state.running
                    btnSendAuth.text = if (state.running) "Передача и проверка..." else "Передать авторизацию"
                    state.result?.let { result ->
                        sending.consumed()
                        result.onSuccess { showAuthApplied(it as Long) }
                            .onFailure { tvSessionStatus.text = "Ошибка применения: ${it.message}" }
                    }
                }
            }
        }
    }

    private fun setTunnelReady(ready: Boolean) {
        tunnelReady = ready
        addressInput.isEnabled = ready
        btnGo.isEnabled = ready
        btnReload.isEnabled = ready
        btnRetryPage.isEnabled = false
        btnSendAuth.isEnabled = ready && !sending.state.value.running
        if (!ready) webView.stopLoading()
    }

    private fun openAddress(input: String) {
        if (!tunnelReady) return
        val url = browserUrl(input)
        if (url == null) {
            Toast.makeText(this, "Введите адрес сайта HTTP или HTTPS", Toast.LENGTH_SHORT).show()
            return
        }
        chrome.navigate(url)
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
            .create().let { dialogs.show(it, "confirm", Bundle().apply { putBundle("origins", Bundle().apply { origins.forEach { (key, value) -> putString(key, value) } }) }) }
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

        val repo = adminRepo
        val server = targetServer
        val app = applicationContext
        sending.run {
            repo.importAuthBundle(session.id, session.serverPub, finalBundle, AdminChannel.DIRECT_SSH, server).getOrThrow()
            InstallationLog.append(app, "auth_import_success", "server=${server?.host.orEmpty()} generation=${finalBundle.generation} probe_passed=true")
            finalBundle.generation
        }
    }

    private fun showAuthApplied(generation: Long) {
        tvSessionStatus.text = "Авторизация успешно применена и проверена! (Gen $generation)"
        tvIncidentBanner.visibility = View.GONE
        val dialog = AlertDialog.Builder(this).setTitle("Авторизация обновлена")
            .setMessage("Сервер подтвердил успешное применение cookies поколения $generation и положительный результат проверочного зонда.")
            .setPositiveButton("OK") { _, _ -> setResult(RESULT_OK); finish() }.create()
        dialogs.show(dialog, "applied", Bundle().apply { putLong("generation", generation) })
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
            .create().let { dialogs.show(it, "manual", Bundle()) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::chrome.isInitialized) chrome.saveState(outState)
        if (::webView.isInitialized) outState.putBundle("browser", Bundle().also { webView.saveState(it) })
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::webView.isInitialized) { webView.stopLoading(); webView.destroy() }
        super.onDestroy()
    }
}
