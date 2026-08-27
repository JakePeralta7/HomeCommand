package net.elad.homecommand.mqtt

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.elad.homecommand.data.Device

/**
 * Typed state-field readers for the predefined device types; all tolerate
 * non-JSON payloads and missing fields by returning null.
 */
object DeviceStateReader {
    fun state(json: String?): String? = parseOrNull(json).string("state")

    fun contact(json: String?): Boolean? = parseOrNull(json).bool("contact")

    fun occupancy(json: String?): Boolean? = parseOrNull(json).bool("occupancy")

    fun vibration(json: String?): Boolean? = parseOrNull(json).bool("vibration")

    fun tamper(json: String?): Boolean? = parseOrNull(json).bool("tamper")

    fun batteryLow(json: String?): Boolean? = parseOrNull(json).bool("battery_low")

    /** Battery percentage, 0..100. */
    fun battery(json: String?): Int? = parseOrNull(json).int("battery")

    fun temperature(json: String?): Float? = parseOrNull(json).float("temperature")

    fun humidity(json: String?): Float? = parseOrNull(json).float("humidity")

    /** Live power draw in watts. */
    fun power(json: String?): Float? = parseOrNull(json).float("power")

    /** Cumulative energy in kWh. */
    fun energy(json: String?): Float? = parseOrNull(json).float("energy")

    /** "LOCK" / "UNLOCK". */
    fun childLock(json: String?): String? = parseOrNull(json).string("child_lock")

    /** Pending shutdown timer in seconds (0 when off). */
    fun countdown(json: String?): Int? = parseOrNull(json).int("countdown")

    /** Transient button press: "single" / "double" / "hold". */
    fun action(json: String?): String? = parseOrNull(json).string("action")

    fun learnedIrCode(json: String?): String? = parseOrNull(json).string("learned_ir_code")
}

/**
 * Every typed field of one payload, extracted in a single parse pass. Tile binds call
 * [readDeviceFields] once instead of re-parsing per extractor.
 */
data class DeviceFields(
    val state: String?,
    val contact: Boolean?,
    val occupancy: Boolean?,
    val vibration: Boolean?,
    val tamper: Boolean?,
    val batteryLow: Boolean?,
    val battery: Int?,
    val temperature: Float?,
    val humidity: Float?,
    val power: Float?,
    val energy: Float?,
    val childLock: String?,
    val countdown: Int?,
    val action: String?,
    val learnedIrCode: String?,
)

/** File-private parse core; top-level so the reader object stays within detekt's budget. */
internal fun readDeviceFields(json: String?): DeviceFields {
    val obj = parseOrNull(json)
    return DeviceFields(
        state = obj.string("state"),
        contact = obj.bool("contact"),
        occupancy = obj.bool("occupancy"),
        vibration = obj.bool("vibration"),
        tamper = obj.bool("tamper"),
        batteryLow = obj.bool("battery_low"),
        battery = obj.int("battery"),
        temperature = obj.float("temperature"),
        humidity = obj.float("humidity"),
        power = obj.float("power"),
        energy = obj.float("energy"),
        childLock = obj.string("child_lock"),
        countdown = obj.int("countdown"),
        action = obj.string("action"),
        learnedIrCode = obj.string("learned_ir_code"),
    )
}

/** Human-readable snapshot of the most important field, for notifications. */
fun DeviceFields.stateSummary(): String? =
    state
        ?: contactStateSummary()
        ?: boolSummary("Occupied", "Clear", occupancy)
        ?: boolSummary("Vibration", "Clear", vibration)
        ?: action
        ?: temperature?.let { "%.1f°C".format(it) }
        ?: humidity?.let { "%.0f%%".format(it) }
        ?: power?.let { "%.1fW".format(it) }
        ?: energy?.let { "%.2fkWh".format(it) }

private fun DeviceFields.contactStateSummary(): String? = contact?.let { if (it) "Closed" else "Open" }

private fun boolSummary(
    trueLabel: String,
    falseLabel: String,
    value: Boolean?,
): String? = value?.let { if (it) trueLabel else falseLabel }

/**
 * True when [topic] just delivered a fresh learned_ir_code belonging to one of
 * [learningDeviceIds]; other topics' codes must not end someone else's learning.
 */
internal fun isLearnedCodeArrival(
    topic: String,
    oldPayload: String?,
    newPayload: String?,
    devices: List<Device>,
    learningDeviceIds: Set<String>,
): Boolean =
    DeviceStateReader.learnedIrCode(newPayload) != null &&
        DeviceStateReader.learnedIrCode(oldPayload) != DeviceStateReader.learnedIrCode(newPayload) &&
        learningDeviceIds.isNotEmpty() &&
        devices.any { it.id in learningDeviceIds && it.stateTopic == topic }

private fun parseOrNull(json: String?): JsonObject? =
    try {
        JsonParser
            .parseString(json)
            .takeIf { it.isJsonObject }
            ?.asJsonObject
    } catch (_: Exception) {
        null
    }

private fun JsonObject?.string(key: String): String? = primitive(key) { it.asString }

private fun JsonObject?.bool(key: String): Boolean? = primitive(key) { it.asBoolean }

private fun JsonObject?.int(key: String): Int? = primitive(key) { it.asInt }

private fun JsonObject?.float(key: String): Float? = primitive(key) { it.asFloat }

private inline fun <T> JsonObject?.primitive(
    key: String,
    read: (JsonElement) -> T,
): T? = this?.get(key)?.takeIf { it.isJsonPrimitive }?.let(read)
