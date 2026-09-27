package com.timepass.financialapp.mail

import android.content.Context
import android.content.SharedPreferences

/** What the email import remembers between runs. */
class MailPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mail_prefs", Context.MODE_PRIVATE)

    /** The user turned email reading on and Gmail access was granted. */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    /** Start of the last sync that finished; the next one searches from a little before. */
    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    /** Parser version the stored email rows were imported with; see MailReader. */
    var importVersion: Int
        get() = prefs.getInt(KEY_IMPORT_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_IMPORT_VERSION, value).apply()

    private companion object {
        const val KEY_IMPORT_VERSION = "import_version"
        const val KEY_ENABLED = "enabled"
        const val KEY_ACCOUNT = "account_email"
        const val KEY_LAST_SYNC = "last_sync_at"
    }
}
