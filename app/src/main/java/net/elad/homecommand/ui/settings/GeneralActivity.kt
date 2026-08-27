package net.elad.homecommand.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import net.elad.homecommand.R
import net.elad.homecommand.data.MqttSettings
import net.elad.homecommand.ui.widgets.BreadcrumbBarView
import net.elad.homecommand.ui.widgets.applySubScreenMotion
import net.elad.homecommand.ui.widgets.installNavigationDrawer
import net.elad.homecommand.ui.widgets.installSubScreenChrome

class GeneralActivity : AppCompatActivity() {
    private val viewModel: SettingsViewModel by lazy { SettingsViewModel.get(this) }

    private lateinit var editStateRetention: TextInputEditText
    private lateinit var editBatteryThreshold: TextInputEditText
    private lateinit var btnSave: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_general)
        bindViews()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        installSubScreenChrome(root = findViewById(R.id.root))
        applySubScreenMotion(R.id.root)
        installNavigationDrawer()
        findViewById<BreadcrumbBarView>(R.id.breadcrumb).setPath(
            BreadcrumbBarView.Crumb(getString(R.string.tab_settings)) { finish() },
            BreadcrumbBarView.Crumb(getString(R.string.settings_general)),
        )

        btnSave.setOnClickListener {
            val loaded = viewModel.settings.value ?: return@setOnClickListener
            viewModel.saveSettings(
                loaded.copy(
                    stateRetention = readRetention(),
                    batteryAlertThreshold = readBatteryThreshold(),
                ),
            )
        }

        observeViewModel()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindViews() {
        editStateRetention = findViewById(R.id.edit_state_retention)
        editBatteryThreshold = findViewById(R.id.edit_battery_threshold)
        btnSave = findViewById(R.id.btn_save)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.settings.collect { settings ->
                        settings?.let {
                            editStateRetention.setText(it.stateRetention.toString())
                            editBatteryThreshold.setText(it.batteryAlertThreshold?.toString().orEmpty())
                        }
                    }
                }
                launch {
                    viewModel.saveOutcomes.collect { saved ->
                        val msgRes = if (saved) R.string.settings_saved else R.string.settings_save_failed
                        Toast.makeText(this@GeneralActivity, msgRes, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** Overwrites only stateRetention; connection settings stay untouched. */
    private fun readRetention(): Int = MqttSettings.clampStateRetention(editStateRetention.text.toString().toIntOrNull())

    private fun readBatteryThreshold(): Int? {
        val raw = editBatteryThreshold.text.toString().toIntOrNull()
        return raw?.coerceIn(1, 100)
    }
}
