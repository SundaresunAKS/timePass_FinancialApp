package com.timepass.financialapp.ui.categorized

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timepass.financialapp.data.local.dao.CategoryDao
import com.timepass.financialapp.data.local.dao.TransactionDao
import com.timepass.financialapp.data.local.entity.CategoryEntity
import com.timepass.financialapp.data.local.entity.TransactionEntity
import com.timepass.financialapp.ui.common.TransactionListViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The Tagged list: categorized, not ignored. */
class CategorizedViewModel(
    transactionDao: TransactionDao,
    categoryDao: CategoryDao
) : TransactionListViewModel(transactionDao) {

    override val categorized = true
    override val ignored = false
    override val supportsCategoryFilter = true

    /** Backs the category-filter dialog and the row tags. */
    val categories: StateFlow<List<CategoryEntity>> = categoryDao
        .getAllCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** What the last untag removed, so it can be put back. */
    data class UndoState(val transactionId: Long, val categoryId: Long, val note: String?)

    private var lastUntag: UndoState? = null

    /**
     * Returns a transaction to Pending, remembering its category so an accidental tap can
     * be undone -- without that, the only way back is to find the row and retag it.
     */
    fun untag(transaction: TransactionEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            transaction.categoryId?.let {
                lastUntag = UndoState(transaction.id, it, transaction.note)
            }
            transactionDao.untag(transaction.id, System.currentTimeMillis())
            onDone()
        }
    }

    fun undoUntag() {
        val state = lastUntag ?: return
        lastUntag = null
        viewModelScope.launch {
            transactionDao.categorize(
                transactionId = state.transactionId,
                categoryId = state.categoryId,
                note = state.note,
                now = System.currentTimeMillis()
            )
        }
    }

    fun recategorize(transactionId: Long, categoryId: Long, note: String?) {
        viewModelScope.launch {
            transactionDao.categorize(
                transactionId = transactionId,
                categoryId = categoryId,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
                now = System.currentTimeMillis()
            )
        }
    }

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

    fun untagAll(ids: Set<Long>, onDone: () -> Unit) {
        viewModelScope.launch {
            transactionDao.untagAll(ids.toList(), System.currentTimeMillis())
            onDone()
        }
    }

    /**
     * Stores a hand-entered transaction directly as tagged.
     *
     * smsRawId and sender stay null, which is what marks the row as not having come from a
     * message; nothing downstream needs them, and leaving them empty keeps a manual entry
     * out of the SMS dedupe and re-parse passes.
     */
    fun addManual(entry: com.timepass.financialapp.ui.common.AddTransactionDialog.Entry, onDone: () -> Unit) {
        viewModelScope.launch {
            transactionDao.insert(
                com.timepass.financialapp.data.local.entity.TransactionEntity(
                    amount = entry.amount,
                    transactionType = entry.transactionType,
                    paymentMode = "MANUAL",
                    categoryId = entry.categoryId,
                    smsRawId = null,
                    sender = null,
                    description = entry.description,
                    merchantName = entry.description,
                    accountRef = null,
                    accountKind = null,
                    note = entry.note,
                    transactionDate = entry.dateMillis,
                    smsDate = entry.dateMillis,
                    isCategorized = true,
                    isIgnored = false
                )
            )
            onDone()
        }
    }

    /** Permanently removes hand-entered rows. Nothing else can be deleted. */
    fun deleteManual(ids: Set<Long>, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            onDone(transactionDao.deleteManual(ids.toList()))
        }
    }

    class Factory(
        private val transactionDao: TransactionDao,
        private val categoryDao: CategoryDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CategorizedViewModel(transactionDao, categoryDao) as T
    }
}
