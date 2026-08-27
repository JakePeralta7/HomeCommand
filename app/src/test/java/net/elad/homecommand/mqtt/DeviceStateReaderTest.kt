package net.elad.homecommand.mqtt

import net.elad.homecommand.data.Device
import net.elad.homecommand.data.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reader tests use real payloads captured from the user's devices. */
class DeviceStateReaderTest {
    private val contactPayload =
        """
        {
            "battery": 100,
            "battery_low": false,
            "contact": true,
            "linkquality": 81,
            "tamper": false,
            "voltage": 2800
        }
        """.trimIndent()

    private val plugPayload =
        """
        {
            "child_lock": "UNLOCK",
            "countdown": 0,
            "current": 0,
            "energy": 10.67,
            "linkquality": 45,
            "power": 0,
            "state": "OFF",
            "update": { "state": "idle" },
            "voltage": 231
        }
        """.trimIndent()

    private val tempHumidityPayload =
        """
        {
            "battery": 100,
            "humidity": 65,
            "linkquality": 99,
            "temperature": 27.66
        }
        """.trimIndent()

    private val motionPayload =
        """
        {
            "battery": 80,
            "battery_low": false,
            "keep_time": 30,
            "linkquality": 141,
            "occupancy": true,
            "sensitivity": "high",
            "tamper": false,
            "voltage": 2800
        }
        """.trimIndent()

    private val vibrationPayload =
        """
        {
            "battery_low": false,
            "linkquality": 144,
            "tamper": false,
            "vibration": false
        }
        """.trimIndent()

    @Test
    fun `contact sensor fields read correctly`() {
        assertTrue(DeviceStateReader.contact(contactPayload) == true)
        assertEquals(100, DeviceStateReader.battery(contactPayload))
        assertFalse(DeviceStateReader.tamper(contactPayload) == true)
        assertFalse(DeviceStateReader.batteryLow(contactPayload) == true)
    }

    @Test
    fun `plug fields read correctly`() {
        assertEquals("OFF", DeviceStateReader.state(plugPayload))
        assertEquals(0f, DeviceStateReader.power(plugPayload))
        assertEquals(10.67f, DeviceStateReader.energy(plugPayload))
        assertEquals("UNLOCK", DeviceStateReader.childLock(plugPayload))
        assertEquals(0, DeviceStateReader.countdown(plugPayload))
    }

    @Test
    fun `temperature and humidity read correctly`() {
        assertEquals(27.66f, DeviceStateReader.temperature(tempHumidityPayload))
        assertEquals(65f, DeviceStateReader.humidity(tempHumidityPayload))
    }

    @Test
    fun `motion fields read correctly`() {
        assertTrue(DeviceStateReader.occupancy(motionPayload) == true)
        assertFalse(DeviceStateReader.tamper(motionPayload) == true)
        assertFalse(DeviceStateReader.batteryLow(motionPayload) == true)
        assertNull(DeviceStateReader.vibration(motionPayload))
    }

    @Test
    fun `vibration fields read correctly`() {
        assertFalse(DeviceStateReader.vibration(vibrationPayload) == true)
        assertFalse(DeviceStateReader.tamper(vibrationPayload) == true)
        assertNull(DeviceStateReader.occupancy(vibrationPayload))
    }

    @Test
    fun `action and learned ir code are extracted`() {
        val press = """{"action":"single","battery":90,"linkquality":123}"""
        assertEquals("single", DeviceStateReader.action(press))
        assertNull(DeviceStateReader.action("""{"battery":90}"""))

        val learned = """{"learned_ir_code":"abc==","linkquality":102}"""
        assertEquals("abc==", DeviceStateReader.learnedIrCode(learned))
        assertNull(DeviceStateReader.learnedIrCode(press))
    }

    @Test
    fun `non-json and missing fields yield nulls`() {
        assertNull(DeviceStateReader.contact("not json"))
        assertNull(DeviceStateReader.state(""))
        assertNull(DeviceStateReader.temperature(null))

        // Nested objects must not be mistaken for primitives.
        assertNull(DeviceStateReader.childLock("""{"child_lock":{"a":1}}"""))
        assertNull(DeviceStateReader.battery("""{"battery":null}"""))
    }

    @Test
    fun `humidity integer value parses as float`() {
        assertEquals(65f, DeviceStateReader.humidity("""{"humidity":65}"""))
    }

    @Test
    fun `readDeviceFields extracts everything in one pass`() {
        val fields = readDeviceFields(plugPayload)
        assertEquals("OFF", fields.state)
        assertEquals(0f, fields.power)
        assertEquals(10.67f, fields.energy)
        assertEquals("UNLOCK", fields.childLock)
        assertEquals(0, fields.countdown)
        assertNull(fields.contact)
        assertNull(fields.learnedIrCode)
    }

    @Test
    fun `readDeviceFields tolerates junk payloads`() {
        val fields = readDeviceFields("not json")
        assertNull(fields.state)
        assertNull(fields.battery)
        assertNull(fields.temperature)

        val empty = readDeviceFields(null)
        assertNull(empty.state)
        assertNull(empty.batteryLow)
        assertNull(empty.learnedIrCode)
    }

    @Test
    fun `learned code arrival detected for matching learning device`() {
        val device = learningDevice("d1")
        assertTrue(
            isLearnedCodeArrival(
                topic = "zigbee2mqtt/remote",
                oldPayload = """{"learned_ir_code":"old"}""",
                newPayload = """{"learned_ir_code":"new=="}""",
                devices = listOf(device),
                learningDeviceIds = setOf("d1"),
            ),
        )
    }

    @Test
    fun `arrival ignored for other topics or non-learning devices`() {
        val device = learningDevice("d1", stateTopic = "zigbee2mqtt/remote")

        // Code arrived on a different device's topic.
        assertFalse(
            isLearnedCodeArrival(
                "zigbee2mqtt/other",
                null,
                """{"learned_ir_code":"x"}""",
                listOf(device),
                setOf("d1"),
            ),
        )
        // Nobody is learning right now.
        assertFalse(
            isLearnedCodeArrival(
                "zigbee2mqtt/remote",
                null,
                """{"learned_ir_code":"x"}""",
                listOf(device),
                emptySet(),
            ),
        )
        // Same code re-published is not a fresh arrival.
        assertFalse(
            isLearnedCodeArrival(
                "zigbee2mqtt/remote",
                """{"learned_ir_code":"x"}""",
                """{"learned_ir_code":"x"}""",
                listOf(device),
                setOf("d1"),
            ),
        )
        // Non-JSON payload carries no code at all.
        assertFalse(isLearnedCodeArrival("zigbee2mqtt/remote", null, "garbage", listOf(device), setOf("d1")))
    }

    private fun learningDevice(
        id: String,
        stateTopic: String = "zigbee2mqtt/remote",
    ): Device = Device(id = id, name = "IR", type = DeviceType.IR_REMOTE, stateTopic = stateTopic)
}
