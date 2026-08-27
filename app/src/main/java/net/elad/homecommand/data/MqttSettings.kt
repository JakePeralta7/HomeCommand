package net.elad.homecommand.data

import com.google.gson.annotations.SerializedName

/** @SerializedName keeps JSON keys stable under R8 obfuscation (see [Device]). */
data class MqttSettings(
    @SerializedName("brokerIp") val brokerIp: String = "",
    @SerializedName("port") val port: Int = DEFAULT_PORT,
    @SerializedName("username") val username: String = "",
    @SerializedName("password") val password: String = "",
    @SerializedName("useTls") val useTls: Boolean = false,
    @SerializedName("topicBase") val topicBase: String? = null,
    @SerializedName("stateRetention") val stateRetention: Int = DEFAULT_STATE_RETENTION,
    @SerializedName("batteryAlertThreshold") val batteryAlertThreshold: Int? = null,
) {
    fun effectiveTopicBase(): String = topicBase?.trim()?.trim('/')?.takeIf { it.isNotBlank() } ?: DEFAULT_TOPIC_BASE

    companion object {
        const val DEFAULT_TOPIC_BASE = "zigbee2mqtt"
        const val DEFAULT_PORT = 1883
        const val DEFAULT_STATE_RETENTION = 100
        const val MIN_STATE_RETENTION = 1
        const val MAX_STATE_RETENTION = 1000

        /** Single clamp point for raw UI/storage inputs so bounds stay consistent everywhere. */
        fun clampStateRetention(raw: Int?): Int = raw?.coerceIn(MIN_STATE_RETENTION, MAX_STATE_RETENTION) ?: DEFAULT_STATE_RETENTION
    }
}
