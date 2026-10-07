package io.github.libreroute.util

import io.github.libreroute.data.TransportType
import java.net.URI
import java.net.URLDecoder

/** Shared validation for manual profiles and signed MQTT/Jitsi routes. */
object ServiceRouteEndpoint {
    const val DEFAULT_MQTT_BROKER = "wss://broker.emqx.io:8084/mqtt"
    const val HIVEMQ_MQTT_BROKER = "wss://broker.hivemq.com:8884/mqtt"
    private val channelName = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]{0,127}$")

    data class MqttEndpoint(val broker: String, val topic: String)

    /** Builds the canonical legacy URL while keeping broker/topic separate in the UI. */
    fun buildMqttUrl(broker: String, topic: String): String? {
        val cleanBroker = broker.trim().let {
            if (it.isBlank() || it == HIVEMQ_MQTT_BROKER || it.contains("broker.hivemq.com:8884")) DEFAULT_MQTT_BROKER
            else it
        }
        val cleanTopic = topic.trim()
        if (!isValid(TransportType.mqtt, "$cleanBroker#$cleanTopic")) return null
        return "$cleanBroker#$cleanTopic"
    }

    /** Parses the combined URL used by old imports and signed route payloads. */
    fun parseMqttUrl(address: String): MqttEndpoint? = runCatching {
        val uri = URI(address.trim())
        require(uri.scheme == "wss" && !uri.host.isNullOrBlank() && !uri.path.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.port in -1..65535 && uri.port != 0)
        val queryTopic = uri.rawQuery?.split('&')?.map {
            val parts = it.split('=', limit = 2)
            require(parts.size == 2 && parts[0] == "topic")
            URLDecoder.decode(parts[1], "UTF-8")
        }.orEmpty()
        require(queryTopic.size <= 1)
        val topic = uri.rawFragment ?: queryTopic.singleOrNull()
        require(topic != null && channelName.matches(topic))
        require(queryTopic.isEmpty() || queryTopic.single() == topic)
        val brokerUri = URI(uri.scheme, uri.userInfo, uri.host, uri.port, uri.path, null, null)
        MqttEndpoint(brokerUri.toString(), topic)
    }.getOrNull()

    fun isValid(type: TransportType, address: String): Boolean = runCatching {
        val uri = URI(address)
        require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.port in -1..65535 && uri.port != 0)
        when (type) {
            TransportType.jitsi -> {
                require(uri.scheme == "https" && uri.rawQuery == null && uri.rawFragment == null)
                val parts = uri.path.trim('/').split('/')
                val host = uri.host.lowercase()
                val isJaas = host == "8x8.vc" || host.endsWith(".8x8.vc")
                if (isJaas) {
                    require(parts.size == 2 && parts.all { channelName.matches(it) })
                } else {
                    require(parts.size == 1 && channelName.matches(parts[0]))
                }
            }
            TransportType.mqtt -> {
                require(uri.scheme == "wss" && !uri.path.isNullOrBlank())
                val pairs = uri.rawQuery?.split('&')?.map {
                    val parts = it.split('=', limit = 2)
                    require(parts.size == 2 && parts[0] == "topic")
                    URLDecoder.decode(parts[1], "UTF-8")
                }.orEmpty()
                require(pairs.size <= 1)
                val topic = uri.fragment ?: pairs.singleOrNull()
                require(topic != null && channelName.matches(topic))
                require(pairs.isEmpty() || pairs.single() == topic)
            }
            else -> return false
        }
        true
    }.getOrDefault(false)
}
