package com.timepass.financialapp.ui.ignored

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timepass.financialapp.data.local.dao.TransactionDao
import com.timepass.financialapp.ui.common.TransactionListViewModel
import kotlinx.coroutines.launch

/** The Ignored list: set aside, never counted in any total. */
class IgnoredViewModel(
    transactionDao: TransactionDao
) : TransactionListViewModel(transactionDao) {

    override val categorized = false
    override val ignored = true

    /** Puts a transaction back in the Pending inbox. */
    fun restore(transactionId: Long, onDone: () -> Unit) {
        viewModelScope.launch {
            transactionDao.unignore(transactionId, System.currentTimeMillis())
            onDone()
        }
    }

    fun undoRestore(transactionId: Long) {
        viewModelScope.launch {
            transactionDao.ignore(transactionId, System.currentTimeMillis())
        }
    }

    fun restoreAll(ids: Set<Long>, onDone: () -> Unit) {
        viewModelScope.launch {
            transactionDao.unignoreAll(ids.toList(), System.currentTimeMillis())
            onDone()
        }
    }

    fun undoRestoreAll(ids: Set<Long>) {
        viewModelScope.launch {
            transactionDao.ignoreAll(ids.toList(), System.currentTimeMillis())
        }
    }

    class Factory(
        private val transactionDao: TransactionDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            IgnoredViewModel(transactionDao) as T
    }
}
