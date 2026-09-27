package com.timepass.financialapp.mail

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.timepass.financialapp.FinancialApp
import com.timepass.financialapp.backup.BackupError
import java.util.concurrent.TimeUnit

/**
 * Checks Gmail for new bank alerts in the background.
 *
 * SMS arrive through a broadcast the moment they land; email has no equivalent the app
 * can listen to without a server, so it is polled instead.
 */
class MailSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? FinancialApp ?: return Result.failure()
        return try {
            val added = MailReader(applicationContext, app.database).syncIfEnabled()
            Log.d(TAG, "Background mail sync: $added added")
            Result.success()
        } catch (e: BackupError) {
            // Consent lapsed or access was refused: retrying cannot fix that, the user can.
            if (e.retryable) Result.retry() else Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Background mail sync failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "MailSyncWorker"
        private const val WORK_NAME = "mail_sync"
        private const val INTERVAL_HOURS = 3L

        /** Idempotent: an existing schedule is kept, so calling this on every start is fine. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MailSyncWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
