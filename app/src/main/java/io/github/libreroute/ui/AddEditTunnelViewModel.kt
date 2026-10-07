package io.github.libreroute.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.libreroute.R
import io.github.libreroute.data.TransportType
import io.github.libreroute.data.Tunnel
import io.github.libreroute.util.ServiceRouteEndpoint
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
import java.security.SecureRandom
import java.util.Base64

data class AddEditFormState(
    val id: Long = System.currentTimeMillis(),
    val name: String = "",
    val transport: TransportType = TransportType.yandex,
    val docUrl: String = "",
    val maxToken: String = "",
    val maxUid: String = "",
    val encryptionKey: String = "",
    val extraParams: String = "",
    val sessionNegotiate: Boolean = false,
    val sessionBatchBytes: Int = 16384,
    val sessionLingerMs: Int = 5,
    val sessionCompression: String = "zstd-auto",
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
                TransportType.directDpi -> Unit
                TransportType.yandex, TransportType.vyandex -> {
                    val urlsVal = argValue(tunnel.transportConnPayload, "--urls")
                    docUrl = if (urlsVal.isNotEmpty()) urlsVal.split(",").joinToString("\n") else argValue(tunnel.transportConnPayload, "--url")
                }
                TransportType.max -> {
                    maxToken = argValue(tunnel.transportConnPayload, "--maxToken")
                    maxUid = argValue(tunnel.transportConnPayload, "--maxUid")
                }
                TransportType.cups, TransportType.mailru, TransportType.mqtt, TransportType.jitsi -> {
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
                sessionNegotiate = tunnel.transportConnPayload.any { it == "--session-negotiate" || it == "--session-negotiate=true" },
                sessionBatchBytes = argValue(tunnel.transportConnPayload, "--session-batch-bytes").toIntOrNull() ?: 16384,
                sessionLingerMs = argValue(tunnel.transportConnPayload, "--session-linger-ms").toIntOrNull() ?: 5,
                sessionCompression = argValue(tunnel.transportConnPayload, "--session-compression").ifBlank { "zstd-auto" },
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
        extraParams: String = "",
        mqttBroker: String = "",
        mqttTopic: String = "",
        mqttClientId: String = "",
        sessionNegotiate: Boolean = false,
        sessionBatchBytes: Int = 16384,
        sessionLingerMs: Int = 5,
        sessionCompression: String = "zstd-auto"
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
          // Keys are generated by the application. The legacy argument is accepted only
          // so older callers keep compiling; it is never required from the UI.
          val requestedKey = encryptionKey.trim()
        val trimmedMqttBroker = mqttBroker.trim().ifBlank { ServiceRouteEndpoint.DEFAULT_MQTT_BROKER }
        val trimmedMqttTopic = mqttTopic.trim()
        val trimmedMqttClientId = mqttClientId.trim()
        when (currentTransport) {
            TransportType.directDpi -> Unit
            TransportType.mqtt -> {
                if (ServiceRouteEndpoint.buildMqttUrl(trimmedMqttBroker, trimmedMqttTopic) == null) {
                    viewModelScope.launch { _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.mqtt_url_hint)) }
                    return
                }
            }
            TransportType.jitsi -> {
                if (!io.github.libreroute.util.ServiceRouteEndpoint.isValid(currentTransport, trimmedUrl)) {
                    viewModelScope.launch { _events.emit(AddEditEvent.ValidationError(AddEditEvent.FieldError.URL, R.string.jitsi_url_hint)) }
                    return
                }
            }
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

        }

          // Offload disk file writing to Dispatchers.IO
        viewModelScope.launch(Dispatchers.IO) {
            _formState.update { it.copy(isSaving = true) }
              val id = editingTunnel?.id ?: System.currentTimeMillis()
              val app = getApplication<Application>()
              val generatedKey = if (requestedKey.length >= 16) requestedKey else generateSecretKey()
              val effectiveMqttTopic = trimmedMqttTopic.ifBlank { "libreroute-${id.toString(16)}" }
              val effectiveMqttClientId = trimmedMqttClientId.ifBlank { "libreroute-${id.toString(16)}" }

              val needsManagedKey = currentTransport in setOf(TransportType.vyandex, TransportType.mqtt, TransportType.jitsi)
              val keyFile = if (needsManagedKey) {
                  val f = File(app.filesDir, "key_${id}.txt")
                  runCatching {
                      app.openFileOutput(f.name, Context.MODE_PRIVATE).use { fos ->
                          fos.write(generatedKey.toByteArray(StandardCharsets.UTF_8))
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

              // Raw CLI flags are intentionally not accepted from the manual flow.
              val parsedExtra = emptyList<String>()
            val payload = buildPayload(
                  currentTransport, trimmedUrl, trimmedToken, trimmedUid, keyFile, parsedExtra,
                  trimmedMqttBroker, effectiveMqttTopic, effectiveMqttClientId
              ).toMutableList()
              // Session negotiation is an internal capability. It is never
              // emitted by the manual configuration flow, even when an old
              // profile being edited still contains legacy session flags.
            val newTunnel = Tunnel(
                id = id,
                name = trimmedName,
                transportType = currentTransport.name,
                transportConnPayload = payload,
                  encryptionKey = if (needsManagedKey) generatedKey else ""
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
        extraTokens: List<String> = emptyList(),
        mqttBroker: String = "",
        mqttTopic: String = "",
        mqttClientId: String = ""
    ): List<String> = buildList {
        when (transport) {
            TransportType.directDpi -> {
                add("--role=client"); add("--transport"); add(transport.cliName)
                add("--dpi-block-quic=true")
            }
            TransportType.mqtt -> {
                val broker = mqttBroker.ifBlank { ServiceRouteEndpoint.DEFAULT_MQTT_BROKER }
                val canonicalUrl = ServiceRouteEndpoint.buildMqttUrl(broker, mqttTopic)
                    ?: "$broker#${mqttTopic.trim()}"
                add("--role=client"); add("--transport"); add(transport.cliName)
                add("--url"); add(canonicalUrl)
                add("--mqtt-broker"); add(broker)
                add("--mqtt-topic"); add(mqttTopic)
                  add("--mqtt-client-id"); add(mqttClientId.ifBlank { "libreroute-${System.currentTimeMillis().toString(16)}" })
                add("--codec=batched"); add("--encryption-layout=packet"); add("--peer-health")
      }

            TransportType.jitsi -> {
                add("--role=client"); add("--transport"); add(transport.cliName)
                add("--url"); add(docUrl)
                add("--codec=batched"); add("--encryption-layout=packet"); add("--peer-health")
            }
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

        }
        keyFile?.let {
            add("--encryption-key-file")
            add(it.absolutePath)
        }
        addAll(extraTokens)
    }

    private fun generateSecretKey(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
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
            "--mqtt-broker", "-mqtt-broker",
            "--mqtt-topic", "-mqtt-topic",
            "--mqtt-client-id", "-mqtt-client-id",
            "--maxToken", "-maxToken",
            "--maxUid", "-maxUid",
            "--encryption-key-file", "-encryption-key-file",
            "--socks5", "-socks5",
            "--socks5-user", "-socks5-user",
            "--socks5-pass", "-socks5-pass",
            "--domain-rules-file", "-domain-rules-file",
            "--domain-mode", "-domain-mode",
            "--ip-rules-file", "-ip-rules-file",
            "--split-mode", "-split-mode",
            "--doh-url", "-doh-url",
            "--tun-fd", "-tun-fd",
            "--tun-socket", "-tun-socket",
            "--tun-mtu", "-tun-mtu",
            "--codec", "-codec",
            "--encryption-layout", "-encryption-layout",
            "--session-batch-bytes", "-session-batch-bytes",
            "--session-linger-ms", "-session-linger-ms",
            "--session-compression", "-session-compression"
        )

        private val MANAGED_BOOL_FLAGS = setOf(
            "--session-negotiate", "-session-negotiate",
            "--doh", "-doh"
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

                // Check session flags wildcard (--session-* or -session-*)
                val lowerToken = token.lowercase()
                if (lowerToken.startsWith("--session-") || lowerToken.startsWith("-session-")) {
                    if (MANAGED_BOOL_FLAGS.any { token.equals(it, true) || token.equals("$it=true", true) || token.equals("$it=false", true) }) {
                        i++
                        continue
                    }
                    if (token.contains('=')) {
                        i++
                        continue
                    }
                    i++
                    if (i < payload.size && !payload[i].startsWith("-")) {
                        i++
                    }
                    continue
                }

                if (MANAGED_BOOL_FLAGS.any { token.equals(it, true) || token.equals("$it=true", true) || token.equals("$it=false", true) }) {
                    i++
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
            val disallowed = setOf(
                "--role", "-role",
                "--transport", "-transport",
                "--encryption-key-file", "-encryption-key-file",
                "--tun-fd", "-tun-fd",
                "--tun-socket", "-tun-socket",
                "--tun-mtu", "-tun-mtu",
                "--inbound", "-inbound",
                "--outbound", "-outbound",
                "--split-mode", "-split-mode",
                "--domain-rules-file", "-domain-rules-file",
                "--domain-mode", "-domain-mode",
                "--ip-rules-file", "-ip-rules-file",
                "--socks5", "-socks5",
                "--socks5-user", "-socks5-user",
                "--socks5-pass", "-socks5-pass",
                "--doh", "-doh",
                "--doh-url", "-doh-url",
                "--codec", "-codec",
                "--encryption-layout", "-encryption-layout"
            )
            while (i < tokens.size) {
                val t = tokens[i]
                val eqIdx = t.indexOf('=')
                val key = if (eqIdx != -1) t.substring(0, eqIdx).lowercase() else t.lowercase()
                val isDisallowed = key in disallowed || key.startsWith("--session-") || key.startsWith("-session-")
                if (isDisallowed) {
                    i++
                    if (eqIdx == -1 && key != "--session-negotiate" && key != "-session-negotiate" && key != "--doh" && key != "-doh" && i < tokens.size && !tokens[i].startsWith("-")) {
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
