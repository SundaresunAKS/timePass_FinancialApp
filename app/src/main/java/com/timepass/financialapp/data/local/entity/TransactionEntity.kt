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
    /** Channel the transaction was first read from; see [TransactionSource]. */
    val source: String = TransactionSource.SMS,
    /** Identity of the message this row was read from: the Gmail message id for MAIL. */
    val sourceRef: String? = null,
    /**
     * The other channel that reported the same transaction, e.g. "MAIL" on an SMS row.
     *
     * A bank usually sends both an SMS and an email for one payment. Only the first to
     * arrive becomes a row; the second is recorded here instead of as a duplicate, which
     * also marks this row as already paired so it cannot absorb a second, different one.
     */
    val confirmedBy: String? = null,
    /**
     * Identity of the message folded in under [confirmedBy] -- a Gmail message id, or
     * "sender
body" for an SMS -- so reading that message again is recognised as already
     * handled rather than imported as a new row.
     */
    val confirmedRef: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Values of [TransactionEntity.source] and [TransactionEntity.confirmedBy]. */
object TransactionSource {
    const val SMS = "SMS"
    const val MAIL = "MAIL"
    const val MANUAL = "MANUAL"
}
