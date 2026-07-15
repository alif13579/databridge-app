package com.cloudx.databridge

data class CallCenterParcelItem(
    val id: String,
    val customer: String,
    val phone: String,
    val address: String,
    val cod: Int,
    val status: String,
    val remarks: String,
    // The latest remark's own status key (e.g. "verify_req") — independent of `status`
    // (the parcel's real delivery status). Never written to courier/consignments/{id}/status.
    val remarkStatus: String = "",
    val validationRequest: Boolean,
    val validationNote: String,
    val time: String,
    val worker: String,
    val branch: String,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val attemptCount: Int = 0,
    val history: List<HistoryEntry> = emptyList()
) {
    /** remarkStatus (if set) always takes priority over the raw parcel status — this is
     *  what the card's status chip shows and what filters/tabs match against. */
    val effectiveStatus: String get() = remarkStatus.ifBlank { status }
}
