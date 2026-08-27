package net.elad.homecommand.data

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Persistence for MQTT settings, rooms and devices; all I/O on [Dispatchers.IO]. */
object DeviceStorage {
    private const val PREFS_NAME = "my_automations"
    private const val KEY_SETTINGS = "mqtt_settings"

    // v2 keys: the predefined-type rewrite intentionally drops pre-rooms data.
    private const val KEY_DEVICES = "devices_v2"
    private const val KEY_ROOMS = "rooms"

    private val gson = Gson()
    private val deviceListType = object : TypeToken<List<Device>>() {}.type
    private val roomListType = object : TypeToken<List<Room>>() {}.type

    /** Marks encrypted passwords so legacy plaintext is detected and migrated on next save. */
    private const val ENCRYPTED_PREFIX = "enc:v1:"

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Returns false when encryption failed (e.g. KeyStore unavailable); nothing is written,
     * so a plaintext password can never silently land on disk.
     */
    suspend fun saveSettings(
        context: Context,
        settings: MqttSettings,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val secured = encryptPassword(settings.password) ?: return@withContext false
            prefs(context)
                .edit()
                .putString(KEY_SETTINGS, gson.toJson(settings.copy(password = secured)))
                .apply()
            true
        }

    suspend fun loadSettings(context: Context): MqttSettings =
        withContext(Dispatchers.IO) {
            val settings =
                prefs(context)
                    .getString(KEY_SETTINGS, null)
                    ?.let { json -> gson.fromJson(json, MqttSettings::class.java) }
                    ?: return@withContext MqttSettings()
            settings.copy(password = decryptPassword(settings.password))
        }

    suspend fun saveDevices(
        context: Context,
        devices: List<Device>,
    ): Unit =
        withContext(Dispatchers.IO) {
            prefs(context).edit().putString(KEY_DEVICES, gson.toJson(devices)).apply()
        }

    suspend fun loadDevices(context: Context): List<Device> =
        withContext(Dispatchers.IO) {
            prefs(context)
                .getString(KEY_DEVICES, null)
                ?.let { json -> gson.fromJson<List<Device>>(json, deviceListType) }
                .orEmpty()
                // Legacy guard: entries must at least be subscribable or controllable, and have valid type.
                .filter { device -> device.type != null && (!device.commandTopic.isNullOrBlank() || device.stateTopic != null) }
        }

    suspend fun saveRooms(
        context: Context,
        rooms: List<Room>,
    ): Unit =
        withContext(Dispatchers.IO) {
            prefs(context).edit().putString(KEY_ROOMS, gson.toJson(rooms)).apply()
        }

    suspend fun loadRooms(context: Context): List<Room> =
        withContext(Dispatchers.IO) {
            prefs(context)
                .getString(KEY_ROOMS, null)
                ?.let { json -> gson.fromJson<List<Room>>(json, roomListType) }
                .orEmpty()
        }

    /** Null when KeyStore encryption fails so the caller can skip persisting. */
    private fun encryptPassword(password: String): String? =
        when {
            password.isEmpty() -> ""
            else -> CryptoManager.encryptOrNull(password)?.let { ENCRYPTED_PREFIX + it }
        }

    /** Legacy plaintext values are returned as-is; they get encrypted on the next save. */
    private fun decryptPassword(stored: String): String {
        if (!stored.startsWith(ENCRYPTED_PREFIX)) return stored
        val encrypted = stored.removePrefix(ENCRYPTED_PREFIX)
        return CryptoManager.decryptOrNull(encrypted) ?: ""
    }
}
