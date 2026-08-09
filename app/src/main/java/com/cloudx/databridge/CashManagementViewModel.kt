package com.cloudx.databridge

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.IgnoreExtraProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

// ── Data models ──────────────────────────────────────────────────────────────
// Flow: branch's collected cash -> handed over to an MFS provider (Rocket/bKash/
// Other) -> MFS credits its own balance -> from that MFS balance a "Hub Payment"
// settles some or all of it against the branch's collection. This is a running,
// non-day-locked ledger: any day's collection can be settled same-day, partially,
// or carried forward, since MFS providers may not always have full balance ready.

const val COLLECTION_TYPE_CASH = "cash"
const val COLLECTION_TYPE_ADJUSTMENT = "adjustment"

@IgnoreExtraProperties
data class CashCollectionEntry(
    val id: String = "",
    val amount: Double = 0.0,
    val type: String = COLLECTION_TYPE_CASH,
    val remarks: String = "",
    val timestamp: Long = 0L,
    val enteredByName: String = "",
    val enteredByUid: String = "",
    val isEdited: Boolean = false,
)

@IgnoreExtraProperties
data class CashLedgerEntry(
    val id: String = "",
    val amount: Double = 0.0,
    val trxId: String = "",
    val remarks: String = "",
    val timestamp: Long = 0L,
    val enteredByName: String = "",
    val enteredByUid: String = "",
    val isEdited: Boolean = false,
)

// Audit trail for edits, stored under each entry's own `history/` child (so it travels
// with the entry and needs no separate lookup). "Created" is NOT stored here -- it's
// synthesized from the entry's own timestamp/enteredByName, same approach as this app's
// existing parcel Journey Log (CallCenterFragment.showActionHistoryDialog). Only edits
// (and any future field-level changes) get a real history node.
@IgnoreExtraProperties
data class CashHistoryEntry(
    val changedByName: String = "",
    val changedByUid: String = "",
    val changedAt: Long = 0L,
    val summary: String = "",
)

data class MfsAccountSummary(
    val provider: String,
    val handovers: List<CashLedgerEntry>,
    val hubPayments: List<CashLedgerEntry>,
) {
    val handoverTotal get() = handovers.sumOf { it.amount }
    val hubPaymentTotal get() = hubPayments.sumOf { it.amount }
    // Leftover sitting inside this MFS provider's account: handed over but not yet paid to the hub.
    val balance get() = handoverTotal - hubPaymentTotal
    val hasActivity get() = handovers.isNotEmpty() || hubPayments.isNotEmpty()
    val settled get() = hasActivity && balance == 0.0
}

data class CashManagementSummary(
    val totalCollection: Double = 0.0,
    val totalHandover: Double = 0.0,
    val totalHubPayment: Double = 0.0,
) {
    // Collected but not yet handed over to any MFS provider.
    val cashInHand get() = totalCollection - totalHandover
    // Sitting inside MFS channel balances: handed over but not yet paid to the hub.
    val totalMfsBalance get() = totalHandover - totalHubPayment
    // Outstanding against total collection: cash-in-hand + whatever is still sitting
    // inside MFS provider balances (handoverTotal - hubPaymentTotal, summed).
    val toBePaid get() = totalCollection - totalHubPayment
}

sealed class CashManagementState {
    object Loading : CashManagementState()
    data class Success(
        val summary: CashManagementSummary,
        val accounts: List<MfsAccountSummary>,
        val collections: List<CashCollectionEntry>,
        val defaultProvider: String?,
    ) : CashManagementState()
    data class Error(val message: String) : CashManagementState()
}

// A single row for CSV export, combining Collections/Deposits/Payments into one
// shape via `type` so all three can be exported together, sorted by date, instead
// of three separate per-mode exports (see CashLedgerListFragment for the
// single-mode equivalent of this same mapping).
data class CashExportRow(
    val timestamp: Long,
    val type: String, // "Collection" | "Deposit" | "Payment"
    val amount: Double,
    val channel: String?, // null for Collections -- no channel concept there
    val trxId: String,    // "" for Collections
    val enteredByName: String,
    val remarks: String,
)

fun CashManagementState.Success.toExportRows(): List<CashExportRow> {
    val collectionRows = collections.map {
        CashExportRow(it.timestamp, "Collection", it.amount, null, "", it.enteredByName, it.remarks)
    }
    val depositRows = accounts.flatMap { acc ->
        acc.handovers.map {
            CashExportRow(it.timestamp, "Deposit", it.amount, acc.provider, it.trxId, it.enteredByName, it.remarks)
        }
    }
    val paymentRows = accounts.flatMap { acc ->
        acc.hubPayments.map {
            CashExportRow(it.timestamp, "Payment", it.amount, acc.provider, it.trxId, it.enteredByName, it.remarks)
        }
    }
    return (collectionRows + depositRows + paymentRows).sortedByDescending { it.timestamp }
}

