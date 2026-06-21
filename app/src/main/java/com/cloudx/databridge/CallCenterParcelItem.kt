package com.cloudx.databridge

data class CallCenterParcelItem(
    val id: String,
    val customer: String,
    val phone: String,
    val address: String,
    val cod: Int,
    val status: String,
    val remarks: String,
    val validationRequest: Boolean,
    val validationNote: String,
    val time: String,
    val worker: String,
    val branch: String
)
