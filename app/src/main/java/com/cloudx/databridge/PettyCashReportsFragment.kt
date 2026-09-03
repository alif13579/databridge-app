package com.cloudx.databridge

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Petty Cash — Reports.
 *
 * Reached from the Reports icon in the Dashboard toolbar (previously the
 * bottom action bar's "Reports" item, removed for being a redundant extra
 * bar — see git history for layout_petty_cash_bottom_nav.xml). This is a
 * menu into the report-style screens that already exist elsewhere in the
 * app rather than a new report-building feature:
 *   - All Requests (filterable, already supports date/status/category)
 *   - Settlement History (everyone — shows what's been settled)
 *   - Deposit History (Accounts only — deposits are an Accounts-only action)
 */
class PettyCashReportsFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()
    private var branchId: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_reports, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()

        view.findViewById<View>(R.id.btnPcReportsBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val menu = view.findViewById<LinearLayout>(R.id.layoutPcReportsMenu)
        addMenuRow(menu, "📊", "Claims Report", "Date-range summary with Excel and PDF export") {
            open(ClaimsReportFragment.newInstance())
        }
        addMenuRow(menu, "\uD83D\uDCCB", "All Requests", "Browse and filter every request") {
            open(PettyCashAllRequestsFragment.newInstance(branchId))
        }
        addMenuRow(menu, "\u2705", "Settlement History", "Requests that have been settled") {
            open(PettyCashSettlementHistoryFragment.newInstance(branchId))
        }

        // Deposit History is an Accounts-only action, so only show it once we
        // know this user actually holds that role for this branch — avoid a
        // flash of a row that then has to disappear, wait for state instead.
        // Same gating for the Firebase→Supabase claims drain below: it
        // deletes Firebase data, so only Accounts may run it.
        if (branchId.isNotBlank()) {
            viewModel.state.observe(viewLifecycleOwner) { state ->
                if (state is PettyCashState.Success && state.roles.isAccounts && menu.findViewWithTag<View>("deposit_history") == null) {
                    addMenuRow(menu, "\uD83D\uDCB0", "Deposit History", "Funds deposited into the wallet", tag = "deposit_history") {
                        open(PettyCashDepositHistoryFragment.newInstance(branchId))
                    }
                }
                if (state is PettyCashState.Success && state.roles.isAccounts && menu.findViewWithTag<View>("firebase_claims_migrate") == null) {
                    addMenuRow(menu, "\uD83D\uDD04", "Migrate Firebase Claims", "Copy verified claims to Supabase, remove from Firebase", tag = "firebase_claims_migrate") {
                        showMigrateConfirm()
                    }
                }
            }
            viewModel.load(branchId)
        }
    }

    /** One-way drain: Firebase claims → Supabase, deleting each Firebase
     *  original only after its Supabase copy reads back field-for-field
     *  identical (see FirebaseClaimsMigrator). Anything unmatched stays in
     *  Firebase and is reported — re-running drains the rest, so Firebase
     *  trends to zero over successive runs. Covers ALL branches. */
    private fun showMigrateConfirm() {
        AlertDialog.Builder(requireContext())
            .setTitle("Migrate Firebase claims?")
            .setMessage(
                "Reads every claim request still in Firebase, saves each one " +
                    "field-wise to Supabase, reads it back, and deletes the " +
                    "Firebase original ONLY on a 100% field match.\n\n" +
                    "Claims that fail or differ stay in Firebase and are listed " +
                    "afterwards — nothing is deleted blindly. Safe to re-run; " +
                    "covers all branches and can take a while."
            )
            .setPositiveButton("Start Migration") { _, _ -> runMigration() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runMigration() {
        val progressDialog = AlertDialog.Builder(requireContext())
            .setTitle("Migrating claims…")
            .setMessage("Starting…")
            .setCancelable(false)
            .create()
            .also { it.show() }
        lifecycleScope.launch {
            val result = runCatching {
                FirebaseClaimsMigrator.migrateAll { done, total ->
                    activity?.runOnUiThread {
                        if (progressDialog.isShowing) progressDialog.setMessage("Checked $done of $total…")
                    }
                }
            }
            runCatching { progressDialog.dismiss() }
            result
                .onSuccess { showMigrationResult(it) }
                .onFailure {
                    Toast.makeText(requireContext(), it.message ?: "Migration failed", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun showMigrationResult(result: FirebaseClaimsMigrator.MigrateResult) {
        val body = buildString {
            append("Firebase claims found: ${result.totalFirebase}\n")
            append("Users ensured in Supabase: ${result.usersEnsured}")
            if (result.userErrors.isNotEmpty()) append(" (${result.userErrors.size} failed)")
            append("\nCopied to Supabase: ${result.copied}\n")
            append("Verified 100% match: ${result.verified}\n")
            append("Deleted from Firebase: ${result.deleted}")
            if (result.userErrors.isNotEmpty()) {
                append("\n\nUser backfill failures: ${result.userErrors.size}")
                result.userErrors.take(8).forEach { append("\n• $it") }
                if (result.userErrors.size > 8) append("\n…and ${result.userErrors.size - 8} more")
            }
            if (result.mismatched.isNotEmpty()) {
                append("\n\nKept in Firebase (differ on read-back): ${result.mismatched.size}")
                result.mismatched.take(8).forEach { append("\n• ${it.claimId}: ${it.fields.joinToString(", ")}") }
                if (result.mismatched.size > 8) append("\n…and ${result.mismatched.size - 8} more")
                append("\n\nUsual cause: the actor's Firebase profile itself is missing data (no system_id) — fix the profile in Firebase, then re-run.")
            }
            if (result.errors.isNotEmpty()) {
                append("\n\nErrors: ${result.errors.size}")
                result.errors.take(8).forEach { append("\n• $it") }
                if (result.errors.size > 8) append("\n…and ${result.errors.size - 8} more")
            }
            if (result.mismatched.isEmpty() && result.errors.isEmpty() && result.totalFirebase == 0) {
                append("\n\nFirebase holds no claims — drain complete.")
            }
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Migration result")
            .setMessage(body)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun addMenuRow(container: LinearLayout, icon: String, title: String, subtitle: String, tag: String? = null, onClick: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_petty_cash_menu_row, container, false)
        row.tag = tag
        row.findViewById<TextView>(R.id.tvPcMenuRowIcon).text = icon
        row.findViewById<TextView>(R.id.tvPcMenuRowTitle).text = title
        row.findViewById<TextView>(R.id.tvPcMenuRowSubtitle).text = subtitle
        row.setOnClickListener { onClick() }
        container.addView(row)
    }

    private fun open(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commitAllowingStateLoss()
    }

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): PettyCashReportsFragment {
            val f = PettyCashReportsFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }
}
