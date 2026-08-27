package net.elad.homecommand.ui.home

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.elad.homecommand.data.AppLog
import net.elad.homecommand.data.Device
import net.elad.homecommand.data.DeviceStorage
import net.elad.homecommand.data.DeviceType
import net.elad.homecommand.data.Room
import net.elad.homecommand.data.StateCacheStorage
import net.elad.homecommand.mqtt.ConnectionState
import net.elad.homecommand.mqtt.DeviceStateReader
import net.elad.homecommand.mqtt.MqttManager
import net.elad.homecommand.mqtt.MqttPayloads
import net.elad.homecommand.mqtt.readDeviceFields
import net.elad.homecommand.mqtt.stateSummary
import net.elad.homecommand.notification.NotificationHelper

/**
 * Single source of truth for rooms, devices and their live MQTT states.
 * Process-scoped singleton so every activity sees the same data, mirroring [MqttManager].
 */
class HomeViewModel private constructor(
    application: Application,
) : AndroidViewModel(application) {
    private val mqtt = MqttManager.get(application)

    val connectionState: StateFlow<ConnectionState> = mqtt.connectionState

    private val _rooms = MutableStateFlow<List<Room>>(emptyList())
    val rooms: StateFlow<List<Room>> = _rooms.asStateFlow()

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()

    /** False until the first [refresh] completes; screens must not treat empty lists as authoritative before. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val deviceStatePayloads = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Live topic->payload map; screens diff consecutive values to know what to re-render. */
    val states: StateFlow<Map<String, String>> = deviceStatePayloads.asStateFlow()

    /** A command that never reached the broker (read-only device or offline); UI shows a snackbar. */
    data class CommandFailure(
        val deviceName: String,
    )

    private val _commandFailures = MutableSharedFlow<CommandFailure>(extraBufferCapacity = 1)
    val commandFailures: SharedFlow<CommandFailure> = _commandFailures.asSharedFlow()

    /** Tracks last-notified payload per topic to avoid duplicate notifications. */
    private val lastNotifiedPayloads = mutableMapOf<String, String>()

    private val stateListener: (String, String) -> Unit =
        { topic, payload ->
            deviceStatePayloads.update { current -> current + (topic to payload) }
            checkAndNotify(topic, payload)
        }

    init {
        mqtt.addStateListener(stateListener)
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                _rooms.value = DeviceStorage.loadRooms(getApplication())
                val devices = DeviceStorage.loadDevices(getApplication())
                _devices.value = devices
                batteryAlertThreshold = DeviceStorage.loadSettings(getApplication()).batteryAlertThreshold
                seedCachedStates(devices)
                _loaded.value = true
            }.onFailure { AppLog.e(TAG, "Failed to load stored data", it) }
        }
    }

    /** Shows each tile's last known value immediately, before MQTT delivers anything fresh. */
    private suspend fun seedCachedStates(devices: List<Device>) {
        val stored = StateCacheStorage.load(getApplication())
        val seeds =
            devices
                .mapNotNull { device ->
                    device.stateTopic?.let { topic ->
                        (mqtt.latestState(topic) ?: stored[topic]?.lastOrNull())?.let { topic to it }
                    }
                }.toMap()
        deviceStatePayloads.update { current -> current + seeds }
    }

    private fun checkAndNotify(
        topic: String,
        payload: String,
    ) {
        if (lastNotifiedPayloads[topic] == payload) return
        lastNotifiedPayloads[topic] = payload

        val devicesForTopic = _devices.value.filter { it.stateTopic == topic && it.notifyOnStateChange }
        if (devicesForTopic.isEmpty()) return

        val app = getApplication<Application>()
        for (device in devicesForTopic) {
            val text = readDeviceFields(payload).stateSummary()
            if (text != null) {
                NotificationHelper.notifyStateChanged(app, device, text)
            }
        }

        checkBatteryAlerts(topic, payload)
    }

    /** Tracks last-notified battery level per device to avoid spam. */
    private val lastNotifiedBattery = mutableMapOf<String, Int>()

    /** Cached from settings on each [refresh]; read from non-suspend callbacks. */
    @Volatile private var batteryAlertThreshold: Int? = null

    private fun checkBatteryAlerts(
        topic: String,
        payload: String,
    ) {
        val threshold = batteryAlertThreshold ?: return

        val app = getApplication<Application>()
        for (device in _devices.value.filter { it.stateTopic == topic }) {
            val battery = DeviceStateReader.battery(payload) ?: continue
            val last = lastNotifiedBattery[device.id]
            val crossingThreshold = (last == null || last > threshold) && battery <= threshold
            if (crossingThreshold) {
                NotificationHelper.notifyBatteryAlert(app, device, battery)
            }
            lastNotifiedBattery[device.id] = battery
        }
    }

    fun saveRoom(name: String): Room {
        val room = Room(name = name.trim())
        _rooms.value = _rooms.value + room
        persistRooms()
        return room
    }

    fun renameRoom(
        room: Room,
        newName: String,
    ) {
        if (newName.isBlank()) return
        _rooms.value = _rooms.value.map { if (it.id == room.id) room.copy(name = newName.trim()) else it }
        persistRooms()
    }

    /** Deleting a room leaves its devices in the app; they fall back to the unassigned section. */
    fun deleteRoom(room: Room) {
        _rooms.value = _rooms.value.filter { it.id != room.id }
        _devices.value = _devices.value.map { if (it.roomId == room.id) it.copy(roomId = null) else it }
        persistRooms()
        persistDevices()
    }

    /** Adds [device], or updates it when a device with the same id exists. */
    fun saveDevice(device: Device): Boolean {
        val existing = _devices.value.firstOrNull { it.id == device.id }
        _devices.value =
            if (existing == null) {
                _devices.value + device
            } else {
                _devices.value.map { if (it.id == device.id) device else it }
            }
        persistDevices()

        if (existing == null || existing.stateTopic != device.stateTopic) {
            existing?.stateTopic?.let { mqtt.unsubscribe(it) }
            device.stateTopic?.let { topic ->
                mqtt.subscribe(topic)
                mqtt.refreshDeviceState(device)
            }
        }
        return existing == null
    }

    fun deleteDevice(device: Device) {
        // Only an unlocked plug has an actuator worth switching off before removal.
        device
            .commandTopic
            ?.takeIf { device.type == DeviceType.SMART_PLUG && !device.readOnly }
            ?.let { topic ->
                viewModelScope.launch {
                    runCatching { mqtt.publish(topic, MqttPayloads.STATE_OFF_PAYLOAD) }
                }
            }
        device.stateTopic?.let { mqtt.unsubscribe(it) }

        _devices.value = _devices.value.filter { it.id != device.id }
        persistDevices()
    }

    /**
     * Publishes [payload] to the device's command topic. Read-only devices, read-only
     * topics and offline publishes surface as [commandFailures] instead of failing silently.
     */
    fun sendCommand(
        device: Device,
        payload: String,
    ) {
        val topic = device.commandTopic
        if (topic == null || device.readOnly) {
            _commandFailures.tryEmit(CommandFailure(device.name))
            return
        }
        viewModelScope.launch {
            if (!mqtt.publish(topic, payload)) {
                _commandFailures.tryEmit(CommandFailure(device.name))
            }
        }
    }

    /** Stores a named IR replay command on the device. */
    fun saveIrCommand(
        device: Device,
        name: String,
        code: String,
    ) {
        updateDevice(device.copy(irCommands = (device.irCommands ?: emptyMap()) + (name.trim() to code)))
    }

    fun deleteIrCommand(
        device: Device,
        name: String,
    ) {
        updateDevice(device.copy(irCommands = device.irCommands?.minus(name)))
    }

    private fun updateDevice(updated: Device) {
        _devices.value = _devices.value.map { if (it.id == updated.id) updated else it }
        persistDevices()
    }

    /** Reorders devices within a room: [orderedIds] gives the new top-to-bottom order. */
    fun reorderDevices(orderedIds: List<String>) {
        val idToIndex = orderedIds.withIndex().associate { (i, id) -> id to i }
        _devices.value =
            _devices.value.map { device ->
                idToIndex[device.id]?.let { device.copy(position = it) } ?: device
            }
        persistDevices()
    }

    /** Reorders rooms on the home screen: [orderedIds] gives the new top-to-bottom order. */
    fun reorderRooms(orderedIds: List<String>) {
        val idToIndex = orderedIds.withIndex().associate { (i, id) -> id to i }
        _rooms.value =
            _rooms.value.map { room ->
                idToIndex[room.id]?.let { room.copy(position = it) } ?: room
            }
        persistRooms()
    }

    fun getDeviceState(topic: String?): String? = topic?.let { deviceStatePayloads.value[it] }

    private fun persistRooms() {
        viewModelScope.launch {
            // Persistence failures are logged, not fatal; the in-memory state stays authoritative.
            runCatching { DeviceStorage.saveRooms(getApplication(), _rooms.value) }
                .onFailure { AppLog.e(TAG, "Failed to save rooms", it) }
        }
    }

    private fun persistDevices() {
        viewModelScope.launch {
            runCatching { DeviceStorage.saveDevices(getApplication(), _devices.value) }
                .onFailure { AppLog.e(TAG, "Failed to save devices", it) }
        }
    }

    companion object {
        private const val TAG = "HomeViewModel"

        @Volatile
        private var instance: HomeViewModel? = null

        /**
         * Process-lifetime instance: onCleared never runs, so the MQTT state listener and
         * viewModelScope intentionally live until process death.
         */
        fun get(context: Context): HomeViewModel =
            instance ?: synchronized(this) {
                instance ?: HomeViewModel(context.applicationContext as Application).also { instance = it }
            }
    }
}