const val LEDGER_TYPE_HANDOVER = "handovers"
const val LEDGER_TYPE_HUB_PAYMENT = "hub_payments"

class CashManagementViewModel : ViewModel() {

    private val db = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _state = MutableLiveData<CashManagementState>(CashManagementState.Loading)
    val state: LiveData<CashManagementState> = _state

    private var branchId: String = ""

    fun load(branchId: String) {
        this.branchId = branchId
        _state.value = CashManagementState.Loading
        viewModelScope.launch {
            try {
                val (collectionsSnap, providersSnap, ledgerSnap, defaultProviderSnap) = withContext(Dispatchers.IO) {
                    coroutineScope {
                        val collectionsDeferred = async { db.reference.child(FirebasePaths.cashManagementCollections(branchId)).get().await() }
                        val providersDeferred   = async { db.reference.child(FirebasePaths.cashManagementProviders(branchId)).get().await() }
                        val ledgerDeferred      = async { db.reference.child(FirebasePaths.cashManagementLedger(branchId)).get().await() }
                        val defaultDeferred     = async { db.reference.child(FirebasePaths.cashManagementDefaultProvider(branchId)).get().await() }
                        listOf(collectionsDeferred.await(), providersDeferred.await(), ledgerDeferred.await(), defaultDeferred.await())
                    }
                }

                val collectionList = collectionsSnap.children
                    .mapNotNull { snap -> snap.getValue(CashCollectionEntry::class.java)?.copy(id = snap.key.orEmpty()) }
                    .sortedByDescending { it.timestamp }

                val providerNames = providersSnap.children.mapNotNull { it.key }

                val accounts = providerNames.map { provider ->
                    val providerSnap = ledgerSnap.child(provider)
                    val handovers = providerSnap.child(LEDGER_TYPE_HANDOVER).children
                        .mapNotNull { snap -> snap.getValue(CashLedgerEntry::class.java)?.copy(id = snap.key.orEmpty()) }
                        .sortedByDescending { it.timestamp }
                    val hubPayments = providerSnap.child(LEDGER_TYPE_HUB_PAYMENT).children
                        .mapNotNull { snap -> snap.getValue(CashLedgerEntry::class.java)?.copy(id = snap.key.orEmpty()) }
                        .sortedByDescending { it.timestamp }
                    MfsAccountSummary(provider, handovers, hubPayments)
                }

                val summary = CashManagementSummary(
                    totalCollection = collectionList.sumOf { it.amount },
                    totalHandover   = accounts.sumOf { it.handoverTotal },
                    totalHubPayment = accounts.sumOf { it.hubPaymentTotal },
                )

                val defaultProvider = defaultProviderSnap.getValue(String::class.java)?.takeIf { it.isNotBlank() }

                _state.value = CashManagementState.Success(summary, accounts, collectionList, defaultProvider)
            } catch (e: Exception) {
                _state.value = CashManagementState.Error(e.message ?: "Failed to load cash management data")
            }
        }
    }

    fun refresh() {
        if (branchId.isNotBlank()) load(branchId)
    }

    private fun currentUserName(): String =
        auth.currentUser?.displayName?.takeIf { it.isNotBlank() }
            ?: auth.currentUser?.email?.takeIf { it.isNotBlank() }
            ?: "Unknown"

