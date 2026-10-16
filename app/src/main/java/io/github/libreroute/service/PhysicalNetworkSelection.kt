package io.github.libreroute.service

/** A VPN must never become its own underlying network. Keep equally good paths stable. */
internal data class PhysicalNetworkCandidate<T>(
    val network: T,
    val internet: Boolean,
    val notVpn: Boolean,
    val validated: Boolean,
    val transportPriority: Int
)

internal fun <T> selectPhysicalNetwork(candidates: Collection<PhysicalNetworkCandidate<T>>, current: T?): T? =
    candidates.filter { it.internet && it.notVpn }.maxWithOrNull(
        compareBy<PhysicalNetworkCandidate<T>> { if (it.validated) 1 else 0 }
            .thenBy { it.transportPriority }
            .thenBy { if (it.network == current) 1 else 0 }
    )?.network

/** Capability restoration can happen without Android creating a new Network. */
internal class PhysicalNetworkTracker<T> {
    data class Change<T>(val network: T?, val changed: Boolean, val validationRestored: Boolean, val initial: Boolean)
    private var current: T? = null
    private var validated = false
    private var observed = false

    fun update(candidates: Collection<PhysicalNetworkCandidate<T>>): Change<T> {
        val selected = selectPhysicalNetwork(candidates, current)
        val nextValidated = candidates.firstOrNull { it.network == selected }?.validated == true
        val result = Change(selected, selected != current,
            selected != null && selected == current && nextValidated && !validated, selected != null && !observed)
        if (selected != null) observed = true
        current = selected
        validated = nextValidated
        return result
    }

    fun clear() { current = null; validated = false; observed = false }
}
