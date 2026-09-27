package com.timepass.financialapp.sms

import android.content.Context
import android.net.Uri
import android.provider.Telephony
import android.util.Log
import com.timepass.financialapp.data.local.AppDatabase
import com.timepass.financialapp.data.local.entity.SmsRawEntity
import com.timepass.financialapp.data.local.entity.TransactionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneId

/**
 * Reads existing SMS messages from the device inbox and processes bank transaction alerts.
 *
 * Scans the SMS inbox for messages from known bank senders that arrived on or after
 * the configured cutoff date (April 1 2026 IST). Each qualifying SMS is saved to the
 * sms_raw table and, if parseable, a corresponding transaction is created.
 */
class SmsReader(
    private val context: Context,
    private val database: AppDatabase
) {

    companion object {
        private const val TAG = "SmsReader"

        /** April 1, 2026 00:00:00 IST (Asia/Kolkata) in epoch milliseconds */
        val CUTOFF_MILLIS: Long = java.time.LocalDate.of(2026, 4, 1)
            .atStartOfDay(ZoneId.of("Asia/Kolkata"))
            .toInstant()
            .toEpochMilli()

        private val SMS_INBOX_URI: Uri = Uri.parse("content://sms/inbox")

        /**
         * How far apart two copies of the same message may be timestamped.
         *
         * The broadcast receiver records the message's own time while the provider stores
         * its own, and the two differ by well under a second. Matches the window used by
         * TransactionDao.removeDuplicates.
         */
        private const val DUPLICATE_TOLERANCE_MS = 120_000L
    }

    /**
     * Records every qualifying inbox message as already-imported, without creating any
     * transaction.
     *
     * Run after a restore: the restored rows already represent these messages. Without it
     * the next scan finds an empty sms_raw, concludes it has never seen them, and imports
     * the lot a second time on top of the restored data.
     */
    suspend fun markExistingAsSeen(): Int = withContext(Dispatchers.IO) {
        val smsRawDao = database.smsRawDao()
        val existingIds = smsRawDao.getAllDeviceSmsIds().toHashSet()
        var marked = 0

        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE
        )
        val cursor = context.contentResolver.query(
            SMS_INBOX_URI, projection,
            "${Telephony.Sms.DATE} >= ?", arrayOf(CUTOFF_MILLIS.toString()), null
        )

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (it.moveToNext()) {
                try {
                    val deviceSmsId = it.getLong(idIndex)
                    val sender = it.getString(addressIndex) ?: continue
                    if (!SmsSenderFilter.isBankSms(sender)) continue
                    if (deviceSmsId in existingIds) continue

                    smsRawDao.insert(
                        SmsRawEntity(
                            sender = sender,
                            body = it.getString(bodyIndex) ?: "",
                            smsDate = it.getLong(dateIndex),
                            deviceSmsId = deviceSmsId,
                            isProcessed = true
                        )
                    )
                    marked++
                } catch (e: Exception) {
                    Log.e(TAG, "Error marking SMS as seen", e)
                }
            }
        }
        Log.d(TAG, "Marked $marked messages as already imported")
        marked
    }

    /**
     * Compares the inbox against what is stored and imports whatever is missing.
     *
     * Unlike [scanExistingSms] this does not trust the sms_raw dedupe table -- it checks
     * the transactions themselves. That matters because sms_raw can say "seen" about a
     * message no transaction was ever created for:
     *
     *  - [markExistingAsSeen] marks the whole inbox seen after a restore, so every message
     *    that arrived *after* the backup was taken was silently discarded;
     *  - a message that failed to parse under an older build stays marked seen forever.
     *
     * Matching is on sender, amount and a prefix of the text within a time tolerance, the
     * same rule the duplicate cleanup uses, so re-running this never creates a second copy.
     *
     * @return how many transactions were added
     */
    suspend fun reconcile(): Int = withContext(Dispatchers.IO) {
        val smsRawDao = database.smsRawDao()
        val transactionDao = database.transactionDao()

        // One read of everything stored; the corpus is a few thousand rows at most and a
        // per-message query would mean one round trip per SMS in the inbox.
        val existing = transactionDao.getAllForBackup()
        val byAmount = existing.groupBy { it.amount }
        val seenSmsIds = smsRawDao.getAllDeviceSmsIds().toHashSet()

        var added = 0

        val projection = arrayOf(
            Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE
        )
        val cursor = context.contentResolver.query(
            SMS_INBOX_URI, projection,
            "${Telephony.Sms.DATE} >= ?", arrayOf(CUTOFF_MILLIS.toString()), null
        )

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (it.moveToNext()) {
                try {
                    val deviceSmsId = it.getLong(idIndex)
                    val sender = it.getString(addressIndex) ?: continue
                    if (!SmsSenderFilter.isBankSms(sender)) continue

                    val body = it.getString(bodyIndex) ?: continue
                    val smsDate = it.getLong(dateIndex)

                    val parsed = SmsParser.parse(body)
                    if (parsed == null) {
                        // Still record it, so the normal scan does not keep revisiting it.
                        if (deviceSmsId !in seenSmsIds) {
                            smsRawDao.insert(
                                SmsRawEntity(
                                sender = sender,
                                body = body,
                                smsDate = smsDate,
                                deviceSmsId = deviceSmsId,
                                isProcessed = true
                            )
                            )
                        }
                        continue
                    }

                    val duplicate = byAmount[parsed.amount].orEmpty().any { txn ->
                        txn.sender == sender &&
                            kotlin.math.abs(txn.transactionDate - smsDate) <= DUPLICATE_TOLERANCE_MS &&
                            isPrefixOfEitherWay(txn.description, body)
                    }
                    if (duplicate) {
                        if (deviceSmsId !in seenSmsIds) {
                            smsRawDao.insert(
                                SmsRawEntity(
                                sender = sender,
                                body = body,
                                smsDate = smsDate,
                                deviceSmsId = deviceSmsId,
                                isProcessed = true
                            )
                            )
                        }
                        continue
                    }

                    val smsRawId = smsRawDao.insert(
                        SmsRawEntity(
                                sender = sender,
                                body = body,
                                smsDate = smsDate,
                                deviceSmsId = deviceSmsId,
                                isProcessed = true
                            )
                    ).takeIf { id -> id != -1L }

                    transactionDao.insert(
                        TransactionEntity(
                            amount = parsed.amount,
                            transactionType = parsed.transactionType,
                            paymentMode = parsed.paymentMode,
                            categoryId = null,
                            smsRawId = smsRawId,
                            sender = sender,
                            description = body,
                            merchantName = parsed.merchantName,
                            accountRef = parsed.accountRef,
                            accountKind = parsed.accountKind,
                            transactionDate = smsDate,
                            smsDate = smsDate,
                            isCategorized = false
                        )
                    )
                    added++
                } catch (e: Exception) {
                    Log.e(TAG, "Error reconciling SMS", e)
                }
            }
        }

        Log.i(TAG, "Reconcile: $added transactions added")
        added
    }

    /** True when one message is the other with more of a multipart SMS filled in. */
    private fun isPrefixOfEitherWay(a: String, b: String): Boolean {
        val shortest = minOf(a.length, b.length)
        return shortest > 0 && a.regionMatches(0, b, 0, shortest)
    }

    /**
     * Scans existing SMS messages in the device inbox for bank transaction alerts.
     *
     * For each qualifying SMS:
     * 1. Checks the sender against [SmsSenderFilter]
     * 2. Verifies the message hasn't already been imported (via deviceSmsId)
     * 3. Saves the raw SMS to the database
     * 4. Parses the SMS body for transaction details
     * 5. If parsing succeeds, creates a [TransactionEntity]
     *
     * @return The count of new transactions found and saved
     */
    suspend fun scanExistingSms(): Int = withContext(Dispatchers.IO) {
        val smsRawDao = database.smsRawDao()
        val transactionDao = database.transactionDao()

        // Load all already-imported device SMS IDs to avoid duplicates
        val existingIds = smsRawDao.getAllDeviceSmsIds().toHashSet()

        var newTransactionCount = 0

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE
        )

        val selection = "${Telephony.Sms.DATE} >= ?"
        val selectionArgs = arrayOf(CUTOFF_MILLIS.toString())
        val sortOrder = "${Telephony.Sms.DATE} DESC"

        val cursor = context.contentResolver.query(
            SMS_INBOX_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )

        cursor?.use {
            val idIndex = it.getColumnIndexOrThrow(Telephony.Sms._ID)
            val addressIndex = it.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIndex = it.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIndex = it.getColumnIndexOrThrow(Telephony.Sms.DATE)

            while (it.moveToNext()) {
                try {
                    val deviceSmsId = it.getLong(idIndex)
                    val sender = it.getString(addressIndex) ?: continue
                    val body = it.getString(bodyIndex) ?: continue
                    val smsDate = it.getLong(dateIndex)

                    // Skip non-bank SMS
                    if (!SmsSenderFilter.isBankSms(sender)) continue

                    // Skip already-imported SMS
                    if (deviceSmsId in existingIds) continue

                    // Save raw SMS to database
                    val smsRawEntity = SmsRawEntity(
                        sender = sender,
                        body = body,
                        smsDate = smsDate,
                        deviceSmsId = deviceSmsId,
                        isProcessed = false
                    )
                    val smsRawId = smsRawDao.insert(smsRawEntity)

                    // If insert returned -1, the row was ignored (duplicate deviceSmsId)
                    if (smsRawId == -1L) continue

                    // Parse the SMS for transaction details
                    val parseResult = SmsParser.parse(body)
                    if (parseResult != null) {
                        val transaction = TransactionEntity(
                            amount = parseResult.amount,
                            transactionType = parseResult.transactionType,
                            paymentMode = parseResult.paymentMode,
                            categoryId = null,
                            smsRawId = smsRawId,
                            sender = sender,
                            description = body,
                            merchantName = parseResult.merchantName,
                            accountRef = parseResult.accountRef,
                            accountKind = parseResult.accountKind,
                            transactionDate = smsDate,
                            smsDate = smsDate,
                            isCategorized = false
                        )
                        transactionDao.insert(transaction)
                        newTransactionCount++
                    }

                    // Mark the raw SMS as processed regardless of parse success
                    smsRawDao.markProcessed(smsRawId)

                } catch (e: Exception) {
                    Log.e(TAG, "Error processing SMS row", e)
                }
            }
        }

        Log.d(TAG, "Scan complete: $newTransactionCount new transactions found")
        newTransactionCount
    }
}
