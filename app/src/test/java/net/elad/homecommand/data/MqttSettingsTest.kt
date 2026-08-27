package net.elad.homecommand.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MqttSettingsTest {
    @Test
    fun `effectiveTopicBase trims whitespace and slashes`() {
        assertEquals("zigbee2mqtt", MqttSettings(topicBase = "  zigbee2mqtt/ ").effectiveTopicBase())
        assertEquals("home/mqtt", MqttSettings(topicBase = "/home/mqtt").effectiveTopicBase())
    }

    @Test
    fun `effectiveTopicBase falls back to default when blank`() {
        assertEquals(MqttSettings.DEFAULT_TOPIC_BASE, MqttSettings().effectiveTopicBase())
        assertEquals(MqttSettings.DEFAULT_TOPIC_BASE, MqttSettings(topicBase = " / ").effectiveTopicBase())
        assertEquals(MqttSettings.DEFAULT_TOPIC_BASE, MqttSettings(topicBase = null).effectiveTopicBase())
    }

    @Test
    fun `clampStateRetention keeps in-range values`() {
        assertEquals(50, MqttSettings.clampStateRetention(50))
        assertEquals(1, MqttSettings.clampStateRetention(1))
        assertEquals(1000, MqttSettings.clampStateRetention(1000))
    }

    @Test
    fun `clampStateRetention coerces out-of-range and null input`() {
        assertEquals(1, MqttSettings.clampStateRetention(0))
        assertEquals(1, MqttSettings.clampStateRetention(-5))
        assertEquals(1000, MqttSettings.clampStateRetention(99999))
        assertEquals(MqttSettings.DEFAULT_STATE_RETENTION, MqttSettings.clampStateRetention(null))
    }
}
