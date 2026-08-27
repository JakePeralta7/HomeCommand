package net.elad.homecommand.ui.widgets

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.navigation.NavigationView
import com.google.android.material.transition.platform.MaterialSharedAxis
import net.elad.homecommand.MainActivity
import net.elad.homecommand.R
import net.elad.homecommand.ui.logs.LogsActivity
import net.elad.homecommand.ui.settings.SettingsActivity

/**
 * Shared chrome for standalone sub-screen activities: edge-to-edge insets (toolbar clears the
 * status bar). Back navigation is system back plus tappable ancestor crumbs; no up arrow needed
 * — each sub-screen sets [AppCompatActivity.setDisplayHomeAsUpEnabled].
 */
fun AppCompatActivity.installSubScreenChrome(root: View) {
    // Root padding rather than toolbar padding: Toolbar measures custom children against its own
    // height minus vertical padding, so inflating the toolbar's top pad collapses them to 0.
    val rootTop = root.paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
        root.updatePadding(
            top = rootTop + maxOf(bars.top, cutout.top),
            bottom = bars.bottom,
            left = bars.left,
            right = bars.right
        )
        insets
    }
}

/**
 * Parent-child drill-in: sub-screen slides in forward, slides back out on finish. Platform
 * transition variant because window transitions need framework Transitions, not AndroidX ones.
 */
fun AppCompatActivity.applySubScreenMotion(rootId: Int) {
    window.enterTransition =
        MaterialSharedAxis(MaterialSharedAxis.X, true).apply { addTarget(rootId) }
    window.returnTransition =
        MaterialSharedAxis(MaterialSharedAxis.X, false).apply { addTarget(rootId) }
}

/** Host side of the shared-axis push: content slides out forward and reenters backward. */
fun AppCompatActivity.applyHostScreenMotion(contentId: Int) {
    window.exitTransition =
        MaterialSharedAxis(MaterialSharedAxis.X, true).apply { addTarget(contentId) }
    window.reenterTransition =
        MaterialSharedAxis(MaterialSharedAxis.X, false).apply { addTarget(contentId) }
}

/** Options bundle every parent→child launch must pass or the shared axis never plays. */
fun AppCompatActivity.pushOptions(): Bundle = ActivityOptions.makeSceneTransitionAnimation(this).toBundle()

/**
 * Adds a hamburger menu + navigation drawer to a sub-screen activity. The layout must contain
 * [R.id.drawer_layout], [R.id.nav_drawer] and [R.id.toolbar]. Drawer navigation uses
 * [Intent.FLAG_ACTIVITY_CLEAR_TOP] so the back stack stays shallow.
 */
fun AppCompatActivity.installNavigationDrawer() {
    val drawerLayout = findViewById<DrawerLayout>(R.id.drawer_layout)
    val navDrawer = findViewById<NavigationView>(R.id.nav_drawer)
    val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)

    val toggle =
        ActionBarDrawerToggle(this, drawerLayout, toolbar, R.string.drawer_open, R.string.drawer_close)
    drawerLayout.addDrawerListener(toggle)
    toggle.syncState()

    navDrawer.setNavigationItemSelectedListener { item ->
        drawerLayout.closeDrawer(GravityCompat.START)
        navigateToTopLevel(item.itemId)
        true
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

private fun AppCompatActivity.navigateToTopLevel(itemId: Int) {
    val target =
        when (itemId) {
            R.id.drawer_rooms -> MainActivity::class.java
            R.id.drawer_settings -> SettingsActivity::class.java
            R.id.drawer_logs -> LogsActivity::class.java
            else -> return
        }
    if (this::class.java == target) return
    startActivity(Intent(this, target).setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
    finish()
}
