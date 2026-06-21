package com.cloudx.databridge

class CallRepository(private val dao: CallDao) {

    suspend fun insertCall(record: CallRecord): Long = dao.insertCall(record)
    suspend fun insertAllCalls(records: List<CallRecord>) = dao.insertAllCalls(records)
    suspend fun updateCall(record: CallRecord) = dao.updateCall(record)

    // ✅ Repository তে সরাসরি অবজেক্ট পাস করা যাবে
    suspend fun deleteCall(record: CallRecord) = dao.deleteCall(record)

    suspend fun deleteAllCalls() = dao.deleteAllCalls()
}