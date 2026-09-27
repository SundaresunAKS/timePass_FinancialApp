package com.timepass.financialapp.util

object Constants {
    const val DATABASE_NAME = "financial_app.db"
    const val PREF_NAME = "app_preferences"
    const val PREF_FIRST_RUN = "first_run"
    const val PREF_LAST_SCAN = "last_scan_time"
    const val PREF_DRIVE_LINKED = "drive_linked"
    const val PREF_ACCENT = "accent_color"
    const val PREF_THEME_MODE = "theme_mode"

    /** Whether the app asks for a fingerprint, face or device PIN before opening. */
    const val PREF_BIOMETRIC = "biometric_lock"

    /** Set once the stored transactions have been re-read with the corrected parser. */
    const val PREF_REPARSED = "reparsed_v4"
}
