package com.timepass.financialapp.ui.common

import com.timepass.financialapp.util.DateUtils
import java.time.YearMonth

/** Direction of money movement, stored by its database value. */
enum class TypeFilter(val dbValue: String) {
    DEBIT("DEBIT"),
    CREDIT("CREDIT")
}

/**
 * The full filter state of a transaction list.
 *
 * Every dimension is a set, and **an empty set means "no restriction"** rather than
 * "match nothing" -- that is what lets a single chip cover both the unfiltered and the
 * multi-selected case. [categoryIds] is only meaningful on the Tagged screen.
 */
data class TransactionFilter(
    val month: YearMonth = DateUtils.currentMonth(),
    val types: Set<String> = emptySet(),
    val categoryIds: Set<Long> = emptySet(),
    val paymentModes: Set<String> = emptySet(),
    val senders: Set<String> = emptySet(),
    /** Last-four account or card references, e.g. "8607". Empty means every account. */
    val accountRefs: Set<String> = emptySet(),
    val search: String? = null
) {
    /** Number of active narrowing filters, for the "Clear" affordance. */
    val activeCount: Int
        get() = listOf(
            types.isNotEmpty(),
            categoryIds.isNotEmpty(),
            paymentModes.isNotEmpty(),
            senders.isNotEmpty(),
            accountRefs.isNotEmpty(),
            !search.isNullOrBlank()
        ).count { it }

    /** Search text normalised for SQL LIKE; null when nothing meaningful was typed. */
    val searchOrNull: String?
        get() = search?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        /**
         * Room renders an empty list as `IN ()`, which SQLite rejects when parsing -- even
         * though the paired count check would short-circuit it at evaluation time. Passing
         * a sentinel that cannot match keeps the SQL valid; the count parameter does the
         * real work of disabling the predicate.
         */
        fun <T> forQuery(values: Set<T>, sentinel: T): List<T> =
            if (values.isEmpty()) listOf(sentinel) else values.toList()
    }
}
