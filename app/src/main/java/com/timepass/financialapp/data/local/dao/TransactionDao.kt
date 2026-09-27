package com.timepass.financialapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.timepass.financialapp.data.local.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

data class CategorySpending(
    val categoryId: Long,
    val total: Double
)

/** How heavily a category is used, across all time. Drives the Manage Categories list. */
data class CategoryUsage(
    val categoryId: Long,
    val txnCount: Int,
    val total: Double
)

/**
 * Month ranges throughout this DAO are half-open: `transactionDate >= start AND < end`,
 * where `end` is the first instant of the following month. `BETWEEN` is inclusive on both
 * sides, so a transaction landing exactly on midnight of the 1st would be counted in two
 * consecutive months.
 */
/** One account reference as seen from one sender; see AccountLabels. */
data class AccountLabelSource(
    val accountRef: String,
    val accountKind: String?,
    val sender: String?,
    val count: Int
)

@Dao
interface TransactionDao {

    /**
     * Single filtered feed backing both the Uncategorized and Categorized screens.
     *
     * Every filter except the month range is optional; passing null for [type],
     * [categoryId] or [search] leaves that dimension unfiltered.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE isCategorized = :categorized
          AND isIgnored = :ignored
          AND transactionDate >= :startMillis
          AND transactionDate < :endMillis
          AND (:typeCount = 0 OR transactionType IN (:types))
          AND (:categoryCount = 0 OR categoryId IN (:categoryIds))
          AND (:modeCount = 0 OR paymentMode IN (:paymentModes))
          AND (:senderCount = 0 OR sender IN (:senders))
          AND (:accountCount = 0 OR accountRef IN (:accountRefs))
          AND (:search IS NULL
               OR description LIKE '%' || :search || '%'
               OR IFNULL(merchantName, '') LIKE '%' || :search || '%')
        ORDER BY transactionDate DESC
        """
    )
    fun getFiltered(
        categorized: Boolean,
        ignored: Boolean,
        startMillis: Long,
        endMillis: Long,
        types: List<String>,
        typeCount: Int,
        categoryIds: List<Long>,
        categoryCount: Int,
        paymentModes: List<String>,
        modeCount: Int,
        senders: List<String>,
        senderCount: Int,
        accountRefs: List<String>,
        accountCount: Int,
        search: String?
    ): Flow<List<TransactionEntity>>

    /**
     * Account and card references present in the rows currently on screen.
     *
     * Scoped like the payment-mode list, and for the same reason: offering an account that
     * holds nothing in the visible month would be a choice that returns an empty list.
     */
    @Query(
        """
        SELECT DISTINCT accountRef FROM transactions
        WHERE isCategorized = :categorized
          AND isIgnored = :ignored
          AND accountRef IS NOT NULL
          AND transactionDate >= :startMillis
          AND transactionDate < :endMillis
          AND (:typeCount = 0 OR transactionType IN (:types))
          AND (:modeCount = 0 OR paymentMode IN (:paymentModes))
          AND (:senderCount = 0 OR sender IN (:senders))
        ORDER BY accountRef
        """
    )
    fun getFilteredAccountRefs(
        categorized: Boolean,
        ignored: Boolean,
        startMillis: Long,
        endMillis: Long,
        types: List<String>,
        typeCount: Int,
        paymentModes: List<String>,
        modeCount: Int,
        senders: List<String>,
        senderCount: Int
    ): Flow<List<String>>

    /**
     * Payment modes occurring in the rows the user is currently looking at.
     *
     * Scoped to the same month and filters as [getFiltered], minus the mode itself, so the
     * dialog never offers a choice that would return nothing. Without the scoping it would
     * list every mode ever seen, most of them irrelevant to the visible month.
     */
    @Query(
        """
        SELECT DISTINCT paymentMode FROM transactions
        WHERE isCategorized = :categorized
          AND isIgnored = :ignored
          AND transactionDate >= :startMillis
          AND transactionDate < :endMillis
          AND (:typeCount = 0 OR transactionType IN (:types))
          AND (:categoryCount = 0 OR categoryId IN (:categoryIds))
          AND (:senderCount = 0 OR sender IN (:senders))
        ORDER BY paymentMode
        """
    )
    fun getPaymentModes(
        categorized: Boolean,
        ignored: Boolean,
        startMillis: Long,
        endMillis: Long,
        types: List<String>,
        typeCount: Int,
        categoryIds: List<Long>,
        categoryCount: Int,
        senders: List<String>,
        senderCount: Int
    ): Flow<List<String>>

