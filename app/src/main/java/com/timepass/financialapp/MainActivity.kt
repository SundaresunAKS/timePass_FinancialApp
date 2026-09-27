package com.timepass.financialapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.timepass.financialapp.backup.BackupPrefs
import com.timepass.financialapp.backup.BackupRepository
import com.timepass.financialapp.data.prefs.AppPreferences
import com.timepass.financialapp.security.AppLock
import com.timepass.financialapp.ui.theme.AccentColor
import com.timepass.financialapp.ui.theme.ThemeMode
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.NavOptions
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.snackbar.Snackbar
import com.timepass.financialapp.databinding.ActivityMainBinding
import com.timepass.financialapp.sms.SmsReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var appBarConfiguration: AppBarConfiguration

    private val smsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            onSmsPermissionGranted()
        } else {
            Snackbar.make(binding.root, R.string.sms_permission_denied, Snackbar.LENGTH_LONG).show()
        }
    }

    private val prefs by lazy { AppPreferences(this) }
    private val backupPrefs by lazy { BackupPrefs(this) }

    /**
     * Survives configuration changes so a rotation does not re-prompt, but not process
     * death, which is what makes the lock a launch-time gate rather than a one-off.
     */
    private var unlocked = false

    /** Guards the one-off profile lookup, which must not repeat on every resume. */
    private var profileRequested = false

    /** True while the system prompt is on screen, so resuming cannot stack a second one. */
    private var promptShowing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        // Both must be applied before any view is inflated: the accent lives in the theme,
        // and the night mode decides which colour resources that theme resolves against.
        AppCompatDelegate.setDefaultNightMode(ThemeMode.fromKey(prefs.themeModeKey).nightMode)
        setTheme(AccentColor.fromKey(prefs.accentKey).themeRes)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        val navController = navHostFragment.navController

        // Only the drawer entries are top level. Categories, Export and Backup are reached
        // from Settings, so they keep an Up arrow that returns there.
        // Deliberately built without the DrawerLayout: handing it over would put a
        // hamburger in the toolbar's navigation slot, which is fixed to the start edge and
        // would sit on the opposite side from the drawer it opens.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.dashboardFragment,
                R.id.uncategorizedFragment,
                R.id.categorizedFragment,
                R.id.ignoredFragment,
                R.id.settingsFragment
            )
        )

        setupActionBarWithNavController(navController, appBarConfiguration)
        // Keeps NavigationUI's destination listener, which ticks the matching drawer item.
        binding.navigationView.setupWithNavController(navController)

        /*
         * Overrides the item handler that setupWithNavController just installed.
         *
         * NavigationUI only closes the drawer when its own navigate() call reports success,
         * and for the start destination it was reporting failure -- so tapping Dashboard
         * left the drawer open and went nowhere, while every other entry worked. The
         * difference is the save/restore-state options it applies, which matter for a
         * multi-back-stack setup this app does not have.
         *
         * Closing the drawer first and navigating plainly removes that dependency: the tap
         * always does something visible, whatever the navigator decides underneath.
         */
        binding.navigationView.setNavigationItemSelectedListener { item ->
            binding.drawerLayout.closeDrawer(GravityCompat.END)

            if (item.itemId != navController.currentDestination?.id) {
                runCatching {
                    navController.navigate(
                        item.itemId,
                        null,
                        NavOptions.Builder()
                            .setLaunchSingleTop(true)
                            .setPopUpTo(navController.graph.startDestinationId, false)
                            .build()
                    )
                }.onFailure {
                    android.util.Log.e("MainActivity", "Drawer navigation failed", it)
                }
            }

            item.isChecked = true
            true
        }

        // The Dashboard carries no title and needs no Up arrow, so the bar would be an
        // empty coloured band; it hosts its own floating drawer button instead.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.toolbar.visibility =
                if (destination.id == R.id.dashboardFragment) View.GONE else View.VISIBLE
        }

        binding.btnUnlock.setOnClickListener { promptUnlock() }
        unlocked = savedInstanceState?.getBoolean(STATE_UNLOCKED) ?: false
        applyLockState()

        if (savedInstanceState == null) {
            checkAndRequestSmsPermissions()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_UNLOCKED, unlocked)
    }

    override fun onResume() {
        super.onResume()
        renderDrawerHeader()
        fetchProfileIfMissing()
        // The drawer lock is window state, not state of this instance. Setting it only in
        // onCreate meant a LOCKED_CLOSED could outlive the reason for it -- through a
        // recreate, or a credential prompt that restarts the activity -- and then nothing
        // would ever open the drawer again. Re-applying here is idempotent and cheap.
        applyLockState()
    }

    /**
     * Fills in the account name for a connection made before the app asked for it.
     *
     * The name arrives with the "profile" scope, which older connections were not granted,
     * so an existing user would otherwise never see their greeting. Runs at most once per
     * process and stays silent on failure -- the generic greeting is a fine outcome.
     */
    private fun fetchProfileIfMissing() {
        if (profileRequested) return
        if (!backupPrefs.isConnected || backupPrefs.accountName != null) return
        profileRequested = true

        lifecycleScope.launch {
            val app = application as FinancialApp
            val repo = BackupRepository(this@MainActivity, app.database, backupPrefs)
            runCatching { repo.refreshProfile() }
            renderDrawerHeader()
        }
    }

    // ------------------------------------------------------------------- lock

    /**
     * Shows or hides the cover, and asks for authentication when it is needed.
     *
     * A lock that is switched on but no longer satisfiable -- the user removed their
     * fingerprint and screen lock in system settings -- is treated as off rather than
     * leaving the app permanently shut.
     */
    private fun applyLockState() {
        val needsUnlock = !unlocked &&
            prefs.biometricEnabled &&
            AppLock.isAvailable(this)

        binding.lockOverlay.visibility = if (needsUnlock) View.VISIBLE else View.GONE
        if (needsUnlock) {
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
            if (!promptShowing) promptUnlock()
        } else {
            unlocked = true
            binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
        }
    }

    private fun promptUnlock() {
        promptShowing = true
        AppLock.prompt(
            activity = this,
            titleRes = R.string.biometric_unlock_title,
            subtitleRes = R.string.biometric_unlock_subtitle,
            onSuccess = {
                promptShowing = false
                unlocked = true
                binding.lockOverlay.visibility = View.GONE
                binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            },
            onFailed = {
                promptShowing = false
                // The overlay stays up with its own Unlock button, so a mistaken cancel is
                // recoverable without killing the app from under the user.
            }
        )
    }

    /** True once the user has got past the lock, for screens that gate on it. */
    fun isUnlocked(): Boolean = unlocked

    /** For screens that draw without a toolbar and supply their own drawer affordance. */
    fun openDrawer() {
        binding.drawerLayout.openDrawer(GravityCompat.END)
    }

    // ----------------------------------------------------------------- drawer

    /**
     * Greets the linked Google account by name, or generically when there is none.
     *
     * Rendered in onResume rather than once in onCreate: connecting Drive happens on
     * another screen, and the drawer should reflect it on the way back.
     */
    private fun renderDrawerHeader() {
        val header = binding.navigationView.getHeaderView(0) ?: return
        val greeting = header.findViewById<android.widget.TextView>(R.id.tvGreeting)
        val email = header.findViewById<android.widget.TextView>(R.id.tvAccountEmail)

        val name = backupPrefs.accountName?.trim()?.substringBefore(' ')?.takeIf { it.isNotEmpty() }
        val address = backupPrefs.accountEmail?.takeIf { it.isNotBlank() }

        greeting.text = when {
            !backupPrefs.isConnected -> getString(R.string.nav_welcome)
            name != null -> getString(R.string.nav_greeting, name)
            address != null -> getString(R.string.nav_greeting_generic)
            else -> getString(R.string.nav_welcome)
        }

        val showEmail = backupPrefs.isConnected && address != null
        email.visibility = if (showEmail) View.VISIBLE else View.GONE
        email.text = address.orEmpty()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.toolbar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        if (item.itemId == R.id.action_open_drawer) {
            openDrawer()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.navHostFragment) as NavHostFragment
        return navHostFragment.navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onBackPressed() {
        if (binding.drawerLayout.isDrawerOpen(GravityCompat.END)) {
            binding.drawerLayout.closeDrawer(GravityCompat.END)
        } else {
            super.onBackPressed()
        }
    }

    private fun checkAndRequestSmsPermissions() {
        val readSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
        val receiveSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)

        if (readSms == PackageManager.PERMISSION_GRANTED && receiveSms == PackageManager.PERMISSION_GRANTED) {
            onSmsPermissionGranted()
        } else {
            smsPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_SMS,
                    Manifest.permission.RECEIVE_SMS
                )
            )
        }
    }

    private companion object {
        const val STATE_UNLOCKED = "unlocked"
    }

    private fun onSmsPermissionGranted() {
        val app = application as FinancialApp
        CoroutineScope(Dispatchers.IO).launch {
            val smsReader = SmsReader(this@MainActivity, app.database)
            smsReader.scanExistingSms()
        }
    }
}
