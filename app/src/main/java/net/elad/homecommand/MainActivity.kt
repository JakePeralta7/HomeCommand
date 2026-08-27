package net.elad.homecommand

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import net.elad.homecommand.mqtt.MqttManager
import net.elad.homecommand.notification.NotificationHelper
import net.elad.homecommand.ui.logs.LogsActivity
import net.elad.homecommand.ui.rooms.RoomsFragment
import net.elad.homecommand.ui.settings.SettingsActivity
import net.elad.homecommand.ui.widgets.BreadcrumbBarView
import net.elad.homecommand.ui.widgets.applyHostScreenMotion

class MainActivity : AppCompatActivity() {
    private val notificationPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (!granted) {
                Toast.makeText(this, R.string.notification_permission_needed, Toast.LENGTH_LONG).show()
            }
        }

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var breadcrumb: BreadcrumbBarView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_menu)

        drawerLayout = findViewById(R.id.drawer_layout)
        val toggle =
            androidx.appcompat.app.ActionBarDrawerToggle(
                this,
                drawerLayout,
                findViewById(R.id.toolbar),
                R.string.drawer_open,
                R.string.drawer_close,
            )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        applySystemBarInsets()
        applyHostScreenMotion(R.id.fragment_container)

        NotificationHelper.createChannels(this)
        requestNotificationPermission()

        breadcrumb = findViewById(R.id.breadcrumb)
        showCrumb(R.string.tab_rooms)

        val navDrawer = findViewById<NavigationView>(R.id.nav_drawer)

        // Add drawer header with app name and version
        val headerView = layoutInflater.inflate(R.layout.drawer_header, navDrawer, false)
        val versionText = headerView.findViewById<TextView>(R.id.drawer_app_version)
        versionText.text = getString(R.string.drawer_version, BuildConfig.VERSION_NAME)
        navDrawer.addHeaderView(headerView)

        navDrawer.setCheckedItem(R.id.drawer_rooms)
        navDrawer.setNavigationItemSelectedListener { item ->
            drawerLayout.closeDrawer(GravityCompat.START)
            when (item.itemId) {
                R.id.drawer_rooms -> { /* already here */ }

                R.id.drawer_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }

                R.id.drawer_logs -> {
                    startActivity(Intent(this, LogsActivity::class.java))
                }
            }
            true
        }

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .add(R.id.fragment_container, RoomsFragment(), TAG_ROOMS)
                .commit()
        }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START)
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    override fun onStart() {
        super.onStart()
        MqttManager.get(this).ensureConnected()
    }

    private fun applySystemBarInsets() {
        val root = findViewById<View>(R.id.root)
        val rootTop = root.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
            root.updatePadding(top = rootTop + maxOf(bars.top, cutout.top))
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun showCrumb(labelRes: Int) {
        breadcrumb.setPath(BreadcrumbBarView.Crumb(getString(labelRes)))
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    companion object {
        private const val TAG_ROOMS = "rooms_fragment"
    }
}
