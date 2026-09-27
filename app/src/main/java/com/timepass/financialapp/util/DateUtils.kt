package com.timepass.financialapp.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Single source of truth for dates across the app.
 *
 * Everything is computed in IST rather than the device default: the data is Indian bank
 * SMS and a "month" here means an Indian calendar month, which should not shift if the
 * phone travels to another timezone.
 */
object DateUtils {

    val IST: ZoneId = ZoneId.of("Asia/Kolkata")

    /** April 1, 2026 00:00:00 IST as epoch milliseconds -- the first day tracked. */
    val CUTOFF_DATE: Long = LocalDate.of(2026, 4, 1)
        .atStartOfDay(IST)
        .toInstant()
        .toEpochMilli()

    /** The earliest month the UI allows navigating back to. */
    val FIRST_MONTH: YearMonth = YearMonth.of(2026, 4)

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(IST)

    private val monthFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("MMMM yyyy")

    fun isAfterCutoff(epochMillis: Long): Boolean = epochMillis >= CUTOFF_DATE

    /** Formats epoch millis as "22 Sep 2026" in IST. */
    fun formatDate(epochMillis: Long): String =
        dateFormatter.format(Instant.ofEpochMilli(epochMillis))

    /** Formats a month as "September 2026". */
    fun formatMonth(yearMonth: YearMonth): String = yearMonth.format(monthFormatter)

    /**
     * Returns the half-open range `[start, end)` for the given month in IST, where `end`
     * is the first instant of the following month.
     *
     * Half-open, not inclusive: an inclusive end would make a transaction stamped exactly
     * at midnight on the 1st belong to two months at once.
     */
    fun getMonthRange(yearMonth: YearMonth): Pair<Long, Long> {
        val start = yearMonth.atDay(1).atStartOfDay(IST).toInstant().toEpochMilli()
        val end = yearMonth.plusMonths(1).atDay(1).atStartOfDay(IST).toInstant().toEpochMilli()
        return start to end
    }

    /** The month containing "now", in IST. */
    fun currentMonth(): YearMonth = YearMonth.now(IST)
}
