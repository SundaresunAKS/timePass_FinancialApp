package com.timepass.financialapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.timepass.financialapp.data.local.entity.SmsRawEntity

@Dao
interface SmsRawDao {

    @Query("SELECT deviceSmsId FROM sms_raw")
    suspend fun getAllDeviceSmsIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(sms: SmsRawEntity): Long

    @Query("SELECT * FROM sms_raw WHERE isProcessed = 0")
    suspend fun getUnprocessed(): List<SmsRawEntity>

    @Query("UPDATE sms_raw SET isProcessed = 1 WHERE id = :id")
    suspend fun markProcessed(id: Long)

    @Query("DELETE FROM sms_raw")
    suspend fun deleteAll()
}
