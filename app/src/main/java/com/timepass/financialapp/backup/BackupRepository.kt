package com.timepass.financialapp.backup

import android.content.Context
import androidx.room.withTransaction
import com.timepass.financialapp.data.local.AppDatabase
import com.timepass.financialapp.data.prefs.AppPreferences
import com.timepass.financialapp.sms.SmsReader
import com.timepass.financialapp.sms.TransactionRepair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Drives the backup and restore flows: token, folder, file, database.
 *
 * All failures surface as [BackupError] so callers -- the settings screen and the
 * WorkManager job -- can present a message or decide to retry without inspecting HTTP
 * codes or JSON themselves.
 */
class BackupRepository(
    private val context: Context,
    private val database: AppDatabase,
    private val prefs: BackupPrefs,
    private val auth: DriveAuth = DriveAuth(context),
    private val drive: DriveClient = DriveClient()
) {

    data class RemoteBackup(val fileId: String, val sizeBytes: Long, val modifiedTime: String?)

    /** What connecting found in Drive, which decides what the UI asks next. */
    sealed class Discovery {
        /** Folder found and it already holds a backup -- offer to restore it. */
        data class FolderWithBackup(val folderName: String, val backup: RemoteBackup) : Discovery()

        /** Folder found but empty -- nothing to restore, ready to back up. */
        data class FolderEmpty(val folderName: String) : Discovery()

        /** Nothing found -- ask the user to name a folder to create. */
        object NoFolder : Discovery()
    }

    /**
     * Reads the linked account's name and address, and remembers them.
     *
     * Best effort: a failure here leaves the greeting generic and nothing else changes.
     */
    suspend fun refreshProfile(): GoogleProfile? {
        val token = runCatching { requireToken() }.getOrNull() ?: return null
        val profile = GoogleProfile.fetch(token) ?: return null
        profile.email?.let { prefs.accountEmail = it }
        profile.name?.let { prefs.accountName = it }
        return profile
    }

    /**
     * Run right after authorization: works out whether this account already has a backup.
     *
     * A fresh install has no saved folder id, so the folder is looked up by name. That
     * works because drive.file grants access to the OAuth client rather than to the
     * installation -- a folder created by a previous install is still ours to see.
     */
    suspend fun discoverOnConnect(defaultName: String = DEFAULT_FOLDER): Discovery {
        val token = requireToken()

        // Prefer the saved id; it survives the user renaming the folder in Drive.
        val savedId = prefs.folderId
        val folderId = if (savedId != null && drive.folderExists(token, savedId)) {
            savedId
        } else {
            prefs.clearFolder()
            val name = prefs.folderName ?: defaultName
            val found = drive.findFolderByName(token, name) ?: return Discovery.NoFolder
            prefs.folderId = found.id
            prefs.folderName = found.name
            found.id
        }

        val name = prefs.folderName ?: defaultName
        val file = drive.findFile(token, folderId, BackupFormat.FILE_NAME)
            ?: return Discovery.FolderEmpty(name)

        prefs.fileId = file.id
        return Discovery.FolderWithBackup(
            folderName = name,
            backup = RemoteBackup(file.id, file.size, file.modifiedTime)
        )
    }

    // ------------------------------------------------------------------ setup

    /** Creates the backup folder and remembers its id. */
    suspend fun createFolder(name: String): String {
        val token = requireToken()
        val id = drive.createFolder(token, name.trim().ifEmpty { DEFAULT_FOLDER })
        prefs.folderId = id
        prefs.folderName = name.trim().ifEmpty { DEFAULT_FOLDER }
        prefs.fileId = null
        return id
    }

    /**
     * Confirms the saved folder is still usable.
     *
     * A folder the user deleted or trashed in Drive is cleared locally so the UI can ask
     * for a new one, rather than uploading into something invisible.
     */
    suspend fun verifyFolder(): Boolean {
        val folderId = prefs.folderId ?: return false
        val token = requireToken()
        val ok = drive.folderExists(token, folderId)
        if (!ok) prefs.clearFolder()
        return ok
    }

    // ----------------------------------------------------------------- backup

    /**
     * Writes the whole database to Drive.
     *
     * Updates the existing file when one is known, so repeated backups overwrite a single
     * file rather than littering the folder with copies.
     */
    suspend fun backupNow(): RemoteBackup {
        val token = requireToken()
        val folderId = prefs.folderId ?: throw BackupError.FolderMissing

        if (!drive.folderExists(token, folderId)) {
            prefs.clearFolder()
            throw BackupError.FolderMissing
        }

        val json = withContext(Dispatchers.IO) {
            BackupFormat.encode(
                categories = database.categoryDao().getAllForBackup(),
                transactions = database.transactionDao().getAllForBackup(),
                appVersion = appVersion(),
                settings = currentSettings()
            )
        }
        val bytes = json.toByteArray()

        // Adopt a file left by another device before creating a second one.
        val existingId = prefs.fileId
            ?: drive.findFile(token, folderId, BackupFormat.FILE_NAME)?.id

        val fileId = if (existingId != null) {
            try {
                drive.updateFile(token, existingId, bytes, BackupFormat.MIME_TYPE)
                existingId
            } catch (e: BackupError.BackupFileMissing) {
                // Deleted in Drive since we last looked; fall through to a fresh create.
                drive.createFile(token, folderId, BackupFormat.FILE_NAME, bytes, BackupFormat.MIME_TYPE)
            }
        } else {
            drive.createFile(token, folderId, BackupFormat.FILE_NAME, bytes, BackupFormat.MIME_TYPE)
        }

        prefs.fileId = fileId
        prefs.lastBackupAt = System.currentTimeMillis()
        prefs.lastBackupSize = bytes.size.toLong()

        return RemoteBackup(fileId, bytes.size.toLong(), null)
    }

    // ---------------------------------------------------------------- restore

    /** Looks for a backup to restore, by saved id first and then by name in the folder. */
    suspend fun findRemoteBackup(): RemoteBackup? {
        val token = requireToken()
        val folderId = prefs.folderId ?: throw BackupError.FolderMissing

        prefs.fileId?.let { id ->
            drive.getFile(token, id)?.let {
                return RemoteBackup(it.id, it.size, it.modifiedTime)
            }
        }
        val found = drive.findFile(token, folderId, BackupFormat.FILE_NAME) ?: return null
        prefs.fileId = found.id
        return RemoteBackup(found.id, found.size, found.modifiedTime)
    }

    /** Downloads and parses without touching the database, so the UI can preview it. */
    suspend fun fetchBackup(fileId: String): BackupFormat.Backup {
        val token = requireToken()
        val json = drive.download(token, fileId)
        return BackupFormat.decode(json)
    }

    /**
     * Replaces local data with [backup].
     *
     * Parsing happens before this is called and the write runs inside a single Room
     * transaction, so a malformed file can never leave the database half-erased: either
     * every table is replaced or none is.
     *
     * sms_raw is cleared and then rebuilt to mark the whole inbox as already-imported.
     * Clearing it alone was a bug: the next scan saw an empty dedupe table and imported
     * every message again, duplicating the data that had just been restored.
     */
    suspend fun restore(backup: BackupFormat.Backup) {
        // Applied before the data, so a failure writing rows does not leave the app with
        // someone else's appearance and none of their expenses.
        backup.settings?.let(::applySettings)

        database.withTransaction {
            database.transactionDao().deleteAll()
            database.categoryDao().deleteAll()
            database.smsRawDao().deleteAll()
            database.mailRawDao().deleteAll()

            database.categoryDao().insertAll(backup.categories)
            database.transactionDao().insertAll(backup.transactions)
        }

        // Outside the transaction: this reads the SMS provider, which should not hold a
        // database write lock open. A failure here costs a duplicate scan, not the restore.
        //
        // Reconcile rather than mark-everything-seen. The old call flagged the entire inbox
        // as already imported, which was right for the messages the backup contained and
        // wrong for every message that arrived after it was taken -- those were discarded
        // and could never come back. Reconcile keeps the first property and fixes the second.
        runCatching { SmsReader(context, database).reconcile() }

        // Same for email: a full re-sync from the cutoff. Restored rows carry their Gmail
        // message ids, so emails already represented are recognised rather than re-added.
        val mailPrefs = com.timepass.financialapp.mail.MailPrefs(context)
        mailPrefs.lastSyncAt = 0L
        runCatching { com.timepass.financialapp.mail.MailReader(context, database, mailPrefs).syncIfEnabled() }

        // A backup written by an older build carries that build's parse of every message,
        // and nothing else would ever revisit those rows -- the startup re-parse runs once
        // and has already been marked done. Without this, restoring quietly undoes every
        // parser fix and brings the non-transactions back with it.
        runCatching { TransactionRepair.reparse(database.transactionDao()) }
        runCatching { database.transactionDao().removeDuplicates() }
    }

    // ----------------------------------------------------------- excel export

    /**
     * Uploads an exported spreadsheet to Drive, into a folder of its own.
     *
     * Kept apart from the backup file deliberately: backups overwrite a single file, while
     * exports are point-in-time documents the user may want to keep several of, so each
     * upload creates a new file named with its timestamp.
     */
    suspend fun exportFileToDrive(file: java.io.File, folderName: String): String {
        val token = requireToken()
        val name = folderName.trim().ifEmpty { DEFAULT_EXPORT_FOLDER }

        // Reuse the saved folder when it is still there; otherwise look it up by name
        // before creating one, so repeated exports do not pile up duplicate folders.
        val savedId = prefs.exportFolderId
        val folderId = if (savedId != null &&
            prefs.exportFolderName == name &&
            drive.folderExists(token, savedId)
        ) {
            savedId
        } else {
            val found = drive.findFolderByName(token, name)
            val id = found?.id ?: drive.createFolder(token, name)
            prefs.exportFolderId = id
            prefs.exportFolderName = name
            id
        }

        val bytes = withContext(Dispatchers.IO) { file.readBytes() }
        return drive.createFile(token, folderId, file.name, bytes, XLSX_MIME)
    }

    // ------------------------------------------------------------------ misc

    suspend fun disconnect() {
        auth.signOut()
        prefs.disconnect()
    }

    private suspend fun requireToken(): String =
        auth.silentToken() ?: throw BackupError.NeedsReauth

    /** Snapshot of the preferences that belong in a backup. */
    private fun currentSettings(): BackupFormat.Settings {
        val app = AppPreferences(context)
        return BackupFormat.Settings(
            themeMode = app.themeModeKey,
            accent = app.accentKey,
            biometricEnabled = app.biometricEnabled
        )
    }

    /**
     * Writes restored preferences back.
     *
     * The lock is only restored when the device can actually satisfy it; carrying an
     * enabled lock onto a phone with nothing enrolled would leave the app unopenable.
     */
    private fun applySettings(settings: BackupFormat.Settings) {
        val app = AppPreferences(context)
        settings.themeMode?.let { app.themeModeKey = it }
        settings.accent?.let { app.accentKey = it }
        settings.biometricEnabled?.let {
            app.biometricEnabled = it && com.timepass.financialapp.security.AppLock.isAvailable(context)
        }
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    companion object {
        const val DEFAULT_FOLDER = "ExpenseTracker"
        const val DEFAULT_EXPORT_FOLDER = "ExpenseTrackerExportFile"
        const val XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
