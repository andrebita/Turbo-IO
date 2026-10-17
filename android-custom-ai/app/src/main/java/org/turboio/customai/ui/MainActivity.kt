package org.turboio.customai.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.bottomnavigation.BottomNavigationView
import org.turboio.customai.R

class MainActivity : AppCompatActivity() {
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private lateinit var nav: BottomNavigationView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply { id = CONTAINER }
        nav = bottomNav(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        nav.setOnItemSelectedListener { item -> show(tab(item.itemId)); true }
        nav.setOnItemReselectedListener { item -> show(tab(item.itemId)) }
        if (savedInstanceState == null) {
            if (intent?.getStringExtra(EXTRA_TAB) == "journal") nav.selectedItemId = R.id.nav_memory
            else show(HomeFragment())
        }
        requestPermissionsIfNeeded()
    }

    private fun tab(id: Int): Fragment = when (id) {
        R.id.nav_glasses -> ConnectionFragment()
        R.id.nav_memory -> JournalFragment()
        R.id.nav_ai -> AiFragment()
        R.id.nav_more -> MoreFragment()
        else -> HomeFragment()
    }

    /** Switches to a bottom tab. */
    fun go(tabId: Int) { nav.selectedItemId = tabId }

    /** Opens a screen on top of the current tab (system back returns). */
    fun open(fragment: Fragment) {
        supportFragmentManager.beginTransaction().setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(CONTAINER, fragment).addToBackStack(null).commit()
    }

    private fun show(fragment: Fragment) {
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction().setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(CONTAINER, fragment).commit()
    }

    fun requestPermissionsIfNeeded() {
        val wanted = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            wanted += Manifest.permission.BLUETOOTH_SCAN; wanted += Manifest.permission.BLUETOOTH_CONNECT
        } else {
            wanted += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        wanted += Manifest.permission.RECORD_AUDIO
        val missing = wanted.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    fun bluetoothPermitted(): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    companion object {
        /** Bottom bar: dark surface, green active item (also used by the screenshot tests). */
        fun bottomNav(context: android.content.Context) = BottomNavigationView(context).apply {
            inflateMenu(R.menu.bottom_nav)
            setBackgroundColor(Palette.SURFACE)
            itemIconTintList = ContextCompat.getColorStateList(context, R.color.nav_item)
            itemTextColor = ContextCompat.getColorStateList(context, R.color.nav_item)
            itemActiveIndicatorColor = android.content.res.ColorStateList.valueOf(Palette.PRIMARY_CONTAINER)
            labelVisibilityMode = com.google.android.material.navigation.NavigationBarView.LABEL_VISIBILITY_LABELED
            elevation = 0f
        }

        private const val CONTAINER = 0x7f0a7392
        const val EXTRA_TAB = "tab"
        const val EXTRA_SECTION = "section"
    }
}
