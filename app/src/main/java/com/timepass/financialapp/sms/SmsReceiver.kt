package com.timepass.financialapp.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.data.local.entity.SmsRawEntity
import com.timepass.financialapp.data.local.entity.TransactionEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for incoming SMS messages.
 *
 * Listens for android.provider.Telephony.SMS_RECEIVED and processes bank transaction
 * alerts in real-time. Uses [goAsync] to safely run coroutine-based database operations
 * within the receiver's lifecycle.
 */
class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                processIncomingSms(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing incoming SMS", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Processes incoming SMS messages from the broadcast intent.
     *
     * Extracts each SMS from the PDU extras, checks if it's from a bank sender,
     * verifies the date is after the cutoff, and saves/parses as needed.
     */
    private suspend fun processIncomingSms(context: Context, intent: Intent) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val app = context.applicationContext as FinancialApp
        val database = app.database
        val smsRawDao = database.smsRawDao()
        val transactionDao = database.transactionDao()

        for (smsMessage in messages) {
            val sender = smsMessage.displayOriginatingAddress ?: continue
            val body = smsMessage.displayMessageBody ?: continue
            val smsDate = smsMessage.timestampMillis

            // Only process bank SMS
            if (!SmsSenderFilter.isBankSms(sender)) continue

            // Only process SMS on or after the cutoff date
            if (smsDate < SmsReader.CUTOFF_MILLIS) continue

            try {
                // Use a hash of sender+body+date as a pseudo device SMS ID for incoming messages.
                // The actual device SMS ID is not available until the message is written to
                // the SMS provider, which may happen asynchronously.
                val pseudoDeviceId = (sender + body + smsDate).hashCode().toLong() and 0x7FFFFFFFL

                // Save raw SMS
                val smsRawEntity = SmsRawEntity(
                    sender = sender,
                    body = body,
                    smsDate = smsDate,
                    deviceSmsId = pseudoDeviceId,
                    isProcessed = false
                )
                val smsRawId = smsRawDao.insert(smsRawEntity)

                // If insert returned -1, it was a duplicate
                if (smsRawId == -1L) continue

                // Parse the SMS
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
                    Log.d(TAG, "New transaction saved: ${parseResult.transactionType} ${parseResult.amount}")
                }

                // Mark as processed
                smsRawDao.markProcessed(smsRawId)

            } catch (e: Exception) {
                Log.e(TAG, "Error saving SMS from $sender", e)
            }
        }
    }
}
