package io.github.p1neapplexpress.openflux.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.p1neapplexpress.openflux.R
import io.github.p1neapplexpress.openflux.data.TransportType
import io.github.p1neapplexpress.openflux.data.Tunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets

data class AddEditFormState(
    val id: Long = System.currentTimeMillis(),
    val name: String = "",
    val transport: TransportType = TransportType.yandex,
    val docUrl: String = "",
    val maxToken: String = "",
    val maxUid: String = "",
    val encryptionKey: String = "",
    val extraParams: String = "",
    val isEditing: Boolean = false,
    val isSaving: Boolean = false
)

sealed interface AddEditEvent {
    data class Saved(val oldTunnel: Tunnel?, val newTunnel: Tunnel) : AddEditEvent
    data class ValidationError(val field: FieldError, val messageRes: Int) : AddEditEvent

    enum class FieldError {
        NAME, URL, TOKEN, UID, KEY
    }
}

class AddEditTunnelViewModel(app: Application) : AndroidViewModel(app) {

    private val _formState = MutableStateFlow(AddEditFormState())
    val formState: StateFlow<AddEditFormState> = _formState.asStateFlow()

    private val _events = MutableSharedFlow<AddEditEvent>()
    val events: SharedFlow<AddEditEvent> = _events.asSharedFlow()

    private var editingTunnel: Tunnel? = null

    fun initWithTunnel(tunnel: Tunnel?) {
        editingTunnel = tunnel
        if (tunnel == null) {
            _formState.value = AddEditFormState()
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val initialTransport = TransportType.from(tunnel.transportType)
            var docUrl = ""
            var maxToken = ""
            var maxUid = ""
            var encKey = tunnel.encryptionKey?.trim().orEmpty()

            when (initialTransport) {
                TransportType.yandex, TransportType.vyandex -> {
                    val urlsVal = argValue(tunnel.transportConnPayload, "--urls")
                    docUrl = if (urlsVal.isNotEmpty()) urlsVal.split(",").joinToString("\n") else argValue(tunnel.transportConnPayload, "--url")
                }
                TransportType.max -> {
                    maxToken = argValue(tunnel.transportConnPayload, "--maxToken")
                    maxUid = argValue(tunnel.transportConnPayload, "--maxUid")
                }
                TransportType.cups, TransportType.mailru, TransportType.gdocs -> {
                    docUrl = argValue(tunnel.transportConnPayload, "--url")
                }
            }

            if (encKey.isEmpty()) {
                val keyPath = argValue(tunnel.transportConnPayload, "--encryption-key-file")
                if (keyPath.isNotEmpty()) {
                    runCatching {
                        val kf = File(keyPath)
                        if (kf.exists()) {
                            encKey = kf.readText().trim()
                        }
                    }
                }
            }

            val extraParams = extractExtraParams(tunnel.transportConnPayload)

            _formState.value = AddEditFormState(
                id = tunnel.id,
                name = tunnel.name,
                transport = initialTransport,
                docUrl = docUrl,
                maxToken = maxToken,
                maxUid = maxUid,
                encryptionKey = encKey,
                extraParams = extraParams,
                isEditing = true
            )
        }
    }

    fun setTransport(transport: TransportType) {
        _formState.update { it.copy(transport = transport) }
    }

