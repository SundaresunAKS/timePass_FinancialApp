package com.timepass.financialapp.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

object CurrencyUtils {

    private val indianFormat: DecimalFormat by lazy {
        val symbols = DecimalFormatSymbols(Locale("en", "IN"))
        // Indian numbering: ##,##,##0.00
        // DecimalFormat with Indian grouping: first group of 3, then groups of 2
        val format = DecimalFormat("##,##,##0.00", symbols)
        format
    }

    /**
     * Formats the given amount in Indian Rupee format.
     * Example: 1234567.89 -> "₹12,34,567.89"
     */
    fun format(amount: Double): String {
        // Indian numbering system: last 3 digits grouped, then every 2 digits
        val isNegative = amount < 0
        val absAmount = Math.abs(amount)

        val parts = String.format(Locale.US, "%.2f", absAmount).split(".")
        val integerPart = parts[0]
        val decimalPart = parts[1]

        val formatted = if (integerPart.length <= 3) {
            integerPart
        } else {
            val lastThree = integerPart.substring(integerPart.length - 3)
            val remaining = integerPart.substring(0, integerPart.length - 3)
            val withCommas = StringBuilder()
            var i = remaining.length
            while (i > 0) {
                val start = maxOf(0, i - 2)
                if (withCommas.isNotEmpty()) {
                    withCommas.insert(0, ",")
                }
                withCommas.insert(0, remaining.substring(start, i))
                i = start
            }
            "$withCommas,$lastThree"
        }

        val prefix = if (isNegative) "-" else ""
        return "${prefix}\u20B9${formatted}.${decimalPart}"
    }
}
