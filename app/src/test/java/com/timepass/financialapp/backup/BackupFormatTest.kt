package com.timepass.financialapp.backup

import com.timepass.financialapp.data.local.entity.CategoryEntity
import com.timepass.financialapp.data.local.entity.TransactionEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round-trip tests for the backup file.
 *
 * The format is the one thing in the app that has to survive being written by one version
 * and read by another, and a restore wipes the database before applying what it parsed --
 * so a decode that quietly loses a field costs real data.
 */
class BackupFormatTest {

    private val categories = listOf(
        CategoryEntity(id = 1, name = "Food", emoji = "🍔", colorHex = "#FFC107", sortOrder = 0),
        CategoryEntity(id = 2, name = "Income", colorHex = "#8BC34A", isIncome = true, sortOrder = 1)
    )

    private val transactions = listOf(
        TransactionEntity(
            id = 10,
            amount = 113.17,
            transactionType = "DEBIT",
            paymentMode = "UPI",
            categoryId = 1,
            sender = "AX-INDUSB-S",
            description = "A/C *XX8607 debited by Rs 113.17 towards shop@ybl",
            merchantName = "shop",
            note = "lunch",
            transactionDate = 1_700_000_000_000,
            smsDate = 1_700_000_000_000,
            isCategorized = true,
            isIgnored = false
        ),
        TransactionEntity(
            id = 11,
            amount = 500.0,
            transactionType = "CREDIT",
            paymentMode = "UNKNOWN",
            description = "credited by Rs.500",
            transactionDate = 1_700_100_000_000,
            smsDate = 1_700_100_000_000,
            isCategorized = false,
            isIgnored = true
        )
    )

    private val settings = BackupFormat.Settings(
        themeMode = "dark",
        accent = "indigo",
        biometricEnabled = true
    )

    @Test
    fun `settings survive a round trip`() {
        val decoded = BackupFormat.decode(
            BackupFormat.encode(categories, transactions, "1.0.0", settings)
        )

        assertEquals("dark", decoded.settings?.themeMode)
        assertEquals("indigo", decoded.settings?.accent)
        assertEquals(true, decoded.settings?.biometricEnabled)
    }

    @Test
    fun `categories and transactions survive a round trip`() {
        val decoded = BackupFormat.decode(
            BackupFormat.encode(categories, transactions, "1.0.0", settings)
        )

        assertEquals(2, decoded.categories.size)
        assertEquals("Food", decoded.categories[0].name)
        assertEquals("🍔", decoded.categories[0].emoji)
        assertTrue(decoded.categories[1].isIncome)

        assertEquals(2, decoded.transactions.size)
        val debit = decoded.transactions[0]
        assertEquals(113.17, debit.amount, 0.001)
        assertEquals("UPI", debit.paymentMode)
        assertEquals("lunch", debit.note)
        assertEquals(1L, debit.categoryId)
        assertTrue(debit.isCategorized)

        // The ignored flag is a user decision; losing it would resurrect set-aside rows.
        assertTrue(decoded.transactions[1].isIgnored)
    }

    @Test
    fun `a v1 file without settings still restores`() {
        val v1 = JSONObject(BackupFormat.encode(categories, transactions, "1.0.0", settings))
            .apply {
                remove("settings")
                put("version", 1)
            }
            .toString()

        val decoded = BackupFormat.decode(v1)

        assertNull(decoded.settings)
        assertEquals(2, decoded.transactions.size)
    }

    @Test
    fun `a settings block missing a field leaves that field alone`() {
        val partial = JSONObject(BackupFormat.encode(categories, transactions, "1.0.0", settings))
            .apply { getJSONObject("settings").put("biometricEnabled", JSONObject.NULL) }
            .toString()

        val decoded = BackupFormat.decode(partial)

        assertEquals("dark", decoded.settings?.themeMode)
        assertNull(decoded.settings?.biometricEnabled)
    }

    @Test(expected = BackupError.CorruptBackup::class)
    fun `a newer backup is refused rather than half-read`() {
        val newer = JSONObject(BackupFormat.encode(categories, transactions, "9.9", settings))
            .apply { put("version", BackupFormat.VERSION + 1) }
            .toString()

        BackupFormat.decode(newer)
    }
}
