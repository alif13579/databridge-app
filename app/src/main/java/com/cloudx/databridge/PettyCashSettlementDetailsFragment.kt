package com.cloudx.databridge

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Petty Cash Management — Settlement Details (mockup screen 4).
 *
 * Wired to PettyCashViewModel. This screen is the one place every role in
 * the approval chain passes through, so the primary action button changes
 * based on both the request's current status AND the signed-in user's role:
 *
 *   PENDING            + isStaff -> "Acknowledge Request" (inline comment box)
 *   ACKNOWLEDGED        + isCashPoc     -> "Approve & Forward" (inline comment box,
 *                                           plus an editable Approved Amount field
 *                                           prefilled with the requested amount --
 *                                           lets Cash POC approve less than asked)
 *   APPROVED            + isAccounts    -> "Mark Ready to Settle" (confirm dialog)
 *   SETTLE_IN_PROCESS   + isAccounts    -> "Mark as Settled" (inline settlement form:
 *                                           Payment Method, Settle Amount, Settlement
 *                                           Date, Transaction ID/Ref)
 *   anything else                       -> no primary action, read-only
 *
 * Matches the mockup's inline forms rather than AlertDialogs for the two
 * decision stages and Settle -- Mark Ready to Settle and Reject stayed as
 * confirm dialogs since the mockup doesn't show extra fields for either.
 *
 * "Hold / Return" (Accounts, at the settle stage) exists as a button to
 * match the mockup's shape but isn't implemented -- says so on tap.
 *
 * Reject is available at PENDING (Staff) and ACKNOWLEDGED (Cash POC) stages
 * only. Once POC has approved, rejecting no longer makes sense — money is
 * already earmarked; Accounts either settles it or handles it manually
 * outside this flow.
 *
 * Edit/Delete: the request's own submitter (workerUid) can edit or delete
 * it, but only while status == PENDING — before Staff has even looked at
 * it. Once acknowledged, the request is "in the system" and shouldn't be
 * silently changed or removed out from under an approver.
 *
 * Note: "Staff" (isStaff, staff_uid, staff_role, staffByName, staffAt) was
 * formerly named "Team Aligned" throughout the codebase — fully renamed,
 * both display label and internal names, since no production data existed
 * under the old names yet.
 *
 * Attachment viewing: since this is the one screen every role in the chain
 * passes through, it's also the only place an uploaded attachment can be
 * opened from (requester, Staff, Cash POC, and Accounts alike — see
 * bindAttachmentOpener). Each tap fetches a fresh presigned download URL
 * (AttachmentUploader.getDownloadUrl) rather than reusing one, since the
 * R2 bucket is private and presigned URLs are short-lived.
 */
class PettyCashSettlementDetailsFragment : Fragment() {

    private val viewModel: PettyCashViewModel by viewModels()

    private var branchId: String = ""
    private var requestCode: String = ""
    private var latestState: PettyCashState.Success? = null

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_REQUEST_CODE = "request_code"

        fun newInstance(branchId: String, requestCode: String): PettyCashSettlementDetailsFragment {
            val f = PettyCashSettlementDetailsFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_REQUEST_CODE, requestCode)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_petty_cash_settlement_details, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        requestCode = arguments?.getString(ARG_REQUEST_CODE).orEmpty()

        view.findViewById<View>(R.id.btnPcDetailBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        viewModel.state.observe(viewLifecycleOwner) { state -> render(state) }
        if (branchId.isBlank() || requestCode.isBlank()) {
            render(PettyCashState.Error("Missing branch or request"))
        } else {
            viewModel.load(branchId)
        }
    }

    private fun taka(amount: Double): String {
        val whole = Math.round(amount)
        return "\u09F3${NumberFormat.getNumberInstance(Locale.US).format(whole)}"
    }

    private fun formatAmountForInput(amount: Double): String = Math.round(amount).toString()

