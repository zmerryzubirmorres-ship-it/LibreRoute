package io.github.libreroute.data

/** Only server profile identity or an exact legacy route proves equivalence. */
internal object RestoredTunnelMerge {
    fun upsert(saved: List<Tunnel>, incoming: List<Tunnel>, preferredId: Long?,
               owners: Map<Long, Set<String>> = emptyMap(), selectedId: Long? = null): List<Tunnel> {
        val result = saved.toMutableList()
        val known = saved + incoming
        for (route in incoming) {
            val matches = result.filter { old -> sameRoute(old, route) &&
                (!old.serverId.isNullOrBlank() || known.filter { candidate ->
                    !candidate.serverId.isNullOrBlank() && !candidate.managedProfileId.isNullOrBlank() &&
                        sameRoute(old, candidate)
                }.map { it.serverId to it.managedProfileId }.distinct().size == 1)
            }
            val survivor = matches.firstOrNull { it.id == preferredId }
                ?: matches.firstOrNull { it.id == selectedId } ?: matches.firstOrNull()
            if (survivor == null) {
                result += route
                continue
            }
            // Different enrollment contexts must retain independent revocation scopes.
            val duplicates = matches.filter { owners[it.id].orEmpty() == owners[survivor.id].orEmpty() }
            val ids = duplicates.map { it.id }.toSet()
            val index = result.indexOfFirst { it.id in ids }
            result.removeAll { it.id in ids }
            result.add(index, route.copy(id = survivor.id, name = survivor.name))
        }
        return result
    }

    private fun endpoint(tunnel: Tunnel): String? {
        val index = tunnel.transportConnPayload.indexOf("--url")
        return if (index >= 0) tunnel.transportConnPayload.getOrNull(index + 1) else null
    }

    internal fun sameRoute(old: Tunnel, route: Tunnel): Boolean {
        if (route.serverId.isNullOrBlank() || (!old.serverId.isNullOrBlank() && old.serverId != route.serverId) ||
            !old.transportType.equals(route.transportType, true)) return false
        if (!old.managedProfileId.isNullOrBlank())
            return old.serverId == route.serverId && old.managedProfileId == route.managedProfileId && !route.managedProfileId.isNullOrBlank()
        return !route.encryptionKey.isNullOrBlank() && old.encryptionKey == route.encryptionKey &&
            endpoint(route) != null && endpoint(old) == endpoint(route) &&
            pairConfig(old) == pairConfig(route)
    }

    private fun pairConfig(tunnel: Tunnel): String? {
        val index = tunnel.transportConnPayload.indexOf("--pair-channel-config")
        return if (index >= 0) tunnel.transportConnPayload.getOrNull(index + 1) else null
    }
}
