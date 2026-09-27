package com.timepass.financialapp.sms

import android.util.Log
import com.timepass.financialapp.data.local.dao.TransactionDao

/**
 * Brings stored transactions up to date with the current parser.
 *
 * Rows do not re-parse themselves: the SMS dedupe table stops a rescan, which is what it
 * is for. So anything that puts old rows into the database -- an upgrade, or a restore
 * from a backup written by an older build -- has to run this afterwards, or those rows
 * keep whatever the parser of the day decided about them.
 */
object TransactionRepair {

    private const val TAG = "TransactionRepair"

    data class Result(val updated: Int, val removed: Int)

    /**
     * Rewrites the parser-derived fields and drops rows the parser no longer recognises
     * as transactions.
     *
     * The user's own decisions are never overwritten: only payment mode and merchant are
     * rewritten, and a row is deleted only while it is still untagged and un-ignored.
     */
    suspend fun reparse(dao: TransactionDao): Result {
        val stale = mutableListOf<Long>()
        var updated = 0

        for (txn in dao.getAllForBackup()) {
            val parsed = SmsParser.parse(txn.description)
            if (parsed == null) {
                stale += txn.id
                continue
            }
            if (parsed.paymentMode != txn.paymentMode ||
                parsed.merchantName != txn.merchantName ||
                parsed.transactionType != txn.transactionType ||
                parsed.accountRef != txn.accountRef
            ) {
                dao.updateParsedFields(
                    id = txn.id,
                    paymentMode = parsed.paymentMode,
                    merchantName = parsed.merchantName,
                    transactionType = parsed.transactionType,
                    accountRef = parsed.accountRef,
                    accountKind = parsed.accountKind
                )
                updated++
            }
        }

        val removed = if (stale.isEmpty()) 0 else dao.deleteUntouched(stale)
        Log.i(TAG, "Re-parse: $updated rows updated, $removed non-transactions removed")
        return Result(updated, removed)
    }
}
