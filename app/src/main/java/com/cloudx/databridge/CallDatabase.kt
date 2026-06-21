package com.cloudx.databridge

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CallRecord::class], version = 2, exportSchema = false)
abstract class CallDatabase : RoomDatabase() {
    abstract fun callDao(): CallDao
    companion object {
        @Volatile private var INSTANCE: CallDatabase? = null
        fun getDatabase(context: Context): CallDatabase = INSTANCE ?: synchronized(this) {
            val instance = Room.databaseBuilder(context.applicationContext, CallDatabase::class.java, "databridge_database")
                .fallbackToDestructiveMigration() // ✅ Dev Safe. Prod-এ প্রপার Migration(1,2) ব্যবহার করুন
                .build()
            INSTANCE = instance; instance
        }
    }
}