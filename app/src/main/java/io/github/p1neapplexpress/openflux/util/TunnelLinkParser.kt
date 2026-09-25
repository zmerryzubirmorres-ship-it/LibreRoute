package io.github.p1neapplexpress.openflux.util

import android.content.Context
import android.net.Uri
import android.util.Base64
import io.github.p1neapplexpress.openflux.data.Tunnel
import io.github.p1neapplexpress.openflux.ui.AddEditTunnelViewModel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.charset.StandardCharsets

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray

object TunnelLinkParser {

    private const val TAG = "TunnelLinkParser"
    private const val SCHEME_OPENFLUX = "openflux"
    private const val SCHEME_FLUXON = "fluxon"
    private const val SCHEME_PAPERFLUX = "paperflux"
    private const val HOST_IMPORT = "import"
    private const val PARAM_DATA = "data"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Prepares tunnel for export: ensures encryptionKey property is populated from
     * local file if it was previously empty, so the receiver gets the key.
     */
    fun prepareForExport(context: Context?, tunnel: Tunnel): Tunnel {
        var key = tunnel.encryptionKey?.trim()
        if (key.isNullOrEmpty() && context != null) {
            val keyPath = argValue(tunnel.transportConnPayload, "--encryption-key-file")
            if (keyPath.isNotEmpty()) {
                val f = File(keyPath).canonicalFile
                val allowedDir = context.filesDir.canonicalFile
                val allowedPrefix = allowedDir.absolutePath + File.separator
                if (f.absolutePath == allowedDir.absolutePath || f.absolutePath.startsWith(allowedPrefix)) {
                    key = runCatching { f.readText().trim() }.getOrNull()
                } else {
                    Logx.w(TAG, "Rejected key file path outside filesDir: $keyPath")
                }
            }
            if (key.isNullOrEmpty()) {
                val defaultFile = File(context.filesDir, "key_${tunnel.id}.txt")
                if (defaultFile.exists()) {
                    key = runCatching { defaultFile.readText().trim() }.getOrNull()
                }
            }
        }
        return if (!key.isNullOrEmpty()) {
            tunnel.copy(encryptionKey = key)
        } else {
            tunnel
        }
    }