    /**
     * Bank SMS headers occurring in the rows the user is currently looking at, e.g.
     * "AX-INDUSB-S". Scoped like [getPaymentModes], minus the sender itself.
     */
    @Query(
        """
        SELECT DISTINCT sender FROM transactions
        WHERE isCategorized = :categorized
          AND isIgnored = :ignored
          AND transactionDate >= :startMillis
          AND transactionDate < :endMillis
          AND (:typeCount = 0 OR transactionType IN (:types))
          AND (:categoryCount = 0 OR categoryId IN (:categoryIds))
          AND (:modeCount = 0 OR paymentMode IN (:paymentModes))
          AND sender IS NOT NULL
        ORDER BY sender
        """
    )
    fun getSenders(
        categorized: Boolean,
        ignored: Boolean,
        startMillis: Long,
        endMillis: Long,
        types: List<String>,
        typeCount: Int,
        categoryIds: List<Long>,
        categoryCount: Int,
        paymentModes: List<String>,
        modeCount: Int
    ): Flow<List<String>>

    /** Count of uncategorized transactions in a month, for the filter-bar badge. */
    @Query(
        """
        SELECT COUNT(*) FROM transactions
        WHERE isCategorized = 0
          AND isIgnored = 0
          AND transactionDate >= :startMillis
          AND transactionDate < :endMillis
          AND (:accountRef IS NULL OR accountRef = :accountRef)
        """
    )
    fun getUncategorizedCount(startMillis: Long, endMillis: Long, accountRef: String?): Flow<Int>

    @Query("SELECT * FROM transactions WHERE isCategorized = 0 ORDER BY transactionDate DESC")
    fun getUncategorized(): Flow<List<TransactionEntity>>

    /**
     * The month's newest transactions, whatever state they are in.
     *
     * Tagged or not is irrelevant here: the dashboard shows this so the screen has
     * something on it before any tagging has been done.
     */
    @Query(
        """
        SELECT * FROM transactions
        WHERE isIgnored = 0
          AND transactionDate >= :startMillis
          AND transactionDate < :endMillis
          AND (:accountRef IS NULL OR accountRef = :accountRef)
        ORDER BY transactionDate DESC, id DESC
        LIMIT :limit
        """
    )
    fun getRecent(startMillis: Long, endMillis: Long, limit: Int, accountRef: String?): Flow<List<TransactionEntity>>

    @Query(
        """
        SELECT categoryId, SUM(amount) AS total
        FROM transactions
        WHERE isCategorized = 1
          AND transactionType = 'DEBIT'
          AND transactionDate >= :startMillis
          AND transactionDate < :endMillis
          AND (:accountRef IS NULL OR accountRef = :accountRef)
        GROUP BY categoryId
        """
    )
    fun getSpendingByCategory(startMillis: Long, endMillis: Long, accountRef: String?): Flow<List<CategorySpending>>

    /**
     * Totals count only tagged transactions, deliberately.
     *
     * Raw alerts over-report: one credit card bill payment arrives as three separate SMS
     * from three banks, each a valid transaction on its own. Tagging is where the user
     * decides which of them is the real movement, so counting untagged rows would inflate
     * the month. A zero total means there is tagging to do, which the hint below says.
     */
    @Query(
        """
        SELECT SUM(amount) FROM transactions
        WHERE transactionType = 'CREDIT'
          AND isCategorized = 1
          AND transactionDate >= :startMillis
          AND transactionDate < :endMillis
          AND (:accountRef IS NULL OR accountRef = :accountRef)
        """
    )
    fun getTotalIncome(startMillis: Long, endMillis: Long, accountRef: String?): Flow<Double?>

