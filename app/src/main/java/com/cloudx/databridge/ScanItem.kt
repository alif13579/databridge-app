package com.cloudx.databridge

data class ScanItem(
    val id: Long,
    val code: String,
    val scanAt: Long,
    val manual: Boolean,
    val uploaded: Boolean = false,
    val firebaseKey: String = "" // Firebase node key for uploaded items
)
