package com.cloudx.databridge

import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
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
                client.realtime.connect()
                val channel = client.realtime.channel(channelKey)
                channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
                    table = "validations"
                    this.filter = "${filter.first}=eq.${filter.second}"
                }.onEach { event ->
                    try {
                        val row = JSONObject(event.record.toString())
                        onInsert(row)
                    } catch (_: Exception) {}
                }.launchIn(scope)
                channel.subscribe()
            } catch (e: Exception) {
                FirebaseErrorLogger.log(
                    "SupabaseRealtimeManager", "subscribe_error",
                    e.message ?: "Subscription failed", channelKey
                )
            }
        }
    }
}
