package io.github.libreroute.admin

import kotlinx.serialization.Serializable
import java.net.URI
import io.github.libreroute.data.TransportType
import io.github.libreroute.util.ServiceRouteEndpoint

@Serializable
data class ChannelGroupLane(val provider: String, val url: String, val binding: PairChannelBinding)

@Serializable
data class ChannelGroupBinding(val schema: Int, val mode: String, val min_ready: Int, val lanes: List<ChannelGroupLane>) {
    fun validate(server: String, fingerprint: String, publicKey: String? = null) {
        require(schema == 1 && mode in setOf("ordered_striping", "flow_affinity") && lanes.size in 1..4 && min_ready in 1..lanes.size) {
            "Некорректные параметры объединения каналов"
        }
        val base = lanes.first().binding
        require(lanes.map { it.binding.lane_id }.distinct().size == lanes.size) { "Повтор номера канала" }
        val documents = mutableSetOf<String>()
        lanes.forEach { lane ->
            val b = lane.binding
            b.validate(server, fingerprint, publicKey, groupLane = true)
            require(b.pair_id == base.pair_id && b.device_id == base.device_id && b.key_revision == base.key_revision &&
                b.package_revision == base.package_revision && b.policy_revision == base.policy_revision &&
                b.device_public_key == base.device_public_key) { "Каналы относятся к разным маршрутам" }
            val identity = endpointIdentity(lane.provider, lane.url)
            require(identity == b.resource_identity) { "Адрес канала не совпадает с выданной конфигурацией" }
            require(documents.add("${lane.provider}:$identity")) { "Для каждого канала нужен отдельный ресурс" }
        }
    }

    val endpoint: String get() = "https://channel-group.libreroute.invalid/${lanes.first().binding.pair_id}"
    val hasYandex: Boolean get() = lanes.any { it.provider == "vyandex" }
}

@Serializable
data class GroupLaneRequest(val provider: String, val url: String)

@Serializable
data class ChannelGroupPlan(val mode: String = "ordered_striping", val min_ready: Int = 1, val lanes: List<GroupLaneRequest>) {
    companion object {
        const val ENROLLMENT_CAPABILITY = "channel-group-enrollment-v1"

        fun fromLinks(text: String): ChannelGroupPlan {
            val urls = text.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
            require(urls.size in 2..4) { "Добавьте от 2 до 4 ссылок, каждую с новой строки" }
            return ChannelGroupPlan(lanes = urls.map { raw ->
                val host = URI(raw).host?.lowercase().orEmpty()
                GroupLaneRequest(if (host in setOf("salutejazz.ru", "jazz.sber.ru")) "jazz" else "vyandex", raw)
            }).also(ChannelGroupPlan::validate)
        }
    }

    fun validate() {
        require(mode in setOf("ordered_striping", "flow_affinity") && lanes.size in 1..4 && min_ready in 1..lanes.size) { "Выберите от 1 до 4 каналов" }
        val identities = lanes.map { "${it.provider}:${endpointIdentity(it.provider, it.url)}" }
        require(identities.distinct().size == identities.size) { "Для каждого канала нужен отдельный документ или комната" }
    }
}

internal fun endpointIdentity(provider: String, raw: String): String {
    val uri = URI(raw)
    return when (provider) {
        "jazz" -> {
            require(ServiceRouteEndpoint.isValid(TransportType.jazz, raw)) { "Укажите полную ссылку Jazz с паролем" }
            uri.path.trim('/').split('/')[1]
        }
        "vyandex" -> {
            require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && !uri.path.isNullOrBlank() &&
                (uri.host == "yandex.ru" || uri.host?.lowercase()?.endsWith(".yandex.ru") == true)) { "Укажите ссылку на документ Яндекса" }
            uri.host.lowercase() + uri.rawPath.trimEnd('/')
        }
        else -> error("Провайдер пока не поддерживает объединение каналов")
    }
}
