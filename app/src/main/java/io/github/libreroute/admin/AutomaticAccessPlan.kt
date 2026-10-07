package io.github.libreroute.admin

/** Build a route set from authority capabilities. Empty external links mean
 * add this method later; automatic methods always accompany selected servers. */
object AutomaticAccessPlan {
    fun validLink(transport: String, value: String): Boolean = if (transport == "vyandex") runCatching {
        val uri = java.net.URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && !uri.path.isNullOrBlank() &&
            (uri.host == "yandex.ru" || uri.host?.endsWith(".yandex.ru") == true)
    }.getOrDefault(false) else io.github.libreroute.util.ServiceRouteEndpoint.isValid(io.github.libreroute.data.TransportType.from(transport), value)

    fun build(snapshot: AdminClusterSnapshot, serverIds: Set<String>, links: Map<Pair<String, String>, String>): List<EnrollmentRoute> {
        require(serverIds.isNotEmpty()) { "Выберите хотя бы один сервер" }
        val routes = serverIds.flatMap { server ->
            require(server in snapshot.distinctServerIds) { "Сервер не подтверждён управляющим узлом" }
            val methods = snapshot.methodsFor(server).filter { it.transport in setOf("vyandex", "mqtt", "jitsi") }
            require(methods.isNotEmpty()) { "Сервер ещё не сообщил доступные протоколы" }
            val selected = methods.mapNotNull { method ->
                val link = links[server to method.transport]?.trim().orEmpty()
                require(link.isEmpty() || validLink(method.transport, link)) { "Проверьте ссылку на документ или комнату" }
                if (method.endpointRequired && link.isEmpty()) null
                else EnrollmentRoute(server, method.transport, link)
            }
            require(selected.isNotEmpty()) { "Для выбранного сервера нужна ссылка на документ или комнату" }
            selected
        }
        require(routes.size <= 8) { "В одном приглашении можно выдать до 8 маршрутов" }
        return routes
    }
}