    /**
     * Exports tunnel to a shareable deep-link URL.
     */
    fun toLink(tunnel: Tunnel, context: Context? = null): String {
        val ready = prepareForExport(context, tunnel)
        val jsonStr = json.encodeToString(ready)
        val base64 = Base64.encodeToString(
            jsonStr.toByteArray(StandardCharsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        return "$SCHEME_OPENFLUX://$HOST_IMPORT?$PARAM_DATA=$base64"
    }

    /**
     * Ensures that if the tunnel has an encryptionKey, the key file is created in
     * the local app filesDir and --encryption-key-file argument in transportConnPayload
     * points to this local file.
     */
    fun ensureLocalKeyFile(context: Context, tunnel: Tunnel): Tunnel {
        val payload = tunnel.transportConnPayload.toMutableList()
        val keyIdx = payload.indexOf("--encryption-key-file")

        // If encryptionKey is explicitly empty string, the user deliberately deleted the key.
        if (tunnel.encryptionKey == "") {
            val fallbackFile = File(context.filesDir, "key_${tunnel.id}.txt")
            runCatching { fallbackFile.delete() }
            if (keyIdx != -1) {
                if (keyIdx + 1 < payload.size) {
                    payload.removeAt(keyIdx + 1)
                }
                payload.removeAt(keyIdx)
            }
            return tunnel.copy(
                encryptionKey = null,
                transportConnPayload = payload
            )
        }

        var key = tunnel.encryptionKey?.trim()

        if (key.isNullOrEmpty()) {
            // Check if local file exists from payload
            if (keyIdx != -1 && keyIdx + 1 < payload.size) {
                val f = File(payload[keyIdx + 1]).canonicalFile
                val allowedDir = context.filesDir.canonicalFile
                val allowedPrefix = allowedDir.absolutePath + File.separator
                if ((f.absolutePath == allowedDir.absolutePath || f.absolutePath.startsWith(allowedPrefix)) && f.exists()) {
                    key = runCatching { f.readText().trim() }.getOrNull()
                }
            }
            if (key.isNullOrEmpty() && keyIdx != -1) {
                val fallbackFile = File(context.filesDir, "key_${tunnel.id}.txt")
                if (fallbackFile.exists()) {
                    key = runCatching { fallbackFile.readText().trim() }.getOrNull()
                }
            }
        }

        if (key.isNullOrEmpty()) {
            val fallbackFile = File(context.filesDir, "key_${tunnel.id}.txt")
            runCatching { fallbackFile.delete() }
            if (keyIdx != -1) {
                if (keyIdx + 1 < payload.size) {
                    payload.removeAt(keyIdx + 1)
                }
                payload.removeAt(keyIdx)
            }
            return tunnel.copy(
                encryptionKey = null,
                transportConnPayload = payload
            )
        }

        // Write to local filesDir with fsync to guarantee persistence before native process starts
        val localKeyFile = File(context.filesDir, "key_${tunnel.id}.txt")
        val writeOk = runCatching {
            // MODE_PRIVATE guarantees 0600 Linux permissions in app private storage
            context.openFileOutput(localKeyFile.name, Context.MODE_PRIVATE).use { fos ->
                fos.write(key.toByteArray(StandardCharsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            localKeyFile.setReadable(true, true)
            localKeyFile.setWritable(true, true)
        }.isSuccess
        if (!writeOk) {
            Logx.w(TAG, "Failed to write local key file: ${localKeyFile.absolutePath}")
        }

        if (keyIdx != -1 && keyIdx + 1 < payload.size) {
            payload[keyIdx + 1] = localKeyFile.absolutePath
        } else {
            payload.add("--encryption-key-file")
            payload.add(localKeyFile.absolutePath)
        }

        return tunnel.copy(
            encryptionKey = key,
            transportConnPayload = payload
        )
    }

    fun decodeTunnelFromJson(jsonStr: String): Tunnel? {
        val direct = runCatching { json.decodeFromString<Tunnel>(jsonStr) }.getOrNull()
        if (direct != null) return direct

        // Flexible JSON decoder for PaperFlux and generic OpenFlux JSON configs
        return runCatching {
            val el = json.parseToJsonElement(jsonStr).jsonObject
            val name = el["name"]?.jsonPrimitive?.contentOrNull
                ?: el["title"]?.jsonPrimitive?.contentOrNull
                ?: "Imported Tunnel"
            val transportRaw = el["transport"]?.jsonPrimitive?.contentOrNull
                ?: el["transportType"]?.jsonPrimitive?.contentOrNull
                ?: "yandex"
            val transType = io.github.p1neapplexpress.openflux.data.TransportType.from(transportRaw)

            val urlsList = mutableListOf<String>()
            val urlsEl = el["urls"]
            if (urlsEl != null) {
                if (urlsEl is JsonArray) {
                    urlsList.addAll(urlsEl.mapNotNull { it.jsonPrimitive.contentOrNull })
                } else {
                    val rawStr = urlsEl.jsonPrimitive.contentOrNull.orEmpty()
                    urlsList.addAll(rawStr.split(",").map { it.trim() }.filter { it.isNotEmpty() })
                }
            }
            val singleUrl = el["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (singleUrl.isNotEmpty() && !urlsList.contains(singleUrl)) {
                urlsList.add(0, singleUrl)
            }

            val key = el["key"]?.jsonPrimitive?.contentOrNull
                ?: el["encryptionKey"]?.jsonPrimitive?.contentOrNull
                ?: el["secret"]?.jsonPrimitive?.contentOrNull
            val token = el["maxToken"]?.jsonPrimitive?.contentOrNull
                ?: el["token"]?.jsonPrimitive?.contentOrNull
            val uid = el["maxUid"]?.jsonPrimitive?.contentOrNull
                ?: el["uid"]?.jsonPrimitive?.contentOrNull

            val extraList = mutableListOf<String>()
            val payloadEl = el["transportConnPayload"]
            if (payloadEl is JsonArray) {
                val rawList = payloadEl.mapNotNull { it.jsonPrimitive.contentOrNull }
                val extraStr = AddEditTunnelViewModel.extractExtraParams(rawList)
                extraList.addAll(AddEditTunnelViewModel.parseCommandLineArguments(extraStr))
            }
            val extraArgsEl = el["extraArgs"] ?: el["args"] ?: el["extra_args"] ?: el["extraParams"]
            if (extraArgsEl != null) {
                if (extraArgsEl is JsonArray) {
                    extraList.addAll(extraArgsEl.mapNotNull { it.jsonPrimitive.contentOrNull })
                } else {
                    val rawStr = extraArgsEl.jsonPrimitive.contentOrNull.orEmpty()
                    extraList.addAll(AddEditTunnelViewModel.parseCommandLineArguments(rawStr))
                }
            }
            val codec = el["codec"]?.jsonPrimitive?.contentOrNull
            if (!codec.isNullOrEmpty() && extraList.none { it.startsWith("--codec", ignoreCase = true) }) {
                extraList.add("--codec=$codec")
            }

            val payload = buildList {
                add("--role=client")
                add("--transport")
                add(transType.cliName)
                if (urlsList.size > 1) {
                    add("--urls")
                    add(urlsList.joinToString(","))
                    add("--url")
                    add(urlsList.first())
                } else if (urlsList.size == 1) {
                    add("--url")
                    add(urlsList.first())
                }
                if (!token.isNullOrEmpty()) {
                    add("--maxToken"); add(token)
                }
                if (!uid.isNullOrEmpty()) {
                    add("--maxUid"); add(uid)
                }
                addAll(AddEditTunnelViewModel.sanitizeExtraTokens(extraList))
            }

            Tunnel(
                id = System.currentTimeMillis() * 1000L + kotlin.random.Random.nextLong(1000L),
                name = name,
                transportType = transType.name,
                transportConnPayload = payload,
                encryptionKey = key
            )
        }.getOrNull()
    }

    fun parse(input: String?, context: Context? = null): Tunnel? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()

        var tunnel: Tunnel? = null

        if (trimmed.startsWith("$SCHEME_OPENFLUX:", ignoreCase = true) ||
            trimmed.startsWith("$SCHEME_FLUXON:", ignoreCase = true) ||
            trimmed.startsWith("$SCHEME_PAPERFLUX:", ignoreCase = true) ||
            trimmed.contains("://$HOST_IMPORT", ignoreCase = true)
        ) {
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            if (uri != null) {
                tunnel = runCatching { fromUri(uri, context) }.getOrNull()
                if (tunnel != null) return tunnel
            }
        }

        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            tunnel = decodeTunnelFromJson(trimmed)
        }

        if (tunnel == null) {
            decodeBase64(trimmed)?.let { jsonStr ->
                tunnel = decodeTunnelFromJson(jsonStr)
            }
        }

        // Smart parser: CLI arguments or raw URLs
        if (tunnel == null) {
            if (trimmed.contains("--transport") || trimmed.contains("--url") || trimmed.contains("--urls") ||
                trimmed.contains("--maxToken") || trimmed.contains("--maxUid")) {
                val tokens = trimmed.split(Regex("\\s+"))
                fun getArg(name: String): String? {
                    val eqPrefix = "$name="
                    for (token in tokens) {
                        if (token.startsWith(eqPrefix, ignoreCase = true)) {
                            return token.substring(eqPrefix.length).trim('"', '\'')
                        }
                    }
                    val idx = tokens.indexOfFirst { it.equals(name, ignoreCase = true) }
                    return if (idx >= 0 && idx + 1 < tokens.size) tokens[idx + 1].trim('"', '\'') else null
                }
                val rawTransport = getArg("--transport") ?: "yandex"
                val transType = io.github.p1neapplexpress.openflux.data.TransportType.from(rawTransport)
                val rawUrls = getArg("--urls").orEmpty()
                val rawUrl = getArg("--url").orEmpty()
                val token = getArg("--maxToken").orEmpty()
                val uid = getArg("--maxUid").orEmpty()
                val key = getArg("--encryption-key-file").orEmpty()

                val extraStr = AddEditTunnelViewModel.extractExtraParams(tokens)
                val extraTokens = AddEditTunnelViewModel.sanitizeExtraTokens(
                    AddEditTunnelViewModel.parseCommandLineArguments(extraStr)
                )

                val payload = buildList {
                    add("--role=client")
                    add("--transport")
                    add(transType.cliName)
                    if (rawUrls.isNotEmpty()) {
                        add("--urls"); add(rawUrls)
                        val firstUrl = rawUrls.split(",").firstOrNull()?.trim().orEmpty()
                        if (firstUrl.isNotEmpty()) {
                            add("--url"); add(firstUrl)
                        }
                    } else if (rawUrl.isNotEmpty()) {
                        add("--url"); add(rawUrl)
                    }
                    if (token.isNotEmpty()) { add("--maxToken"); add(token) }
                    if (uid.isNotEmpty()) { add("--maxUid"); add(uid) }
                    addAll(extraTokens)
                }
                tunnel = Tunnel(
                    id = System.currentTimeMillis() * 1000L + kotlin.random.Random.nextLong(1000L),
                    name = when (transType) {
                        io.github.p1neapplexpress.openflux.data.TransportType.cups -> "CUPS Tunnel"
                        io.github.p1neapplexpress.openflux.data.TransportType.max -> "MAX Tunnel"
                        io.github.p1neapplexpress.openflux.data.TransportType.vyandex -> "Yandex Volga"
                        io.github.p1neapplexpress.openflux.data.TransportType.mailru -> "Mail.ru Docs"
                        io.github.p1neapplexpress.openflux.data.TransportType.gdocs -> "Google Docs"
                        else -> "Yandex Docs"
                    },
                    transportType = transType.name,
                    transportConnPayload = payload,
                    encryptionKey = if (key.isNotEmpty()) runCatching { File(key).readText().trim() }.getOrNull() else null
                )
            } else if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                val isCups = trimmed.contains("cups.online", ignoreCase = true)
                val isMailRu = trimmed.contains("mail.ru", ignoreCase = true)
                val isGdocs = trimmed.contains("docs.google.com", ignoreCase = true) || trimmed.contains("drive.google.com", ignoreCase = true)
                val transType = when {
                    isCups -> io.github.p1neapplexpress.openflux.data.TransportType.cups
                    isMailRu -> io.github.p1neapplexpress.openflux.data.TransportType.mailru
                    isGdocs -> io.github.p1neapplexpress.openflux.data.TransportType.gdocs
                    else -> io.github.p1neapplexpress.openflux.data.TransportType.yandex
                }
                tunnel = Tunnel(
                    id = System.currentTimeMillis() * 1000L + kotlin.random.Random.nextLong(1000L),
                    name = when (transType) {
                        io.github.p1neapplexpress.openflux.data.TransportType.cups -> "CUPS Tunnel"
                        io.github.p1neapplexpress.openflux.data.TransportType.mailru -> "Mail.ru Docs"
                        io.github.p1neapplexpress.openflux.data.TransportType.gdocs -> "Google Docs"
                        else -> "Yandex Docs"
                    },
                    transportType = transType.name,
                    transportConnPayload = listOf(
                        "--role=client",
                        "--transport", transType.cliName,
                        "--url", trimmed
                    ),
                    encryptionKey = null
                )
            }
        }

        return if (tunnel != null && context != null) {
            ensureLocalKeyFile(context, tunnel)
        } else {
            tunnel
        }
    }

    fun fromUri(uri: Uri, context: Context? = null): Tunnel? = runCatching {
        val scheme = uri.scheme?.lowercase()
        if (scheme != SCHEME_OPENFLUX &&
            scheme != SCHEME_FLUXON &&
            scheme != SCHEME_PAPERFLUX
        ) {
            return null
        }

        var tunnel: Tunnel? = null
        val uriStr = uri.toString().trim()

        // Helper to parse query parameters from a query string (e.g. key=val&key2=val2)
        fun parseQueryPairs(query: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            query.split('&').forEach { pair ->
                if (pair.isNotBlank()) {
                    val eqIdx = pair.indexOf('=')
                    if (eqIdx > 0) {
                        val k = runCatching { Uri.decode(pair.substring(0, eqIdx)) }.getOrDefault(pair.substring(0, eqIdx))
                        val v = runCatching { Uri.decode(pair.substring(eqIdx + 1)) }.getOrDefault(pair.substring(eqIdx + 1))
                        map[k] = v
                    } else {
                        val k = runCatching { Uri.decode(pair) }.getOrDefault(pair)
                        map[k] = ""
                    }
                }
            }
            return map
        }

        // Case-preserved extraction of raw authority / host:
        // RFC 3986 URI parsing converts uri.host to lowercase, corrupting case-sensitive Base64.
        // We extract the raw authority substring directly preserving original letter casing.
        val rawAuthority = if (uriStr.startsWith("$scheme://", ignoreCase = true)) {
            val afterScheme = uriStr.substring(scheme.length + 3)
            afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        } else {
            uri.encodedAuthority ?: uri.authority ?: ""
        }
        val rawHost = rawAuthority.substringAfter('@').substringBefore(':')

        // 1. Data parameter parsing (Base64 or raw JSON)
        if (uri.isHierarchical) {
            val dataParam = runCatching {
                uri.getQueryParameter(PARAM_DATA)
                    ?: uri.getQueryParameter("config")
                    ?: uri.getQueryParameter("c")
                    ?: uri.getQueryParameter("tunnel")
            }.getOrNull()

            if (!dataParam.isNullOrBlank()) {
                val decodedJson = decodeBase64(dataParam)
                    ?: dataParam.trim().takeIf { it.startsWith("{") }
                if (decodedJson != null) {
                    tunnel = decodeTunnelFromJson(decodedJson)
                }
            }
        } else {
            // Opaque URI handling (e.g. openflux:eyJuYW1l... or openflux:import?data=... or openflux:?data=...)
            val ssp = uri.schemeSpecificPart.orEmpty().trim().removePrefix("//")
            // Try direct Base64 or JSON from ssp first
            val directJson = decodeBase64(ssp)
                ?: ssp.takeIf { it.startsWith("{") }
            if (directJson != null) {
                tunnel = decodeTunnelFromJson(directJson)
            }

            // If ssp contains query syntax, extract data parameter
            if (tunnel == null && (ssp.contains('?') || ssp.contains('='))) {
                val queryPart = if (ssp.contains('?')) ssp.substringAfter('?') else ssp
                val params = parseQueryPairs(queryPart)
                val dataVal = params[PARAM_DATA] ?: params["config"] ?: params["c"] ?: params["tunnel"]
                if (!dataVal.isNullOrBlank()) {
                    val decodedJson = decodeBase64(dataVal)
                        ?: dataVal.trim().takeIf { it.startsWith("{") }
                    if (decodedJson != null) {
                        tunnel = decodeTunnelFromJson(decodedJson)
                    }
                }
            }
        }

        // 2. Fragment fallback
        if (tunnel == null) {
            val fragment = uri.fragment
            if (!fragment.isNullOrBlank()) {
                val cleanFrag = fragment.removePrefix("data=").removePrefix("config=")
                val decodedJson = decodeBase64(cleanFrag)
                    ?: cleanFrag.trim().takeIf { it.startsWith("{") }
                if (decodedJson != null) {
                    tunnel = decodeTunnelFromJson(decodedJson)
                }
            }
        }

        // 3. Path segments fallback (hierarchical only)
        if (tunnel == null && uri.isHierarchical) {
            for (segment in uri.pathSegments.reversed()) {
                decodeBase64(segment)?.let { jsonStr ->
                    tunnel = decodeTunnelFromJson(jsonStr)
                    if (tunnel != null) break
                }
            }
        }

        // 4. Host position Base64 fallback (with case preservation!)
        if (tunnel == null) {
            if (rawHost.isNotBlank() &&
                !rawHost.equals(HOST_IMPORT, ignoreCase = true) &&
                !rawHost.equals("config", ignoreCase = true)
            ) {
                decodeBase64(rawHost)?.let { jsonStr ->
                    tunnel = decodeTunnelFromJson(jsonStr)
                }
            }
        }

        // 5. Direct query parameter construction (including MAX transport token and uid support)
        if (tunnel == null) {
            val queryMap: Map<String, String> = if (uri.isHierarchical) {
                val keys = runCatching { uri.queryParameterNames }.getOrNull() ?: emptySet()
                keys.associateWith { k -> runCatching { uri.getQueryParameter(k) }.getOrNull().orEmpty() }
            } else {
                val ssp = uri.schemeSpecificPart.orEmpty().trim().removePrefix("//")
                val queryPart = if (ssp.contains('?')) ssp.substringAfter('?') else ssp
                parseQueryPairs(queryPart)
            }

            fun param(k: String): String? = queryMap[k]?.takeIf { it.isNotBlank() }

            val rawUrls = param("urls")
            val rawUrl = param("url")
            val token = param("token") ?: param("maxToken")
            val uid = param("uid") ?: param("maxUid")
            val rawTransport = param("transport")
                ?: rawHost.takeIf { it.isNotBlank() && !it.equals(HOST_IMPORT, ignoreCase = true) && !it.equals("config", ignoreCase = true) }
                ?: "yandex"
            val transType = io.github.p1neapplexpress.openflux.data.TransportType.from(rawTransport)

            if (!rawUrls.isNullOrBlank() ||
                !rawUrl.isNullOrBlank() ||
                (!token.isNullOrBlank() && !uid.isNullOrBlank()) ||
                transType == io.github.p1neapplexpress.openflux.data.TransportType.max
            ) {
                val key = param("key") ?: param("secret")
                val name = param("name") ?: when (transType) {
                    io.github.p1neapplexpress.openflux.data.TransportType.cups -> "CUPS Tunnel"
                    io.github.p1neapplexpress.openflux.data.TransportType.max -> "MAX Tunnel"
                    io.github.p1neapplexpress.openflux.data.TransportType.vyandex -> "Yandex Volga"
                    io.github.p1neapplexpress.openflux.data.TransportType.mailru -> "Mail.ru Docs"
                        io.github.p1neapplexpress.openflux.data.TransportType.gdocs -> "Google Docs"
                    else -> "Yandex Docs"
                }
                val codec = param("codec")
                val extraArgsParam = param("extraArgs") ?: param("args") ?: param("extra")
                val extraFromUri = mutableListOf<String>()
                if (!codec.isNullOrBlank()) {
                    extraFromUri.add("--codec=$codec")
                }
                if (!extraArgsParam.isNullOrBlank()) {
                    extraFromUri.addAll(AddEditTunnelViewModel.parseCommandLineArguments(extraArgsParam))
                }

                val payload = buildList {
                    add("--role=client")
                    add("--transport")
                    add(transType.cliName)
                    if (!rawUrls.isNullOrBlank()) {
                        add("--urls"); add(rawUrls)
                        val firstUrl = rawUrls.split(",").firstOrNull()?.trim().orEmpty()
                        if (firstUrl.isNotEmpty()) {
                            add("--url"); add(firstUrl)
                        }
                    } else if (!rawUrl.isNullOrBlank()) {
                        add("--url"); add(rawUrl)
                    }
                    if (!token.isNullOrBlank()) {
                        add("--maxToken"); add(token)
                    }
                    if (!uid.isNullOrBlank()) {
                        add("--maxUid"); add(uid)
                    }
                    addAll(AddEditTunnelViewModel.sanitizeExtraTokens(extraFromUri))
                }
                tunnel = Tunnel(
                    id = System.currentTimeMillis() * 1000L + kotlin.random.Random.nextLong(1000L),
                    name = name,
                    transportType = transType.name,
                    transportConnPayload = payload,
                    encryptionKey = key
                )
            }
        }

        if (tunnel != null && context != null) {
            ensureLocalKeyFile(context, tunnel)
        } else {
            tunnel
        }
    }.getOrNull()

    private fun decodeBase64(data: String): String? {
        val flagsList = intArrayOf(
            Base64.URL_SAFE or Base64.NO_WRAP,
            Base64.URL_SAFE,
            Base64.DEFAULT,
            Base64.NO_WRAP
        )
        for (flag in flagsList) {
            try {
                val bytes = Base64.decode(data, flag)
                if (bytes != null && bytes.isNotEmpty()) {
                    val str = String(bytes, StandardCharsets.UTF_8).trim()
                    if (str.startsWith("{") && str.endsWith("}")) {
                        return str
                    }
                }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun argValue(payload: List<String>, key: String): String {
        val eqPrefix = "$key="
        for (item in payload) {
            if (item.startsWith(eqPrefix, ignoreCase = true)) {
                return item.substring(eqPrefix.length).trim('"', '\'')
            }
        }
        val i = payload.indexOfFirst { it.equals(key, ignoreCase = true) }
        return if (i != -1 && i + 1 < payload.size) payload[i + 1] else ""
    }
}

