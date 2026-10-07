package io.github.libreroute.data

interface TunnelStore {
    fun load(): List<Tunnel>
    fun save(tunnels: List<Tunnel>)
    fun getSelectedId(): Long?
    fun setSelectedId(id: Long)
}
