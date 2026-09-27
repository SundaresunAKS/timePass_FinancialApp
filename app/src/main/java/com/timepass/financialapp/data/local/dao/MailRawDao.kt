package com.timepass.financialapp.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.timepass.financialapp.data.local.entity.MailRawEntity

@Dao
interface MailRawDao {

    @Query("SELECT messageId FROM mail_raw")
    suspend fun getAllMessageIds(): List<String>

    @Query("SELECT sender FROM mail_raw WHERE messageId = :messageId LIMIT 1")
    suspend fun senderOf(messageId: String): String?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(mail: MailRawEntity): Long

    /** Forgets emails before [cutoff]; receivedAt 0 marks restore placeholders, kept. */
    @Query("DELETE FROM mail_raw WHERE receivedAt > 0 AND receivedAt < :cutoff")
    suspend fun deleteBefore(cutoff: Long): Int

    @Query("DELETE FROM mail_raw")
    suspend fun deleteAll()
}
