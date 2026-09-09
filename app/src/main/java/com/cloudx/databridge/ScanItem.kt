package com.cloudx.databridge

data class ScanItem(
    val id: Long,
    val code: String,
    val scanAt: Long,
    val manual: Boolean,
    val uploaded: Boolean = false,
    val firebaseKey: String = "", // Firebase node key for uploaded items
    val status: String = "pending", // pending | approved | rejected — read back for All Scans display
    // Denormalized at upload time so the Incharge queue can filter by branch
    // without joining users/{uid} per scan. Old scans predate these (blank).
    val branchId: String = "",
    val employeeId: String = "",
    val agentName: String = "",
    val agentUid: String = "",
    val reviewedBy: String = "",
    val reviewedAt: Long = 0L,
    val sheetWritten: Boolean = false
)
