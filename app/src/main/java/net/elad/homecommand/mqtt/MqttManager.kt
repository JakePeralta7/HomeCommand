package net.elad.homecommand.mqtt

import android.content.Context
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.MqttClientSslConfig
import com.hivemq.client.mqtt.MqttGlobalPublishFilter
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import net.elad.homecommand.data.AppLog
import net.elad.homecommand.data.Device
import net.elad.homecommand.data.DeviceStorage
import net.elad.homecommand.data.MqttSettings
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Connection lifecycle exposed as an observable state. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data object Connecting : ConnectionState

    data object Connected : ConnectionState
}

/** App-scoped MQTT client owner; lives for the whole process lifetime via [get]. */
class MqttManager private constructor(
    private val appContext: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Volatile: written on scope coroutines, read from HiveMQ listener threads and UI callers. */
    @Volatile
    private var client: Mqtt5AsyncClient? = null

    private val listeners = CopyOnWriteArrayList<(String, String) -> Unit>()

    private val subscriptions = LinkedHashSet<String>()

    /** Broker-confirmed topics (SUBACK received) while connected; drives the cards' status dots. */
    private val _activeSubscriptions = MutableStateFlow<Set<String>>(emptySet())
    val activeSubscriptions: StateFlow<Set<String>> = _activeSubscriptions.asStateFlow()

    val stateHistory = MqttStateHistory(appContext)

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    /**
     * Incremented per connection attempt so results of superseded attempts are ignored.
     * Atomic: increments arrive from both dispatcher coroutines and netty listener threads,
     * so a plain volatile ++ could mint duplicate generations.
     */
    private val connectGeneration = AtomicInteger(0)

    /** Ensures overlapping [ensureConnected] kicks collapse into one rebuild. */
    private val rebuildInFlight = AtomicBoolean(false)

    /** Consecutive failed reconnects; grows the self-heal delay, resets on success. */
    private val reconnectAttempts = AtomicInteger(0)

    /**
     * Connects with saved settings; false when unconfigured or the handshake failed.
     * Suspending instead of callback-based so coroutine callers get the outcome inline.
     */
    suspend fun connect(): Boolean {
        val settings = DeviceStorage.loadSettings(appContext)
        if (settings.brokerIp.isBlank()) {
            AppLog.w(TAG, "No broker configured, skipping connect")
            return false
        }
        return connectOnce(settings)
    }

    /**
     * Foreground recovery. Once Android cuts a backgrounded app's socket, the broker keeps
     * rejecting that client identity with NOT_AUTHORIZED, so same-id auto-reconnect can
     * never succeed: recovery means a fresh id via [connect]. Connecting skips an
     * in-flight manual attempt; the flag collapses near-simultaneous kicks and is released
     * in finally so a superseded or throwing attempt can never latch it.
     */
    fun ensureConnected() {
        val canRebuild =
            _connectionState.value != ConnectionState.Connected &&
                _connectionState.value != ConnectionState.Connecting
        if (canRebuild && rebuildInFlight.compareAndSet(false, true)) {
            scope.launch {
                try {
                    runCatching { connect() }
                } finally {
                    rebuildInFlight.set(false)
                }
            }
        }
    }

    /** Probe outcome: success flag plus the transport failure reason for the UI. */
    data class ConnectionTest(
        val success: Boolean,
        val error: String? = null,
    )

    /** Probes connectivity with a throwaway client; the active session stays untouched. */
    suspend fun testConnection(settings: MqttSettings): ConnectionTest {
        if (settings.brokerIp.isBlank()) return ConnectionTest(success = false)

        val testClient =
            buildClient(
                settings,
                clientIdSuffix = "test-" + UUID.randomUUID().toString().substring(0, 4),
            )
        val connect =
            testClient
                .connectWith()
                .cleanStart(true)
                .keepAlive(TEST_KEEP_ALIVE_SECONDS)
                .sessionExpiryInterval(0L)

        if (settings.username.isNotBlank()) {
            connect
                .simpleAuth()
                .username(settings.username)
                .password(settings.password.toByteArray(Charsets.UTF_8))
                .applySimpleAuth()
        }

        val result =
            try {
                connect.send().awaitSettled()
                ConnectionTest(success = true)
            } catch (e: Exception) {
                AppLog.w(TAG, "Test connection failed: ${e.message}")
                ConnectionTest(success = false, error = e.message)
            }
        try {
            testClient.disconnect()
        } catch (_: Exception) {
            // best-effort cleanup of the throwaway client
        }
        return result
    }

    fun subscribe(topic: String) {
        synchronized(subscriptions) {
            if (!subscriptions.add(topic)) return
        }

        val c = client
        if (c == null || _connectionState.value != ConnectionState.Connected) {
            AppLog.d(TAG, "Queued subscription for '$topic' (not connected yet)")
            return
        }

        doSubscribe(c, topic)
    }

    fun unsubscribe(topic: String) {
        val removed = synchronized(subscriptions) { subscriptions.remove(topic) }
        if (!removed) return

        val c = client
        if (c == null || _connectionState.value != ConnectionState.Connected) {
            AppLog.d(TAG, "Removed queued subscription for '$topic' (not connected)")
            return
        }

        c
            .unsubscribeWith()
            .topicFilter(topic)
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    AppLog.e(TAG, "Failed to unsubscribe from '$topic': ${throwable.message}")
                } else {
                    AppLog.d(TAG, "Unsubscribed from '$topic'")
                    _activeSubscriptions.update { it - topic }
                }
            }
    }

    /**
     * Publishes at QoS 1 and reports delivery; false when offline or the broker
     * rejected the publish. Suspends until the broker acks (or it fails).
     */
    suspend fun publish(
        topic: String,
        payload: String,
    ): Boolean {
        val c = client
        if (c == null || _connectionState.value != ConnectionState.Connected) {
            AppLog.w(TAG, "Cannot publish to '$topic' - not connected")
            return false
        }

        return try {
            c
                .publishWith()
                .topic(topic)
                .payload(payload.toByteArray(Charsets.UTF_8))
                .qos(MqttQos.AT_LEAST_ONCE)
                .send()
                .awaitSettled()
            AppLog.d(TAG, "Published to '$topic'")
            true
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to publish to '$topic': ${e.message}")
            false
        }
    }

    fun refreshDeviceState(device: Device) {
        device.stateTopic?.let { refreshStateTopic(it) }
    }

    private fun refreshStateTopic(topic: String) {
        scope.launch { publish("$topic/get", MqttPayloads.STATE_GET_PAYLOAD) }
    }

    /**
     * Listeners fire synchronously on HiveMQ's network threads, possibly concurrently.
     * Implementations must be main-safe and thread-safe (e.g. StateFlow.update only).
     */
    fun addStateListener(listener: (String, String) -> Unit) {
        listeners.add(listener)
    }

    fun removeStateListener(listener: (String, String) -> Unit) {
        listeners.remove(listener)
    }

    private fun disconnect() {
        val c = client ?: return
        client = null
        _connectionState.value = ConnectionState.Disconnected
        try {
            c.disconnect().whenComplete { _, throwable ->
                if (throwable != null) {
                    AppLog.w(TAG, "Error during disconnect: ${throwable.message}")
                }
            }
        } catch (e: Exception) {
            AppLog.w(TAG, "Error during disconnect: ${e.message}")
        }
    }

    /**
     * One connection attempt. A superseded attempt reports false without touching state
     * (the newer attempt owns the session); a failed own attempt tears down and self-heals.
     */
    private suspend fun connectOnce(settings: MqttSettings): Boolean {
        stateHistory.setCapacityAndRestore(MqttSettings.clampStateRetention(settings.stateRetention))
        disconnect()

        _connectionState.value = ConnectionState.Connecting
        AppLog.d(TAG, "Connecting to ${settings.brokerIp}:${settings.port} (tls=${settings.useTls})")

        val newClient = buildClient(settings)

        try {
            newClient.publishes(MqttGlobalPublishFilter.ALL) { publish ->
                val topic = publish.topic.toString()
                val payload = String(publish.payloadAsBytes, Charsets.UTF_8)
                AppLog.d(TAG, "Received '$topic': $payload")
                dispatchMessage(topic, payload)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Failed to register publish listener", e)
        }

        client = newClient

        val connect =
            newClient
                .connectWith()
                .cleanStart(true)
                .keepAlive(KEEP_ALIVE_SECONDS)
                .sessionExpiryInterval(0L)

        if (settings.username.isNotBlank()) {
            connect
                .simpleAuth()
                .username(settings.username)
                .password(settings.password.toByteArray(Charsets.UTF_8))
                .applySimpleAuth()
        }

        val generation = connectGeneration.incrementAndGet()

        val handshakeOk =
            try {
                connect.send().awaitSettled()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e(TAG, "MQTT connection failed: ${e.message}", e)
                false
            }

        return when {
            generation != connectGeneration.get() -> {
                // Superseded mid-flight: the newer attempt owns both session and recovery.
                AppLog.d(TAG, "Ignoring result of superseded connection attempt")
                false
            }

            handshakeOk -> {
                AppLog.i(TAG, "MQTT connection established")
                true
            }

            else -> {
                disconnect()
                scheduleReconnect()
                false
            }
        }
    }

    private fun buildClient(
        settings: MqttSettings,
        clientIdSuffix: String = UUID.randomUUID().toString().substring(0, 8),
    ): Mqtt5AsyncClient {
        val builder =
            MqttClient
                .builder()
                .useMqttVersion5()
                .identifier(CLIENT_ID_PREFIX + clientIdSuffix)
                .serverHost(settings.brokerIp)
                .serverPort(settings.port)

        if (settings.useTls) {
            // Hostname verification guards against MITM when the broker cert is issued to a different host.
            builder.sslConfig(
                MqttClientSslConfig
                    .builder()
                    .hostnameVerifier(HttpsURLConnection.getDefaultHostnameVerifier())
                    .build(),
            )
        }

        var self: Mqtt5AsyncClient? = null

        // Retired clients' events are ignored so teardown never flips state or reschedules.
        builder.addConnectedListener {
            val me = self
            if (me == null || client !== me) return@addConnectedListener
            reconnectAttempts.set(0)
            _connectionState.value = ConnectionState.Connected
            AppLog.i(TAG, "MQTT connected")
            resubscribeAll()
            subscribeDeviceTopicsAndRefreshStates()
        }

        builder.addDisconnectedListener { context ->
            val me = self
            if (me == null || client !== me) return@addDisconnectedListener
            _connectionState.value = ConnectionState.Disconnected
            _activeSubscriptions.value = emptySet()
            AppLog.w(TAG, "MQTT disconnected: ${context.cause?.message ?: "unknown reason"}")
            scheduleReconnect()
        }

        // No automaticReconnect: brokers reject a frozen client's stale id with
        // NOT_AUTHORIZED forever, so self-healing always means a fresh rebuild below.
        // Events fire only once connect() starts, after [self] is assigned here.
        return builder.buildAsync().also { self = it }
    }

    /** Unattended drops self-heal with a fresh client id after an exponentially capped delay. */
    private fun scheduleReconnect() {
        val gen = connectGeneration.incrementAndGet()
        scope.launch {
            delay(reconnectBackoffMs())
            if (gen != connectGeneration.get()) return@launch
            val settings = DeviceStorage.loadSettings(appContext)
            if (settings.brokerIp.isBlank()) return@launch
            connectOnce(settings)
        }
    }

    private fun reconnectBackoffMs(): Long {
        val attempts = reconnectAttempts.incrementAndGet()
        val shift = minOf(attempts - 1, BACKOFF_MAX_SHIFTS)
        return minOf(RECONNECT_MAX_BACKOFF_MS, RECONNECT_BASE_BACKOFF_MS shl shift)
    }

    private fun doSubscribe(
        c: Mqtt5AsyncClient,
        topic: String,
    ) {
        c
            .subscribeWith()
            .topicFilter(topic)
            .qos(MqttQos.AT_LEAST_ONCE)
            .send()
            .whenComplete { _, throwable ->
                if (throwable != null) {
                    AppLog.e(TAG, "Failed to subscribe to '$topic': ${throwable.message}")
                    _activeSubscriptions.update { it - topic }
                } else {
                    AppLog.d(TAG, "Subscribed to '$topic'")
                    _activeSubscriptions.update { it + topic }
                }
            }
    }

    private fun resubscribeAll() {
        val c = client ?: return
        val topics = synchronized(subscriptions) { subscriptions.toList() }
        topics.forEach { doSubscribe(c, it) }
    }

    /** Subscribes each distinct device state topic once and requests fresh state after connect. */
    private fun subscribeDeviceTopicsAndRefreshStates() {
        scope.launch {
            DeviceStorage
                .loadDevices(appContext)
                .mapNotNull { it.stateTopic }
                .distinct()
                .forEach { topic ->
                    subscribe(topic)
                    refreshStateTopic(topic)
                }
        }
    }

    fun latestState(topic: String): String? = stateHistory.latest(topic)

    fun stateHistory(topic: String): List<String> = stateHistory.history(topic)

    private fun dispatchMessage(
        topic: String,
        payload: String,
    ) {
        val tracked = synchronized(subscriptions) { topic in subscriptions }
        if (tracked) {
            stateHistory.record(topic, payload)
        }
        listeners.forEach { listener ->
            try {
                listener(topic, payload)
            } catch (e: Exception) {
                AppLog.e(TAG, "Error in state listener for '$topic'", e)
            }
        }
    }

    companion object {
        private const val TAG = "MqttManager"
        private const val CLIENT_ID_PREFIX = "homecommand-"
        private const val KEEP_ALIVE_SECONDS = 60
        private const val TEST_KEEP_ALIVE_SECONDS = 10
        private const val RECONNECT_BASE_BACKOFF_MS = 1_000L
        private const val RECONNECT_MAX_BACKOFF_MS = 30_000L
        private const val BACKOFF_MAX_SHIFTS = 5

        @Volatile
        private var instance: MqttManager? = null

        fun get(context: Context): MqttManager =
            instance ?: synchronized(this) {
                instance ?: MqttManager(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * File-private so the manager stays within detekt's per-class function budget. Settles a
 * HiveMQ CompletionStage; coroutine cancellation abandons only the await, never the
 * underlying network operation.
 */
private suspend fun CompletionStage<*>.awaitSettled() {
    suspendCancellableCoroutine<Unit> { continuation ->
        whenComplete { _, throwable ->
            if (throwable == null) {
                continuation.resume(Unit)
            } else {
                continuation.resumeWithException(throwable)
            }
        }
    }
}
