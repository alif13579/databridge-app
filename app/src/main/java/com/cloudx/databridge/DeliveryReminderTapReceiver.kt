package com.cloudx.databridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Notification-tap target for "ডেলিভারি আপডেট দরকার": rebuilds the reminder
 * [DeliveryReminderOverlay.Data] from the tap extras and shows the same
 * overlay popup (parcel details + 3 Bangla options) the alarm path shows.
 * Self-fired only (exported=false) — no overlay permission means the
 * notification falls back to opening the app instead (see
 * DeliveryReminderReceiver.showNotification).
 */
class DeliveryReminderTapReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val consignmentId = intent.getStringExtra(DeliveryReminderReceiver.EXTRA_CONSIGNMENT).orEmpty()
        if (consignmentId.isBlank()) return
        val data = DeliveryReminderOverlay.Data(
            consignmentId = consignmentId,
            branchId = intent.getStringExtra("branch_id").orEmpty(),
            assignedAgentSystemId = intent.getStringExtra("assigned_agent").orEmpty(),
            customerName = intent.getStringExtra("customer_name").orEmpty(),
            customerPhone = intent.getStringExtra("customer_phone").orEmpty(),
            address = intent.getStringExtra("address").orEmpty(),
            status = intent.getStringExtra("status").orEmpty(),
            cod = intent.getDoubleExtra("cod", 0.0),
            ccRemarkText = intent.getStringExtra("cc_remark").orEmpty(),
            ccAuthorName = intent.getStringExtra("cc_author").orEmpty(),
            ccRemarkAtMs = intent.getLongExtra("cc_at_ms", 0L),
            otherPendingCount = intent.getIntExtra("other_pending", 0),
        )
        DeliveryReminderOverlay.show(context.applicationContext, data)
    }
}
