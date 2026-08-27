package net.elad.homecommand.ui.settings

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
import kotlinx.coroutines.launch
import net.elad.homecommand.data.DeviceStorage
import net.elad.homecommand.data.MqttSettings
import net.elad.homecommand.mqtt.ConnectionState
import net.elad.homecommand.mqtt.MqttManager

/**
 * Single source of truth for MQTT settings, shared by the settings hub and both editor
 * sub-screens. Process-scoped singleton like [net.elad.homecommand.ui.home.HomeViewModel],
 * so a save in a sub-screen is visible to MainActivity immediately.
 */
class SettingsViewModel private constructor(
    application: Application,
) : AndroidViewModel(application) {
    private val mqtt = MqttManager.get(application)

    val connectionState: StateFlow<ConnectionState> = mqtt.connectionState

    private val _settings = MutableStateFlow<MqttSettings?>(null)
    val settings: StateFlow<MqttSettings?> = _settings.asStateFlow()

    private val _testInProgress = MutableStateFlow(false)
    val testInProgress: StateFlow<Boolean> = _testInProgress.asStateFlow()

    /** One-shot save results for the UI: true = persisted, false = storage/crypto failure. */
    private val _saveOutcomes = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    val saveOutcomes: SharedFlow<Boolean> = _saveOutcomes.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { _settings.value = DeviceStorage.loadSettings(getApplication()) }
        }
    }

    /** Persists [settings], publishes it, then reconnects so the new config takes effect. */
    fun saveSettings(settings: MqttSettings) {
        viewModelScope.launch {
            val saved =
                runCatching {
                    val ok = DeviceStorage.saveSettings(getApplication(), settings)
                    if (ok) {
                        _settings.value = settings
                        mqtt.connect()
                    }
                    ok
                }.getOrDefault(false)
            _saveOutcomes.tryEmit(saved)
        }
    }

    fun testConnection(
        settings: MqttSettings,
        onResult: (MqttManager.ConnectionTest) -> Unit,
    ) {
        if (_testInProgress.value) return
        _testInProgress.value = true
        viewModelScope.launch {
            val outcome =
                runCatching { mqtt.testConnection(settings) }
                    .getOrElse { MqttManager.ConnectionTest(success = false, error = it.message) }
            _testInProgress.value = false
            onResult(outcome)
        }
    }

    companion object {
        @Volatile
        private var instance: SettingsViewModel? = null

        /**
         * Process-lifetime instance: onCleared never runs, mirroring HomeViewModel so every
         * screen reads the same cached settings without reload races.
         */
        fun get(context: Context): SettingsViewModel =
            instance ?: synchronized(this) {
                instance ?: SettingsViewModel(context.applicationContext as Application).also { instance = it }
            }
    }
}
