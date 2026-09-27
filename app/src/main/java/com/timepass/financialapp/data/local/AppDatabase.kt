package com.timepass.financialapp.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.timepass.financialapp.data.local.dao.CategoryDao
import com.timepass.financialapp.data.local.dao.MailRawDao
import com.timepass.financialapp.data.local.dao.SmsRawDao
import com.timepass.financialapp.data.local.dao.TransactionDao
import com.timepass.financialapp.data.local.entity.CategoryEntity
import com.timepass.financialapp.data.local.entity.MailRawEntity
import com.timepass.financialapp.data.local.entity.SmsRawEntity
import com.timepass.financialapp.data.local.entity.TransactionEntity

@Database(
    entities = [
        TransactionEntity::class, CategoryEntity::class, SmsRawEntity::class, MailRawEntity::class
    ],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun categoryDao(): CategoryDao
    abstract fun smsRawDao(): SmsRawDao
    abstract fun mailRawDao(): MailRawDao

    companion object {

        /**
         * v2 adds `transactions.sender`, so a transaction carries the SMS header it came
         * from ("AX-INDUSB-S") without joining sms_raw on every query.
         *
         * Existing rows are backfilled from sms_raw via smsRawId. Rows whose raw SMS is
         * gone keep a null sender and simply do not match a sender filter -- no data is
         * dropped or rewritten.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN sender TEXT")

                db.execSQL(
                    """
                    UPDATE transactions
                    SET sender = (
                        SELECT s.sender FROM sms_raw s WHERE s.id = transactions.smsRawId
                    )
                    WHERE smsRawId IS NOT NULL
                    """.trimIndent()
                )

                // Name must match Room's generated identity hash, i.e. index_<table>_<column>.
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_sender " +
                        "ON transactions (sender)"
                )
            }
        }

        /**
         * v3 adds `transactions.note`, the optional free-text the user can attach when
         * tagging. Existing rows get null, which renders as no note.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN note TEXT")
            }
        }

        /**
         * v4 adds `categories.emoji`. The seeded categories are matched by name and given
         * a default, so an existing install gains icons without being reset. A category
         * the user renamed or created keeps a null emoji and falls back to its initials.
         */
        /**
         * v5 adds `transactions.isIgnored`, the third inbox state. Existing rows default
         * to 0, so nothing moves out of Pending or Tagged.
         */
        /**
         * Adds the account/card reference the lists group by.
         *
         * Left null here rather than backfilled in SQL: deriving it needs the parser, so
         * the one-off re-parse on the next start fills it in for existing rows.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN accountRef TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN accountKind TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_transactions_accountRef ON transactions(accountRef)")
            }
        }

        /**
         * v7 reads bank emails as well as SMS: `transactions.source` says which channel a
         * row came from, `confirmedBy` records the other channel reporting the same
         * payment (the refs identify the messages), and `mail_raw` is the email dedupe table.
         *
         * Every existing row came from an SMS except the hand-entered ones, which are the
         * only rows with the MANUAL payment mode.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN source TEXT NOT NULL DEFAULT 'SMS'")
                db.execSQL("ALTER TABLE transactions ADD COLUMN sourceRef TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN confirmedBy TEXT")
                db.execSQL("ALTER TABLE transactions ADD COLUMN confirmedRef TEXT")
                db.execSQL("UPDATE transactions SET source = 'MANUAL' WHERE paymentMode = 'MANUAL'")

                // Must match what Room generates for MailRawEntity, or schema validation fails.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS mail_raw (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        messageId TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        subject TEXT NOT NULL,
                        receivedAt INTEGER NOT NULL,
                        isProcessed INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_mail_raw_messageId ON mail_raw (messageId)"
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN isIgnored INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_transactions_isIgnored " +
                        "ON transactions (isIgnored)"
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE categories ADD COLUMN emoji TEXT")
                db.execSQL("UPDATE categories SET emoji = '👤' WHERE name = 'Personal' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '✈️' WHERE name = 'Travel' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '🏥' WHERE name = 'Health/medical' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '🏠' WHERE name = 'Rent' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '🏡' WHERE name = 'Home-Krr' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '💰' WHERE name = 'Savings' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '🚨' WHERE name = 'EmergencySavings' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '📉' WHERE name = 'Debt' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '📦' WHERE name = 'Parumal Box' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '🏦' WHERE name = 'Mva Acc' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '⏳' WHERE name = 'ShortLoan' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '💵' WHERE name = 'Income' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '🔁' WHERE name = 'ReturnShortLoan' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '🍔' WHERE name = 'Food' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '⛽' WHERE name = 'Fuel' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '🧾' WHERE name = 'Loan' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '🔖' WHERE name = 'Others' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '👨' WHERE name = 'Savings-ToDad' AND emoji IS NULL")
                db.execSQL("UPDATE categories SET emoji = '🏛️' WHERE name = 'Savings-PPF' AND emoji IS NULL")
            }
        }
    }
}
