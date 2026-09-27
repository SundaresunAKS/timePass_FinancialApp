package com.timepass.financialapp.ui.common

import android.content.Context
import android.graphics.Color
import android.content.res.ColorStateList
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.timepass.financialapp.R
import com.timepass.financialapp.data.local.entity.TransactionEntity
import com.timepass.financialapp.data.local.entity.TransactionSource

/**
 * Shared colour/label rules for transaction rows, so the Uncategorized and Categorized
 * lists stay visually consistent.
 */
object TransactionStyling {

    /** Opacity of a tag's fill relative to its text colour. */
    private const val TAG_FILL_ALPHA = 30

    @ColorInt
    fun directionColor(context: Context, transactionType: String): Int =
        ContextCompat.getColor(
            context,
            if (transactionType == "DEBIT") R.color.debit_red else R.color.credit_green
        )

    @ColorInt
    fun modeColor(context: Context, paymentMode: String): Int {
        val res = when (paymentMode) {
            "UPI" -> R.color.mode_upi
            "CREDIT_CARD" -> R.color.mode_credit_card
            "DEBIT_CARD" -> R.color.mode_debit_card
            "NEFT", "RTGS", "IMPS" -> R.color.mode_neft
            else -> R.color.mode_unknown
        }
        return ContextCompat.getColor(context, res)
    }

    /**
     * Where the row came from: "SMS", "Mail", or "SMS + Mail" when the bank sent both and
     * the second was folded into the first.
     */
    fun sourceLabel(context: Context, source: String, confirmedBy: String?): String {
        fun name(channel: String) = context.getString(
            when (channel) {
                TransactionSource.MAIL -> R.string.source_mail
                TransactionSource.MANUAL -> R.string.source_manual
                else -> R.string.source_sms
            }
        )
        // Always SMS first, whichever arrived first, so the pair reads the same everywhere.
        return if (confirmedBy != null) {
            context.getString(R.string.source_both, name(TransactionSource.SMS), name(TransactionSource.MAIL))
        } else {
            name(source)
        }
    }

    @ColorInt
    fun sourceColor(context: Context, source: String, confirmedBy: String?): Int =
        ContextCompat.getColor(
            context,
            when {
                confirmedBy != null -> R.color.source_both
                source == TransactionSource.MAIL -> R.color.source_mail
                source == TransactionSource.MANUAL -> R.color.mode_unknown
                else -> R.color.source_sms
            }
        )

    /** Paints the source tag on a transaction row. */
    fun applySourceTag(view: TextView, transaction: TransactionEntity) {
        val context = view.context
        applyTag(
            view,
            sourceLabel(context, transaction.source, transaction.confirmedBy),
            sourceColor(context, transaction.source, transaction.confirmedBy)
        )
    }

    /** Payment-mode words that stay fully capitalised rather than title-cased. */
    private val ACRONYMS = setOf("UPI", "NEFT", "RTGS", "IMPS", "ATM", "POS")

    /** "CREDIT_CARD" -> "Credit Card", "UPI" -> "UPI" (not "Upi"). */
    fun modeLabel(paymentMode: String): String =
        paymentMode.split('_').joinToString(" ") { word ->
            if (word.uppercase() in ACRONYMS) word.uppercase()
            else word.lowercase().replaceFirstChar { it.uppercase() }
        }

    /**
     * Category colours are user-editable strings, so a bad value must not crash a row.
     */
    @ColorInt
    fun parseCategoryColor(hex: String?, @ColorInt fallback: Int): Int =
        try {
            if (hex.isNullOrBlank()) fallback else Color.parseColor(hex)
        } catch (e: IllegalArgumentException) {
            fallback
        }

    /** Paints a tag pill: translucent fill of [color], solid [color] text. */
    fun applyTag(view: TextView, text: String, @ColorInt color: Int) {
        view.text = text
        view.setTextColor(color)
        view.backgroundTintList =
            ColorStateList.valueOf(ColorUtils.setAlphaComponent(color, TAG_FILL_ALPHA))
    }
}
