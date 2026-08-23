package com.cloudx.databridge

import android.util.Log
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Manages Supabase Realtime WebSocket subscriptions for the validations table.
 *
 * Replaces the polling mechanism in CallCenterFragment and WorkerSpaceFragment.
 * No Edge Function invocations are consumed — Realtime uses a persistent
 * WebSocket connection and the free tier allows 2 million messages/month.
 *
 * Usage (one call per Fragment.onResume, cancel in onPause/onDestroyView):
 *
 *   val job = SupabaseRealtimeManager.subscribeValidations(
 *       channelKey  = "cc_branch_$branchId",
 *       filter      = "branch_id" to branchId,
 *       scope       = viewLifecycleOwner.lifecycleScope,
 *   ) { row -> /* update card */ }
 *   // onPause: job.cancel()
 */
object SupabaseRealtimeManager {

    private const val TAG = "SupabaseRealtime"

    /**
     * Subscribes to INSERT events on public.validations filtered by one column.
     *
     * @param channelKey Unique key for this channel (e.g. "cc_branch_dhaka").
     *                   Reusing the same key reconnects to the existing channel.
     * @param filter     Single column→value RLS-safe filter, e.g. "branch_id" to "dhaka".
     * @param scope      Coroutine scope tied to the Fragment's view lifecycle.
     * @param onInsert   Called on the main thread with the new row as [JSONObject].
     * @return           A [Job] — cancel it in onPause / onDestroyView.
     */
    fun subscribeValidations(
        channelKey: String,
        filter: Pair<String, String>,
        scope: CoroutineScope,
        onInsert: (JSONObject) -> Unit,
    ): Job {
        val client = SupabaseClientManager.client
        return scope.launch {
            try {
                val token = SupabaseClientManager.getAccessToken()
                if (token != null) {
                    Log.d(TAG, "[$channelKey] Firebase JWT available for the channel")
                    RemarkPushChainLog.log("RemarkPushChain", "[$channelKey] Firebase JWT available for the channel")
                } else {
                    Log.e(TAG, "[$channelKey] auth update SKIPPED — getAccessToken() returned null; " +
                        "Realtime will connect with anon key only and RLS will block all events")
                    RemarkPushChainLog.log("RemarkPushChain", "[$channelKey] auth update SKIPPED — " +
                        "getAccessToken() returned null; Realtime will connect with anon key only and RLS will block all events", isWarning = true)
                }
                client.realtime.connect()
                Log.i(TAG, "[$channelKey] connect() called — filter: ${filter.first}=${filter.second}")
                RemarkPushChainLog.log("RemarkPushChain", "[$channelKey] connect() called — filter: ${filter.first}=${filter.second}")
                val channel = client.realtime.channel(channelKey)
                channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "validations"
                    filter(filter.first, FilterOperator.EQ, filter.second)
                }.onEach { event ->
                    try {
                        val row = JSONObject(event.record.toString())
                        Log.d(TAG, "[$channelKey] INSERT received — consignment=${row.optString("consignment")} " +
                            "source=${row.optString("source")} status=${row.optString("remarks_status")}")
                        RemarkPushChainLog.log("RemarkPushChain", "[$channelKey] Realtime INSERT received — " +
                            "consignment=${row.optString("consignment")} source=${row.optString("source")} status=${row.optString("remarks_status")}")
                        onInsert(row)
                    } catch (e: Exception) {
                        Log.e(TAG, "[$channelKey] Failed to parse INSERT event: ${e.message}")
                        RemarkPushChainLog.log("RemarkPushChain", "[$channelKey] Failed to parse INSERT event: ${e.message}", isWarning = true)
                    }
                }.launchIn(scope)
                // supabase-kt 3.x exposes JWT updates on each channel, not on the
                // Realtime client (the old client.realtime.setAuth API was removed).
                // MUST run before subscribe(): postgres_changes RLS is evaluated against
                // the channel's auth state at the time it subscribes, so subscribing first
                // (with only the anon key attached) meant my_branch_ids() saw no authenticated
                // uid and every RLS-protected INSERT was silently dropped for that channel.
                token?.let { channel.updateAuth(it) }
                channel.subscribe()
                Log.i(TAG, "[$channelKey] subscribe() called")
                RemarkPushChainLog.log("RemarkPushChain", "[$channelKey] subscribe() called")
            } catch (e: Exception) {
                Log.e(TAG, "[$channelKey] Subscription failed: ${e.message}", e)
                RemarkPushChainLog.log("RemarkPushChain", "[$channelKey] Subscription failed: ${e.message}", isWarning = true)
                FirebaseErrorLogger.log(
                    "SupabaseRealtimeManager", "subscribe_error",
                    e.message ?: "Subscription failed", mapOf("channelKey" to channelKey)
                )
            }
        }
    }
}
