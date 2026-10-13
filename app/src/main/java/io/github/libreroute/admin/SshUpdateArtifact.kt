package io.github.libreroute.admin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** Reuse the signed SSH staging helper only to receive an approved file.
 * Later stages stay on the existing coordinator and keep the original plan.
 */
internal fun sshUpdateArtifactManifest(channel: AdminChannel, action: String, sshUser: String,
                                       plan: JsonObject?): JsonObject? {
    if (channel !in setOf(AdminChannel.DIRECT_SSH, AdminChannel.SSH_VDS, AdminChannel.SSH_NETCRAZE) ||
        action != "begin" || sshUser != "root" || plan?.string("adapter") != "systemd") return null
    return plan["signed_manifest"]?.jsonObject
}
