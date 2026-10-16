package io.github.libreroute.admin

/** Session-only authority confirmed by a fresh signed response, never by a cached grant. */
data class AdminLiveAccess(val probeId: Long, val state: State, val role: AdminRole = AdminRole.NONE,
                           val reason: String? = null) {
    enum class State { CHECKING, READY, DENIED, UNAVAILABLE }
}
