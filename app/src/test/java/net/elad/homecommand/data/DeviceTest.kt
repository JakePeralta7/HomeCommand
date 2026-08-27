package net.elad.homecommand.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceTest {
    private val gson = Gson()

    @Test
    fun `stateRequestTopic appends get suffix`() {
        val device =
            Device(name = "Plug", type = DeviceType.SMART_PLUG, commandTopic = "t/set", stateTopic = "t")
        assertEquals("t/get", device.stateRequestTopic())

        val noState = Device(name = "S", type = DeviceType.SMART_BUTTON, stateTopic = null)
        assertNull(noState.stateRequestTopic())
    }

    @Test
    fun `stateTopicWith combines base and name`() {
        val device = Device(name = "front_door", type = DeviceType.CONTACT_SENSOR)
        assertEquals("zigbee2mqtt/front_door", device.stateTopicWith("zigbee2mqtt"))
    }

    @Test
    fun `commandTopicWith appends set suffix`() {
        val device = Device(name = "server_plug", type = DeviceType.SMART_PLUG)
        assertEquals("zigbee2mqtt/server_plug/set", device.commandTopicWith("zigbee2mqtt"))
    }

    @Test
    fun `sensor types can exist without a command topic`() {
        val sensor =
            Device(
                name = "Front Door",
                type = DeviceType.CONTACT_SENSOR,
                roomId = "room-1",
                stateTopic = "zigbee2mqtt/front_door",
            )
        assertNull(sensor.commandTopic)
        assertEquals("room-1", sensor.roomId)
    }

    @Test
    fun `gson round trip preserves rooms and ir commands`() {
        val device =
            Device(
                name = "IR Blaster",
                type = DeviceType.IR_REMOTE,
                roomId = "living",
                stateTopic = "zigbee2mqtt/ir",
                commandTopic = "zigbee2mqtt/ir/set",
                irCommands = mapOf("TV On" to "abc=="),
            )
        val json = gson.toJson(device)
        val restored = gson.fromJson(json, Device::class.java)
        assertEquals(device, restored)
        assertEquals("abc==", restored.irCommands?.get("TV On"))
    }

    @Test
    fun `gson round trip preserves read only flag`() {
        val device =
            Device(
                name = "Server plug",
                type = DeviceType.SMART_PLUG,
                stateTopic = "zigbee2mqtt/server",
                commandTopic = "zigbee2mqtt/server/set",
                readOnly = true,
            )
        val restored = gson.fromJson(gson.toJson(device), Device::class.java)
        assertEquals(true, restored.readOnly)
    }

    @Test
    fun `legacy json without read only key defaults to false`() {
        val legacy = """{"id":"d1","name":"Plug","type":"SMART_PLUG","commandTopic":"t/set"}"""
        val restored = gson.fromJson(legacy, Device::class.java)
        assertEquals(false, restored.readOnly)
    }

    @Test
    fun `gson round trip preserves rooms list`() {
        val rooms = listOf(Room(id = "a", name = "Living room"), Room(id = "b", name = "Kitchen"))
        val restored = gson.fromJson<List<Room>>(gson.toJson(rooms), object : TypeToken<List<Room>>() {}.type)
        assertEquals(rooms, restored)
    }
}
