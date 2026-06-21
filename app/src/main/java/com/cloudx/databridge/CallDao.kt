package com.cloudx.databridge

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CallDao {

    /** 🔹 সব রেকর্ড ফেচ করবে (নতুন → পুরনো অর্ডারে) */
    @Query("SELECT * FROM calls ORDER BY received_at DESC")
    fun getAllCalls(): Flow<List<CallRecord>>

    /** 🔹 একটি রেকর্ড ইনসার্ট/আপডেট করবে (ডুপ্লিকেট হলে রিপ্লেস করবে) */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCall(record: CallRecord): Long

    /** 🔹 একাধিক রেকর্ড ইনসার্ট/আপডেট করবে (সিঙ্কের জন্য) */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllCalls(records: List<CallRecord>)

    /** 🔹 একটি রেকর্ড আপডেট করবে (অ্যাকশন লগ সেভের জন্য) */
    @Update
    suspend fun updateCall(record: CallRecord)

    /** 🔹 একটি রেকর্ড অবজেক্ট দিয়ে ডিলিট করবে */
    @Delete
    suspend fun deleteCall(record: CallRecord)

    /** 🔹 সব রেকর্ড ডিলিট করবে (Clear History-এর জন্য) */
    @Query("DELETE FROM calls")
    suspend fun deleteAllCalls()

    /** 🔹 একটি নির্দিষ্ট আইডির রেকর্ড খুঁজবে */
    @Query("SELECT * FROM calls WHERE id = :id LIMIT 1")
    suspend fun getCallById(id: String): CallRecord?
}