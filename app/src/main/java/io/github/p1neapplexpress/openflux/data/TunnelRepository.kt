package io.github.p1neapplexpress.openflux.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.p1neapplexpress.openflux.util.Constants


class TunnelRepository(context: Context) {

    companion object {
        private val lock = Any()
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(Constants.PREF, Context.MODE_PRIVATE)

    private val encryptedRepo = EncryptedProfileRepository(context)

    fun load(): List<Tunnel> = synchronized(lock) {
        encryptedRepo.load()
    }

    fun save(tunnels: List<Tunnel>) = synchronized(lock) {
        encryptedRepo.save(tunnels)
    }

    /** ID последнего выбранного туннеля, или null если не выбран. */
    fun getSelectedId(): Long? {
        val v = prefs.getLong(Constants.PREF_SELECTED_TUNNEL_ID, -1L)
        return if (v == -1L) null else v
    }

    fun setSelectedId(id: Long) {
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
