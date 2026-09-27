package com.timepass.financialapp.ui.uncategorized

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timepass.financialapp.data.local.dao.CategoryDao
import com.timepass.financialapp.data.local.dao.TransactionDao
import com.timepass.financialapp.ui.common.TransactionListViewModel
import kotlinx.coroutines.launch

/** The Pending inbox: neither tagged nor ignored. */
/** Outcome of a manual refresh, so the screen can say what happened. */
sealed class RefreshResult {
    data class Added(val count: Int) : RefreshResult()
    object NothingNew : RefreshResult()
    data class Failed(val message: String?) : RefreshResult()
}

class UncategorizedViewModel(
    transactionDao: TransactionDao,
    private val categoryDao: CategoryDao
) : TransactionListViewModel(transactionDao) {

    override val categorized = false
    override val ignored = false

    /**
     * Re-reads the SMS inbox and imports anything that is missing.
     *
     * Needed because the dedupe table can claim a message was handled when no transaction
     * exists for it -- most visibly after a restore, which marks the whole inbox as seen
     * and so drops everything that arrived after the backup was taken.
     */
    fun refresh(reader: com.timepass.financialapp.sms.SmsReader, onDone: (RefreshResult) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { reader.reconcile() }
            onDone(
                result.fold(
                    onSuccess = { if (it > 0) RefreshResult.Added(it) else RefreshResult.NothingNew },
                    onFailure = { RefreshResult.Failed(it.message) }
                )
            )
        }
    }

    fun categorize(transactionId: Long, categoryId: Long, note: String?) {
        viewModelScope.launch {
            transactionDao.categorize(
                transactionId = transactionId,
                categoryId = categoryId,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                now = System.currentTimeMillis()
            )
        }
    }

    /** Sets a transaction aside; [onDone] fires so the UI can offer an undo. */
    fun ignore(transactionId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            transactionDao.ignore(transactionId, System.currentTimeMillis())
            onDone()
        }
    }

    fun undoIgnore(transactionId: Long) {
        viewModelScope.launch {
            transactionDao.unignore(transactionId, System.currentTimeMillis())
        }
    }

    /** Applies one category to a whole selection. */
    fun categorizeAll(ids: Set<Long>, categoryId: Long, note: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            transactionDao.categorizeAll(
                ids = ids.toList(),
                categoryId = categoryId,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                now = System.currentTimeMillis()
            )
            onDone()
        }
    }

    fun ignoreAll(ids: Set<Long>, onDone: () -> Unit) {
        viewModelScope.launch {
            transactionDao.ignoreAll(ids.toList(), System.currentTimeMillis())
            onDone()
        }
    }

    fun undoIgnoreAll(ids: Set<Long>) {
        viewModelScope.launch {
            transactionDao.unignoreAll(ids.toList(), System.currentTimeMillis())
        }
    }

    class Factory(
        private val transactionDao: TransactionDao,
        private val categoryDao: CategoryDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UncategorizedViewModel(transactionDao, categoryDao) as T
    }
}
