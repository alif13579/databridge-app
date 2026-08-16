package com.cloudx.databridge

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Shared "switch branch" chip behavior for Leave Management screens
 * (My Requests, Queue). Both need the exact same thing: if the signed-in
 * user is assigned to more than one branch, show a chip they can tap to
 * pick which branch's requests they're looking at, remember the choice as
 * their default for next time, and resolve branch ids to display names
 * without racing (a name resolved for one screen is reused by the other).
 *
 * Pulled out instead of duplicated per-fragment because the first version
 * of this (in LeaveMyRequestsFragment alone) only resolved the FIRST
 * branch's name synchronously from RbacManager.current.branchName and left
 * every other branch showing its raw id until the async Firebase lookup
 * finished — a real but easy-to-miss bug once someone has 3+ branches.
 * Centralizing the resolved-name cache here means that bug can only exist
 * once, not once per screen.
 */
object LeaveBranchSwitcher {

    // Shared pref key across My Requests and Queue: if an Incharge/Shift
    // Lead switches branch on the Queue screen, their next visit to My
    // Requests (or vice versa) should open on that same branch — it's one
    // "which branch am I working in right now" choice, not two.
    private const val PREF_KEY_SELECTED_BRANCH = "lm_selected_branch_id"

    // In-memory cache of resolved branch id -> name, shared for the
    // process lifetime so switching screens doesn't re-fetch names that
    // were already resolved.
    private var resolvedNames: Map<String, String> = emptyMap()

    fun rememberedBranchId(context: Context): String? =
        prefs(context).getString(PREF_KEY_SELECTED_BRANCH, null)

    fun remember(context: Context, branchId: String) {
        prefs(context).edit().putString(PREF_KEY_SELECTED_BRANCH, branchId).apply()
    }

    /**
     * Resolves the initial branch to show: the remembered choice if it's
     * still one of the user's assigned branches, else their first
     * assigned branch, else the branchId passed in as a fallback.
     */
    fun resolveInitialBranchId(context: Context, fallback: String): String {
        val assigned = RbacManager.current.branchIds
        val remembered = rememberedBranchId(context)
        return when {
            remembered != null && remembered in assigned -> remembered
            assigned.isNotEmpty() -> assigned.first()
            else -> fallback
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences("databridge_toggles", Context.MODE_PRIVATE)

    /**
     * Wires up a chip TextView as the branch switcher. Hides itself and
     * does nothing if the user only has one (or zero) assigned branches.
     * [currentBranchId] / [onBranchSelected] let the caller own which
     * branch is "current" rather than this object owning that state.
     */
    fun setup(
        context: Context,
        scope: LifecycleCoroutineScope,
        chip: TextView,
        currentBranchId: String,
        onBranchSelected: (branchId: String) -> Unit
    ) {
        val branchIds = RbacManager.current.branchIds
        if (branchIds.size <= 1) {
            chip.isVisible = false
            return
        }

        val arrow = ContextCompat.getDrawable(context, R.drawable.ic_arrow_drop_down_white)?.mutate()
        arrow?.setTint(Color.parseColor("#0F172A"))
        chip.setCompoundDrawablesWithIntrinsicBounds(null, null, arrow, null)
        chip.isVisible = true

        // Seed from RbacManager's own branch (covers the common case of
        // just 2 branches where the primary one is already known) so the
        // chip doesn't show a raw id even for a split second before the
        // async lookup below finishes.
        val primaryId = branchIds.first()
        if (primaryId !in resolvedNames && RbacManager.current.branchName.isNotBlank()) {
            resolvedNames = resolvedNames + (primaryId to RbacManager.current.branchName)
        }
        chip.text = resolvedNames[currentBranchId] ?: "Branch"

        chip.setOnClickListener {
            showPicker(context, scope, branchIds, chip, onBranchSelected)
        }

        val unresolved = branchIds.filter { it !in resolvedNames }
        if (unresolved.isEmpty()) return

        scope.launch {
            val db = FirebaseDatabase.getInstance()
            val resolved = coroutineScope {
                unresolved.associateWith { id ->
                    async {
                        runCatching {
                            db.reference.child("branches/$id/name").get().await().getValue(String::class.java)
                        }.getOrNull()
                    }
                }.mapValues { (id, deferred) -> deferred.await()?.takeIf { it.isNotBlank() } ?: id }
            }
            resolvedNames = resolvedNames + resolved
            chip.text = resolvedNames[currentBranchId] ?: currentBranchId
        }
    }

    /** Call after the caller updates its own current-branch state, to keep the chip label in sync. */
    fun refreshLabel(chip: TextView, branchId: String) {
        chip.text = resolvedNames[branchId] ?: branchId
    }

    private fun showPicker(
        context: Context,
        scope: LifecycleCoroutineScope,
        branchIds: List<String>,
        chip: TextView,
        onBranchSelected: (branchId: String) -> Unit
    ) {
        val labels = branchIds.map { resolvedNames[it] ?: it }.toTypedArray()
        android.app.AlertDialog.Builder(context)
            .setTitle("Switch branch")
            .setItems(labels) { _, index ->
                val newBranchId = branchIds[index]
                remember(context, newBranchId)
                chip.text = resolvedNames[newBranchId] ?: newBranchId
                onBranchSelected(newBranchId)
            }
            .show()
    }
}
