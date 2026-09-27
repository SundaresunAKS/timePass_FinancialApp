package com.timepass.financialapp.ui.export

import android.app.Application
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timepass.financialapp.data.local.dao.CategoryDao
import com.timepass.financialapp.data.local.dao.TransactionDao
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.R
import com.timepass.financialapp.backup.BackupPrefs
import com.timepass.financialapp.backup.BackupRepository
import com.timepass.financialapp.export.ExcelExporter
import com.timepass.financialapp.ui.common.AccountLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ExportViewModel(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao,
    private val application: Application
) : ViewModel() {

    /** Transient outcome of a single export run; consumed by the UI, then reset. */
    sealed class ExportState {
        object Idle : ExportState()
        object Exporting : ExportState()
        data class Success(val filePath: String) : ExportState()
        data class UploadedToDrive(val folderName: String) : ExportState()
        data class Error(val message: String) : ExportState()
    }

    /** The most recent export still on disk. Survives navigating away and restarts. */
    data class LastExport(
        val filePath: String,
        val fileName: String,
        val sizeBytes: Long,
        val savedAt: Long
    )

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    private val _lastExport = MutableStateFlow<LastExport?>(null)
    val lastExport: StateFlow<LastExport?> = _lastExport.asStateFlow()

    private val prefs = BackupPrefs(application)
    private val repo = BackupRepository(
        application,
        (application as FinancialApp).database,
        prefs
    )

    /**
     * Uploading to Drive reuses the backup connection, so the option is only offered once
     * that is set up -- otherwise the button would exist solely to show an error.
     */
    private val _driveConnected = MutableStateFlow(false)
    val driveConnected: StateFlow<Boolean> = _driveConnected.asStateFlow()

    /** Default folder name offered when exporting to Drive. */
    val defaultExportFolder: String
        get() = prefs.exportFolderName ?: BackupRepository.DEFAULT_EXPORT_FOLDER

    init {
        refreshLastExport()
        refreshDriveState()
    }

    fun refreshDriveState() {
        _driveConnected.value = prefs.isConnected
    }

    /** Uploads the most recent export to Drive, into [folderName]. */
    fun exportToDrive(folderName: String) {
        val last = _lastExport.value ?: return
        viewModelScope.launch {
            _exportState.value = ExportState.Exporting
            _exportState.value = try {
                repo.exportFileToDrive(File(last.filePath), folderName)
                ExportState.UploadedToDrive(folderName)
            } catch (t: Throwable) {
                ExportState.Error(t.message ?: t::class.java.simpleName)
            }
        }
    }

    fun exportToExcel() {
        viewModelScope.launch {
            _exportState.value = ExportState.Exporting
            // Apache POI workbook construction and file IO are far too slow for the main
            // thread, which is what viewModelScope dispatches to.
            _exportState.value = withContext(Dispatchers.IO) {
                try {
                    val transactions = transactionDao.getAllCategorized().first()
                    if (transactions.isEmpty()) {
                        // Otherwise this writes a header-only file and calls it a success,
                        // which reads as "exported" when nothing was.
                        return@withContext ExportState.Error(
                            application.getString(R.string.export_nothing_tagged)
                        )
                    }
                    val categories = categoryDao.getAllCategories().first()
                    val accountLabels = AccountLabels.build(
                        transactionDao.getAccountLabelSources().first()
                    )

                    val exporter = ExcelExporter(application)
                    val path = exporter.export(transactions, categories, accountLabels)
                    pruneOldExports()
                    ExportState.Success(path)
                } catch (t: Throwable) {
                    // Throwable, not Exception: POI surfaces missing JDK-only classes as
                    // NoClassDefFoundError, which is an Error and would otherwise take the
                    // whole process down instead of showing the user a message.
                    ExportState.Error(t.message ?: t::class.java.simpleName)
                }
            }
            refreshLastExport()
        }
    }

    /**
     * Keeps only the most recent few exports.
     *
     * Every run writes a new timestamped file and nothing ever removed the old ones, so
     * the app's documents folder grew by one spreadsheet per export forever.
     */
    private fun pruneOldExports() {
        val dir = application.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".xlsx") } ?: return
        files.sortedByDescending { it.lastModified() }
            .drop(MAX_KEPT_EXPORTS)
            .forEach { runCatching { it.delete() } }
    }

    /** Clears the transient result only; [lastExport] is left intact. */
    fun consumeState() {
        _exportState.value = ExportState.Idle
    }

    /**
     * Picks the newest .xlsx in the export directory, so the share card is populated even
     * on a cold start where nothing has been exported this session.
     */
    private fun refreshLastExport() {
        viewModelScope.launch(Dispatchers.IO) {
            val dir = application.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val newest: File? = dir?.listFiles { f -> f.isFile && f.name.endsWith(".xlsx") }
                ?.maxByOrNull { it.lastModified() }

            _lastExport.value = newest?.let {
                LastExport(
                    filePath = it.absolutePath,
                    fileName = it.name,
                    sizeBytes = it.length(),
                    savedAt = it.lastModified()
                )
            }
        }
    }

    private companion object {
        /** Enough to go back to a previous export, few enough not to accumulate. */
        const val MAX_KEPT_EXPORTS = 5
    }

    class Factory(
        private val transactionDao: TransactionDao,
        private val categoryDao: CategoryDao,
        private val application: Application
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ExportViewModel(transactionDao, categoryDao, application) as T
        }
    }
}
