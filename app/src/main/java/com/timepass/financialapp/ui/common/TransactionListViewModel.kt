package com.timepass.financialapp.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.timepass.financialapp.data.local.dao.TransactionDao
import com.timepass.financialapp.data.local.entity.TransactionEntity
import com.timepass.financialapp.util.DateUtils
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.YearMonth

/**
 * Shared behaviour of the three transaction lists -- Pending, Tagged and Ignored.
 *
 * They differ only in which inbox state they select ([categorized] / [ignored]) and in the
 * actions they offer, so the filter state, the query plumbing and the filter-option feeds
 * live here rather than being written out three times.
 */
abstract class TransactionListViewModel(
    protected val transactionDao: TransactionDao
) : ViewModel() {

    /** Which inbox this list shows. */
    protected abstract val categorized: Boolean
    protected abstract val ignored: Boolean

    /** Only the Tagged list has categories to narrow by. */
    protected open val supportsCategoryFilter: Boolean = false

    /** Value that cannot occur in real data; see [TransactionFilter.forQuery]. */
    private val sentinel = "__no_match__"

    private val _filter = MutableStateFlow(TransactionFilter())
    val filter: StateFlow<TransactionFilter> = _filter.asStateFlow()

    private fun categoryArgs(f: TransactionFilter): Pair<List<Long>, Int> =
        if (supportsCategoryFilter) {
            TransactionFilter.forQuery(f.categoryIds, -1L) to f.categoryIds.size
        } else {
            listOf(-1L) to 0
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val transactions: StateFlow<List<TransactionEntity>> = _filter
        .flatMapLatest { f ->
            val (start, end) = DateUtils.getMonthRange(f.month)
            val (categoryIds, categoryCount) = categoryArgs(f)
            transactionDao.getFiltered(
                categorized = categorized,
                ignored = ignored,
                startMillis = start,
                endMillis = end,
                types = TransactionFilter.forQuery(f.types, sentinel),
                typeCount = f.types.size,
                categoryIds = categoryIds,
                categoryCount = categoryCount,
                paymentModes = TransactionFilter.forQuery(f.paymentModes, sentinel),
                modeCount = f.paymentModes.size,
                senders = TransactionFilter.forQuery(f.senders, sentinel),
                senderCount = f.senders.size,
                accountRefs = TransactionFilter.forQuery(f.accountRefs, sentinel),
                accountCount = f.accountRefs.size,
                search = f.searchOrNull
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Modes present in the rows currently on screen, not in the whole table. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val paymentModes: StateFlow<List<String>> = _filter
        .flatMapLatest { f ->
            val (start, end) = DateUtils.getMonthRange(f.month)
            val (categoryIds, categoryCount) = categoryArgs(f)
            transactionDao.getPaymentModes(
                categorized = categorized,
                ignored = ignored,
                startMillis = start,
                endMillis = end,
                types = TransactionFilter.forQuery(f.types, sentinel),
                typeCount = f.types.size,
                categoryIds = categoryIds,
                categoryCount = categoryCount,
                senders = TransactionFilter.forQuery(f.senders, sentinel),
                senderCount = f.senders.size
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Senders present in the rows currently on screen, scoped like [paymentModes]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val senders: StateFlow<List<String>> = _filter
        .flatMapLatest { f ->
            val (start, end) = DateUtils.getMonthRange(f.month)
            val (categoryIds, categoryCount) = categoryArgs(f)
            transactionDao.getSenders(
                categorized = categorized,
                ignored = ignored,
                startMillis = start,
                endMillis = end,
                types = TransactionFilter.forQuery(f.types, sentinel),
                typeCount = f.types.size,
                categoryIds = categoryIds,
                categoryCount = categoryCount,
                paymentModes = TransactionFilter.forQuery(f.paymentModes, sentinel),
                modeCount = f.paymentModes.size
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Accounts and cards present in the rows on screen, scoped like [paymentModes]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val accountRefs: StateFlow<List<String>> = _filter
        .flatMapLatest { f ->
            val (start, end) = DateUtils.getMonthRange(f.month)
            transactionDao.getFilteredAccountRefs(
                categorized = categorized,
                ignored = ignored,
                startMillis = start,
                endMillis = end,
                types = TransactionFilter.forQuery(f.types, sentinel),
                typeCount = f.types.size,
                paymentModes = TransactionFilter.forQuery(f.paymentModes, sentinel),
                modeCount = f.paymentModes.size,
                senders = TransactionFilter.forQuery(f.senders, sentinel),
                senderCount = f.senders.size
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Reference to display name, e.g. "8607" to "IndusInd A/c 8607". */
    val accountLabels: StateFlow<Map<String, String>> = transactionDao
        .getAccountLabelSources()
        .map { AccountLabels.build(it) }
        // Eagerly, not WhileSubscribed: this map is read synchronously from click
        // handlers, and a lazily-started flow hands back an empty map to the first caller
        // -- which is exactly what made the picker show bare digits. It is a dozen rows.
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** The display name for a reference, falling back to the bare digits. */
    fun accountLabel(ref: String): String = accountLabels.value[ref] ?: ref

    fun setMonth(month: YearMonth) = _filter.update { it.copy(month = month) }
    fun previousMonth() = _filter.update { it.copy(month = it.month.minusMonths(1)) }
    fun nextMonth() = _filter.update { it.copy(month = it.month.plusMonths(1)) }
    fun setTypes(types: Set<String>) = _filter.update { it.copy(types = types) }
    fun setPaymentModes(modes: Set<String>) = _filter.update { it.copy(paymentModes = modes) }
    fun setSenders(senders: Set<String>) = _filter.update { it.copy(senders = senders) }
    fun setAccountRefs(refs: Set<String>) = _filter.update { it.copy(accountRefs = refs) }
    fun setCategoryIds(ids: Set<Long>) = _filter.update { it.copy(categoryIds = ids) }
    fun setSearch(query: String?) = _filter.update { it.copy(search = query) }

    /** Resets everything except the selected month. */
    fun clearFilters() = _filter.update { TransactionFilter(month = it.month) }
}
