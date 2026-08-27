package net.elad.homecommand.data

import com.google.gson.annotations.SerializedName
import java.util.UUID

/**
 * @SerializedName on every persisted field: JSON keys stay stable when R8 renames
 * properties (Gson 2.11+ consumer keep rules preserve annotated fields only).
 */
data class Device(
    @SerializedName("id") val id: String = UUID.randomUUID().toString(),
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: DeviceType,
    @SerializedName("roomId") val roomId: String? = null,
    @SerializedName("stateTopic") val stateTopic: String? = null,
    @SerializedName("commandTopic") val commandTopic: String? = null,
    /** Locked device: tile controls render disabled and sendCommand refuses to publish. */
    @SerializedName("readOnly") val readOnly: Boolean = false,
    /** Named IR codes for IR_REMOTE replay ("TV On" -> learned code). */
    @SerializedName("irCommands") val irCommands: Map<String, String>? = null,
    /** Ordering position within the owning room; lower values appear first. */
    @SerializedName("position") val position: Int = 0,
    /** When true, a notification fires on state change. Default opt-in off. */
    @SerializedName("notifyOnStateChange") val notifyOnStateChange: Boolean = false,
) {
    fun stateRequestTopic(): String? = stateTopic?.let { "$it/get" }

    fun stateTopicWith(base: String): String = "$base/$name"

    fun commandTopicWith(base: String): String = "$base/$name/set"
}
