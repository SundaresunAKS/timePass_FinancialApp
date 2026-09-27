package com.timepass.financialapp.backup

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.timepass.financialapp.FinancialApp
import java.util.concurrent.TimeUnit

/**
 * The scheduled backup.
 *
 * Distinguishes retryable failures (offline, rate limited, server error) from ones that
 * need the user (consent expired, folder deleted, Drive full). Retrying the latter would
 * burn battery and quota to fail identically each time.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? FinancialApp ?: return Result.failure()
        val prefs = BackupPrefs(applicationContext)

        if (!prefs.isConnected) {
            Log.d(TAG, "Drive not connected; nothing to do")
            return Result.success()
        }

        return try {
            BackupRepository(applicationContext, app.database, prefs).backupNow()
            Log.d(TAG, "Scheduled backup complete")
            Result.success()
        } catch (e: BackupError) {
            if (e.retryable) {
                Log.w(TAG, "Backup failed, will retry: ${e.detail ?: e::class.java.simpleName}")
                Result.retry()
            } else {
                Log.e(TAG, "Backup failed, needs the user: ${e::class.java.simpleName}")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed unexpectedly", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BackupWorker"
        private const val WORK_NAME = "drive_backup"

        /**
         * Applies the user's schedule. Replaces any existing work so changing frequency or
         * constraints takes effect immediately rather than at the next period.
         */
        fun schedule(context: Context, prefs: BackupPrefs) {
            val manager = WorkManager.getInstance(context)
            val frequency = prefs.frequency

            if (frequency == BackupFrequency.OFF) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(
                    if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
                )
                .setRequiresCharging(prefs.chargingOnly)
                .build()

            val request = PeriodicWorkRequestBuilder<BackupWorker>(
                frequency.days, TimeUnit.DAYS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.EXPONENTIAL,
                    30, TimeUnit.MINUTES
                )
                .build()

            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
