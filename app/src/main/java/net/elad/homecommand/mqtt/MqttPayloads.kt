package net.elad.homecommand.mqtt

/** Command payload builders for the predefined Zigbee2MQTT device types. */
object MqttPayloads {
    const val STATE_ON_PAYLOAD = """{"state":"ON"}"""
    const val STATE_OFF_PAYLOAD = """{"state":"OFF"}"""
    const val STATE_GET_PAYLOAD = """{"state":""}"""

    /** Starts IR learning; the learned code arrives on the state topic as learned_ir_code. */
    const val LEARN_IR_PAYLOAD = """{"learn_ir_code":"ON"}"""
    const val CHILD_LOCK_PAYLOAD = """{"child_lock":"LOCK"}"""
    const val CHILD_UNLOCK_PAYLOAD = """{"child_lock":"UNLOCK"}"""

    fun countdownPayload(seconds: Int): String = """{"countdown":$seconds}"""

    /** Learned codes are base64, so no JSON escaping is needed. */
    fun sendIrPayload(code: String): String = """{"ir_code_to_send":"$code"}"""

    /** Parses a raw countdown input; null when non-numeric or outside the supported window. */
    fun parseCountdown(raw: String?): Int? = raw?.toIntOrNull()?.takeIf { it in COUNTDOWN_MIN..COUNTDOWN_MAX }

    private const val COUNTDOWN_MIN = 0
    private const val COUNTDOWN_MAX = 43200
}
