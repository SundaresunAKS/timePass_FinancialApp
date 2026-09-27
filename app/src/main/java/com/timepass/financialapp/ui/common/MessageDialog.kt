package com.timepass.financialapp.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.core.content.getSystemService
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.timepass.financialapp.R
import com.timepass.financialapp.data.local.entity.TransactionEntity
import com.timepass.financialapp.util.CurrencyUtils
import com.timepass.financialapp.util.DateUtils

/**
 * Shows the complete bank SMS behind a transaction.
 *
 * The list can only show two lines, and the detail that matters when a row looks wrong --
 * the reference number, the balance, the full VPA -- is usually past that cut.
 */
object MessageDialog {

    fun show(context: Context, transaction: TransactionEntity) {
        val sign = if (transaction.transactionType == "DEBIT") "- " else "+ "
        val meta = context.getString(
            R.string.message_meta,
            sign + CurrencyUtils.format(transaction.amount),
            DateUtils.formatDate(transaction.transactionDate),
            transaction.sender ?: TransactionStyling.modeLabel(transaction.paymentMode)
        )

        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.message_dialog_title)
            .setMessage("$meta\n\n${transaction.description}")
            .setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy_message) { _, _ ->
                context.getSystemService<ClipboardManager>()?.setPrimaryClip(
                    ClipData.newPlainText("SMS", transaction.description)
                )
                Toast.makeText(context, R.string.message_copied, Toast.LENGTH_SHORT).show()
            }
            .show()
    }
}
