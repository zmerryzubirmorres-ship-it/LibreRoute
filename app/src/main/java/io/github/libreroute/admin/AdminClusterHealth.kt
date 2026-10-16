package io.github.libreroute.admin

internal data class AdminClusterHealth(val confirmed: Boolean, val message: String)

/** Report readiness of the servers in the signed snapshot, rather than implying extra servers. */
internal fun adminClusterHealth(snapshot: AdminClusterSnapshot): AdminClusterHealth {
    val servers = snapshot.distinctServerIds
    val ready = servers.count { id -> snapshot.perServerStatus[id]?.let { it.isApplied && it.isReady } == true }
    val confirmed = snapshot.isComplete && servers.isNotEmpty() && ready == servers.size
    val message = when {
        confirmed -> if (servers.size == 1) "Конфигурация и маршруты сервера готовы" else "Конфигурации и маршруты серверов готовы"
        servers.size > 1 -> "Готовность подтверждена для $ready из ${servers.size} серверов" +
            if (snapshot.isComplete) "" else ". Сведения получены не полностью"
        else -> {
            val status = servers.singleOrNull()?.let { snapshot.perServerStatus[it] }
            when {
                status == null -> "Сервер ещё не сообщил состояние рабочих маршрутов"
                !status.lastError.isNullOrBlank() -> "Сервер сообщил об ошибке рабочих маршрутов"
                !snapshot.isComplete -> "Сведения о сервере получены не полностью"
                !status.isApplied -> "Сервер ещё не подтвердил применение конфигурации"
                else -> "Готовность маршрутов сервера ещё не подтверждена"
            }
        }
    }
    return AdminClusterHealth(confirmed, message)
}