    fun validateAndSave(
        name: String,
        docUrl: String,
        maxToken: String,
        maxUid: String,
        encryptionKey: String,
        extraParams: String = ""
    ) {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) {
            viewModelScope.launch {
                _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.NAME, R.string.name_required))
            }
            return
        }

        val currentTransport = _formState.value.transport
        val trimmedUrl = docUrl.trim()
        val trimmedToken = maxToken.trim()
        val trimmedUid = maxUid.trim()
        val trimmedKey = encryptionKey.trim()

        when (currentTransport) {
            TransportType.yandex, TransportType.vyandex -> {
                val urls = trimmedUrl.split(Regex("[,\\s\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
                if (urls.isEmpty()) {
                    viewModelScope.launch {
                        _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.err_invalid_url))
                    }
                    return
                }
                for (u in urls) {
                    if (!u.startsWith("http://", ignoreCase = true) && !u.startsWith("https://", ignoreCase = true)) {
                        viewModelScope.launch {
                            _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.err_invalid_url))
                        }
                        return
                    }
                    val host = runCatching { URI(u).host }.getOrNull()?.lowercase()
                    if (host.isNullOrEmpty() || !host.contains(".")) {
                        viewModelScope.launch {
                            _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.err_invalid_url))
                        }
                        return
                    }
                    val isYandexDomain = host.contains("yandex.") || host.contains("yadi.sk") || host.contains("ya.ru")
                    if (!isYandexDomain) {
                        viewModelScope.launch {
                            _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.err_invalid_yandex_url))
                        }
                        return
                    }
                }
            }
            TransportType.max -> {
                if (trimmedToken.isEmpty()) {
                    viewModelScope.launch {
                        _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.TOKEN, R.string.err_max_token_required))
                    }
                    return
                }
                val uidLong = trimmedUid.toLongOrNull()
                if (uidLong == null || uidLong <= 0) {
                    viewModelScope.launch {
                        _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.UID, R.string.err_max_uid_required))
                    }
                    return
                }
            }
            TransportType.cups -> {
                if (trimmedUrl.isEmpty() || (!trimmedUrl.startsWith("http://", ignoreCase = true) && !trimmedUrl.startsWith("https://", ignoreCase = true))) {
                    viewModelScope.launch {
                        _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.err_invalid_url))
                    }
                    return
                }
                val host = runCatching { URI(trimmedUrl).host }.getOrNull()?.lowercase()
                if (host.isNullOrEmpty() || !host.contains(".") || !host.contains("cups.online")) {
                    viewModelScope.launch {
                        _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.err_cups_domain_invalid))
                    }
                    return
                }
            }
            TransportType.mailru -> {
                if (trimmedUrl.isEmpty() || (!trimmedUrl.startsWith("http://", ignoreCase = true) && !trimmedUrl.startsWith("https://", ignoreCase = true))) {
                    viewModelScope.launch {
                        _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.err_invalid_url))
                    }
                    return
                }
                val host = runCatching { URI(trimmedUrl).host }.getOrNull()?.lowercase()
                val isMailRuDomain = host != null && (host.contains("mail.ru") || host.contains("my.mail.ru") || host.contains("cloud.mail.ru"))
                if (!isMailRuDomain) {
                    viewModelScope.launch {
                        _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.err_mailru_domain_invalid))
                    }
                    return
                }
            }
            TransportType.gdocs -> {
                if (trimmedUrl.isEmpty()) {
                    viewModelScope.launch {
                        _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.err_gdocs_url_required))
                    }
                    return
                }
                val isGdocs = trimmedUrl.contains("docs.google.com") ||
                        trimmedUrl.contains("drive.google.com") ||
                        trimmedUrl.matches(Regex("^[a-zA-Z0-9_-]{25,}$"))
                if (!isGdocs) {
                    viewModelScope.launch {
                        _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.err_gdocs_domain_invalid))
                    }
                    return
                }
            }
        }

        if (trimmedKey.isNotEmpty() && trimmedKey.length < 16) {
            viewModelScope.launch {
                _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.KEY, R.string.err_encryption_key_short))
            }
            return
        }

        // Offload disk file writing to Dispatchers.IO
        viewModelScope.launch(Dispatchers.IO) {
            _formState.update { it.copy(isSaving = true) }
            val id = editingTunnel?.id ?: System.currentTimeMillis()
            val app = getApplication<Application>()

            val keyFile = if (trimmedKey.isNotEmpty()) {
                val f = File(app.filesDir, "key_${id}.txt")
                runCatching {
                    app.openFileOutput(f.name, Context.MODE_PRIVATE).use { fos ->
                        fos.write(trimmedKey.toByteArray(StandardCharsets.UTF_8))
                        fos.flush()
                        fos.fd.sync()
                    }
                    f.setReadable(true, true)
                    f.setWritable(true, true)
                }
                f
            } else {
                val f = File(app.filesDir, "key_${id}.txt")
                runCatching { f.delete() }
                null
            }

            val extraTokens = sanitizeExtraTokens(parseCommandLineArguments(extraParams))
            val payload = buildPayload(currentTransport, trimmedUrl, trimmedToken, trimmedUid, keyFile, extraTokens)
            val newTunnel = Tunnel(
                id = id,
                name = trimmedName,
                transportType = currentTransport.name,
                transportConnPayload = payload,
                encryptionKey = trimmedKey
            )

            _formState.update { it.copy(isSaving = false) }
            _events.emit(AddEditEvent.Saved(editingTunnel, newTunnel))
        }
    }

    private fun buildPayload(
        transport: TransportType,
        docUrl: String,
        maxToken: String,
        maxUid: String,
        keyFile: File?,
        extraTokens: List<String> = emptyList()
    ): List<String> = buildList {
        when (transport) {
            TransportType.yandex -> {
                val urls = docUrl.split(Regex("[,\\s\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
                add("--role=client"); add("--transport"); add("yandex")
                if (urls.size > 1) {
                    add("--urls"); add(urls.joinToString(","))
                    add("--url"); add(urls.first())
                } else if (urls.size == 1) {
                    add("--url"); add(urls.first())
                }
            }
            TransportType.vyandex -> {
                val urls = docUrl.split(Regex("[,\\s\\n\\r]+")).map { it.trim() }.filter { it.isNotEmpty() }
                add("--role=client"); add("--transport"); add("vyandex")
                if (urls.size > 1) {
                    add("--urls"); add(urls.joinToString(","))
                    add("--url"); add(urls.first())
                } else if (urls.size == 1) {
                    add("--url"); add(urls.first())
                }
            }
            TransportType.max -> {
                add("--role=client"); add("--transport"); add("oneme")
                add("--maxToken"); add(maxToken)
                add("--maxUid"); add(maxUid)
            }
            TransportType.cups -> {
                add("--role=client"); add("--transport"); add("cupsonline")
                add("--url"); add(docUrl)
            }
            TransportType.mailru -> {
                add("--role=client"); add("--transport"); add("mailru")
                add("--url"); add(docUrl)
            }
            TransportType.gdocs -> {
                add("--role=client"); add("--transport"); add("googledocs")
                add("--url"); add(docUrl)
            }
        }
        keyFile?.let {
            add("--encryption-key-file")
            add(it.absolutePath)
        }
        addAll(extraTokens)
    }

    private fun argValue(payload: List<String>, key: String): String {
        val eqPrefix = "$key="
        for (item in payload) {
            if (item.startsWith(eqPrefix, ignoreCase = true)) {
                return item.substring(eqPrefix.length).trim('"', '\'')
            }
        }
        val idx = payload.indexOfFirst { it.equals(key, ignoreCase = true) }
        return if (idx >= 0 && idx + 1 < payload.size) payload[idx + 1] else ""
    }

    companion object {
        private val MANAGED_FLAGS_WITH_VALUE = setOf(
            "--transport", "-transport",
            "--url", "-url",
            "--urls", "-urls",
            "--maxToken", "-maxToken",
            "--maxUid", "-maxUid",
            "--encryption-key-file", "-encryption-key-file",
            "--socks5", "-socks5",
            "--socks5-user", "-socks5-user",
            "--socks5-pass", "-socks5-pass",
            "--domain-rules-file", "-domain-rules-file",
            "--doh-url", "-doh-url",
            "--doh", "-doh",
            "--tun-fd", "-tun-fd",
            "--tun-socket", "-tun-socket",
            "--tun-mtu", "-tun-mtu"
        )

        fun extractExtraParams(payload: List<String>): String {
            if (payload.isEmpty()) return ""
            val extra = mutableListOf<String>()
            var i = 0
            while (i < payload.size) {
                val token = payload[i]

                if (token.startsWith("--role=", ignoreCase = true) || token.startsWith("-role=", ignoreCase = true)) {
                    i++
                    continue
                }
                if (token.equals("--role", ignoreCase = true) || token.equals("-role", ignoreCase = true)) {
                    i++
                    if (i < payload.size && !payload[i].startsWith("-")) i++
                    continue
                }

                val isManagedEq = MANAGED_FLAGS_WITH_VALUE.any { token.startsWith("$it=", ignoreCase = true) }
                if (isManagedEq) {
                    i++
                    continue
                }

                val matchingManaged = MANAGED_FLAGS_WITH_VALUE.firstOrNull { it.equals(token, ignoreCase = true) }
                if (matchingManaged != null) {
                    i++
                    if (i < payload.size && !payload[i].startsWith("-")) {
                        i++
                    }
                    continue
                }

                extra.add(token)
                i++
            }

            return extra.joinToString(" ") { token ->
                if (token.contains(' ') || token.contains('\t')) {
                    "\"${token.replace("\"", "\\\"")}\""
                } else {
                    token
                }
            }
        }

        fun parseCommandLineArguments(input: String): List<String> {
            if (input.isBlank()) return emptyList()
            val tokens = mutableListOf<String>()
            val current = StringBuilder()
            var inDoubleQuote = false
            var inSingleQuote = false
            var escape = false

            for (ch in input) {
                if (escape) {
                    current.append(ch)
                    escape = false
                    continue
                }
                if (ch == '\\') {
                    escape = true
                    continue
                }
                if (ch == '"' && !inSingleQuote) {
                    inDoubleQuote = !inDoubleQuote
                    continue
                }
                if (ch == '\'' && !inDoubleQuote) {
                    inSingleQuote = !inSingleQuote
                    continue
                }
                if (ch.isWhitespace() && !inDoubleQuote && !inSingleQuote) {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current.clear()
                    }
                    continue
                }
                current.append(ch)
            }
            if (current.isNotEmpty()) {
                tokens.add(current.toString())
            }
            return tokens
        }

        fun sanitizeExtraTokens(tokens: List<String>): List<String> {
            val result = mutableListOf<String>()
            var i = 0
            val disallowedPrefixes = setOf(
                "--role", "-role",
                "--transport", "-transport",
                "--encryption-key-file", "-encryption-key-file",
                "--tun-fd", "--tun-socket", "--inbound", "--outbound"
            )
            while (i < tokens.size) {
                val t = tokens[i]
                val eqIdx = t.indexOf('=')
                val key = if (eqIdx != -1) t.substring(0, eqIdx).lowercase() else t.lowercase()
                if (key in disallowedPrefixes) {
                    i++
                    if (eqIdx == -1 && i < tokens.size && !tokens[i].startsWith("-")) {
                        i++
                    }
                    continue
                }
                result.add(t)
                i++
            }
            return result
        }
    }
}
