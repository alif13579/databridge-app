package com.cloudx.databridge

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 📦 CallRecord.kt (Room Entity - Production-Ready v2.0)
 * ✅ সব এক্সিস্টিং ফিল্ড প্রিজার্ভড
 * ✅ নতুন ফিল্ড অ্যাডেড: source, container_id, extension_id (স্মার্ট পাথ রেজোলিউশনের জন্য)
 * ✅ ডিফল্ট ভ্যালু: null → পুরনো ডাটার সাথে কম্প্যাটিবল
 */
@Entity(tableName = "calls")
data class CallRecord(
    @PrimaryKey val id: String,

    val text: String,
    val cleaned: String,
    val type: String,

    @ColumnInfo(name = "received_at") val received_at: Long,

    @ColumnInfo(name = "actions") val actions: String = "{}", // JSON Object as String

    // 🔹 নতুন ফিল্ড: স্মার্ট সিঙ্ক ও ডিলিটের জন্য (ডিফল্ট: null)
    @ColumnInfo(name = "source") val source: String? = null,           // "container" | "session"
    @ColumnInfo(name = "container_id") val container_id: String? = null, // "container_{uid}"
    @ColumnInfo(name = "extension_id") val extension_id: String? = null  // "{extension_id}"
)