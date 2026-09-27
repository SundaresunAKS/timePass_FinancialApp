package com.timepass.financialapp.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.timepass.financialapp.data.local.dao.CategoryDao
import com.timepass.financialapp.data.local.dao.TransactionDao
import com.timepass.financialapp.data.local.entity.TransactionEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.YearMonth
import com.timepass.financialapp.util.DateUtils

class DashboardViewModel(
    private val transactionDao: TransactionDao,
    private val categoryDao: CategoryDao
) : ViewModel() {

    private val _selectedMonthYear = MutableStateFlow(DateUtils.currentMonth())
    val selectedMonthYear: StateFlow<YearMonth> = _selectedMonthYear.asStateFlow()

    /** Last-four of the account or card in view; null means all of them. */
    private val _accountRef = MutableStateFlow<String?>(null)
    val accountRef: StateFlow<String?> = _accountRef.asStateFlow()

    /** Accounts and cards with activity in the selected month, for the picker. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val availableAccounts: StateFlow<List<String>> = _selectedMonthYear
        .flatMapLatest { monthYear ->
            val (start, end) = DateUtils.getMonthRange(monthYear)
            transactionDao.getAccountRefs(start, end)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Reference to display name, shared with the list screens. */
    val accountLabels: StateFlow<Map<String, String>> = transactionDao
        .getAccountLabelSources()
        .map { com.timepass.financialapp.ui.common.AccountLabels.build(it) }
        // Eagerly, not WhileSubscribed: this map is read synchronously from click
        // handlers, and a lazily-started flow hands back an empty map to the first caller
        // -- which is exactly what made the picker show bare digits. It is a dozen rows.
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    fun accountLabel(ref: String): String = accountLabels.value[ref] ?: ref

    fun setAccountRef(ref: String?) {
        _accountRef.value = ref
    }

    data class DashboardData(
        val totalIncome: Double,
        val totalExpense: Double,
        /** How many rows are waiting in Pending this month. */
        val pendingCount: Int,
        /** Newest few transactions, so the screen has content before anything is tagged. */
        val recent: List<TransactionEntity>,
        val categorySpending: List<CategorySpendingDisplay>
    )

    data class CategorySpendingDisplay(
        val categoryName: String,
        val amount: Double,
        val colorHex: String
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    val dashboardData: StateFlow<DashboardData?> =
        combine(_selectedMonthYear, _accountRef) { month, ref -> month to ref }
        .flatMapLatest { (monthYear, account) ->
            val (startMillis, endMillis) = DateUtils.getMonthRange(monthYear)

            // Paired first: combine is only typed up to five flows, and a sixth would
            // turn every argument into Any?.
            val pending = combine(
                transactionDao.getUncategorizedCount(startMillis, endMillis, account),
                transactionDao.getRecent(startMillis, endMillis, RECENT_LIMIT, account)
            ) { count, recent -> count to recent }

            combine(
                transactionDao.getTotalIncome(startMillis, endMillis, account),
                transactionDao.getTotalExpense(startMillis, endMillis, account),
                pending,
                transactionDao.getSpendingByCategory(startMillis, endMillis, account),
                categoryDao.getAllCategories()
            ) { income, expense, (pendingCount, recent), spending, categories ->
                val categoryMap = categories.associateBy { it.id }
                val spendingDisplay = spending.mapNotNull { cs ->
                    val cat = categoryMap[cs.categoryId] ?: return@mapNotNull null
                    CategorySpendingDisplay(cat.name, cs.total, cat.colorHex)
                }.sortedByDescending { it.amount }

                DashboardData(
                    totalIncome = income ?: 0.0,
                    totalExpense = expense ?: 0.0,
                    pendingCount = pendingCount,
                    recent = recent,
                    categorySpending = spendingDisplay
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setMonth(month: YearMonth) {
        _selectedMonthYear.value = month
        // A card that had activity last month may have none in the new one, and a filter
        // pinned to it would show an empty dashboard with no obvious cause.
        _accountRef.value = null
    }

    fun previousMonth() {
        _selectedMonthYear.update { it.minusMonths(1) }
    }

    fun nextMonth() {
        _selectedMonthYear.update { it.plusMonths(1) }
    }

    private companion object {
        /** Enough to fill the screen, few enough to stay a glance rather than a list. */
        const val RECENT_LIMIT = 6
    }

    class Factory(
        private val transactionDao: TransactionDao,
        private val categoryDao: CategoryDao
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(transactionDao, categoryDao) as T
        }
    }
}
