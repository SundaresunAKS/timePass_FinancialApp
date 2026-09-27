package com.timepass.financialapp

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.timepass.financialapp.data.local.AppDatabase
import com.timepass.financialapp.data.local.entity.CategoryEntity
import com.timepass.financialapp.mail.MailPrefs
import com.timepass.financialapp.mail.MailSyncWorker
import com.timepass.financialapp.sms.TransactionRepair
import com.timepass.financialapp.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class FinancialApp : Application() {

    val database: AppDatabase by lazy {
        Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            Constants.DATABASE_NAME
        )
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7
            )
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        seedDefaultCategoriesIfFirstRun()
        repairDuplicates()
        reparseStoredTransactions()
        // Email is read only while Google is connected on the Backup screen.
        if (MailPrefs(this).enabled && com.timepass.financialapp.backup.BackupPrefs(this).authorized) {
            MailSyncWorker.schedule(this)
        }
    }

    /**
     * Re-reads every stored transaction with the current parser, once.
     *
     * Rows imported by an earlier version keep whatever the parser of the day decided, and
     * nothing would ever revisit them: the SMS dedupe table stops a rescan, which is what
     * it is for. Without this, a parser fix only helps messages that arrive afterwards --
     * the 800 already on file would keep their wrong payment mode and the bill reminders
     * mistaken for spending would stay in the list forever.
     *
     * The user's own decisions are never overwritten. Only the derived fields are rewritten,
     * and a row is only deleted if it is still untagged and un-ignored.
     */
    private fun reparseStoredTransactions() {
        val prefs = getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(Constants.PREF_REPARSED, false)) return

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                TransactionRepair.reparse(database.transactionDao())
            }.onFailure {
                android.util.Log.e("FinancialApp", "Re-parse failed", it)
            }

            // Marked only after the pass finishes, so a crash mid-way retries next launch.
            prefs.edit().putBoolean(Constants.PREF_REPARSED, true).apply()
        }
    }

    /**
     * Clears duplicate transactions left behind by a restore that wiped the SMS dedupe
     * table. Cheap and idempotent, so it runs every start rather than needing a migration.
     */
    private fun repairDuplicates() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val removed = database.transactionDao().removeDuplicates()
                if (removed > 0) {
                    android.util.Log.w("FinancialApp", "Removed $removed duplicate transactions")
                }
            }
        }
    }

    private fun seedDefaultCategoriesIfFirstRun() {
        val prefs = getSharedPreferences(Constants.PREF_NAME, Context.MODE_PRIVATE)
        val isFirstRun = prefs.getBoolean(Constants.PREF_FIRST_RUN, true)

        if (isFirstRun) {
            CoroutineScope(Dispatchers.IO).launch {
                database.categoryDao().insertAll(defaultCategories())
                prefs.edit().putBoolean(Constants.PREF_FIRST_RUN, false).apply()
            }
        }
    }

    private fun defaultCategories(): List<CategoryEntity> = listOf(
        CategoryEntity(name = "Personal", emoji = "👤",          colorHex = "#E91E63", sortOrder = 0),
        CategoryEntity(name = "Travel", emoji = "✈️",             colorHex = "#9C27B0", sortOrder = 1),
        CategoryEntity(name = "Health/medical", emoji = "🏥",     colorHex = "#F44336", sortOrder = 2),
        CategoryEntity(name = "Rent", emoji = "🏠",               colorHex = "#3F51B5", sortOrder = 3),
        CategoryEntity(name = "Home-Krr", emoji = "🏡",           colorHex = "#2196F3", sortOrder = 4),
        CategoryEntity(name = "Savings", emoji = "💰",            colorHex = "#4CAF50", sortOrder = 5),
        CategoryEntity(name = "EmergencySavings", emoji = "🚨",   colorHex = "#FF9800", sortOrder = 6),
        CategoryEntity(name = "Debt", emoji = "📉",               colorHex = "#795548", sortOrder = 7),
        CategoryEntity(name = "Parumal Box", emoji = "📦",        colorHex = "#607D8B", sortOrder = 8),
        CategoryEntity(name = "Mva Acc", emoji = "🏦",            colorHex = "#009688", sortOrder = 9),
        CategoryEntity(name = "ShortLoan", emoji = "⏳",          colorHex = "#FF5722", sortOrder = 10),
        CategoryEntity(name = "Income", emoji = "💵",             colorHex = "#8BC34A", isIncome = true, sortOrder = 11),
        CategoryEntity(name = "ReturnShortLoan", emoji = "🔁",    colorHex = "#00BCD4", isIncome = true, sortOrder = 12),
        CategoryEntity(name = "Food", emoji = "🍔",               colorHex = "#FFC107", sortOrder = 13),
        CategoryEntity(name = "Fuel", emoji = "⛽",               colorHex = "#CDDC39", sortOrder = 14),
        CategoryEntity(name = "Loan", emoji = "🧾",               colorHex = "#D32F2F", sortOrder = 15),
        CategoryEntity(name = "Others", emoji = "🔖",             colorHex = "#9E9E9E", sortOrder = 16),
        CategoryEntity(name = "Savings-ToDad", emoji = "👨",      colorHex = "#4DB6AC", sortOrder = 17),
        CategoryEntity(name = "Savings-PPF", emoji = "🏛️",        colorHex = "#66BB6A", sortOrder = 18)
    )
}