    private fun plainTaka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${java.text.NumberFormat.getNumberInstance(java.util.Locale.US).format(whole)}"
    }

    private fun historyEntryMap(summary: String): Map<String, Any?> = mapOf(
        "changedByName" to currentUserName(),
        "changedByUid" to (auth.currentUser?.uid ?: ""),
        "changedAt" to System.currentTimeMillis(),
        "summary" to summary,
    )

    /** Path to a Collection entry, for history lookups from the UI layer. */
    fun collectionEntryPath(entryId: String) = "${FirebasePaths.cashManagementCollections(branchId)}/$entryId"

    /** Path to a Deposit/Payment (ledger) entry, for history lookups from the UI layer. */
    fun ledgerEntryPath(providerName: String, type: String, entryId: String) =
        "${FirebasePaths.cashManagementLedger(branchId)}/$providerName/$type/$entryId"

    /** Reads the `history/` child at [entryPath] (an entry path from the two helpers above), newest first. */
    fun loadHistory(entryPath: String, onResult: (List<CashHistoryEntry>) -> Unit) {
        db.reference.child(entryPath).child("history").get()
            .addOnSuccessListener { snap ->
                onResult(snap.children.mapNotNull { it.getValue(CashHistoryEntry::class.java) }.sortedByDescending { it.changedAt })
            }
            .addOnFailureListener { onResult(emptyList()) }
    }

    fun addCollection(amount: Double, type: String, remarks: String, timestampMillis: Long, onDone: (Boolean) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid == null || amount <= 0.0) { onDone(false); return }
        val ref = db.reference.child(FirebasePaths.cashManagementCollections(branchId)).push()
        val entry = mapOf(
            "amount" to amount,
            "type" to type,
            "remarks" to remarks.trim(),
            "timestamp" to timestampMillis,
            "enteredByName" to currentUserName(),
            "enteredByUid" to uid,
        )
        ref.setValue(entry)
            .addOnSuccessListener { refresh(); onDone(true) }
            .addOnFailureListener { e ->
                FirebaseErrorLogger.log(
                    screen = "CashManagement", action = "add_collection",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("branchId" to branchId)
                )
                onDone(false)
            }
    }

    fun setDefaultProvider(providerName: String, onDone: (Boolean) -> Unit = {}) {
        if (branchId.isBlank()) { onDone(false); return }
        db.reference.child(FirebasePaths.cashManagementDefaultProvider(branchId)).setValue(providerName)
            .addOnSuccessListener { refresh(); onDone(true) }
            .addOnFailureListener { onDone(false) }
    }

    fun addProvider(providerName: String, onDone: (Boolean) -> Unit = {}) {
        val name = providerName.trim()
        if (name.isBlank() || branchId.isBlank()) { onDone(false); return }
        db.reference.child(FirebasePaths.cashManagementProviders(branchId)).child(name).setValue(true)
            .addOnSuccessListener { refresh(); onDone(true) }
            .addOnFailureListener { e ->
                FirebaseErrorLogger.log(
                    screen = "CashManagement", action = "add_provider",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("branchId" to branchId, "provider" to name)
                )
                onDone(false)
            }
    }

    // Removing a channel now deletes its ledger history along with the marker --
    // previously this only hid the channel (ledger data stayed in Firebase), which
    // meant re-adding the same name later silently resurrected old handover/hub-
    // payment records. That was surprising, so removal is a real delete now.
    fun removeProvider(providerName: String, onDone: (Boolean) -> Unit = {}) {
        if (providerName.isBlank() || branchId.isBlank()) { onDone(false); return }
        val updates: Map<String, Any?> = mapOf(
            "${FirebasePaths.cashManagementProviders(branchId)}/$providerName" to null,
            "${FirebasePaths.cashManagementLedger(branchId)}/$providerName" to null,
        )
        db.reference.updateChildren(updates)
            .addOnSuccessListener { refresh(); onDone(true) }
            .addOnFailureListener { e ->
                FirebaseErrorLogger.log(
                    screen = "CashManagement", action = "remove_provider",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("branchId" to branchId, "provider" to providerName)
                )
                onDone(false)
            }
    }

    fun updateCollection(entryId: String, amount: Double, remarks: String, timestampMillis: Long, onDone: (Boolean) -> Unit) {
        if (entryId.isBlank() || amount <= 0.0) { onDone(false); return }
        val ref = db.reference.child(FirebasePaths.cashManagementCollections(branchId)).child(entryId)
        ref.get().addOnSuccessListener { snap ->
            val old = snap.getValue(CashCollectionEntry::class.java)
            if (old == null) { onDone(false); return@addOnSuccessListener }

            val changes = mutableListOf<String>()
            if (old.amount != amount) changes.add("Amount ${plainTaka(old.amount)} \u2192 ${plainTaka(amount)}")
            if (old.remarks.trim() != remarks.trim()) changes.add("Remarks updated")
            if (old.timestamp != timestampMillis) changes.add("Date changed")
            if (changes.isEmpty()) { onDone(true); return@addOnSuccessListener }

            val updates = mapOf(
                "amount" to amount,
                "remarks" to remarks.trim(),
                "timestamp" to timestampMillis,
                "isEdited" to true,
            )
            ref.updateChildren(updates)
                .addOnSuccessListener {
                    ref.child("history").push().setValue(historyEntryMap(changes.joinToString("; ")))
                    refresh(); onDone(true)
                }
                .addOnFailureListener { e ->
                    FirebaseErrorLogger.log(
                        screen = "CashManagement", action = "update_collection",
                        errorMessage = e.message ?: "unknown",
                        extra = mapOf("branchId" to branchId, "entryId" to entryId)
                    )
                    onDone(false)
                }
        }.addOnFailureListener { onDone(false) }
    }

    fun deleteCollection(entryId: String, onDone: (Boolean) -> Unit) {
        if (entryId.isBlank()) { onDone(false); return }
        db.reference.child(FirebasePaths.cashManagementCollections(branchId)).child(entryId).removeValue()
            .addOnSuccessListener { refresh(); onDone(true) }
            .addOnFailureListener { e ->
                FirebaseErrorLogger.log(
                    screen = "CashManagement", action = "delete_collection",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("branchId" to branchId, "entryId" to entryId)
                )
                onDone(false)
            }
    }

    fun updateLedgerEntry(providerName: String, type: String, entryId: String, amount: Double, trxId: String, remarks: String, timestampMillis: Long, onDone: (Boolean) -> Unit) {
        if (providerName.isBlank() || entryId.isBlank() || amount <= 0.0) { onDone(false); return }
        val ref = db.reference.child(FirebasePaths.cashManagementLedger(branchId)).child(providerName).child(type).child(entryId)
        ref.get().addOnSuccessListener { snap ->
            val old = snap.getValue(CashLedgerEntry::class.java)
            if (old == null) { onDone(false); return@addOnSuccessListener }

            val changes = mutableListOf<String>()
            if (old.amount != amount) changes.add("Amount ${plainTaka(old.amount)} \u2192 ${plainTaka(amount)}")
            if (old.trxId.trim() != trxId.trim()) changes.add("TRX ID updated")
            if (old.remarks.trim() != remarks.trim()) changes.add("Remarks updated")
            if (old.timestamp != timestampMillis) changes.add("Date changed")
            if (changes.isEmpty()) { onDone(true); return@addOnSuccessListener }

            val updates = mapOf(
                "amount" to amount,
                "trxId" to trxId.trim(),
                "remarks" to remarks.trim(),
                "timestamp" to timestampMillis,
                "isEdited" to true,
            )
            ref.updateChildren(updates)
                .addOnSuccessListener {
                    ref.child("history").push().setValue(historyEntryMap(changes.joinToString("; ")))
                    refresh(); onDone(true)
                }
                .addOnFailureListener { e ->
                    FirebaseErrorLogger.log(
                        screen = "CashManagement", action = "update_ledger_entry_$type",
                        errorMessage = e.message ?: "unknown",
                        extra = mapOf("branchId" to branchId, "provider" to providerName, "entryId" to entryId)
                    )
                    onDone(false)
                }
        }.addOnFailureListener { onDone(false) }
    }

    fun deleteLedgerEntry(providerName: String, type: String, entryId: String, onDone: (Boolean) -> Unit) {
        if (providerName.isBlank() || entryId.isBlank()) { onDone(false); return }
        db.reference.child(FirebasePaths.cashManagementLedger(branchId)).child(providerName).child(type).child(entryId).removeValue()
            .addOnSuccessListener { refresh(); onDone(true) }
            .addOnFailureListener { e ->
                FirebaseErrorLogger.log(
                    screen = "CashManagement", action = "delete_ledger_entry_$type",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("branchId" to branchId, "provider" to providerName, "entryId" to entryId)
                )
                onDone(false)
            }
    }

    fun addLedgerEntry(providerName: String, type: String, amount: Double, trxId: String, remarks: String, timestampMillis: Long, onDone: (Boolean) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid == null || providerName.isBlank() || amount <= 0.0) { onDone(false); return }
        val ref = db.reference.child(FirebasePaths.cashManagementLedger(branchId)).child(providerName).child(type).push()
        val entry = mapOf(
            "amount" to amount,
            "trxId" to trxId.trim(),
            "remarks" to remarks.trim(),
            "timestamp" to timestampMillis,
            "enteredByName" to currentUserName(),
            "enteredByUid" to uid,
        )
        ref.setValue(entry)
            .addOnSuccessListener { refresh(); onDone(true) }
            .addOnFailureListener { e ->
                FirebaseErrorLogger.log(
                    screen = "CashManagement", action = "add_ledger_entry_$type",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("branchId" to branchId, "provider" to providerName)
                )
                onDone(false)
            }
    }
}
