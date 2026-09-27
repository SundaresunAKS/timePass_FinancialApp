package com.timepass.financialapp.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timepass.financialapp.data.local.dao.CategoryDao
import com.timepass.financialapp.data.local.dao.TransactionDao
import com.timepass.financialapp.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CategoryManagementViewModel(
    private val categoryDao: CategoryDao,
    private val transactionDao: TransactionDao
) : ViewModel() {

    /** A category plus how much it is actually used, which is what the row renders. */
    data class CategoryRow(
        val category: CategoryEntity,
        val txnCount: Int,
        val total: Double
    )

    val rows: StateFlow<List<CategoryRow>> = combine(
        categoryDao.getAllCategories(),
        transactionDao.getCategoryUsage()
    ) { categories, usage ->
        val byId = usage.associateBy { it.categoryId }
        categories.map { category ->
            val u = byId[category.id]
            CategoryRow(
                category = category,
                txnCount = u?.txnCount ?: 0,
                total = u?.total ?: 0.0
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addCategory(name: String, isIncome: Boolean, colorHex: String, emoji: String?) {
        viewModelScope.launch {
            categoryDao.insert(
                CategoryEntity(
                    name = name,
                    colorHex = colorHex,
                    emoji = emoji,
                    isIncome = isIncome,
                    isDefault = false,
                    sortOrder = (rows.value.maxOfOrNull { it.category.sortOrder } ?: 0) + 1,
                    createdAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun updateCategory(id: Long, name: String, isIncome: Boolean, colorHex: String, emoji: String?) {
        viewModelScope.launch {
            val existing = categoryDao.getCategoryById(id) ?: return@launch
            categoryDao.update(
                existing.copy(name = name, isIncome = isIncome, colorHex = colorHex, emoji = emoji)
            )
        }
    }

    /**
     * Deletes a category. Any transactions still pointing at it are returned to the
     * Uncategorized inbox first.
     *
     * The foreign key is ON DELETE SET NULL, which on its own would leave those rows
     * flagged as categorized with no category -- absent from the Uncategorized list, absent
     * from the pie chart, and effectively unreachable.
     */
    fun deleteCategory(id: Long) {
        viewModelScope.launch {
            transactionDao.uncategorizeByCategory(id, System.currentTimeMillis())
            categoryDao.deleteById(id)
        }
    }

    suspend fun transactionCount(categoryId: Long): Int =
        transactionDao.getCountByCategory(categoryId)

    class Factory(
        private val categoryDao: CategoryDao,
        private val transactionDao: TransactionDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return CategoryManagementViewModel(categoryDao, transactionDao) as T
        }
    }
}