    private fun formatDateTime(millis: Long): String {
        if (millis == 0L) return "—"
        return SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(millis))
    }

    private fun formatDate(millis: Long): String {
        if (millis == 0L) return "—"
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(millis))
    }

    /**
     * Wires the attachment row's tap targets (the name and the "View" text)
     * to fetch a fresh presigned download URL and hand it to the device's
     * default viewer. Every tap requests a new URL rather than reusing one —
     * presigned URLs are short-lived (see getDownloadUrl's doc comment) and
     * this screen can stay open far longer than that.
     */
    private fun bindAttachmentOpener(root: View, request: PettyCashRequest) {
        val tvName = root.findViewById<TextView>(R.id.tvPcDetailAttachmentName)
        val tvView = root.findViewById<TextView>(R.id.btnPcDetailAttachmentView)
        val hasAttachment = request.attachmentUrl.isNotBlank()

        tvView.isVisible = hasAttachment
        tvView.isEnabled = hasAttachment
        tvName.isEnabled = hasAttachment

        if (!hasAttachment) {
            tvName.setOnClickListener(null)
            tvView.setOnClickListener(null)
            return
        }

        val openAttachment = View.OnClickListener {
            if (!isAdded) return@OnClickListener
            tvView.isEnabled = false // avoid double taps stacking up multiple in-flight requests
            lifecycleScope.launch {
                when (val result = AttachmentUploader.getDownloadUrl(request.attachmentUrl)) {
                    is AttachmentUploader.DownloadResult.Success -> openInViewer(result.downloadUrl, request.attachmentName)
                    is AttachmentUploader.DownloadResult.Failed ->
                        if (isAdded) Toast.makeText(requireContext(), result.message, Toast.LENGTH_SHORT).show()
                }
                if (isAdded) tvView.isEnabled = true
            }
        }
        tvName.setOnClickListener(openAttachment)
        tvView.setOnClickListener(openAttachment)
    }

    /** Infers a MIME type from the stored file name's extension so the device picks an appropriate viewer (image app, PDF reader, etc.) rather than guessing from the bare URL. */
    private fun openInViewer(downloadUrl: String, fileName: String) {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "*/*" // unrecognized extension — let the device offer whatever can handle a generic view
        try {
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(downloadUrl), mimeType)
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            if (isAdded) Toast.makeText(requireContext(), "No app found to open this attachment", Toast.LENGTH_SHORT).show()
        }
    }

    private fun render(state: PettyCashState) {
        val root = view ?: return
        val pbLoading = root.findViewById<View>(R.id.pbPcDetailLoading)
        val layoutError = root.findViewById<View>(R.id.layoutPcDetailError)
        val scroll = root.findViewById<View>(R.id.scrollPcDetail)

        when (state) {
            is PettyCashState.Loading -> {
                pbLoading.isVisible = true
                layoutError.isVisible = false
                scroll.isVisible = false
            }
            is PettyCashState.Error -> {
                pbLoading.isVisible = false
                scroll.isVisible = false
                layoutError.isVisible = true
                root.findViewById<TextView>(R.id.tvPcDetailError).text = state.message
                root.findViewById<View>(R.id.btnPcDetailRetry).setOnClickListener {
                    if (branchId.isNotBlank()) viewModel.load(branchId)
                }
            }
            is PettyCashState.Success -> {
                latestState = state
                val request = state.requests.find { it.requestCode == requestCode }
                if (request == null) {
                    pbLoading.isVisible = false
                    scroll.isVisible = false
                    layoutError.isVisible = true
                    root.findViewById<TextView>(R.id.tvPcDetailError).text = "Request $requestCode not found"
                    root.findViewById<View>(R.id.btnPcDetailRetry).setOnClickListener {
                        if (branchId.isNotBlank()) viewModel.load(branchId)
                    }
                } else {
                    pbLoading.isVisible = false
                    layoutError.isVisible = false
                    scroll.isVisible = true
                    renderRequest(root, request, state.roles)
                }
            }
        }
    }

    private fun renderRequest(root: View, request: PettyCashRequest, roles: PettyCashUserRoles) {
        root.findViewById<TextView>(R.id.tvPcDetailCode).text = request.requestCode
        root.findViewById<TextView>(R.id.tvPcDetailPriority).isVisible = request.priority == PC_PRIORITY_HIGH

        root.findViewById<TextView>(R.id.tvPcDetailWorkerInitial).text = request.workerName.take(1).uppercase()
        root.findViewById<TextView>(R.id.tvPcDetailWorkerName).text = request.workerName
        root.findViewById<TextView>(R.id.tvPcDetailWorkerRole).text = request.workerRole

        bindRow(root, R.id.rowPcCategory, "Category", request.category)
        bindRow(root, R.id.rowPcAmount, "Amount", taka(request.amount))
        bindRow(root, R.id.rowPcRequestedOn, "Requested On", formatDate(if (request.requestedDate != 0L) request.requestedDate else request.createdAt))

        val rowExtra = root.findViewById<View>(R.id.rowPcCategoryExtra)
        when {
            request.consignmentId.isNotBlank() -> {
                rowExtra.isVisible = true
                bindRow(root, R.id.rowPcCategoryExtra, "Consignment ID", request.consignmentId)
            }
            request.storeName.isNotBlank() -> {
                rowExtra.isVisible = true
                bindRow(root, R.id.rowPcCategoryExtra, "Store", request.storeName)
            }
            else -> rowExtra.isVisible = false
        }

        val rowPickupCount = root.findViewById<View>(R.id.rowPcPickupCount)
        if (request.pickupCount > 0) {
            rowPickupCount.isVisible = true
            bindRow(root, R.id.rowPcPickupCount, "Number of Pickups", request.pickupCount.toString())
        } else {
            rowPickupCount.isVisible = false
        }

        root.findViewById<TextView>(R.id.tvPcDetailPurpose).text = request.purpose
        root.findViewById<TextView>(R.id.tvPcDetailAttachmentName).text =
            request.attachmentName.ifBlank { "No attachment" }
        bindAttachmentOpener(root, request)

        // Settlement Summary: Claimed Amount (the original ask, permanent record —
        // never overwritten by edits along the approval chain), Approved Amount
        // (set by Cash POC at approval time — may differ from Claimed on a
        // partial approval), and Settled Amount (the actual final figure paid
        // out, set by Accounts at the Settle step, defaulting to what Cash POC
        // approved but adjustable once more there). Each amount shows "—" until
        // its own stage is actually reached, so it doesn't look like 0 was
        // approved/paid for a still-in-flight request.
        val cardSummary = root.findViewById<View>(R.id.cardPcSettlementSummary)
        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val canSeeSummary = roles.isAccounts || request.workerUid == myUid

        cardSummary.isVisible = canSeeSummary
        if (canSeeSummary) {
            val approvedAmountText = if (request.pocApprovedAt != 0L) taka(request.approvedAmount) else "—"
            val settledAmountText = if (request.status == PC_STATUS_SETTLED) taka(request.settledAmount) else "—"
            bindRow(root, R.id.rowPcSummaryRequestAmount, "Claimed Amount", taka(request.amount))
            bindRow(root, R.id.rowPcSummaryApprovedAmount, "Approved Amount", approvedAmountText)
            bindRow(root, R.id.rowPcSummarySettledAmount, "Settled Amount", settledAmountText)
        }

        // POC's approval comment, surfaced directly (not just buried in the
        // approval-flow subtitle) once it exists — this is what Accounts
        // sees just before settling, matching the mockup's "POC Comment" row.
        val rowPoc = root.findViewById<View>(R.id.rowPcPocComment)
        if (request.pocApprovedAt != 0L && request.pocComment.isNotBlank()) {
            rowPoc.isVisible = true
            bindRow(root, R.id.rowPcPocComment, "POC Comment", request.pocComment)
        } else {
            rowPoc.isVisible = false
        }

        buildApprovalSteps(root, request)
        bindActions(root, request, roles)
    }

    private fun bindRow(root: View, includeId: Int, label: String, value: String) {
        val row = root.findViewById<View>(includeId)
        row.findViewById<TextView>(R.id.tvDetailRowLabel).text = label
        row.findViewById<TextView>(R.id.tvDetailRowValue).text = value
    }

    private fun nameWithComment(name: String, comment: String): String =
        if (comment.isBlank()) name else "$name — \"$comment\""

    private fun buildApprovalSteps(root: View, request: PettyCashRequest) {
        val container = root.findViewById<LinearLayout>(R.id.layoutPcApprovalSteps)
        container.removeAllViews()

        // Build the 5 canonical stages from the request's actual fields —
        // a stage counts as "done" only if its timestamp is set, so a
        // request still at PENDING correctly shows only step 1 as done.
        data class Stage(val title: String, val subtitle: String, val at: Long)
        val stages = listOf(
            Stage("Request Submitted", request.workerName, request.createdAt),
            Stage(pettyCashStatusLabel(PC_STATUS_ACKNOWLEDGED), nameWithComment(request.staffByName, request.staffComment), request.staffAt),
            Stage(pettyCashStatusLabel(PC_STATUS_APPROVED), nameWithComment(request.pocApprovedByName, request.pocComment), request.pocApprovedAt),
            Stage(pettyCashStatusLabel(PC_STATUS_SETTLE_IN_PROCESS), request.settleInProcessByName, request.settleInProcessAt),
            Stage(pettyCashStatusLabel(PC_STATUS_SETTLED), request.settledByName, request.settledAt)
        )

        stages.forEachIndexed { index, stage ->
            val stepView = layoutInflater.inflate(R.layout.item_petty_cash_approval_step, container, false)
            val isDone = stage.at != 0L
            val isLast = index == stages.lastIndex

            stepView.findViewById<TextView>(R.id.tvStepTitle).text = stage.title
            stepView.findViewById<TextView>(R.id.tvStepSubtitle).text = stage.subtitle
            stepView.findViewById<TextView>(R.id.tvStepTime).text = if (isDone) formatDateTime(stage.at) else ""

            val tvDot = stepView.findViewById<TextView>(R.id.tvStepDot)
            if (isDone) {
                tvDot.text = "\u2713"
                tvDot.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_step_done)
            } else {
                tvDot.text = ""
                tvDot.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_step_pending)
            }

            stepView.findViewById<View>(R.id.viewStepConnector).isVisible = !isLast
            container.addView(stepView)
        }

        if (request.status == PC_STATUS_REJECTED) {
            val stepView = layoutInflater.inflate(R.layout.item_petty_cash_approval_step, container, false)
            stepView.findViewById<TextView>(R.id.tvStepTitle).text = pettyCashStatusLabel(PC_STATUS_REJECTED)
            stepView.findViewById<TextView>(R.id.tvStepSubtitle).text =
                "${request.rejectedByName}${if (request.rejectReason.isNotBlank()) " — ${request.rejectReason}" else ""}"
            stepView.findViewById<TextView>(R.id.tvStepTime).text = formatDateTime(request.rejectedAt)
            val tvDot = stepView.findViewById<TextView>(R.id.tvStepDot)
            tvDot.text = "\u2715"
            tvDot.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.bg_pc_step_pending)
            stepView.findViewById<View>(R.id.viewStepConnector).isVisible = false
            container.addView(stepView)
        }
    }

    // ── Actions ──────────────────────────────────────────────────────────────

    private fun bindActions(root: View, request: PettyCashRequest, roles: PettyCashUserRoles) {
        val btnPrimary = root.findViewById<Button>(R.id.btnPcDetailSettleNow)
        val btnReject = root.findViewById<Button>(R.id.btnPcDetailReject)
        val btnHoldReturn = root.findViewById<Button>(R.id.btnPcDetailHoldReturn)
        val layoutComment = root.findViewById<View>(R.id.layoutPcDetailComment)
        val layoutApprovedAmount = root.findViewById<View>(R.id.layoutPcDetailApprovedAmount)
        val cardSettleForm = root.findViewById<View>(R.id.cardPcDetailSettleForm)

        val myUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
        val isOwner = request.workerUid == myUid

        val canAcknowledge = request.status == PC_STATUS_PENDING && roles.isStaff
        val canApprove = request.status == PC_STATUS_ACKNOWLEDGED && roles.isCashPoc
        val canMarkReady = request.status == PC_STATUS_APPROVED && roles.isAccounts
        val canSettle = request.status == PC_STATUS_SETTLE_IN_PROCESS && roles.isAccounts
        val canReject = (request.status == PC_STATUS_PENDING && roles.isStaff) ||
            (request.status == PC_STATUS_ACKNOWLEDGED && roles.isCashPoc)
        val canEditOrDelete = isOwner && request.status == PC_STATUS_PENDING

        btnReject.isVisible = canReject
        btnReject.setOnClickListener { confirmReject() }

        // "Hold / Return" only has a real destination at the settle stage —
        // sending money back to the requester before that doesn't fit the
        // model (nothing has been set aside yet). Not implemented beyond the
        // button existing to match the mockup's shape; says so on tap rather
        // than silently doing nothing.
        btnHoldReturn.isVisible = canSettle
        btnHoldReturn.setOnClickListener {
            Toast.makeText(requireContext(), "Hold / Return isn't implemented yet", Toast.LENGTH_SHORT).show()
        }

        layoutComment.isVisible = canAcknowledge || canApprove
        layoutApprovedAmount.isVisible = canApprove
        if (canApprove) {
            val etAmount = root.findViewById<android.widget.EditText>(R.id.etPcDetailApprovedAmount)
            // Only prefill when empty so re-binds (e.g. a Firebase update
            // ticking in) don't clobber a value the POC is mid-typing.
            if (etAmount.text.isNullOrBlank()) {
                etAmount.setText(formatAmountForInput(request.amount))
            }
        }
        cardSettleForm.isVisible = canSettle
        if (canSettle) prefillSettleForm(root, request)

        when {
            canAcknowledge -> {
                btnPrimary.isVisible = true
                btnPrimary.text = "Acknowledge Request"
                btnPrimary.setOnClickListener {
                    val comment = root.findViewById<android.widget.EditText>(R.id.etPcDetailComment).text?.toString()?.trim().orEmpty()
                    runAction { onSupa -> viewModel.acknowledgeRequest(branchId, requestIdFor(requestCode), comment, onSupabaseResult = onSupa) }
                }
            }
            canApprove -> {
                btnPrimary.isVisible = true
                btnPrimary.text = "Approve & Forward"
                btnPrimary.setOnClickListener {
                    val comment = root.findViewById<android.widget.EditText>(R.id.etPcDetailComment).text?.toString()?.trim().orEmpty()
                    val amountText = root.findViewById<android.widget.EditText>(R.id.etPcDetailApprovedAmount).text?.toString()?.trim().orEmpty()
                    val approvedAmount = amountText.toDoubleOrNull() ?: request.amount
                    runAction { onSupa -> viewModel.approveRequest(branchId, requestIdFor(requestCode), comment, approvedAmount, onSupabaseResult = onSupa) }
                }
            }
            canMarkReady -> {
                btnPrimary.isVisible = true
                btnPrimary.text = "Mark Ready to Settle"
                btnPrimary.setOnClickListener { confirmMarkReady() }
            }
            canSettle -> {
                btnPrimary.isVisible = true
                btnPrimary.text = "Mark as Settled"
                btnPrimary.setOnClickListener { submitSettle(root, request) }
            }
            else -> {
                btnPrimary.isVisible = false
            }
        }

        bindEditDeleteRow(root, request, canEditOrDelete)
    }

    /** Payment Method spinner, editable Settle Amount (pre-filled from approved amount,
     *  Accounts can adjust it down/up once more before settling), Settlement Date
     *  (read-only), Transaction ID (optional). */
    private fun prefillSettleForm(root: View, request: PettyCashRequest) {
        val spinner = root.findViewById<android.widget.Spinner>(R.id.spinnerPcSettlePaymentMethod)
        if (spinner.adapter == null) {
            spinner.adapter = android.widget.ArrayAdapter(
                requireContext(), android.R.layout.simple_spinner_dropdown_item, arrayOf("Cash", "Bank")
            )
        }
        val etSettleAmount = root.findViewById<android.widget.EditText>(R.id.etPcSettleAmount)
        val defaultAmount = request.approvedAmount.takeIf { it > 0 } ?: request.amount
        if (etSettleAmount.text.isNullOrBlank()) {
            etSettleAmount.setText(if (defaultAmount == defaultAmount.toLong().toDouble())
                defaultAmount.toLong().toString() else defaultAmount.toString())
        }
        bindRow(root, R.id.rowPcSettleDate, "Settlement Date", formatDate(System.currentTimeMillis()))
    }

    private fun submitSettle(root: View, request: PettyCashRequest) {
        val spinner = root.findViewById<android.widget.Spinner>(R.id.spinnerPcSettlePaymentMethod)
        val paymentMethod = spinner.selectedItem?.toString() ?: "Cash"
        val typedTrxId = root.findViewById<android.widget.EditText>(R.id.etPcSettleTrxId).text?.toString()?.trim().orEmpty()
        val trxId = typedTrxId.ifBlank { "TXN-${System.currentTimeMillis().toString().takeLast(5)}" }
        val typedAmount = root.findViewById<android.widget.EditText>(R.id.etPcSettleAmount)
            .text?.toString()?.trim()?.toDoubleOrNull()
        if (typedAmount == null || typedAmount <= 0) {
            Toast.makeText(requireContext(), "Enter a valid settle amount", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val result = viewModel.settleRequest(branchId, requestIdFor(requestCode), paymentMethod, trxId, typedAmount,
                onSupabaseResult = { ok ->
                    activity?.runOnUiThread {
                        if (isAdded) Toast.makeText(requireContext(),
                            if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                    }
                })
            if (result.isSuccess) {
                Toast.makeText(requireContext(), "✓ Firebase saved", Toast.LENGTH_SHORT).show()
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PettyCashSettlementSuccessFragment.newInstance(branchId, requestCode))
                    .addToBackStack(null)
                    .commitAllowingStateLoss()
            } else {
                Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Settlement failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Owner-only Edit/Delete row, only while the request is still PENDING. */
    private fun bindEditDeleteRow(root: View, request: PettyCashRequest, canEditOrDelete: Boolean) {
        val layoutEditDelete = root.findViewById<View?>(R.id.layoutPcDetailEditDelete)
        layoutEditDelete?.isVisible = canEditOrDelete
        if (!canEditOrDelete) return

        root.findViewById<View>(R.id.btnPcDetailEdit).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, PettyCashRequestCreateFragment.newInstance(branchId, editRequestId = request.id))
                .addToBackStack(null)
                .commitAllowingStateLoss()
        }
        root.findViewById<View>(R.id.btnPcDetailDelete).setOnClickListener { confirmDelete() }
    }

    private fun confirmMarkReady() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Mark $requestCode ready to settle?")
            .setMessage("This moves the request into your cash handover queue.")
            .setPositiveButton("Mark Ready") { _, _ -> runAction { onSupa -> viewModel.markReadyToSettle(branchId, requestIdFor(requestCode), onSupabaseResult = onSupa) } }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmReject() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = "Reason (optional)"
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Reject $requestCode?")
            .setMessage("This request will be marked as rejected and removed from the queue.")
            .setView(input)
            .setPositiveButton("Reject") { _, _ ->
                val reason = input.text?.toString()?.trim().orEmpty()
                lifecycleScope.launch {
                    val result = viewModel.rejectRequest(branchId, requestIdFor(requestCode), reason,
                        onSupabaseResult = { ok ->
                            activity?.runOnUiThread {
                                if (isAdded) Toast.makeText(requireContext(),
                                    if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                            }
                        })
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), "✓ Firebase saved — $requestCode rejected", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    } else {
                        Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Reject failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun confirmDelete() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete $requestCode?")
            .setMessage("This permanently removes the request. This can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    val result = viewModel.deleteRequest(branchId, requestIdFor(requestCode),
                        onSupabaseResult = { ok ->
                            activity?.runOnUiThread {
                                if (isAdded) Toast.makeText(requireContext(),
                                    if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                            }
                        })
                    if (result.isSuccess) {
                        Toast.makeText(requireContext(), "✓ Firebase saved — $requestCode deleted", Toast.LENGTH_SHORT).show()
                        parentFragmentManager.popBackStack()
                    } else {
                        Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestIdFor(code: String): String =
        latestState?.requests?.find { it.requestCode == code }?.id.orEmpty()

    private fun runAction(block: suspend ((Boolean) -> Unit) -> Result<Unit>) {
        lifecycleScope.launch {
            val result = block { ok ->
                activity?.runOnUiThread {
                    if (isAdded) Toast.makeText(requireContext(),
                        if (ok) "✓ Supabase saved" else "⚠ Supabase save failed", Toast.LENGTH_SHORT).show()
                }
            }
            if (result.isSuccess) {
                Toast.makeText(requireContext(), "✓ Firebase saved — Done", Toast.LENGTH_SHORT).show()
                if (branchId.isNotBlank()) viewModel.load(branchId)
            } else {
                Toast.makeText(requireContext(), result.exceptionOrNull()?.message ?: "Action failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
