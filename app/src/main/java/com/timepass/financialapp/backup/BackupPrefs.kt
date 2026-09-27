package com.timepass.financialapp.backup

import android.content.Context
import android.content.SharedPreferences

/** How often an automatic backup runs. */
enum class BackupFrequency(val key: String, val days: Long) {
    OFF("off", 0),
    DAILY("daily", 1),
    WEEKLY("weekly", 7),
    MONTHLY("monthly", 30);

    companion object {
        fun fromKey(key: String?): BackupFrequency =
            entries.firstOrNull { it.key == key } ?: OFF
    }
}

/**
 * Everything the backup feature remembers between runs.
 *
 * The folder and file are stored by **id**, never by name or path, so a user who renames
 * or moves the folder in Drive does not silently start a second backup chain.
 */
class BackupPrefs(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("backup_prefs", Context.MODE_PRIVATE)

    /**
     * Set when authorization succeeds.
     *
     * Kept separate from [accountEmail] because the drive.file scope alone does not carry
     * an email address: keying "connected" on the email made a perfectly good connection
     * look like no connection at all.
     */
    var authorized: Boolean
        get() = prefs.getBoolean(KEY_AUTHORIZED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTHORIZED, value).apply()

    var accountEmail: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    /** Display name of the linked account, for the drawer greeting. */
    var accountName: String?
        get() = prefs.getString(KEY_ACCOUNT_NAME, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT_NAME, value).apply()

    var folderId: String?
        get() = prefs.getString(KEY_FOLDER_ID, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_ID, value).apply()

    /** Kept only to show the user which folder is in use; lookups always go by id. */
    var folderName: String?
        get() = prefs.getString(KEY_FOLDER_NAME, null)
        set(value) = prefs.edit().putString(KEY_FOLDER_NAME, value).apply()

    var fileId: String?
        get() = prefs.getString(KEY_FILE_ID, null)
        set(value) = prefs.edit().putString(KEY_FILE_ID, value).apply()

    /** Folder that Excel exports are uploaded to; separate from the backup folder. */
    var exportFolderId: String?
        get() = prefs.getString(KEY_EXPORT_FOLDER_ID, null)
        set(value) = prefs.edit().putString(KEY_EXPORT_FOLDER_ID, value).apply()

    var exportFolderName: String?
        get() = prefs.getString(KEY_EXPORT_FOLDER_NAME, null)
        set(value) = prefs.edit().putString(KEY_EXPORT_FOLDER_NAME, value).apply()

    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_AT, value).apply()

    var lastBackupSize: Long
        get() = prefs.getLong(KEY_LAST_SIZE, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SIZE, value).apply()

    var frequency: BackupFrequency
        get() = BackupFrequency.fromKey(prefs.getString(KEY_FREQUENCY, null))
        set(value) = prefs.edit().putString(KEY_FREQUENCY, value.key).apply()

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    var chargingOnly: Boolean
        get() = prefs.getBoolean(KEY_CHARGING_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_CHARGING_ONLY, value).apply()

    val isConnected: Boolean get() = authorized && folderId != null

    /** Clears the Drive link but keeps schedule preferences. */
    fun disconnect() {
        prefs.edit()
            .remove(KEY_AUTHORIZED)
            .remove(KEY_ACCOUNT)
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_FOLDER_ID)
            .remove(KEY_FOLDER_NAME)
            .remove(KEY_FILE_ID)
            .remove(KEY_EXPORT_FOLDER_ID)
            .remove(KEY_EXPORT_FOLDER_NAME)
            .remove(KEY_LAST_AT)
            .remove(KEY_LAST_SIZE)
            .apply()
    }

    /** Called when the saved folder turns out to be gone, so the UI can re-prompt. */
    fun clearFolder() {
        prefs.edit().remove(KEY_FOLDER_ID).remove(KEY_FOLDER_NAME).remove(KEY_FILE_ID).apply()
    }

    private companion object {
        const val KEY_AUTHORIZED = "authorized"
        const val KEY_ACCOUNT = "account_email"
        const val KEY_ACCOUNT_NAME = "account_name"
        const val KEY_FOLDER_ID = "folder_id"
        const val KEY_FOLDER_NAME = "folder_name"
        const val KEY_FILE_ID = "file_id"
        const val KEY_EXPORT_FOLDER_ID = "export_folder_id"
        const val KEY_EXPORT_FOLDER_NAME = "export_folder_name"
        const val KEY_LAST_AT = "last_backup_at"
        const val KEY_LAST_SIZE = "last_backup_size"
        const val KEY_FREQUENCY = "frequency"
        const val KEY_WIFI_ONLY = "wifi_only"
        const val KEY_CHARGING_ONLY = "charging_only"
    }
}
