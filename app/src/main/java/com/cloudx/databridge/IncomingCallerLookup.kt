package com.cloudx.databridge

import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

data class CallerMatch(
    val consignmentId: String,
    val name: String,
    val phone: String,
    val address: String,
    val cod: Int,
    val status: String,
    val statusLabel: String,
    val updatedAt: Long,
)

data class CallerLookupResult(
    val primary: CallerMatch,
    val otherCount: Int,
)

/**
 * Reverse phone -> consignment lookup for the incoming-call popup. Reuses the app's
 * existing courier/consignments_by_phone index (ConfigSheetWizardSteps writes it on
 * every consignment insert, keyed by ConfigSheetParseUtil.normalizePhone -- same
 * normalization used here so the key we read matches the key that's actually there)
 * rather than a fresh parallel index.
 */
object IncomingCallerLookup {
    private val db by lazy { FirebaseDatabase.getInstance() }

    /**
     * Looks up every consignment for [rawPhone]. When a customer has more than one,
     * picks the one still awaiting action (isVerifyRequestStatus) as primary, falling
     * back to the most recently updated -- the rest are just counted for "+N more".
     * Returns null on no match or any read failure; callers should treat that as
     * "don't show a popup" rather than retrying.
     */
    suspend fun lookup(rawPhone: String): CallerLookupResult? = withContext(Dispatchers.IO) {
        try {
            val normalized = ConfigSheetParseUtil.normalizePhone(rawPhone)
            if (normalized.isBlank()) return@withContext null

            val idsSnap = db.reference.child("courier/consignments_by_phone/$normalized").get().await()
            if (!idsSnap.exists()) return@withContext null
            val ids = idsSnap.children.mapNotNull { it.key }
            if (ids.isEmpty()) return@withContext null

            // Best-effort; a stale/empty label cache still leaves us the raw status string.
            runCatching { StatusMetaCache.refresh() }

            val matches = coroutineScope {
                ids.map { id ->
                    async {
                        val snap = db.reference.child("courier/consignments/$id").get().await()
                        if (!snap.exists()) return@async null
                        val status = snap.child("status").getValue(String::class.java).orEmpty()
                        CallerMatch(
                            consignmentId = id,
                            name = snap.child("recipientName").getValue(String::class.java).orEmpty(),
                            phone = snap.child("recipientPhone").getValue(String::class.java).orEmpty(),
                            address = snap.child("recipientAddress").getValue(String::class.java).orEmpty(),
                            cod = snap.child("collectableAmount").getValue(String::class.java)?.toDoubleOrNull()?.toInt()
                                ?: snap.child("collectableAmount").getValue(Long::class.java)?.toInt() ?: 0,
                            status = status,
                            statusLabel = StatusMetaCache.labelOrNull(status, "en") ?: status.ifBlank { "Unknown" },
                            updatedAt = snap.child("updatedAt").getValue(Long::class.java) ?: 0L,
                        )
                    }
                }.mapNotNull { it.await() }
            }
            if (matches.isEmpty()) return@withContext null

            val primary = matches.firstOrNull { isVerifyRequestStatus(it.status) }
                ?: matches.maxByOrNull { it.updatedAt }
                ?: matches.first()

            CallerLookupResult(primary = primary, otherCount = matches.size - 1)
        } catch (_: Exception) {
            null
        }
    }
}
