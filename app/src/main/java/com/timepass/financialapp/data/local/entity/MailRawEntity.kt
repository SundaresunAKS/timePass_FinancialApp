package com.timepass.financialapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Every bank email that has been looked at, transaction or not -- the mail counterpart
 * of [SmsRawEntity].
 *
 * Keyed on the Gmail message id, which is stable for the life of the message, so a sync
 * never fetches or imports the same email twice.
 */
@Entity(tableName = "mail_raw", indices = [Index(value = ["messageId"], unique = true)])
data class MailRawEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val messageId: String,
    val sender: String,
    val subject: String,
    val receivedAt: Long,
    val isProcessed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
