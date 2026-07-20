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
    /** employee_id / systemId — the same token embedded in run_ids. Unique per agent, unlike
     *  `worker` (display name), which two different agents can share. */
    val workerSystemId: String = "",
    val branch: String,
    val branchIds: List<String> = emptyList(),
    /** createdAt of the specific remark entry shown in `remarks` above — used to render
     *  "Xh Ym ago" under the remark text. 0 when `remarks` is blank (nothing shown). */
    val remarksAt: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** courier/remarks_by_consignment/{id}/engaged_at's timestamp — 0 if nobody has this
     *  parcel's card open right now (or that engagement has gone stale). See
     *  EngagedStateManager for the write/clear/staleness logic this is populated from. */
    val engagedAt: Long = 0L,
    val attemptCount: Int = 0,
    val history: List<HistoryEntry> = emptyList()
) {
    /** remarkStatus (if set) always takes priority over the raw parcel status — this is
     *  what the card's status chip shows and what filters/tabs match against. */
    val effectiveStatus: String get() = remarkStatus.ifBlank { status }
}
