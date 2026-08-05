package com.cloudx.databridge

/**
 * Shared constants/models for the Petty Cash Management feature (mockup: "Accounts
 * Dashboard" -> "Filter/Search", 10 screens total). This is the feature's brand-new
 * entry point behind the existing "Approvals" drawer item (nav_approvals permission),
 * which was previously just a PagePlaceholderFragment.
 *
 * Screens are being built back-to-front (10 -> 1) as a separate, parallel effort to
 * screens 1-3 being built elsewhere, so this file is intentionally the single shared
 * source of truth for status/category vocabulary + the filter contract every list
 * screen (Pending Settlement, Deposit History, Settlement History, All Requests) will
 * consume. If a differently-shaped model already exists from the other side by the
 * time this merges, reconcile against THIS file rather than duplicating a second one.
 */

// ── Settlement request status ──────────────────────────────────────────────────
// Firebase-stored string values -- keep stable once any data is written.
const val SETTLEMENT_STATUS_PENDING = "pending_approval"
const val SETTLEMENT_STATUS_APPROVED = "approved"   // = "Approved (Waiting Settlement)" in mockup
const val SETTLEMENT_STATUS_SETTLED = "settled"
const val SETTLEMENT_STATUS_REJECTED = "rejected"

/** Display label for a status constant, matching mockup wording exactly. */
fun settlementStatusLabel(status: String): String = when (status) {
    SETTLEMENT_STATUS_PENDING -> "Pending Approval"
    SETTLEMENT_STATUS_APPROVED -> "Approved (Waiting Settlement)"
    SETTLEMENT_STATUS_SETTLED -> "Settled"
    SETTLEMENT_STATUS_REJECTED -> "Rejected"
    else -> status
}

// ── Category vocabulary ─────────────────────────────────────────────────────────
// ASSUMPTION: no petty-cash expense-category list exists anywhere in the app/Firebase
// yet (checked). Seeded from what's visible across the mockup's request cards. Treat
// as a starting point, not a locked spec -- easy to extend in one place.
object PettyCashCategories {
    val EXPENSE_CATEGORIES = listOf(
        "Travel Expense", "Fuel Expense", "Stationery", "Office Supplies",
        "Meals & Entertainment", "Utilities", "Maintenance", "Other"
    )

    // ASSUMPTION: "Worker Category" (screen 10) is kept as its own small list rather
    // than reusing EmployeeFragment.ROLE_LABELS directly, since the mockup gives no
    // evidence it's meant to be exactly the system role list. Revisit once screen 1/8
    // (requester context) lands and it's clear whether these should be unified.
    val WORKER_CATEGORIES = listOf("Worker", "Agent", "Supervisor", "Stuff", "Incharge")
}

// ── Filter contract (screen 10 produces this; screens 3/6/7/8 consume it) ──────
data class SettlementFilterCriteria(
    val fromDateMillis: Long? = null,
    val toDateMillis: Long? = null,
    val statuses: Set<String> = DEFAULT_STATUSES,
    val category: String? = null,        // null = "All Categories"
    val workerCategory: String? = null,  // null = "All Categories"
) {
    companion object {
        val DEFAULT_STATUSES = setOf(
            SETTLEMENT_STATUS_PENDING, SETTLEMENT_STATUS_APPROVED,
            SETTLEMENT_STATUS_SETTLED, SETTLEMENT_STATUS_REJECTED,
        )

        /** Rebuild criteria from a Fragment Result bundle -- see PettyCashFilterFragment. */
        fun fromBundle(bundle: android.os.Bundle): SettlementFilterCriteria {
            val from = bundle.getLong(PettyCashFilterFragment.RESULT_FROM_MILLIS, -1L).takeIf { it >= 0 }
            val to = bundle.getLong(PettyCashFilterFragment.RESULT_TO_MILLIS, -1L).takeIf { it >= 0 }
            val statuses = bundle.getStringArrayList(PettyCashFilterFragment.RESULT_STATUSES)?.toSet()
                ?: DEFAULT_STATUSES
            val category = bundle.getString(PettyCashFilterFragment.RESULT_CATEGORY)?.takeIf { it.isNotBlank() }
            val workerCategory = bundle.getString(PettyCashFilterFragment.RESULT_WORKER_CATEGORY)?.takeIf { it.isNotBlank() }
            return SettlementFilterCriteria(from, to, statuses, category, workerCategory)
        }
    }

    /** Bundle form used to send this back via setFragmentResult. */
    fun toBundle(): android.os.Bundle = android.os.Bundle().apply {
        putLong(PettyCashFilterFragment.RESULT_FROM_MILLIS, fromDateMillis ?: -1L)
        putLong(PettyCashFilterFragment.RESULT_TO_MILLIS, toDateMillis ?: -1L)
        putStringArrayList(PettyCashFilterFragment.RESULT_STATUSES, ArrayList(statuses))
        putString(PettyCashFilterFragment.RESULT_CATEGORY, category.orEmpty())
        putString(PettyCashFilterFragment.RESULT_WORKER_CATEGORY, workerCategory.orEmpty())
    }

    /** True when every filter is at its default ("no filtering") state. */
    fun isDefault(): Boolean = fromDateMillis == null && toDateMillis == null &&
        statuses == DEFAULT_STATUSES && category == null && workerCategory == null
}
