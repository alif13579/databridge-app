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
        if (data["type"] != "remark") return

        val title = data["title"]?.takeIf { it.isNotBlank() } ?: "নতুন রিমার্ক"
        val body = data["body"]?.takeIf { it.isNotBlank() } ?: "একটি নতুন রিমার্ক এসেছে"
        AppNotificationManager.add(
            applicationContext,
            AppNotificationManager.NotifItem(
                title = title,
                message = body,
                type = "remark",
                parcelId = data["consignment_id"].orEmpty(),
                scope = data["scope"].orEmpty().ifBlank { "cc" }
            )
        )
    }
}
