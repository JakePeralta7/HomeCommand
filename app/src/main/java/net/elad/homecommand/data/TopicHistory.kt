package net.elad.homecommand.data

/**
 * Capacity-bounded history of recent payloads per topic.
 * Pure Kotlin so eviction logic stays unit-testable.
 */
class TopicHistory(
    private var capacity: Int,
) {
    private val topics = LinkedHashMap<String, ArrayDeque<String>>()

    @Synchronized
    fun setCapacity(newCapacity: Int) {
        capacity = newCapacity
        topics.values.forEach(::trim)
    }

    @Synchronized
    fun record(
        topic: String,
        payload: String,
    ) {
        val deque = topics.getOrPut(topic) { ArrayDeque() }
        deque.addLast(payload)
        trim(deque)
    }

    @Synchronized
    fun latest(topic: String): String? = topics[topic]?.lastOrNull()

    @Synchronized
    fun history(topic: String): List<String> = topics[topic]?.toList().orEmpty()

    @Synchronized
    fun snapshot(): Map<String, List<String>> = topics.mapValues { (_, deque) -> deque.toList() }

    @Synchronized
    fun restore(stored: Map<String, List<String>>) {
        stored.forEach { (topic, values) ->
            if (values.isNotEmpty()) topics[topic] = ArrayDeque(values.takeLast(capacity))
        }
    }

    private fun trim(deque: ArrayDeque<String>) {
        while (deque.size > capacity) deque.removeFirst()
    }
}
