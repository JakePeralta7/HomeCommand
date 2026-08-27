package net.elad.homecommand.ui.settings

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.launch
import net.elad.homecommand.R
import net.elad.homecommand.data.MqttSettings
import net.elad.homecommand.mqtt.ConnectionState
import net.elad.homecommand.ui.widgets.BreadcrumbBarView
import net.elad.homecommand.ui.widgets.applySubScreenMotion
import net.elad.homecommand.ui.widgets.installNavigationDrawer
import net.elad.homecommand.ui.widgets.installSubScreenChrome
import net.elad.homecommand.ui.widgets.pushOptions

class SettingsActivity : AppCompatActivity() {
    private val viewModel: SettingsViewModel by lazy { SettingsViewModel.get(this) }

    private lateinit var entryMqttConnection: MaterialCardView
    private lateinit var entryGeneral: MaterialCardView
    private lateinit var dotHubConnection: View
    private lateinit var textMqttSummary: MaterialTextView
    private lateinit var textGeneralSummary: MaterialTextView

    override fun onCreate(savedInstanceState: Bundle?) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        installSubScreenChrome(root = findViewById(R.id.root))
        applySubScreenMotion(R.id.root)
        installNavigationDrawer()
        findViewById<BreadcrumbBarView>(R.id.breadcrumb).setPath(
            BreadcrumbBarView.Crumb(getString(R.string.tab_settings)),
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

        bindViews()

        entryMqttConnection.setOnClickListener {
            startActivity(Intent(this, MqttConnectionActivity::class.java), pushOptions())
        }
        entryGeneral.setOnClickListener {
            startActivity(Intent(this, GeneralActivity::class.java), pushOptions())
        }

        observeViewModel()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    private fun bindViews() {
        entryMqttConnection = findViewById(R.id.entry_mqtt_connection)
        entryGeneral = findViewById(R.id.entry_general)
        dotHubConnection = findViewById(R.id.dot_hub_connection)
        textMqttSummary = findViewById(R.id.text_mqtt_summary)
        textGeneralSummary = findViewById(R.id.text_general_summary)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.connectionState.collect(::renderConnectionDot)
                }
                launch {
                    viewModel.settings.collect { settings ->
                        settings?.let(::renderSummaries)
                    }
                }
            }
        }
    }

    private fun renderConnectionDot(state: ConnectionState) {
        dotHubConnection.background.setTint(
            ContextCompat.getColor(dotHubConnection.context, state.dotColorRes),
        )
        dotHubConnection.contentDescription = getString(state.labelRes)
    }

    private fun renderSummaries(settings: MqttSettings) {
        textMqttSummary.text =
            if (settings.brokerIp.isBlank()) {
                getString(R.string.settings_not_configured)
            } else {
                getString(R.string.settings_broker_summary_format, settings.brokerIp, settings.port)
            }
        textGeneralSummary.text = getString(R.string.settings_general_summary_format, settings.stateRetention)
    }
}
