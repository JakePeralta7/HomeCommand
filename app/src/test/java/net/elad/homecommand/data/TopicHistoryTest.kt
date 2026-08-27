package net.elad.homecommand.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TopicHistoryTest {
    @Test
    fun `record keeps newest and trims oldest beyond capacity`() {
        val history = TopicHistory(2)
        history.record("t", "a")
        history.record("t", "b")
        history.record("t", "c")

        assertEquals(listOf("b", "c"), history.history("t"))
        assertEquals("c", history.latest("t"))
    }

    @Test
    fun `topics are isolated`() {
        val history = TopicHistory(3)
        history.record("temp", "21")
        history.record("hum", "75")

        assertEquals(listOf("21"), history.history("temp"))
        assertEquals(listOf("75"), history.history("hum"))
    }

    @Test
    fun `unknown topic yields null and empty list`() {
        val history = TopicHistory(5)
        assertNull(history.latest("nope"))
        assertEquals(emptyList<String>(), history.history("nope"))
    }

    @Test
    fun `restore trims oversized stored lists`() {
        val history = TopicHistory(2)
        history.restore(mapOf("t" to listOf("1", "2", "3", "4")))

        assertEquals(listOf("3", "4"), history.history("t"))
        assertEquals("4", history.latest("t"))
    }

    @Test
    fun `setCapacity shrinks existing topics`() {
        val history = TopicHistory(3)
        history.record("t", "1")
        history.record("t", "2")
        history.record("t", "3")
        history.setCapacity(1)

        assertEquals(listOf("3"), history.history("t"))
    }

    @Test
    fun `snapshot round-trips through restore`() {
        val original = TopicHistory(3)
        original.record("a", "x")
        original.record("a", "y")
        original.record("b", "z")

        val copy = TopicHistory(3)
        copy.restore(original.snapshot())

        assertEquals(original.snapshot(), copy.snapshot())
    }
}
