package com.cloudx.databridge

import com.google.firebase.database.IgnoreExtraProperties

@IgnoreExtraProperties
data class Branch(
    val branch_id: String      = "",
    val branch_code: String    = "",
    val name: String           = "",
    val branch_type: String    = "",
    val address: String        = "",
    val latitude: Double       = 0.0,
    val longitude: Double      = 0.0,
    val email: String          = "",
    val phone: String          = "",
    val manager_uid: String    = "",
    val manager_name: String   = "",
    val accountant_uid: String = "",
    val accountant_name: String = "",
    val accountant_role: String = "",
    val petty_cash_poc_uid: String = "",
    val petty_cash_poc_name: String = "",
    val staff_uid: String = "",
    val staff_name: String = "",
    val staff_role: String = "",
    val parent_branch_id: String = "",
    val status: String         = "active",
    val image_url: String      = "",
    val created_by: String     = "",
    val created_at: Long       = 0L,
    val updated_at: Long       = 0L,
    val updated_log: Map<String, UpdateLogEntry> = emptyMap()
)

@IgnoreExtraProperties
data class UpdateLogEntry(
    val by_uid: String   = "",
    val by_name: String  = "",
    val action: String   = "",
    val at: Long         = 0L
)
