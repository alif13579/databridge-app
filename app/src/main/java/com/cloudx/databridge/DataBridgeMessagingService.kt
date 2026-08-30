package com.cloudx.databridge

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives FCM data messages. Data-only messages let us use the app's existing
 * notification channel and preserve the parcel/screen destination on notification tap.
 */
class DataBridgeMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        // This can happen long after sign-in; registration is authenticated by the writer.
        SupabaseRemarkValidationWriter.registerPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        RemarkPushChainLog.log("RemarkPushChain", "onMessageReceived: data=$data")
        if (data["type"] != "remark") {
            RemarkPushChainLog.log("RemarkPushChain", "onMessageReceived: ignored, type=${data["type"]}")
            return
        }

        val title = data["title"]?.takeIf { it.isNotBlank() } ?: "নতুন রিমার্ক"
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: "একটি নতুন রিমার্ক এসেছে"
        val parcelId = data["consignment_id"].orEmpty()
        val scope = data["scope"].orEmpty()

        // scope="worker" means CC just wrote a remark this worker hasn't answered yet --
        // exactly what DeliveryReminderReceiver checks for. FCM data messages reach
        // onMessageReceived() even with the app fully closed (unlike the Realtime
        // subscription, which only runs while WorkerSpaceFragment's view is alive), so
        // this is what re-arms the reminder chain when it had already stopped (nothing
        // was pending on its last check) instead of waiting for the worker to reopen the
        // app. Safe to call even when nothing is actually still pending -- the alarm
        // fires once, finds nothing, and simply doesn't reschedule again.
        if (scope == "worker") {
            DeliveryReminderReceiver.arm(applicationContext)
        }

        RemarkPushChainLog.log("RemarkPushChain", "onMessageReceived: parcelId=$parcelId scope=$scope " +
            "-> AppNotificationManager.add()")
        AppNotificationManager.add(
            applicationContext,
            AppNotificationManager.NotifItem(
                title = title,
                message = body,
                type = "remark",
                parcelId = parcelId,
                scope = scope
            )
        )
    }
}
