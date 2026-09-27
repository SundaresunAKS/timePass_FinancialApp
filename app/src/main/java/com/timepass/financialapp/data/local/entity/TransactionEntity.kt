package com.timepass.financialapp.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("categoryId"),
        Index("isCategorized"),
        Index("isIgnored"),
        Index("transactionDate"),
        Index("sender"),
        Index("accountRef")
    ]
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val amount: Double,
    val transactionType: String,  // "DEBIT" or "CREDIT"
    val paymentMode: String,      // "UPI", "CREDIT_CARD", "DEBIT_CARD", "NEFT", "UNKNOWN"
    val categoryId: Long? = null,
    val smsRawId: Long? = null,
    /** SMS header the transaction came from, e.g. "AX-INDUSB-S". Copied from
     *  sms_raw so lists and filters do not need a join. */
    val sender: String? = null,
    val description: String,
    val merchantName: String? = null,
    /**
     * Last four digits of the account or card the transaction touched, e.g. "8607".
     *
     * Only the masked tail is kept, which is all the SMS itself discloses -- enough to
     * group a month by card, and not enough to identify the account.
     */
    val accountRef: String? = null,
    /** What [accountRef] identifies: "ACCOUNT" or "CARD". */
    val accountKind: String? = null,
    /** Optional free-text note the user adds when tagging. */
    val note: String? = null,
    val transactionDate: Long,
    val smsDate: Long,
    val isCategorized: Boolean = false,
    /**
     * Set aside as not worth tracking. Ignored rows leave the Pending inbox
     * without being given a category, and are excluded from all totals.
     */
    val isIgnored: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
