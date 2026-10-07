package io.github.libreroute.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.libreroute.util.Constants


class TunnelRepository(context: Context) : TunnelStore {

    companion object {
        private val lock = Any()
        private const val DAMAGED_MQTT_NAME = "\u003f\u003f\u003f\u003f\u003f \u003f\u003f\u003f \u003f MQTT"
        private const val DEFAULT_MQTT_NAME = "MQTT · умный дом"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)

    private val encryptedRepo = EncryptedProfileRepository(context)

    override fun load(): List<Tunnel> = synchronized(lock) {
        val stored = encryptedRepo.load()
        val repaired = stored.map { tunnel ->
            var t = tunnel
            if (t.transportType.equals("mqtt", ignoreCase = true)) {
                if (t.name == DAMAGED_MQTT_NAME) {
                    t = t.copy(name = DEFAULT_MQTT_NAME)
                }
                if (t.transportConnPayload.any { it.contains("broker.hivemq.com:8884") }) {
                    t = t.copy(transportConnPayload = t.transportConnPayload.map { it.replace("broker.hivemq.com:8884", "broker.emqx.io:8084") })
                }
            }
            DirectDpiProfile.ensureConfigured(t)
        }
        val withDirect = if (repaired.none { DirectDpiProfile.isDirect(it) }) {
            repaired + DirectDpiProfile.create()
        } else repaired
        if (withDirect != stored) encryptedRepo.save(withDirect)
        withDirect
    }

    override fun save(tunnels: List<Tunnel>) = synchronized(lock) {
        encryptedRepo.save(tunnels)
    }

    /** ID последнего выбранного туннеля, или null если не выбран. */
    override fun getSelectedId(): Long? {
        val v = prefs.getLong(Constants.PREF_SELECTED_TUNNEL_ID, -1L)
        return if (v == -1L) null else v
    }

    override fun setSelectedId(id: Long) {
        prefs.edit { putLong(Constants.PREF_SELECTED_TUNNEL_ID, id) }
    }

    /** Возвращает выбранный туннель, или первый из списка, или null. */
    fun getSelected(): Tunnel? {
        val all = load()
        if (all.isEmpty()) return null
        val id = getSelectedId()
        return all.firstOrNull { it.id == id } ?: all.first()
    }
}
