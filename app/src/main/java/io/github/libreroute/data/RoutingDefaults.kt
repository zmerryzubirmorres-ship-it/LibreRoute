package io.github.libreroute.data

import kotlinx.serialization.Serializable

/** Portable assignments only: no servers, credentials, UIDs or runtime ports. */
@Serializable
data class RoutingDefaults(
    val version: Int,
    val profiles: ZapretProfileState? = null,
    val apps: AppRoutingState,
    val sites: SiteRoutingState,
) {
    fun validate() {
        require(version == 1) { "Неподдерживаемая версия преднастройки маршрутов" }
        val effectiveProfiles = profiles ?: ZapretProfileState()
        apps.validate(effectiveProfiles)
        sites.validate(effectiveProfiles)
    }
}
