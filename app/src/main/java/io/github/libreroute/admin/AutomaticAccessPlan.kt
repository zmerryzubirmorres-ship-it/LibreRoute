package io.github.libreroute.admin

/** Build only the explicitly selected routes from authority capabilities. */
object AutomaticAccessPlan {
    fun validLink(transport: String, value: String): Boolean = if (transport == "vyandex") runCatching {
        val uri = java.net.URI(value)
        uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && !uri.path.isNullOrBlank() &&
            (uri.host == "yandex.ru" || uri.host?.endsWith(".yandex.ru") == true)
    }.getOrDefault(false) else io.github.libreroute.util.ServiceRouteEndpoint.isValid(io.github.libreroute.data.TransportType.from(transport), value)

    fun build(snapshot: AdminClusterSnapshot, serverIds: Set<String>, links: Map<Pair<String, String>, String>,
              selectedProtocols: Set<Pair<String, String>>): List<EnrollmentRoute> {
        require(serverIds.isNotEmpty()) { "Выберите хотя бы один сервер" }
        val routes = serverIds.flatMap { server ->
            require(server in snapshot.distinctServerIds) { "Сервер не подтверждён управляющим узлом" }
            val methods = snapshot.methodsFor(server).filter {
                it.transport in setOf("vyandex", "mqtt", "jitsi", "jazz") &&
                    io.github.libreroute.data.TransportType.isAvailable(it.transport)
            }
            require(methods.isNotEmpty()) { "Сервер ещё не сообщил доступные протоколы" }
            val chosen = selectedProtocols.filter { it.first == server }.map { it.second }.toSet()
            require(chosen.isNotEmpty()) { "Выберите хотя бы один протокол для каждого выбранного сервера" }
            require(chosen.all { transport -> methods.any { it.transport == transport } }) {
                "Выбранный протокол недоступен на сервере. Откройте настройку заново"
            }
            val selected = methods.filter { it.transport in chosen }.map { method ->
                val link = links[server to method.transport]?.trim().orEmpty()
                require(link.isEmpty() || validLink(method.transport, link)) { "Проверьте ссылку на документ или комнату" }
                if (method.transport == "jazz" && method.automaticAllocation && link.isEmpty()) {
                    require(method.requiredClientSchema == 2) { "Обновите приложение для выдачи Jazz" }
                    EnrollmentRoute(server, "jazz", allocation_mode = "automatic", client_channel_schema = 2)
                } else if (method.endpointRequired && link.isEmpty()) {
                    error("Для выбранного протокола нужна ссылка на отдельный документ или комнату")
                }
                else EnrollmentRoute(server, method.transport, link)
            }
            selected
        }
        require(routes.size <= 8) { "В одном приглашении можно выдать до 8 маршрутов" }
        return routes
    }
}
