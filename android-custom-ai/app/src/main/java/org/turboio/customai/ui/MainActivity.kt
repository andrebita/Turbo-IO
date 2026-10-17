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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = FrameLayout(this).apply { id = CONTAINER }
        val nav = BottomNavigationView(this).apply { inflateMenu(R.menu.bottom_nav) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(nav, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(root)
        nav.setOnItemSelectedListener { item ->
            show(when (item.itemId) {
                R.id.nav_ai -> AiFragment()
                R.id.nav_journal -> JournalFragment()
                R.id.nav_test -> TestFragment()
                R.id.nav_diag -> DiagnosticsFragment()
                else -> ConnectionFragment()
            }, item.title.toString())
            true
        }
        if (savedInstanceState == null) {
            if (intent?.getStringExtra(EXTRA_TAB) == "journal") nav.selectedItemId = R.id.nav_journal
            else show(ConnectionFragment(), getString(R.string.tab_connection))
        }
        requestPermissionsIfNeeded()
    }

    private fun show(fragment: Fragment, title: String) {
        supportFragmentManager.beginTransaction().replace(CONTAINER, fragment).commit()
        supportActionBar?.title = "Turbo IO IA · $title"
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
        private const val CONTAINER = 0x7f0a7392
        const val EXTRA_TAB = "tab"
    }
}
