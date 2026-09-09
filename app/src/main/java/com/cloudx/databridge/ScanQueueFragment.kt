package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.launch

/**
 * Scan Approval queue (Incharge screen).
 *
 * Agent uploads land as status=pending (timestamp-ordered by scan_at).
 * Any Incharge of the branch acts first (shared queue, same role-NAME
 * pattern as Leave). Single approve/reject per row + bulk bar
 * (select-all, approve selected, reject selected). Approve writes the
 * scan_text into the branch's scanner-connector sheet at the agent's
 * employee_id blank row; sheet failures queue for retry without
 * blocking the approval.
 */
class ScanQueueFragment : Fragment() {

    private val viewModel: ScanApprovalViewModel by viewModels()

    private var branchId: String = ""
    private var filter: String = "pending"

    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tvCount: TextView
    private lateinit var chipBranch: TextView
    private lateinit var chipPending: TextView
    private lateinit var chipApproved: TextView
    private lateinit var chipRejected: TextView
    private lateinit var btnSelectAll: Button
    private lateinit var btnApproveSel: Button
    private lateinit var btnRejectSel: Button
    private lateinit var btnRetry: Button
    private lateinit var progress: View

    @Volatile private var acting = false
    private var lastSuccess: ScanQueueState.Success? = null

    private val adapter = ScanQueueAdapter(
        onApprove = { scan ->
            actSingle {
                viewModel.approve(scan, requireContext().applicationContext) { ok, sheet ->
                    activity?.runOnUiThread {
                        if (ok && sheet.isBlank()) toast("✓ Approved + sheet updated")
                        else if (ok) toast("✓ Approved (sheet retry queued: $sheet)")
                        else toast("Approve failed: $sheet")
                    }
                }
            }
        },
        onReject = { scan ->
            actSingle {
                viewModel.reject(scan) { ok ->
                    activity?.runOnUiThread { toast(if (ok) "✕ Rejected" else "Reject failed") }
                }
            }
        },
        onSelectionChanged = { count -> updateBulkBar(count) }
    )

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        fun newInstance(branchId: String): ScanQueueFragment {
            val f = ScanQueueFragment()
            f.arguments = Bundle().apply { putString(ARG_BRANCH_ID, branchId) }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_scan_queue, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = LeaveBranchSwitcher.resolveInitialBranchId(
            requireContext(), arguments?.getString(ARG_BRANCH_ID).orEmpty()
        )
        rv = view.findViewById(R.id.rvScanQueue)
        tvEmpty = view.findViewById(R.id.tvScanQueueEmpty)
        tvCount = view.findViewById(R.id.tvScanQueueCount)
        chipBranch = view.findViewById(R.id.tvScanQueueBranch)
        chipPending = view.findViewById(R.id.chipScanPending)
        chipApproved = view.findViewById(R.id.chipScanApproved)
        chipRejected = view.findViewById(R.id.chipScanRejected)
        btnSelectAll = view.findViewById(R.id.btnScanQueueSelectAll)
        btnApproveSel = view.findViewById(R.id.btnScanQueueApproveSel)
        btnRejectSel = view.findViewById(R.id.btnScanQueueRejectSel)
        btnRetry = view.findViewById(R.id.btnScanQueueRetry)
        progress = view.findViewById(R.id.pbScanQueueLoading)

        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        view.findViewById<View>(R.id.btnScanQueueBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        chipPending.setOnClickListener { filter = "pending"; render() }
        chipApproved.setOnClickListener { filter = "approved"; render() }
        chipRejected.setOnClickListener { filter = "rejected"; render() }

        btnSelectAll.setOnClickListener { adapter.selectAll() }
        btnApproveSel.setOnClickListener {
            val sel = adapter.selectedScans().filter { it.status.equals("pending", ignoreCase = true) }
            if (sel.isEmpty()) { toast("Select pending scans first"); return@setOnClickListener }
            if (acting) return@setOnClickListener
            acting = true
            progress.visibility = View.VISIBLE
            viewModel.approveAll(sel, requireContext().applicationContext) { res ->
                acting = false
                activity?.runOnUiThread {
                    progress.visibility = View.GONE
                    val sheetNote = if (res.sheetRetry > 0) " • sheet retry: ${res.sheetRetry}" else ""
                    toast("✓ ${res.acted} approved${if (res.failed > 0) " • failed: ${res.failed}" else ""}$sheetNote")
                }
            }
        }
        btnRejectSel.setOnClickListener {
            val sel = adapter.selectedScans().filter { it.status.equals("pending", ignoreCase = true) }
            if (sel.isEmpty()) { toast("Select pending scans first"); return@setOnClickListener }
            if (acting) return@setOnClickListener
            acting = true
            progress.visibility = View.VISIBLE
            viewModel.rejectAll(sel) { acted, failed ->
                acting = false
                activity?.runOnUiThread {
                    progress.visibility = View.GONE
                    toast("✕ $acted rejected${if (failed > 0) " • failed: $failed" else ""}")
                }
            }
        }
        btnRetry.setOnClickListener {
            progress.visibility = View.VISIBLE
            viewModel.retryPendingWrites(requireContext().applicationContext) { ok, pending ->
                activity?.runOnUiThread {
                    progress.visibility = View.GONE
                    toast(if (pending == 0) "✓ Sheet queue clear ($ok written)" else "↻ $ok written • $pending still pending")
                }
            }
        }

        LeaveBranchSwitcher.setup(
            context = requireContext(),
            scope = viewLifecycleOwner.lifecycleScope,
            chip = chipBranch,
            currentBranchId = branchId
        ) { newBranchId ->
            if (newBranchId != branchId) {
                branchId = newBranchId
                lastSuccess = null
                viewModel.load(branchId)
            }
        }

        viewModel.state.observe(viewLifecycleOwner) { state ->
            when (state) {
                is ScanQueueState.Loading -> {
                    progress.visibility = View.VISIBLE
                }
                is ScanQueueState.Error -> {
                    progress.visibility = View.GONE
                    tvEmpty.visibility = View.VISIBLE
                    tvEmpty.text = "⚠ ${state.message}"
                }
                is ScanQueueState.Success -> {
                    progress.visibility = View.GONE
                    lastSuccess = state
                    if (!state.isIncharge) {
                        tvEmpty.visibility = View.VISIBLE
                        tvEmpty.text = "Only Incharge can approve scans"
                        adapter.items = emptyList()
                        return@observe
                    }
                    render()
                    // Auto-retry queued sheet writes once per load, quietly.
                    if (state.retryCount > 0) {
                        viewLifecycleOwner.lifecycleScope.launch {
                            viewModel.retryPendingWrites(requireContext().applicationContext) { _, _ -> }
                        }
                    }
                }
            }
        }
        viewModel.load(branchId)
    }

    private inline fun actSingle(crossinline block: () -> Unit) {
        if (acting) return
        acting = true
        progress.visibility = View.VISIBLE
        block()
        viewLifecycleOwner.lifecycleScope.launch {
            // state observer refreshes; release the gate shortly after.
            kotlinx.coroutines.delay(1200)
            acting = false
            runCatching { progress.visibility = View.GONE }
        }
    }

    private fun render() {
        val state = lastSuccess ?: return
        val list = when (filter) {
            "approved" -> state.approved.sortedByDescending { it.scanAt }
            "rejected" -> state.rejected.sortedByDescending { it.scanAt }
            else -> state.pending.sortedByDescending { it.scanAt }
        }
        adapter.items = list
        val p = state.pending.size
        tvCount.text = "⏳ $p pending • ✓ ${state.approved.size} • ✕ ${state.rejected.size}"
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        tvEmpty.text = when (filter) {
            "approved" -> "No approved scans"
            "rejected" -> "No rejected scans"
            else -> "✅\n\nNo pending scans"
        }
        paintChip(chipPending, filter == "pending")
        paintChip(chipApproved, filter == "approved")
        paintChip(chipRejected, filter == "rejected")
        btnRetry.visibility = if (state.retryCount > 0) View.VISIBLE else View.GONE
        if (state.retryCount > 0) btnRetry.text = "↻ Sheet retry (${state.retryCount})"
        updateBulkBar(0)
        LeaveBranchSwitcher.refreshLabel(chipBranch, branchId)
    }

    private fun paintChip(chip: TextView, active: Boolean) {
        chip.setBackgroundResource(
            if (active) R.drawable.bg_filter_chip_active else R.drawable.bg_filter_chip_inactive
        )
        chip.setTextColor(
            requireContext().getColor(
                if (active) android.R.color.white else R.color.theme_text_secondary
            )
        )
    }

    private fun updateBulkBar(count: Int) {
        val pendingSel = adapter.selectedScans().count { it.status.equals("pending", ignoreCase = true) }
        btnApproveSel.text = "✓ Approve ($pendingSel)"
        btnRejectSel.text = "✕ Reject ($pendingSel)"
        val show = filter == "pending" && adapter.items.isNotEmpty()
        btnSelectAll.visibility = if (show) View.VISIBLE else View.GONE
        btnApproveSel.visibility = if (show) View.VISIBLE else View.GONE
        btnRejectSel.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun toast(msg: String) {
        runCatching { Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show() }
    }
}
