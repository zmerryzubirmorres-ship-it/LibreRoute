package io.github.libreroute.data

import kotlinx.serialization.Serializable

@Serializable
data class Tunnel(
    val id: Long,
    val name: String,
    val transportType: String,
    val transportConnPayload: List<String>,
    val encryptionKey: String? = null,
    /** A route hint; administrative authority still requires a verified server grant. */
    val serverId: String? = null,
    /** Signed server profile identity, used to preserve the route when restoring again. */
    val managedProfileId: String? = null,
)
