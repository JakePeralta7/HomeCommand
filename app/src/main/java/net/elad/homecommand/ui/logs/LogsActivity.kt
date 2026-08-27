package net.elad.homecommand.ui.logs

import android.graphics.Typeface
import android.os.Bundle
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import net.elad.homecommand.R
import net.elad.homecommand.data.AppLog
import net.elad.homecommand.ui.widgets.BreadcrumbBarView
import net.elad.homecommand.ui.widgets.applySubScreenMotion
import net.elad.homecommand.ui.widgets.installNavigationDrawer
import net.elad.homecommand.ui.widgets.installSubScreenChrome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsActivity : AppCompatActivity() {
    private lateinit var adapter: LogAdapter
    private lateinit var textEmpty: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_logs)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        installSubScreenChrome(root = findViewById(R.id.root))
        applySubScreenMotion(R.id.root)
        installNavigationDrawer()
        findViewById<BreadcrumbBarView>(R.id.breadcrumb).setPath(
            BreadcrumbBarView.Crumb(getString(R.string.drawer_logs)) { finish() },
        )

        textEmpty = findViewById(R.id.text_logs_empty)
        adapter = LogAdapter { entry -> showLogDetail(entry) }
        val recycler = findViewById<RecyclerView>(R.id.recycler_logs)
        recycler.layoutManager = LinearLayoutManager(this).apply { reverseLayout = true }
        recycler.adapter = adapter

        observeLogs()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Emit current snapshot first
                val initial = AppLog.snapshot()
                adapter.submitList(initial.reversed())
                textEmpty.visibility = if (initial.isEmpty()) View.VISIBLE else View.GONE

                // Then collect live entries
                AppLog.flow.collect { entry ->
                    val current = adapter.currentList.toMutableList()
                    current.add(0, entry)
                    if (current.size > MAX_DISPLAY) current.removeLast()
                    adapter.submitList(current)
                    textEmpty.visibility = View.GONE
                }
            }
        }
    }

    private fun showLogDetail(entry: AppLog.Entry) {
        val levelRes =
            when (entry.level) {
                AppLog.Level.DEBUG -> R.color.log_debug
                AppLog.Level.INFO -> R.color.log_info
                AppLog.Level.WARN -> R.color.log_warn
                AppLog.Level.ERROR -> R.color.log_error
            }
        val header = "${LEVEL_FORMAT.format(Date(entry.timestamp))}  ${entry.level.name}  ${entry.tag}"

        val messageView =
            TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 13f
                setPadding(48, 32, 48, 32)
                setTextIsSelectable(true)
                text = entry.message
            }

        val scroll =
            ScrollView(this).apply { addView(messageView) }

        MaterialAlertDialogBuilder(this)
            .setTitle(header)
            .setView(scroll)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    companion object {
        private const val MAX_DISPLAY = 200
        private val LEVEL_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    }
}
