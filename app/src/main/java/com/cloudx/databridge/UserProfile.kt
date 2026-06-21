package com.cloudx.databridge

import com.google.firebase.database.IgnoreExtraProperties

/**
 * 👤 ইউজার প্রোফাইল ডাটা মডেল
 * ✅ bucketId → containerId আপডেটেড
 * ✅ Firebase Realtime Database-এ সরাসরি ম্যাপ হবে
 */
@IgnoreExtraProperties
data class CompanyInfo(
    val role_id: String = "",
    val branch_ids: List<String> = emptyList(),
    val employee_id: String = "",
    val designation: String = "",
    val agent_type: String = "",
    val salary_model: String = "",
    val salary_type: String = "",
    val fixed_amount: String = "",
    val status: String = ""
)

@IgnoreExtraProperties
data class UserProfile(
    val name: String = "",
    val email: String = "",
    val phone_number: String = "",
    val containerId: String = "",
    val user_id: String = "",
    val photo_url: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val lastActive: Long = System.currentTimeMillis(),
    val company_info: CompanyInfo = CompanyInfo()
)