    @Query(
        """
        SELECT SUM(amount) FROM transactions
        WHERE transactionType = 'DEBIT'
          AND isCategorized = 1
          AND transactionDate >= :startMillis
          AND transactionDate < :endMillis
          AND (:accountRef IS NULL OR accountRef = :accountRef)
        """
    )
    fun getTotalExpense(startMillis: Long, endMillis: Long, accountRef: String?): Flow<Double?>

    @Query(
        """
        UPDATE transactions
        SET categoryId = :categoryId, isCategorized = 1, isIgnored = 0,
            note = :note, updatedAt = :now
        WHERE id = :transactionId
        """
    )
    suspend fun categorize(
        transactionId: Long,
        categoryId: Long,
        note: String?,
        now: Long = System.currentTimeMillis()
    )

    /**
     * Moves a transaction out of Pending without giving it a category.
     *
     * Ignored rows keep their data -- this is a filing decision, not a delete -- and are
     * excluded from every total because they never become categorized.
     */
    @Query("UPDATE transactions SET isIgnored = 1, updatedAt = :now WHERE id = :transactionId")
    suspend fun ignore(transactionId: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET isIgnored = 0, updatedAt = :now WHERE id = :transactionId")
    suspend fun unignore(transactionId: Long, now: Long = System.currentTimeMillis())

    /**
     * Returns a single transaction to the Pending inbox.
     *
     * The note is deliberately kept: it describes the transaction, not the category, so
     * discarding it would lose text the user typed for an unrelated reason.
     */
    @Query(
        """
        UPDATE transactions
        SET categoryId = NULL, isCategorized = 0, isIgnored = 0, updatedAt = :now
        WHERE id = :transactionId
        """
    )
    suspend fun untag(transactionId: Long, now: Long = System.currentTimeMillis())

    @Query("SELECT * FROM transactions WHERE isCategorized = 1 ORDER BY transactionDate DESC")
    fun getAllCategorized(): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions WHERE categoryId = :categoryId")
    suspend fun getCountByCategory(categoryId: Long): Int

    @Query(
        """
        SELECT categoryId AS categoryId,
               COUNT(*) AS txnCount,
               SUM(amount) AS total
        FROM transactions
        WHERE categoryId IS NOT NULL
        GROUP BY categoryId
        """
    )
    fun getCategoryUsage(): Flow<List<CategoryUsage>>

    /**
     * Clears the category from every transaction pointing at it and returns them to the
     * Uncategorized inbox. The foreign key is ON DELETE SET NULL, which would otherwise
     * leave rows flagged categorized with no category -- invisible on every screen.
     */
    @Query(
        """
        UPDATE transactions
        SET categoryId = NULL, isCategorized = 0, updatedAt = :now
        WHERE categoryId = :categoryId
        """
    )
    suspend fun uncategorizeByCategory(categoryId: Long, now: Long = System.currentTimeMillis())

    @Insert
    suspend fun insert(transaction: TransactionEntity)

    /**
     * Removes rows that duplicate another on sender, message and amount.
     *
     * Neither the timestamp nor the text is compared for equality. The same message can
     * arrive twice -- once through the broadcast receiver as it lands, once through the
     * inbox scan afterwards -- and the two copies differ in both:
     *
     *  - the timestamps by under a second, because the receiver uses the message's own
     *    time while the provider stores its own;
     *  - the text by however much of a multipart message had arrived, so the receiver's
     *    copy is often a truncated prefix of the provider's.
     *
     * So a pair counts as duplicate when one message is a prefix of the other, from the
     * same sender, for the same amount, within the tolerance. Two genuinely separate
     * payments diverge at their reference number, so neither is a prefix of the other.
     *
     * Keeps the lowest id, which is the older row and therefore the one carrying any tags
     * or notes the user added. Idempotent, so it is safe to run on every start.
     */
    @Query(
        """
        DELETE FROM transactions
        WHERE id IN (
            SELECT later.id
            FROM transactions AS later
            JOIN transactions AS earlier
              ON earlier.id < later.id
             AND earlier.amount = later.amount
             AND IFNULL(earlier.sender, '') = IFNULL(later.sender, '')
             AND substr(earlier.description, 1, min(length(earlier.description), length(later.description)))
               = substr(later.description, 1, min(length(earlier.description), length(later.description)))
             AND ABS(earlier.transactionDate - later.transactionDate) <= :toleranceMillis
        )
        """
    )
    suspend fun removeDuplicates(toleranceMillis: Long = 120_000L): Int

    // --- Bulk actions from multi-select ---

    @Query(
        """
        UPDATE transactions
        SET categoryId = :categoryId, isCategorized = 1, isIgnored = 0,
            note = :note, updatedAt = :now
        WHERE id IN (:ids)
        """
    )
    suspend fun categorizeAll(
        ids: List<Long>,
        categoryId: Long,
        note: String?,
        now: Long = System.currentTimeMillis()
    )

    @Query("UPDATE transactions SET isIgnored = 1, updatedAt = :now WHERE id IN (:ids)")
    suspend fun ignoreAll(ids: List<Long>, now: Long = System.currentTimeMillis())

    @Query("UPDATE transactions SET isIgnored = 0, updatedAt = :now WHERE id IN (:ids)")
    suspend fun unignoreAll(ids: List<Long>, now: Long = System.currentTimeMillis())

    @Query(
        """
        UPDATE transactions
        SET categoryId = NULL, isCategorized = 0, isIgnored = 0, updatedAt = :now
        WHERE id IN (:ids)
        """
    )
    suspend fun untagAll(ids: List<Long>, now: Long = System.currentTimeMillis())

    // --- Backup / restore ---

    @Query("SELECT * FROM transactions ORDER BY id")
    suspend fun getAllForBackup(): List<TransactionEntity>

    @Insert
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    // --- Re-parse repair ---

    /**
     * Rewrites the fields the parser derives. The user's own fields -- category, note,
     * ignored -- are deliberately untouched.
     */
    @Query(
        """
        UPDATE transactions
        SET paymentMode = :paymentMode,
            merchantName = :merchantName,
            transactionType = :transactionType,
            accountRef = :accountRef,
            accountKind = :accountKind,
            updatedAt = :now
        WHERE id = :id
        """
    )
    suspend fun updateParsedFields(
        id: Long,
        paymentMode: String,
        merchantName: String?,
        transactionType: String,
        accountRef: String?,
        accountKind: String?,
        now: Long = System.currentTimeMillis()
    )

    /**
     * Which sender each account reference is seen with, so the UI can name the bank.
     *
     * Grouped rather than raw: a dozen accounts across a handful of senders is a few
     * dozen rows, where the ungrouped form would be every transaction in the database.
     */
    @Query(
        """
        SELECT accountRef AS accountRef,
               accountKind AS accountKind,
               sender AS sender,
               COUNT(*) AS count
        FROM transactions
        WHERE accountRef IS NOT NULL
        GROUP BY accountRef, accountKind, sender
        """
    )
    fun getAccountLabelSources(): Flow<List<AccountLabelSource>>

    /** Distinct account and card references present in a month, for the filter. */
    @Query(
        """
        SELECT DISTINCT accountRef FROM transactions
        WHERE accountRef IS NOT NULL
          AND transactionDate >= :startMillis
          AND transactionDate < :endMillis
        ORDER BY accountRef
        """
    )
    fun getAccountRefs(startMillis: Long, endMillis: Long): Flow<List<String>>

    /**
     * Drops rows the parser no longer considers transactions.
     *
     * Restricted to untouched rows: if the user has tagged or ignored something, that
     * judgement outranks the parser's.
     */
    @Query("DELETE FROM transactions WHERE id IN (:ids) AND isCategorized = 0 AND isIgnored = 0")
    suspend fun deleteUntouched(ids: List<Long>): Int

    /**
     * Removes rows outright.
     *
     * Restricted to manual entries: an SMS-derived row would simply be re-imported by the
     * next scan, so deleting one would look like it had not worked.
     */
    @Query("DELETE FROM transactions WHERE id IN (:ids) AND paymentMode = 'MANUAL'")
    suspend fun deleteManual(ids: List<Long>): Int
}
