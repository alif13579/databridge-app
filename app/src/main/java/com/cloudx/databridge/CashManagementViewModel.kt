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

    fun updateCollection(entryId: String, amount: Double, onDone: (Boolean) -> Unit) {
        if (entryId.isBlank() || amount <= 0.0) { onDone(false); return }
        db.reference.child(FirebasePaths.cashManagementCollections(branchId)).child(entryId).child("amount").setValue(amount)
            .addOnSuccessListener { refresh(); onDone(true) }
            .addOnFailureListener { e ->
                FirebaseErrorLogger.log(
                    screen = "CashManagement", action = "update_collection",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("branchId" to branchId, "entryId" to entryId)
                )
                onDone(false)
            }
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

    fun updateLedgerEntry(providerName: String, type: String, entryId: String, amount: Double, trxId: String, onDone: (Boolean) -> Unit) {
        if (providerName.isBlank() || entryId.isBlank() || amount <= 0.0) { onDone(false); return }
        val updates = mapOf("amount" to amount, "trxId" to trxId.trim())
        db.reference.child(FirebasePaths.cashManagementLedger(branchId)).child(providerName).child(type).child(entryId)
            .updateChildren(updates)
            .addOnSuccessListener { refresh(); onDone(true) }
            .addOnFailureListener { e ->
                FirebaseErrorLogger.log(
                    screen = "CashManagement", action = "update_ledger_entry_$type",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("branchId" to branchId, "provider" to providerName, "entryId" to entryId)
                )
                onDone(false)
            }
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
