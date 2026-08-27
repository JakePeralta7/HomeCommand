package net.elad.homecommand.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.elad.homecommand.R
import net.elad.homecommand.data.MqttSettings
import net.elad.homecommand.mqtt.ConnectionState
import net.elad.homecommand.ui.widgets.BreadcrumbBarView
import net.elad.homecommand.ui.widgets.applySubScreenMotion
import net.elad.homecommand.ui.widgets.installNavigationDrawer
import net.elad.homecommand.ui.widgets.installSubScreenChrome
import java.net.InetAddress

class MqttConnectionActivity : AppCompatActivity() {
    private val viewModel: SettingsViewModel by lazy { SettingsViewModel.get(this) }

    private var pendingAction: (suspend () -> Unit)? = null

    private val localNetworkPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                pendingAction?.let { action ->
                    lifecycleScope.launch { action() }
                }
            } else {
                Toast.makeText(this, R.string.local_network_permission_needed, Toast.LENGTH_LONG).show()
            }
            pendingAction = null
        }

    private lateinit var editBrokerIp: TextInputEditText
    private lateinit var editPort: TextInputEditText
    private lateinit var editUsername: TextInputEditText
    private lateinit var editPassword: TextInputEditText
    private lateinit var switchTls: SwitchMaterial
    private lateinit var editTopicBase: TextInputEditText
    private lateinit var btnTest: MaterialButton
    private lateinit var btnSave: MaterialButton
    private lateinit var dotConnectionStatus: View
    private lateinit var textConnectionStatus: MaterialTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mqtt_connection)
        bindViews()

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        installSubScreenChrome(root = findViewById(R.id.root))
        applySubScreenMotion(R.id.root)
        installNavigationDrawer()
        findViewById<BreadcrumbBarView>(R.id.breadcrumb).setPath(
            BreadcrumbBarView.Crumb(getString(R.string.tab_settings)) { finish() },
            BreadcrumbBarView.Crumb(getString(R.string.settings_mqtt_connection)),
        )

        // Apply dynamic bottom insets for navigation bar awareness to ScrollView content
        val scrollContent = findViewById<LinearLayout>(R.id.scroll_content)
        val scrollContentInitialPadding = scrollContent.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(scrollContent) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            scrollContent.setPadding(
                scrollContent.paddingLeft,
                scrollContent.paddingTop,
                scrollContent.paddingRight,
                scrollContentInitialPadding + bars.bottom
            )
            insets
        }

        // No toast here: the save is async; feedback arrives via saveOutcomes below.
        btnSave.setOnClickListener {
            val loaded = viewModel.settings.value ?: return@setOnClickListener
            lifecycleScope.launch {
                val settings = readConnectionSettings(loaded)
                requestLocalNetworkPermissionIfNeeded(settings.brokerIp) {
                    warnIfPlaintextRemote(settings)
                    viewModel.saveSettings(settings)
                }
            }
        }

        btnTest.setOnClickListener {
            val loaded = viewModel.settings.value ?: return@setOnClickListener
            val settings = readConnectionSettings(loaded)
            if (settings.brokerIp.isBlank()) {
                editBrokerIp.error = getString(R.string.required)
                return@setOnClickListener
            }

            lifecycleScope.launch {
                requestLocalNetworkPermissionIfNeeded(settings.brokerIp) {
                    viewModel.testConnection(settings) { outcome ->
                        val message =
                            if (outcome.success) {
                                getString(R.string.connection_success)
                            } else {
                                listOfNotNull(getString(R.string.connection_failed), outcome.error).joinToString(": ")
                            }
                        Toast.makeText(this@MqttConnectionActivity, message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        observeViewModel()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun bindViews() {
        editBrokerIp = findViewById(R.id.edit_broker_ip)
        editPort = findViewById(R.id.edit_port)
        editUsername = findViewById(R.id.edit_username)
        editPassword = findViewById(R.id.edit_password)
        switchTls = findViewById(R.id.switch_tls)
        editTopicBase = findViewById(R.id.edit_topic_base)
        btnTest = findViewById(R.id.btn_test_connection)
        btnSave = findViewById(R.id.btn_save)
        dotConnectionStatus = findViewById(R.id.dot_connection_status)
        textConnectionStatus = findViewById(R.id.text_connection_status)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.settings.collect { settings ->
                        settings?.let(::populate)
                    }
                }
                launch {
                    viewModel.connectionState.collect(::renderConnection)
                }
                launch {
                    viewModel.testInProgress.collect(::renderTesting)
                }
                launch {
                    viewModel.saveOutcomes.collect { saved ->
                        val msgRes = if (saved) R.string.settings_saved else R.string.settings_save_failed
                        Toast.makeText(this@MqttConnectionActivity, msgRes, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    /** Overwrites only connection-owned fields; storage/General settings stay untouched. */
    private fun readConnectionSettings(loaded: MqttSettings): MqttSettings =
        loaded.copy(
            brokerIp = editBrokerIp.text.toString().trim(),
            port = readPort(),
            username = editUsername.text.toString().trim(),
            password = editPassword.text.toString(),
            useTls = switchTls.isChecked,
            topicBase =
                editTopicBase.text
                    .toString()
                    .trim()
                    .ifBlank { null },
        )

    /** Out-of-range or non-numeric input falls back to the default instead of breaking connect. */
    private fun readPort(): Int {
        val parsed = editPort.text.toString().toIntOrNull()
        return if (parsed != null && parsed in MIN_PORT..MAX_PORT) parsed else MqttSettings.DEFAULT_PORT
    }

    /**
     * Cleartext to a non-private host exposes the broker password beyond the LAN.
     * networkSecurityConfig cannot scope cleartext by IP range, so surface the risk here.
     */
    private suspend fun warnIfPlaintextRemote(settings: MqttSettings) {
        if (settings.useTls || isLocalBroker(settings.brokerIp)) return
        Toast.makeText(this, R.string.plaintext_remote_warning, Toast.LENGTH_LONG).show()
    }

    private suspend fun isLocalBroker(host: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val address = InetAddress.getByName(host)
                address.isSiteLocalAddress || address.isLoopbackAddress
            }.getOrDefault(false)
        }

    private fun populate(settings: MqttSettings) {
        editBrokerIp.setText(settings.brokerIp)
        editPort.setText(settings.port.toString())
        editUsername.setText(settings.username)
        editPassword.setText(settings.password)
        switchTls.isChecked = settings.useTls
        editTopicBase.setText(settings.effectiveTopicBase())
    }

    private fun renderConnection(state: ConnectionState) {
        textConnectionStatus.setText(state.labelRes)
        dotConnectionStatus.background.setTint(
            ContextCompat.getColor(dotConnectionStatus.context, state.dotColorRes),
        )
        dotConnectionStatus.contentDescription = getString(state.labelRes)
    }

    private fun renderTesting(testing: Boolean) {
        btnTest.isEnabled = !testing
        btnTest.setText(if (testing) R.string.testing_connection else R.string.test_connection)
    }

    /**
     * If the broker address resolves to a LAN/loopback address and the permission
     * is not yet granted, request it and defer [action] until the user responds.
     * Public/unknown addresses skip the check entirely (least privilege).
     */
    private suspend fun requestLocalNetworkPermissionIfNeeded(
        brokerHost: String,
        action: suspend () -> Unit,
    ) {
        if (isLocalBroker(brokerHost) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingAction = action
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            action()
        }
    }

    private companion object {
        const val MIN_PORT = 1
        const val MAX_PORT = 65535
    }
}
