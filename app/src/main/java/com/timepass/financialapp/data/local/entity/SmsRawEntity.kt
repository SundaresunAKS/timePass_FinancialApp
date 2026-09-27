package com.timepass.financialapp.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "sms_raw", indices = [Index(value = ["deviceSmsId"], unique = true)])
data class SmsRawEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sender: String,
    val body: String,
    val smsDate: Long,
    val deviceSmsId: Long,
    val isProcessed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
