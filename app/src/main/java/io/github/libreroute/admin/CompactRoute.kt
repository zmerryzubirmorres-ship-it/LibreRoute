package io.github.libreroute.admin

import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.InflaterInputStream

/** One signed route, without a document redemption or an administrative grant. */
object CompactRoute {
    const val PREFIX = "libreroute://invitation?route="
    private const val DOMAIN = "LibreRoute/compact-route/v2\u0000"
    const val MAX_LINK_LENGTH = 3500
    private val json = Json { ignoreUnknownKeys = false }
    data class Candidate(val route: RouteCandidate, val serverKey: String, val privateKey: String?, val digest: String)

    fun toLink(configuration: String, privateKey: String? = null): String {
        require(configuration.matches(Regex("[A-Za-z0-9_-]{1,3000}"))) { "Некорректная строка настроек" }
        val link = PREFIX + configuration + (privateKey?.let {
            "." + Base64.getUrlEncoder().withoutPadding().encodeToString(Base64.getDecoder().decode(it))
        } ?: "")
        require(link.length <= MAX_LINK_LENGTH) { "Строка настроек не помещается в одно сообщение Telegram" }
        return link
    }

    fun parse(link: String, now: Long = System.currentTimeMillis() / 1000, pinned: (String) -> String? = { null }): Candidate {
        require(link.startsWith(PREFIX) && link.length <= MAX_LINK_LENGTH) { "Некорректная строка настроек" }
        val pieces = link.removePrefix(PREFIX).split('.')
        require(pieces.size in 1..2 && pieces.all { it.isNotEmpty() && it.matches(Regex("[A-Za-z0-9_-]+")) }) { "Некорректная строка настроек" }
        val raw = Base64.getUrlDecoder().decode(pieces[0])
        val input = DataInputStream(ByteArrayInputStream(raw))
        require(input.readUnsignedByte() == 2) { "Обновите приложение для импорта" }
        fun part(max: Int): ByteArray {
            val size = input.readUnsignedShort()
            require(size in 1..max && size <= input.available()) { "Некорректная строка настроек" }
            return ByteArray(size).also { input.readFully(it) }
        }
        val publicKey = part(128)
        val packed = part(1100)
        val signedSize = raw.size - input.available()
        val signature = part(80)
        require(input.available() == 0) { "Некорректная строка настроек" }
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKey)) as java.security.interfaces.ECPublicKey
        require(key.params.curve.field.fieldSize == 256) { "Некорректный ключ сервера" }
        require(Signature.getInstance("SHA256withECDSA").run {
            initVerify(key); update(DOMAIN.toByteArray()); update(raw, 0, signedSize); verify(signature)
        }) { "Подпись настроек не подтверждена" }
        val unpacked = InflaterInputStream(ByteArrayInputStream(packed)).use { stream ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(512)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 8192) { "Настройки превышают допустимый размер" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        val a = json.parseToJsonElement(unpacked.toString(Charsets.UTF_8)).jsonArray
        require(a.size == 7 && a[0].jsonPrimitive.content == "jazz") { "Нужна конфигурация одного протокола Jazz" }
        val b = a[4].jsonArray
        require(b.size == 14) { "Некорректная конфигурация Jazz" }
        fun s(i: Int) = b[i].jsonPrimitive.content
        fun n(i: Int) = b[i].jsonPrimitive.long
        val binding = PairChannelBinding(n(0).toInt(), s(1), n(2).toInt(), s(3), s(4), s(5), n(6), n(7), n(8), n(9), s(10), s(11), s(12), s(13))
        binding.validate(binding.server_id, binding.device_fingerprint, binding.device_public_key)
        val issued = a[5].jsonPrimitive.long
        val expires = a[6].jsonPrimitive.long
        require(issued <= now + 60 && expires > now && expires > issued && expires - issued <= 900) { "Срок импорта настроек истёк" }
        // Admin pins use docrecords' canonical uncompressed P-256 point format.
        // The compact envelope stores PKIX DER so the signature verifier can
        // parse it directly; normalize both representations before comparing.
        val serverKey = canonicalServerKey(key)
        val encodedDerServerKey = Base64.getEncoder().encodeToString(publicKey)
        require(pinned(binding.server_id).let { it == null || it == serverKey || it == encodedDerServerKey }) { "Ключ сервера изменился" }
        val route = RouteCandidate(route_id = binding.pair_id, server_id = binding.server_id, transport = "jazz",
            name = a[1].jsonPrimitive.content, url = a[2].jsonPrimitive.content, secret_key = a[3].jsonPrimitive.content,
            codec = "batched", encryption_layout = "batch", pair_channel = binding)
        require(route.name.length <= 200 && route.secret_key.matches(Regex("[0-9a-f]{64}")) &&
            io.github.libreroute.util.ServiceRouteEndpoint.isValid(io.github.libreroute.data.TransportType.jazz, route.url) &&
            java.net.URI(route.url).path.split('/').getOrNull(2) == binding.resource_identity) { "Некорректные настройки комнаты Jazz" }
        val privateKey = pieces.getOrNull(1)?.let { Base64.getEncoder().encodeToString(Base64.getUrlDecoder().decode(it)) }
        privateKey?.let {
            RecipientInvitationCrypto(InMemoryInvitationStateStore()).importIdentity(it, binding.device_public_key)
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        return Candidate(route, serverKey, privateKey, digest)
    }

    private fun canonicalServerKey(key: ECPublicKey): String {
        fun coordinate(value: java.math.BigInteger): ByteArray {
            val source = value.toByteArray()
            val result = ByteArray(32)
            val copy = minOf(source.size, result.size)
            source.copyInto(result, result.size - copy, source.size - copy, source.size)
            return result
        }
        val raw = ByteArray(65)
        raw[0] = 0x04
        coordinate(key.w.affineX).copyInto(raw, 1)
        coordinate(key.w.affineY).copyInto(raw, 33)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw)
    }
}

/** Separate identities preserve other installed routes and the phone's own key. */
class ScopedInvitationStateStore(private val base: InvitationStateStore, private val fingerprint: String) : InvitationStateStore {
    init { require(fingerprint.matches(Regex("[0-9a-f]{64}"))) }
    override fun get(key: String) = base.get("route_identity_${fingerprint}_$key")
    override fun put(key: String, value: String) = base.put("route_identity_${fingerprint}_$key", value)
}

fun routeIdentity(store: InvitationStateStore, fingerprint: String): RecipientInvitationCrypto {
    val scoped = ScopedInvitationStateStore(store, fingerprint)
    return RecipientInvitationCrypto(if (scoped.get("recipient_p256_private_v1") != null) scoped else store)
}
