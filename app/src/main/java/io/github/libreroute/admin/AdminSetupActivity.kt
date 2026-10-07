package io.github.libreroute.admin

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.material.button.MaterialButton
import io.github.libreroute.ui.CalmViews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Entry point available before an admin grant exists. */
class AdminSetupActivity : AppCompatActivity() {
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var busy = false
    private var page = "home"
    private val drafts by lazy { io.github.libreroute.util.SecurePreferences(this, "admin_setup_draft") }
    private val draftFields = mutableMapOf<String, EditText>()
    private var refreshKeyHint: (() -> Unit)? = null
    private var importedKeyPath: String? = null
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); saveDraft(); super.onSaveInstanceState(outState) }
    override fun onPause() { saveDraft(); super.onPause() }
    private fun saveDraft() { draftFields.forEach { (key, input) -> drafts.putString(key, input.text.toString()) } }
    private fun bindDraft(key: String, field: EditText) { draftFields[key] = field; drafts.getString(key, null)?.let { field.setText(it) }; field.isSaveEnabled = false }
    private val keyFilePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        lifecycleScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) {
                val text = contentResolver.openInputStream(uri)?.use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(4096)
                    while (true) {
                        val count = input.read(chunk)
                        if (count < 0) break
                        require(buffer.size() + count <= 128 * 1024) { "Файл ключа слишком большой" }
                        buffer.write(chunk, 0, count)
                    }
                    buffer.toByteArray().toString(Charsets.UTF_8)
                } ?: error("Файл не прочитан")
                require(text.contains("PRIVATE KEY") && text.contains("-----BEGIN")) { "Выберите приватный SSH-ключ OpenSSH или PEM" }
                val file = java.io.File(filesDir, "ssh-import-${java.util.UUID.randomUUID()}")
                file.writeText(text.trim() + "\n")
                file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
                file.absolutePath
            } }
            result.onSuccess { importedKeyPath = it; drafts.putString("imported_key_path", it); refreshKeyHint?.invoke(); toast("SSH-ключ выбран") }
                .onFailure { toast(it.message ?: "Ошибка импорта ключа") }
        }
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(resources.getColor(io.github.libreroute.R.color.text_secondary, theme))
        setPadding(0, CalmViews.dp(this@AdminSetupActivity, 8), 0, CalmViews.dp(this@AdminSetupActivity, 4))
    }

    private fun field(hint: String, secret: Boolean = false): EditText = EditText(this).apply {
        this.hint = hint
        minHeight = CalmViews.dp(this@AdminSetupActivity, 52)
        setSingleLine(!hint.contains("JSON"))
        if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Доступ администратора"
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(MaterialButton(this).apply {
            text = "‹"; contentDescription = "Назад"; setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(CalmViews.dp(this@AdminSetupActivity, 56), CalmViews.dp(this@AdminSetupActivity, 56)))
        header.addView(TextView(this).apply { text = "Доступ администратора"; textSize = 22f })
        root.addView(header)
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(CalmViews.dp(this@AdminSetupActivity, 16), 0, CalmViews.dp(this@AdminSetupActivity, 16), CalmViews.dp(this@AdminSetupActivity, 24))
        }
        scroll.addView(body); root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        val incoming = intent.getStringExtra("admin_link").orEmpty()
        if (incoming.isNotBlank()) renderGrantImport(incoming)
        else if (savedInstanceState?.getString("page") == "ssh") renderSshForm()
        else if (intent.getBooleanExtra("add_server", false)) renderSshForm()
        else if (intent.getBooleanExtra("connect_server", false)) {
            // «Серверы» is a management hub. Keep saved nodes visible and let
            // the user explicitly choose between checking one and adding a new one.
            if (AdminRepository.getInstance(this).serverNodesFlow.value.isEmpty()) renderSshForm() else renderHome()
        } else renderHome()
    }

    private fun renderHome() {
        saveDraft(); draftFields.clear(); page = "home"
        body.removeAllViews()
        val repo = AdminRepository.getInstance(this)
        val role = repo.getRole()
        val roleText = when (role) {
            AdminRole.OWNER -> "Вы владелец сервера. Доступ администратора подтверждён."
            AdminRole.OPERATOR -> "Вы оператор. Доступ ограничен назначенными серверами."
            AdminRole.VIEWER -> "Вы наблюдатель. Доступен просмотр без изменения."
            AdminRole.PENDING -> "Запрос прав ожидает подтверждения администратора."
            AdminRole.NONE -> "Админский доступ ещё не привязан к этому устройству."
        }
        body.addView(CalmViews.row(this, "Состояние доступа", roleText))
        body.addView(label("SSH-доступ к серверу сам по себе не выдаёт права. Приложение проверит сервер и получит подписанное подтверждение."))
        if (role == AdminRole.PENDING) addButton("Скопировать запрос прав") {
            repo.pendingRequestFlow.value?.let { AdminDelegationLink.createRequest(it) }?.let {
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("LibreRoute admin request", it)); toast("Запрос скопирован")
            } ?: toast("Запрос ещё не создан")
        }
        addButton("Принять приглашение или подтверждение") { renderGrantImport() }
        addButton("Настроить сервер по SSH") { renderSshForm() }
        if (repo.serverNodesFlow.value.isNotEmpty()) {
            body.addView(label("Существующий сервер"))
            body.addView(label("Выберите сохранённый сервер, чтобы открыть его настройку и проверить установку."))
            repo.serverNodesFlow.value.forEach { node ->
                body.addView(CalmViews.row(this, node.name, "${node.host}:${node.port} · ${node.status.displayName}\n${coreVersionSummary(node.coreObservation)}") { launchServerSetup(node.id) })
                addButton("Открыть существующий сервер: ${node.name}") { launchServerSetup(node.id) }
                val access = repo.getServerAccess(node.id)
                if (access.state == AdminAccessState.NONE || access.state == AdminAccessState.EXPIRED || access.state == AdminAccessState.INVALID || access.state == AdminAccessState.REVOKED || access.role == AdminRole.OWNER) {
                    addButton("Проверить восстановление владения: ${node.name}") { launchServerSetup(node.id, recovery = true) }
                }
            }
        }
        status = TextView(this).apply { setTextColor(Color.DKGRAY) }; body.addView(status)
    }

    override fun onResume() {
        super.onResume()
        if (page != "home") return
        renderHome()
        val repo = AdminRepository.getInstance(this)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repo.serverNodesFlow.value.filter { repo.getRole(it.id) in setOf(AdminRole.OWNER, AdminRole.OPERATOR, AdminRole.VIEWER) }
                    .forEach { repo.refreshServerCore(it) }
            }
            if (page == "home") renderHome()
        }
    }

    private fun renderGrantImport(initialLink: String = "") {
        saveDraft(); draftFields.clear(); page = "grant"
        body.removeAllViews(); body.addView(CalmViews.row(this, "Подтверждение администратора", "Проверьте grant и отпечаток сервера"))
        val grant = field("Ссылка admin-grant или JSON подписанного grant").apply { minLines = 5; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE; setText(initialLink) }
        val fp = field("SHA-256 отпечаток ключа сервера")
        body.addView(grant); body.addView(fp)
        addButton("Проверить и сохранить") {
            val raw = grant.text.toString().trim(); val fingerprint = fp.text.toString().trim()
            if (raw.isBlank() || fingerprint.isBlank()) { toast("Нужны grant и отпечаток сервера"); return@addButton }
            setBusy(true); lifecycleScope.launch {
                val template = raw.startsWith("libreroute://admin-invite", true)
                val outcome = withContext(Dispatchers.IO) {
                    val repository = AdminRepository.getInstance(this@AdminSetupActivity)
                    if (raw.startsWith("libreroute://admin-grant", true)) Pair(repository.importAdminGrantLink(raw, fingerprint), null)
                    else if (template) repository.acceptAdminInvitationTemplate(raw, fingerprint)
                    else Pair(repository.importTrustedBootstrapGrant(raw, fingerprint), null)
                }
                val result = outcome.first
                setBusy(false)
                when (result) { is AdminOpResult.Success -> { toast(if (template) "Приглашение принято. Передайте запрос владельцу." else "Доступ подтверждён"); if (!template && !raw.startsWith("libreroute://admin-grant", true)) finish() }; is AdminOpResult.Failure -> toast(result.error) }
                if (template && result is AdminOpResult.Success) {
                    val requestLink = outcome.second
                    if (!requestLink.isNullOrBlank()) shareLink(requestLink, "Запрос подтверждения LibreRoute")
                } else if (!template && result is AdminOpResult.Success && raw.startsWith("libreroute://admin-grant", true)) {
                    if (AdminRepository.getInstance(this@AdminSetupActivity).getRole() == AdminRole.OWNER) {
                        val serverId = AdminDelegationLink.parseGrant(raw)?.serverId
                        val acceptance = serverId?.let { withContext(Dispatchers.IO) { AdminRepository.getInstance(this@AdminSetupActivity).createOwnerTransferAcceptanceLink(it) } }
                        if (!acceptance.isNullOrBlank()) shareLink(acceptance, "Подтверждение передачи владения")
                    }
                    finish()
                }
            }
        }
        addButton("Назад") { renderHome() }
    }

    private fun renderSshForm() {
        saveDraft(); draftFields.clear(); page = "ssh"
        body.removeAllViews()
        body.addView(CalmViews.row(this, "Подключить сервер", "Введите SSH-доступ. Проверка и сохранение выполнятся одной кнопкой."))
        val name = field("Название (необязательно)")
        val host = field("Адрес или IP")
        val port = field("Порт SSH").apply { setText(drafts.getString("port", "22")); inputType = InputType.TYPE_CLASS_NUMBER }
        val user = field("Пользователь SSH").apply { setText(drafts.getString("user", "root")) }
        bindDraft("name", name); bindDraft("host", host); bindDraft("port", port); bindDraft("user", user)
        body.addView(name); body.addView(host); body.addView(port); body.addView(user)
        val auth = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val key = RadioButton(this).apply { text = "SSH-ключ"; id = View.generateViewId(); isChecked = true }
        val password = RadioButton(this).apply { text = "Пароль"; id = View.generateViewId() }
        auth.addView(key); auth.addView(password); body.addView(auth)
        val secret = field("Пароль SSH", true)
        val keyInput = field("Приватный ключ OpenSSH/PEM (BEGIN PRIVATE KEY)", true).apply {
            setSingleLine(false); minLines = 3; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSaveEnabled = false
        }
        val passphrase = field("Парольная фраза ключа", true)
        bindDraft("password", secret); bindDraft("passphrase", passphrase)
        body.addView(secret); body.addView(keyInput); body.addView(passphrase)
        val keyHint = TextView(this).apply { textSize = 13f }; body.addView(keyHint)
        fun updateAuth() {
            val byKey = key.isChecked
            secret.visibility = if (byKey) View.GONE else View.VISIBLE
            keyInput.visibility = if (byKey) View.VISIBLE else View.GONE
            passphrase.visibility = if (byKey) View.VISIBLE else View.GONE
            val repo = AdminRepository.getInstance(this)
            keyHint.text = if (byKey && (!importedKeyPath.isNullOrBlank() || repo.hasAdminSshKey())) "SSH-ключ выбран." else if (byKey) "Выберите приватный ключ файлом или вставьте текст." else "Пароль будет сохранён в защищённом хранилище."
        }
        importedKeyPath = drafts.getString("imported_key_path", null)?.takeIf { java.io.File(it).isFile }
        refreshKeyHint = { updateAuth() }
        auth.setOnCheckedChangeListener { _, _ -> updateAuth() }; updateAuth()
        addButton("Выбрать файл SSH-ключа") { keyFilePicker.launch(arrayOf("*/*")) }
        fun saveAndOpenSetup(observedKey: String, observedFingerprint: String) {
            val h = host.text.toString().trim(); val p = port.text.toString().trim().toIntOrNull() ?: 22
            val u = user.text.toString().trim(); val n = name.text.toString().trim().ifBlank { h }
            val repo = AdminRepository.getInstance(this); val byKey = key.isChecked
            val node = ServerNode(name = n, host = h, port = p, sshUser = u,
                authType = if (byKey) SshAuthType.KEY else SshAuthType.PASSWORD,
                privateKeyPath = if (byKey) importedKeyPath ?: repo.getEffectiveAdminSshKeyPath() else null,
                password = if (byKey) null else secret.text.toString(),
                passphrase = if (byKey) passphrase.text.toString().ifBlank { null } else null,
                hostPublicKey = observedKey, allowPasswordAuth = !byKey, status = ServerNodeStatus.UNKNOWN)
            drafts.putString("trusted_host_key", observedKey); drafts.putString("trusted_fingerprint", observedFingerprint); drafts.putString("trusted_target", "$h:$p")
            setBusy(true); status.text = "Проверяем SSH-доступ и параметры сервера…"
            lifecycleScope.launch {
                val connected = withContext(Dispatchers.IO) { repo.connectServer(node) }
                setBusy(false)
                connected.onSuccess {
                    drafts.putString("password", null); keyInput.text.clear()
                    toast("SSH-доступ проверен. Сервер добавлен."); askAdminProfileName(it.id)
                }.onFailure { status.text = it.message ?: "SSH-подключение не удалось" }
            }
        }
        addButton("Подключиться и проверить") {
            val h = host.text.toString().trim(); val p = port.text.toString().trim().toIntOrNull() ?: 22; val u = user.text.toString().trim()
            if (h.isBlank() || u.isBlank() || p !in 1..65535) { toast("Укажите адрес, порт и пользователя SSH"); return@addButton }
            val repo = AdminRepository.getInstance(this); val byKey = key.isChecked
            if (byKey && keyInput.text.toString().isNotBlank()) {
                val pem = keyInput.text.toString().trim()
                if (!pem.contains("PRIVATE KEY") || !pem.contains("-----BEGIN")) { keyInput.error = "Нужен приватный SSH-ключ целиком, включая BEGIN/END"; return@addButton }
                val file = java.io.File(filesDir, "ssh-import-${java.util.UUID.randomUUID()}")
                file.writeText(pem + "\n"); file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true)
                importedKeyPath = file.absolutePath; drafts.putString("imported_key_path", importedKeyPath)
            }
            if (byKey && importedKeyPath.isNullOrBlank() && !repo.hasAdminSshKey()) { toast("Выберите файл или вставьте приватный SSH-ключ"); return@addButton }
            if (!byKey && secret.text.toString().isBlank()) { secret.error = "Введите пароль SSH"; return@addButton }
            saveDraft()
            val trustedTarget = drafts.getString("trusted_target", "").orEmpty(); val trustedKey = drafts.getString("trusted_host_key", "").orEmpty(); val trustedFingerprint = drafts.getString("trusted_fingerprint", "").orEmpty()
            setBusy(true)
            lifecycleScope.launch {
                if (trustedKey.isNotBlank() && trustedTarget == "$h:$p") { setBusy(false); saveAndOpenSetup(trustedKey, trustedFingerprint); return@launch }
                val result = runCatching { withContext(Dispatchers.IO) { ServerInstaller(this@AdminSetupActivity).discoverHostKey(h, p) } }
                setBusy(false)
                result.onSuccess { discovered ->
                    val keyText = discovered["host_public_key"]?.toString()?.trim('"').orEmpty(); val fpText = discovered["fingerprint"]?.toString()?.trim('"').orEmpty()
                    if (keyText.isBlank() || fpText.isBlank()) { toast("Сервер не вернул проверяемый host key"); return@onSuccess }
                    AlertDialog.Builder(this@AdminSetupActivity).setTitle("Подтвердите сервер")
                        .setMessage("$h:$p\n\nОтпечаток: $fpText\nСверьте его с панелью VDS или доверенным администратором.")
                        .setNegativeButton("Отмена", null).setPositiveButton("Продолжить") { _, _ -> saveAndOpenSetup(keyText, fpText) }.show()
                }.onFailure { toast("Не удалось проверить SSH-сервер: ${it.message ?: "ошибка сети"}") }
            }
        }
        status = TextView(this).apply { textSize = 14f; setTextColor(resources.getColor(io.github.libreroute.R.color.text_secondary, theme)) }
        body.addView(status)
        addButton("Назад") { renderHome() }
    }

    private fun launchServerSetup(id: String, recovery: Boolean = false, autoProvision: Boolean = false, profileName: String = "") {
        startActivity(android.content.Intent(this, ServerSetupActivity::class.java)
            .putExtra("server_id", id)
            .putExtra("owner_recovery", recovery)
            .putExtra("auto_provision", autoProvision)
            .putExtra("admin_profile_name", profileName))
    }

    private fun askAdminProfileName(id: String) {
        val input = EditText(this).apply { hint = "Администратор — ${drafts.getString("host", "сервер")}"; setSingleLine(true) }
        AlertDialog.Builder(this).setTitle("Название профиля администратора").setMessage("Это только отображаемое имя. Ключи и секреты создаются автоматически.")
            .setView(input).setNegativeButton("По умолчанию") { _, _ -> launchServerSetup(id, autoProvision = true) }
            .setPositiveButton("Продолжить") { _, _ -> launchServerSetup(id, autoProvision = true, profileName = input.text.toString().trim()) }.show()
    }
    private fun shareLink(link: String, title: String) {
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 8) }
        val qr = io.github.libreroute.util.QrGenerator.generateQrBitmap(link, 480)
        if (qr != null) content.addView(ImageView(this).apply { setImageBitmap(qr); contentDescription = "QR-код $title" }, LinearLayout.LayoutParams(-1, CalmViews.dp(this, 280)))
        content.addView(TextView(this).apply { text = title })
        AlertDialog.Builder(this).setTitle("QR и ссылка").setView(content)
            .setNegativeButton("Закрыть", null)
            .setNeutralButton("Скопировать") { _, _ -> (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(title, link)); toast("Ссылка скопирована") }
            .setPositiveButton("Передать") { _, _ -> startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(android.content.Intent.EXTRA_SUBJECT, title); putExtra(android.content.Intent.EXTRA_TEXT, link)
            }, "Передать запрос")) }.show()
    }
    private fun addButton(text: String, action: () -> Unit) { body.addView(MaterialButton(this).apply { this.text = text; isAllCaps = false; setOnClickListener { if (!busy) action() } }, LinearLayout.LayoutParams(-1, CalmViews.dp(this, 56))) }
    private fun setBusy(value: Boolean) {
        busy = value
        fun enable(view: View) { if (view is android.view.ViewGroup) (0 until view.childCount).forEach { enable(view.getChildAt(it)) }; view.isEnabled = !value }
        enable(body)
        if (::status.isInitialized) status.text = if (value) "Проверяем…" else ""
    }
    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()
}
