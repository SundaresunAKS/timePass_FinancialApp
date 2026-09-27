package com.timepass.financialapp.data.prefs

import android.content.Context
import android.content.SharedPreferences
import com.timepass.financialapp.util.Constants

/**
 * Wrapper around SharedPreferences for app-wide preference access.
 *
 * Provides typed properties for common settings used throughout the app,
 * backed by the shared preferences file defined in [Constants.PREF_NAME].
 */
class AppPreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)

    /**
     * Whether this is the first time the app has been launched.
     * Set to false after initial setup (category seeding, etc.) is complete.
     */
    var isFirstRun: Boolean
        get() = prefs.getBoolean(Constants.PREF_FIRST_RUN, true)
        set(value) {
            prefs.edit().putBoolean(Constants.PREF_FIRST_RUN, value).apply()
        }

    /**
     * The epoch millis timestamp of the last successful SMS scan.
     * Used to track when SMS scanning was last performed so the UI
     * can prompt the user or auto-scan at appropriate intervals.
     */
    var lastScanTime: Long
        get() = prefs.getLong(Constants.PREF_LAST_SCAN, 0L)
        set(value) {
            prefs.edit().putLong(Constants.PREF_LAST_SCAN, value).apply()
        }

    /** Accent colour key; see AccentColor. */
    var accentKey: String
        get() = prefs.getString(Constants.PREF_ACCENT, null) ?: "green"
        set(value) {
            prefs.edit().putString(Constants.PREF_ACCENT, value).apply()
        }

    /**
     * Whether the app is locked behind the device's own authentication.
     *
     * Off by default: this is a personal expense tracker, and a lock the user did not ask
     * for is friction on every launch.
     */
    var biometricEnabled: Boolean
        get() = prefs.getBoolean(Constants.PREF_BIOMETRIC, false)
        set(value) {
            prefs.edit().putBoolean(Constants.PREF_BIOMETRIC, value).apply()
        }

    /** Light/dark preference key; see ThemeMode. */
    var themeModeKey: String
        get() = prefs.getString(Constants.PREF_THEME_MODE, null) ?: "system"
        set(value) {
            prefs.edit().putString(Constants.PREF_THEME_MODE, value).apply()
        }

    /**
     * Whether the user has linked their Google Drive account for export uploads.
     */
    var isDriveLinked: Boolean
        get() = prefs.getBoolean(Constants.PREF_DRIVE_LINKED, false)
        set(value) {
            prefs.edit().putBoolean(Constants.PREF_DRIVE_LINKED, value).apply()
        }
}
