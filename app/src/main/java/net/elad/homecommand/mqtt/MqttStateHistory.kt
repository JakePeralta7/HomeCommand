package net.elad.homecommand.mqtt

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.elad.homecommand.data.MqttSettings
import net.elad.homecommand.data.StateCacheStorage
import net.elad.homecommand.data.TopicHistory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the per-topic payload ring buffer and its debounced persistence.
 * Extracted from MqttManager so connection lifecycle and history management stay separate.
 */
class MqttStateHistory(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Recent payloads per subscribed topic, persisted so cards show known values instantly on relaunch. */
    private val history = TopicHistory(MqttSettings.DEFAULT_STATE_RETENTION)

    @Volatile
    private var restored = false

    /** Collapses payload bursts into one pending cache write (see [persistAsync]). */
    private val persistPending = AtomicBoolean(false)

    /** Applies the retention setting and restores persisted history once per process. */
    suspend fun setCapacityAndRestore(capacity: Int) {
        history.setCapacity(capacity)
        if (!restored) {
            restored = true
            history.restore(StateCacheStorage.load(appContext))
        }
    }

    fun record(
        topic: String,
        payload: String,
    ) {
        history.record(topic, payload)
        persistAsync()
    }

    fun latest(topic: String): String? = history.latest(topic)

    fun history(topic: String): List<String> = history.history(topic)

    /**
     * Coalesces bursts into a single write: a busy sensor fleet can emit several payloads
     * per second and each flush serializes the whole history. The last window's snapshot
     * always wins; only an in-window process death can lose those messages.
     */
    private fun persistAsync() {
        if (!persistPending.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            delay(PERSIST_DEBOUNCE_MS)
            persistPending.set(false)
            StateCacheStorage.save(appContext, history.snapshot())
        }
    }

    companion object {
        private const val TAG = "MqttStateHistory"
        private const val PERSIST_DEBOUNCE_MS = 500L
    }
}
