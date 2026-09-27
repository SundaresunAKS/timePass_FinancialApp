package com.timepass.financialapp.ui.backup

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.backup.BackupError
import com.timepass.financialapp.backup.BackupFormat
import com.timepass.financialapp.backup.BackupFrequency
import com.timepass.financialapp.backup.BackupPrefs
import com.timepass.financialapp.backup.BackupRepository
import com.timepass.financialapp.backup.BackupWorker
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class BackupViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = BackupPrefs(app)
    private val repo = BackupRepository(
        app,
        (app as FinancialApp).database,
        prefs
    )

    data class UiState(
        val connected: Boolean = false,
        val accountEmail: String? = null,
        val accountName: String? = null,
        val folderName: String? = null,
        val lastBackupAt: Long = 0L,
        val lastBackupSize: Long = 0L,
        val frequency: BackupFrequency = BackupFrequency.OFF,
        val wifiOnly: Boolean = true,
        val chargingOnly: Boolean = false,
        val busy: Boolean = false
    )

    /** One-shot messages: errors, confirmations, and a restore offer. */
    sealed class Event {
        data class Error(val error: BackupError) : Event()
        data class Message(val res: Int) : Event()
        data class RestoreAvailable(
            val fileId: String,
            val sizeBytes: Long,
            val exportedAt: Long,
            val count: Int
        ) : Event()
        object NeedsFolder : Event()

        /**
         * A restore brought back the theme, so the activity has to be rebuilt for it to
         * show. Separate from [Message] because only the restore path needs it.
         */
        object SettingsRestored : Event()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(
            connected = prefs.isConnected,
            accountEmail = prefs.accountEmail,
            accountName = prefs.accountName,
            folderName = prefs.folderName,
            lastBackupAt = prefs.lastBackupAt,
            lastBackupSize = prefs.lastBackupSize,
            frequency = prefs.frequency,
            wifiOnly = prefs.wifiOnly,
            chargingOnly = prefs.chargingOnly
        )
    }

    /**
     * Runs after authorization succeeds: records the account, then looks in Drive for an
     * existing folder and backup so a reinstall can offer to restore straight away.
     */
    fun onAuthorized(email: String?) {
        prefs.authorized = true
        prefs.accountEmail = email ?: prefs.accountEmail
        refresh()

        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            // The address the authorization result carries is often absent with drive.file;
            // this is what actually fills in the account name and email.
            runCatching { repo.refreshProfile() }.onSuccess { refresh() }

            try {
                when (val found = repo.discoverOnConnect()) {
                    is BackupRepository.Discovery.FolderWithBackup -> {
                        val parsed = repo.fetchBackup(found.backup.fileId)
                        _events.send(
                            Event.RestoreAvailable(
                                fileId = found.backup.fileId,
                                sizeBytes = found.backup.sizeBytes,
                                exportedAt = parsed.exportedAt,
                                count = parsed.transactions.size
                            )
                        )
                    }
                    is BackupRepository.Discovery.FolderEmpty ->
                        _events.send(Event.Message(com.timepass.financialapp.R.string.backup_folder_found))
                    BackupRepository.Discovery.NoFolder ->
                        _events.send(Event.NeedsFolder)
                }
            } catch (e: BackupError) {
                _events.send(Event.Error(e))
            } catch (e: Exception) {
                _events.send(Event.Error(BackupError.Unknown(e.message)))
            } finally {
                _state.value = _state.value.copy(busy = false)
                refresh()
            }
        }
    }

    fun createFolder(name: String) = run {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                repo.createFolder(name)
                _events.send(Event.Message(com.timepass.financialapp.R.string.backup_folder_created))
            } catch (e: BackupError) {
                _events.send(Event.Error(e))
            } catch (e: Exception) {
                _events.send(Event.Error(BackupError.Unknown(e.message)))
            } finally {
                _state.value = _state.value.copy(busy = false)
                refresh()
            }
        }
    }

    fun backupNow() {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                repo.backupNow()
                _events.send(Event.Message(com.timepass.financialapp.R.string.backup_done))
            } catch (e: BackupError) {
                _events.send(Event.Error(e))
                if (e is BackupError.FolderMissing) _events.send(Event.NeedsFolder)
            } catch (e: Exception) {
                _events.send(Event.Error(BackupError.Unknown(e.message)))
            } finally {
                _state.value = _state.value.copy(busy = false)
                refresh()
            }
        }
    }

    /** Looks for a backup and, if found, asks the UI to confirm before touching anything. */
    fun checkForRestore() {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val remote = repo.findRemoteBackup()
                if (remote == null) {
                    _events.send(Event.Error(BackupError.BackupFileMissing))
                } else {
                    // Parsed up front so a corrupt file is rejected before the user is
                    // asked to confirm a destructive action.
                    val parsed = repo.fetchBackup(remote.fileId)
                    _events.send(
                        Event.RestoreAvailable(
                            fileId = remote.fileId,
                            sizeBytes = remote.sizeBytes,
                            exportedAt = parsed.exportedAt,
                            count = parsed.transactions.size
                        )
                    )
                }
            } catch (e: BackupError) {
                _events.send(Event.Error(e))
            } catch (e: Exception) {
                _events.send(Event.Error(BackupError.Unknown(e.message)))
            } finally {
                _state.value = _state.value.copy(busy = false)
            }
        }
    }

    fun restore(fileId: String) {
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try {
                val backup = repo.fetchBackup(fileId)
                repo.restore(backup)
                _events.send(Event.Message(com.timepass.financialapp.R.string.restore_done))
                if (backup.settings != null) _events.send(Event.SettingsRestored)
            } catch (e: BackupError) {
                _events.send(Event.Error(e))
            } catch (e: Exception) {
                _events.send(Event.Error(BackupError.Unknown(e.message)))
            } finally {
                _state.value = _state.value.copy(busy = false)
                refresh()
            }
        }
    }

    fun setFrequency(frequency: BackupFrequency) {
        prefs.frequency = frequency
        BackupWorker.schedule(getApplication(), prefs)
        refresh()
    }

    fun setWifiOnly(value: Boolean) {
        prefs.wifiOnly = value
        BackupWorker.schedule(getApplication(), prefs)
        refresh()
    }

    fun setChargingOnly(value: Boolean) {
        prefs.chargingOnly = value
        BackupWorker.schedule(getApplication(), prefs)
        refresh()
    }

    fun disconnect() {
        viewModelScope.launch {
            runCatching { repo.disconnect() }
            BackupWorker.cancel(getApplication())
            refresh()
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BackupViewModel(app) as T
    }
}
