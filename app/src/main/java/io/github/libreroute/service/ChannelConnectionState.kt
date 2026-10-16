package io.github.libreroute.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Latest native channel state, retained across activity/binder recreation. */
internal class ChannelConnectionState(private val now: () -> Long) {
    data class Snapshot(
        val tunnelId: Long? = null,
        val running: Boolean = false,
        val ready: Boolean = false,
        val rejected: Boolean = false,
        val waitingForNetwork: Boolean = false,
        val rttMs: Long? = null,
        val measuredAt: Long = 0,
    ) {
        fun freshRtt(now: Long): Long? = rttMs?.takeIf {
            ready && running && now - measuredAt in 0 until SAMPLE_LIFETIME_MS
        }
    }

    companion object {
        const val SAMPLE_LIFETIME_MS = 20_000L
        private val samplePattern = Regex("\\[CORE_RTT] rtt_us=([0-9]+) age_ms=([0-9]+)$")
    }

    private var owner: Any? = null
    private val mutable = MutableStateFlow(Snapshot())
    val snapshots = mutable.asStateFlow()
    val value get() = mutable.value

    @Synchronized fun begin(token: Any, tunnelId: Long?) {
        owner = token
        mutable.value = Snapshot(tunnelId = tunnelId, running = true)
    }

    @Synchronized fun ready(token: Any, ready: Boolean, rejected: Boolean = false) {
        if (owner !== token) return
        if (ready && value.waitingForNetwork) return
        mutable.value = mutable.value.copy(ready = ready, rejected = rejected,
            rttMs = mutable.value.rttMs.takeIf { ready }, measuredAt = mutable.value.measuredAt.takeIf { ready } ?: 0)
    }

    @Synchronized fun networkLost(token: Any) {
        if (owner !== token) return
        mutable.value = value.copy(ready = false, waitingForNetwork = true, rttMs = null, measuredAt = 0)
    }

    @Synchronized fun localNetworkRestored(token: Any) {
        if (owner !== token) return
        mutable.value = value.copy(waitingForNetwork = false, ready = true)
    }

    @Synchronized fun sample(token: Any, line: String) {
        if (owner !== token || !value.ready) return
        val match = samplePattern.find(line) ?: return
        val micros = match.groupValues[1].toLongOrNull() ?: return
        val age = match.groupValues[2].toLongOrNull() ?: return
        if (micros !in 1..60_000_000 || age !in 0 until SAMPLE_LIFETIME_MS) return
        mutable.value = value.copy(rttMs = ((micros + 999) / 1000).coerceAtLeast(1), measuredAt = now() - age)
    }

    @Synchronized fun end(token: Any) {
        if (owner !== token) return
        owner = null
        mutable.value = Snapshot()
    }
}

internal object ClientChannel {
    val state = ChannelConnectionState { android.os.SystemClock.elapsedRealtime() }
}
