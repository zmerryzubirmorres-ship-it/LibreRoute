package io.github.libreroute.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.libreroute.util.Constants


class TunnelRepository(context: Context) : TunnelStore {

    companion object {
        private val lock = Any()
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)

    private val encryptedRepo = EncryptedProfileRepository(context)

    override fun load(): List<Tunnel> = synchronized(lock) {
        val stored = encryptedRepo.load()
        val repaired = stored.filter { TransportType.isAvailable(it.transportType) }
            .map(DirectDpiProfile::ensureConfigured)
        // A local zapret connection must also be available without any server profile.
        val connections = if (repaired.none(DirectDpiProfile::isDirect)) {
            repaired + DirectDpiProfile.create()
        } else repaired
        if (connections != stored) encryptedRepo.save(connections)
        repairSelection(connections)
        connections
    }

    override fun save(tunnels: List<Tunnel>) = synchronized(lock) {
        val available = tunnels.filter { TransportType.isAvailable(it.transportType) }
        encryptedRepo.save(available)
        repairSelection(available)
    }

    private fun repairSelection(tunnels: List<Tunnel>) {
        val selected = getSelectedId() ?: return
        if (tunnels.any { it.id == selected }) return
        val replacement = tunnels.firstOrNull()
        prefs.edit {
            if (replacement == null) remove(Constants.PREF_SELECTED_TUNNEL_ID)
            else putLong(Constants.PREF_SELECTED_TUNNEL_ID, replacement.id)
        }
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
