package io.github.libreroute.service

import io.github.libreroute.data.ZapretProfile

/** Runtime settings replace legacy options stored in a direct-dpi route. */
internal fun zapretLaunchOptions(payload: List<String>, profile: ZapretProfile, port: Int): List<String> {
    profile.validate()
    require(port in 1..65535)
    val result = mutableListOf<String>()
    var index = 0
    while (index < payload.size) {
        val option = payload[index++]
        when {
            option == "--dpi-proxy" -> {
                if (payload.getOrNull(index)?.startsWith("--") == false) index++
            }
            option == "--dpi-block-quic" -> {
                if (payload.getOrNull(index) in listOf("true", "false")) index++
            }
            option.startsWith("--dpi-proxy=") || option.startsWith("--dpi-block-quic=") -> Unit
            else -> result.add(option)
        }
    }
    result.add("--dpi-proxy=127.0.0.1:$port")
    result.add("--dpi-block-quic=${profile.blockQuic}")
    return result
}
