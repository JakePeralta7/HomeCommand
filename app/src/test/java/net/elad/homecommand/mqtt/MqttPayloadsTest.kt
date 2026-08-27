package net.elad.homecommand.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MqttPayloadsTest {
    @Test
    fun `state payloads are exact`() {
        assertEquals("""{"state":"ON"}""", MqttPayloads.STATE_ON_PAYLOAD)
        assertEquals("""{"state":"OFF"}""", MqttPayloads.STATE_OFF_PAYLOAD)
        assertEquals("""{"state":""}""", MqttPayloads.STATE_GET_PAYLOAD)
    }

    @Test
    fun `ir and lock payloads are exact`() {
        assertEquals("""{"learn_ir_code":"ON"}""", MqttPayloads.LEARN_IR_PAYLOAD)
        assertEquals("""{"child_lock":"LOCK"}""", MqttPayloads.CHILD_LOCK_PAYLOAD)
        assertEquals("""{"child_lock":"UNLOCK"}""", MqttPayloads.CHILD_UNLOCK_PAYLOAD)
    }

    @Test
    fun `countdown embeds seconds`() {
        assertEquals("""{"countdown":600}""", MqttPayloads.countdownPayload(600))
        assertEquals("""{"countdown":0}""", MqttPayloads.countdownPayload(0))
    }

    @Test
    fun `sendIr embeds base64 code`() {
        val code =
            "DesR6xFiAiAGYgLPAWICQAdAA0ALA88BmwLgAQsAYuAIC8AbQAdAA8AjQAvgBwPAG0AH4AEDAJvgAgvgAxfgCQvgB0/gBw8B6xFAAQFiAkA" +
                "XQD9AB0ADQAtAA+ATC8AbQAdAA8AvQAvgBwPAG0AH4CsD4AdP4AQPAgZiAg=="
        assertEquals("""{"ir_code_to_send":"$code"}""", MqttPayloads.sendIrPayload(code))
    }

    @Test
    fun `parseCountdown accepts in-window numbers`() {
        assertEquals(0, MqttPayloads.parseCountdown("0"))
        assertEquals(600, MqttPayloads.parseCountdown("600"))
        assertEquals(43200, MqttPayloads.parseCountdown("43200"))
    }

    @Test
    fun `parseCountdown rejects junk and out-of-window values`() {
        assertNull(MqttPayloads.parseCountdown(null))
        assertNull(MqttPayloads.parseCountdown(""))
        assertNull(MqttPayloads.parseCountdown("abc"))
        assertNull(MqttPayloads.parseCountdown("-1"))
        assertNull(MqttPayloads.parseCountdown("43201"))
    }
}
