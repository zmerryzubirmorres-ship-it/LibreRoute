package io.github.libreroute.admin

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.Base64

/** Public binding from the signed, device-encrypted package. No private key. */
@Serializable
data class PairChannelBinding(
    val schema: Int,
    val pair_id: String,
    val lane_id: Int,
    val lease_id: String,
    val resource_id: String,
    val resource_identity: String,
    val resource_generation: Long,
    val key_revision: Long,
    val package_revision: Long,
    val policy_revision: Long,
    val server_id: String,
    val device_id: String,
    val device_fingerprint: String,
    val device_public_key: String
) {
    fun validate(server: String, fingerprint: String, publicKey: String? = null, groupLane: Boolean = false) {
        require(schema == 2 && pair_id.matches(Regex("[0-9a-f]{32}")) && (if (groupLane) lane_id > 0 else lane_id == 1) &&
            lease_id.isNotBlank() && resource_id.isNotBlank() && resource_identity.isNotBlank() &&
            resource_generation > 0 && key_revision > 0 && package_revision > 0 && policy_revision > 0 &&
            server_id == server && device_id.isNotBlank() && device_fingerprint == fingerprint) {
            "Конфигурация Jazz адресована другому устройству или серверу"
        }
        val raw = Base64.getDecoder().decode(device_public_key)
        val actual = MessageDigest.getInstance("SHA-256").digest(raw).joinToString("") { "%02x".format(it) }
        require(actual == fingerprint && (publicKey == null || publicKey == device_public_key)) { "Ключ устройства Jazz не совпадает" }
        val key = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(raw)) as java.security.interfaces.ECPublicKey
        require(key.params.curve.field.fieldSize == 256) { "Jazz требует ключ P-256" }
    }
}

@Serializable
data class JazzRoomPolicy(
    val policy_id: String = "default",
    val server_id: String,
    val placement_mode: String = "dedicated",
    val max_pairs_per_room: Int = 1,
    val max_rooms: Int = 4,
    val max_logical_pairs: Int = 15,
    val max_total_lanes: Int = 15,
    val provider_participant_cap: Int = 6,
    val participant_headroom: Int = 2,
    val desired_revision: Long = 1,
    val applied_revision: Long = 1
)

@Serializable
data class JazzRoomView(
    val resource_id: String, val state: String, val capacity: Int,
    val reserved: Int, val committed: Int, val full: Boolean, val generation: Long
)
@Serializable
data class JazzPoolProjection(
    val revision: Long,
    val policy: JazzRoomPolicy,
    val rooms: List<JazzRoomView> = emptyList(),
    val checked_at: String = "",
    val operations: List<JazzRoomOperation> = emptyList()
)

@Serializable
data class JazzRoomOperation(val operation_id: String, val state: String, val result_resource_id: String = "", val side_effect_unknown: Boolean = false)
