package com.cloudx.databridge

/**
 * 🔹 সিম্পল সিলড ক্লাস (শুধু প্রয়োজনীয় প্রপার্টি)
 */
sealed class HistoryItem {
    // ✅ DateDivider-এ শুধু 'date' প্রপার্টি রাখা হয়েছে (সিম্পলিফাইড)
    data class DateDivider(val date: String) : HistoryItem()
    data class RecordItem(val record: CallRecord) : HistoryItem()
}