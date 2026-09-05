package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen parcel detail view — like a product page in eCommerce.
 *
 * Top section : identity (customer, phone, status, COD) + overview, then the
 *               complete journey log / remarks timeline (live-updated) — shown
 *               before address/hub/dates since it's what a decision on the
 *               parcel actually depends on.
 * Bottom section: address, hub, dates (reference info, needed once you act)
 *
 * Entry point: create via [newInstance] and load via MainActivity.loadFragment().
 */
class ParcelDetailFragment : Fragment() {

    companion object {
        fun newInstance(parcelId: String, scope: String) = ParcelDetailFragment().apply {
            arguments = Bundle().apply {
                putString("parcel_id", parcelId)
                putString("scope", scope)
            }
        }
    }

    private val db   = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val parcelId by lazy { arguments?.getString("parcel_id") ?: "" }
    private val scope    by lazy { arguments?.getString("scope")     ?: "cc"  }
    private val userId   by lazy { auth.currentUser?.uid ?: "" }

    // Resolved once (scope == "worker" only) for the "Set Remarks" save below — the signed-in
    // worker's own systemId, since a worker landing here is always looking at their own
    // assigned parcel (unlike CC scope, where the assigned worker isn't the signed-in user
    // and must come from the remark history instead — see lastResolvedAgentSystemId).
    private var ownWorkerSystemId: String = ""
    // Remark options for the "Set Remarks" sheet — loaded once from the scope-appropriate
    // Supabase source ('CC' or 'WORKER' in public.validation_remarks, same admin-managed
    // catalog CallCenterFragment/WorkerSpaceFragment/RemarkPopupOverlay already read via
    // SupabaseClientManager.fetchRemarkOptions()), so options stay in sync with whichever
    // screen the agent/worker started from.
    private data class PdRemarkOption(
        val icon: String,
        val label: String,
        val englishLabel: String,
        val statusKey: String,
        val statusPreview: String,
        val statusColor: Int,
        val category: String = ""
    )
    private var pdRemarkOptions: List<PdRemarkOption> = emptyList()

    // Views
    private lateinit var tvParcelId:     TextView
    private lateinit var tvStatus:       TextView
    private lateinit var tvCod:          TextView
    private lateinit var tvCustomer:     TextView
    private lateinit var tvMeta:         TextView
    private lateinit var tvAgeAttempt:   TextView
    private lateinit var tvAddress:      TextView
    private lateinit var tvHub:          TextView
    private lateinit var tvDates:        TextView
    private lateinit var tvRemarksCount: TextView
    private lateinit var tvEmpty:        TextView
    private lateinit var layoutTimeline: LinearLayout
    private lateinit var progressBar:    ProgressBar
    private lateinit var tvOverviewStatus:    TextView
    private lateinit var tvOverviewCreatedAt: TextView
    private lateinit var tvOverviewUpdatedAt: TextView
    private lateinit var tvOverviewAge:       TextView

    // Fetched from courier/consignments/{id} — cached so tap-to-call and the
    // Overview age counter both have the values without re-reading Firebase.
    private var currentPhone: String = ""
    private var currentCreatedAt: Long = 0L
    private var currentUpdatedAt: Long = 0L
    private var currentAttemptCount: Int = 0

    // Assigned worker's phone, for the WhatsApp-to-agent button. This page has no direct
    // parcel->agent field to read (that link only exists via courier/runs_by_*, which
    // would mean scanning runs just for one parcel) so it's derived from the remark
    // timeline instead: the most recent remarked_by=="worker" entry's agentSystemId
    // (written by WorkerSpaceFragment) resolved through users_by_systemId -> uid ->
    // profile/phone, same targeted-lookup path ensureAgentNameMap() uses per-uid.
    // Blank until a worker has touched this parcel at least once.
    private var currentAgentPhone: String = ""
    private var lastResolvedAgentSystemId: String = ""

    // Live remark timeline: initial load via SupabaseRemarkValidationWriter.fetchHistory()
    // (free PostgREST read, zero Edge Function invocations), kept live afterward via a
    // Realtime subscription on validations filtered by consignment — same pattern
    // CallCenterFragment/WorkerSpaceFragment use, replacing this fragment's old
    // courier/remarks_by_consignment ValueEventListener.
    private var timelineRealtimeJob: kotlinx.coroutines.Job? = null
    private var timelineRows: MutableList<org.json.JSONObject> = mutableListOf()

    // Cache: uid → display name (resolved lazily from Firebase via UserNameResolver)
    private val uidNameCache = mutableMapOf<String, String>()
    private val uidPhotoCache = mutableMapOf<String, String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_parcel_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvParcelId     = view.findViewById(R.id.tvPdParcelId)
        tvStatus       = view.findViewById(R.id.tvPdStatus)
        tvCod          = view.findViewById(R.id.tvPdCod)
        tvCustomer     = view.findViewById(R.id.tvPdCustomer)
        tvMeta         = view.findViewById(R.id.tvPdMeta)
        tvAgeAttempt   = view.findViewById(R.id.tvPdAgeAttempt)
        tvAddress      = view.findViewById(R.id.tvPdAddress)
        tvHub          = view.findViewById(R.id.tvPdHub)
        tvDates        = view.findViewById(R.id.tvPdDates)
        tvRemarksCount = view.findViewById(R.id.tvPdRemarksCount)
        tvEmpty        = view.findViewById(R.id.tvPdRemarksEmpty)
        layoutTimeline = view.findViewById(R.id.layoutPdTimeline)
        progressBar    = view.findViewById(R.id.pdProgressBar)
        tvOverviewStatus    = view.findViewById(R.id.tvPdOverviewStatus)
        tvOverviewCreatedAt = view.findViewById(R.id.tvPdOverviewCreatedAt)
        tvOverviewUpdatedAt = view.findViewById(R.id.tvPdOverviewUpdatedAt)
        tvOverviewAge       = view.findViewById(R.id.tvPdOverviewAge)

        tvParcelId.text = parcelId
        view.findViewById<View>(R.id.btnPdBack).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        view.findViewById<View>(R.id.btnPdCall).setOnClickListener {
            if (currentPhone.isNotBlank()) {
                AutoDialHelper.dial(this, currentPhone)
            }
        }
        view.findViewById<View>(R.id.btnPdSetRemarks).setOnClickListener {
            showSetRemarksDialog()
        }
        view.findViewById<View>(R.id.btnPdWhatsapp).setOnClickListener {
            if (currentAgentPhone.isBlank()) {
                Toast.makeText(
                    requireContext(),
                    "⚠ এই parcel-এ এখনো কোনো worker touch করেনি, তাই agent-এর number পাওয়া যায়নি",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                val message = WhatsAppHelper.fillTemplate(
                    body = "📦 Parcel Info\n" +
                        "Consignment ID : {consignmentId}\n" +
                        "Customer Name : {name}\n" +
                        "Phone Number : {phone}\n" +
                        "Address : {address}\n" +
                        "COD Amount : ৳{cod}\n" +
                        "Hub : {hub}",
                    name = tvCustomer.text.toString(),
                    phone = currentPhone,
                    address = tvAddress.text.toString().removePrefix("📍 "),
                    cod = tvCod.text.toString().removePrefix("৳"),
                    consignmentId = parcelId,
                    hub = tvHub.text.toString().removePrefix("🏢 ")
                )
                WhatsAppHelper.send(requireContext(), currentAgentPhone, message)
            }
        }

        loadPdRemarkOptions()
        loadParcelInfo()
        if (scope == "worker") loadOwnWorkerSystemId()
    }

    /** Resolves the signed-in user's own systemId once, for the "Set Remarks" save below. */
    private fun loadOwnWorkerSystemId() {
        if (userId.isBlank()) return
        viewLifecycleOwner.lifecycleScope.launch {
            ownWorkerSystemId = withContext(Dispatchers.IO) {
                db.reference.child("users/$userId/profile/company_info/system_id")
                    .get().await().getValue(String::class.java)?.trim()
            }.orEmpty()
        }
    }

    override fun onDestroyView() {
        timelineRealtimeJob?.cancel()
        super.onDestroyView()
    }

    // ── Set Remarks (reachable from a notification tap — same capability the
    //    Call Center card / Worker card "✏️ Set Remarks" already have, so an
    //    agent or worker landing here directly doesn't have to back out to set one) ──

    private fun loadPdRemarkOptions() {
        val source = if (scope == "worker") "WORKER" else "CC"
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { StatusMetaCache.refresh() }
                val options = SupabaseClientManager.fetchRemarkOptions("ParcelDetailFragment", source)
                val fetched = options.mapNotNull { opt ->
                    val label = opt.textBn.ifBlank { opt.textEn }
                    if (label.isBlank()) return@mapNotNull null
                    val target = opt.targetStatus.ifBlank { return@mapNotNull null }
                    val metaEntry = StatusMetaCache.entries[target]
                    val preview = StatusMetaCache.labelOrNull(target, "bn") ?: target
                    PdRemarkOption(
                        icon = "💬",
                        label = label,
                        englishLabel = opt.textEn.ifBlank { opt.textBn },
                        statusKey = target,
                        statusPreview = preview,
                        statusColor = metaEntry?.color ?: android.graphics.Color.GRAY,
                        category = opt.category
                    ) to opt.priority
                }
                if (isAdded) {
                    pdRemarkOptions = fetched.sortedByDescending { it.second }.map { it.first }
                }
            } catch (e: Exception) {
                FirebaseErrorLogger.log(
                    screen = "ParcelDetailFragment", action = "load_remark_options",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("parcelId" to parcelId, "scope" to scope)
                )
            }
        }
    }

    private fun showSetRemarksDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val view   = layoutInflater.inflate(R.layout.bottom_sheet_remarks, null)
        dialog.setContentView(view)

        val tvTitle       = view.findViewById<TextView>(R.id.tvRemarksTitle)
        val etRemarks     = view.findViewById<EditText>(R.id.etRemarksText)
        val tvAutoStatus  = view.findViewById<TextView>(R.id.tvRemarksAutoStatus)
        val layoutOptions = view.findViewById<LinearLayout>(R.id.layoutCcRemarkOptions)
        val btnCancel     = view.findViewById<TextView>(R.id.btnRemarksCancel)
        val btnSave       = view.findViewById<TextView>(R.id.btnRemarksSave)

        tvTitle.text = "${tvCustomer.text} · $parcelId · $currentPhone"
        btnCancel.setOnClickListener { dialog.dismiss() }

        if (pdRemarkOptions.isEmpty()) {
            val tv = TextView(requireContext())
            val scopeName = if (scope == "worker") "Worker" else "Call Center"
            tv.text = "⚠ Config-এ কোনো remark সেট করা নেই।\nAdmin-কে $scopeName remark config-এ remark যোগ করতে বলুন।"
            tv.textSize = 13f
            tv.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
            tv.setPadding(0, 24, 0, 24)
            layoutOptions.addView(tv)
        }

        var selectedStatus     = ""
        var selectedRemarkText = ""
        var selectedRemarkTextEn = ""
        var selectedVerdict    = ""
        val optionViews = mutableListOf<View>()

        btnSave.isEnabled = false
        btnSave.alpha     = 0.5f
        fun refreshSaveEnabled() {
            val hasNote = etRemarks.text?.toString()?.trim().orEmpty().isNotBlank()
            val enabled = selectedStatus.isNotBlank() || hasNote
            btnSave.isEnabled = enabled
            btnSave.alpha     = if (enabled) 1f else 0.5f
        }
        etRemarks.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { refreshSaveEnabled() }
        })

        for (opt in pdRemarkOptions) {
            val optView = layoutInflater.inflate(R.layout.item_worker_remark_option, layoutOptions, false)
            val tvIcon  = optView.findViewById<TextView>(R.id.twRemarkOptIcon)
            val tvText  = optView.findViewById<TextView>(R.id.twRemarkOptText)
            val tvTag   = optView.findViewById<TextView>(R.id.twRemarkOptAutoTag)
            val dot     = optView.findViewById<View>(R.id.viewRemarkOptSelected)

            tvIcon.text = opt.icon
            tvText.text = opt.label
            tvTag.text  = "→${opt.statusPreview.uppercase()}"
            tvTag.visibility = View.VISIBLE

            optView.setOnClickListener {
                optionViews.forEach { v ->
                    v.setBackgroundResource(R.drawable.bg_remark_opt_inactive)
                    v.findViewById<TextView>(R.id.twRemarkOptText)
                        .setTextColor(requireContext().getColor(R.color.theme_text_remark_opt))
                    v.findViewById<View>(R.id.viewRemarkOptSelected).visibility = View.GONE
                }
                optView.setBackgroundResource(R.drawable.bg_remark_opt_active)
                tvText.setTextColor(requireContext().getColor(R.color.theme_text_remark_opt_selected))
                dot.visibility = View.VISIBLE

                selectedStatus       = opt.statusKey
                selectedRemarkText   = opt.label
                selectedRemarkTextEn = opt.englishLabel
                selectedVerdict      = opt.category
                tvAutoStatus.text  = opt.statusPreview
                tvAutoStatus.setTextColor(opt.statusColor)
                refreshSaveEnabled()
            }
            optionViews.add(optView)
            layoutOptions.addView(optView)
        }

        btnSave.setOnClickListener {
            val noteText = etRemarks.text?.toString()?.trim().orEmpty()
            if (selectedStatus.isBlank() && noteText.isBlank()) return@setOnClickListener

            val timestamp    = System.currentTimeMillis()
            val indexDateKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(timestamp))
            val source       = if (scope == "worker") "WORKER" else "CC"
            // worker scope: the signed-in worker IS the assigned agent (they're looking at
            // their own parcel). CC scope: the assigned worker is whoever the remark history
            // last showed (lastResolvedAgentSystemId) — the CC agent themselves is never the
            // assigned_to_system_id. See ownWorkerSystemId/lastResolvedAgentSystemId's doc
            // comments above for why these differ.
            val assignedAgentSystemId = if (scope == "worker") ownWorkerSystemId else lastResolvedAgentSystemId
            val branchId = RbacManager.current.branchIds.firstOrNull().orEmpty()

            if (assignedAgentSystemId.isBlank()) {
                // write() silently no-ops on a blank assignedAgentSystemId (logs and returns,
                // no error callback) — CC scope hits this when no worker has touched the
                // parcel yet (lastResolvedAgentSystemId only populates from remark history).
                // Surfacing it here beats a false "saved" toast over a remark that never wrote.
                Toast.makeText(requireContext(),
                    "⚠ এই parcel-এ এখনো কোনো worker assign/touch করেনি, তাই remark save করা যাচ্ছে না",
                    Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            SupabaseRemarkValidationWriter.write(
                assignedAgentSystemId = assignedAgentSystemId,
                branchId = branchId,
                consignmentId = parcelId,
                status = selectedStatus,
                remarksText = selectedRemarkTextEn.ifBlank { noteText },
                noteText = noteText,
                source = source,
                screen = "ParcelDetailFragment",
                // Blank when selectedRemarkTextEn == selectedRemarkText (config language is
                // already English) or this was a note-only save with no option picked — same
                // reasoning as CallCenterFragment's saveCcRemarkForItems.
                remarksBnText = selectedRemarkText.takeIf { it.isNotBlank() && it != selectedRemarkTextEn } ?: "",
                verdictText = if (source == "CC") selectedVerdict else "",
                appContext = requireContext().applicationContext
            )

            // Kept alongside the validations write above: these feed CC's push-queue index
            // (courier/remarks_by_userId) and per-day dedup (courier/users_by_consignment),
            // unrelated to the remark record itself, which now lives in Supabase.
            db.reference.child("courier/remarks_by_userId/$userId/push_${indexDateKey}_$parcelId")
                .setValue(
                    mapOf(
                        "final_status" to selectedStatus,
                        "remarks"      to selectedRemarkText.ifBlank { noteText },
                        "created_at"   to timestamp,
                        "updated_at"   to timestamp
                    )
                )
            db.reference.child("courier/users_by_consignment/$parcelId/$indexDateKey/$userId")
                .setValue(true)

            EngagedStateManager.clearEngaged(parcelId, userId)

            Toast.makeText(requireContext(), "✅ Remark saved", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    // ── Parcel info ─────────────────────────────────────────────────────────────

    private fun loadParcelInfo() {
        progressBar.visibility = View.VISIBLE
        db.reference.child("courier/consignments/$parcelId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!isAdded || view == null) return
                    // Defensive: this crash has resisted several targeted fixes already
                    // (isAdded/view races, transaction timing). Until the real cause is
                    // caught red-handed via a logged stack trace, don't let ANY exception
                    // here take down the whole app — log it and fail gracefully instead.
                    try {
                        progressBar.visibility = View.GONE

                        val ctx          = context ?: return
                        val lang         = if (scope == "worker") "bn" else "bn"
                        val customer     = snap.child("recipientName").getValue(String::class.java) ?: "—"
                        val phone        = snap.child("recipientPhone").getValue(String::class.java) ?: "—"
                        val address      = snap.child("recipientAddress").getValue(String::class.java) ?: "—"
                        val hub          = snap.child("deliveryHub").getValue(String::class.java) ?: "—"
                        val cod          = readCod(snap)
                        val status       = snap.child("status").getValue(String::class.java) ?: "pending"
                        val createdAt    = snap.child("createdAt").getValue(Long::class.java) ?: 0L
                        val updatedAt    = snap.child("updatedAt").getValue(Long::class.java) ?: 0L
                        val attempt      = readAttempt(snap)

                        val cfg = WorkerParcelAdapter.getStatusConfig(ctx, status, lang)
                        tvStatus.text = cfg.label
                        tvStatus.setTextColor(cfg.color)
                        tvStatus.setBackgroundColor(cfg.bg)

                        tvCod.text      = "৳$cod"
                        tvCustomer.text = customer
                        tvMeta.text     = "$parcelId · $phone"
                        tvAddress.text  = "📍 $address"
                        tvHub.text      = "🏢 $hub"

                        currentPhone        = phone.takeIf { it != "—" } ?: ""
                        currentCreatedAt    = createdAt
                        currentUpdatedAt    = updatedAt
                        currentAttemptCount = attempt

                        // Same compact "2d · A3" badge + urgency color/bold the parcel card
                        // shows (WorkerParcelAdapter.formatAgeCompact/ageColorFor) — this page
                        // only had the verbose Created/Updated/Age trio further down before.
                        val (ageColor, ageBold) = WorkerParcelAdapter.ageColorFor(createdAt)
                        tvAgeAttempt.text = "🕐 ${WorkerParcelAdapter.formatAgeCompact(createdAt)}  ·  A$attempt"
                        tvAgeAttempt.setTextColor(ageColor)
                        tvAgeAttempt.setTypeface(null, if (ageBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)

                        // Overview card — same fields shown in the long-press Journey Log dialog.
                        tvOverviewStatus.text = cfg.label
                        tvOverviewStatus.setTextColor(cfg.color)
                        val fullFmt = SimpleDateFormat("dd-MM-yy hh:mm:ss a", Locale.getDefault())
                        tvOverviewCreatedAt.text = if (createdAt > 0) fullFmt.format(Date(createdAt)) else "—"
                        tvOverviewUpdatedAt.text = if (updatedAt > 0) fullFmt.format(Date(updatedAt)) else "—"
                        tvOverviewAge.text = formatAge(createdAt, updatedAt)
                        tvOverviewAge.setTextColor(ageColor)

                        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                        val createdStr = if (createdAt > 0) sdf.format(Date(createdAt)) else "—"
                        val updatedStr = if (updatedAt > 0 && updatedAt != createdAt) "  ·  Updated ${sdf.format(Date(updatedAt))}" else ""
                        tvDates.text = "Created: $createdStr$updatedStr"
                    } catch (e: Exception) {
                        FirebaseErrorLogger.log(
                            screen = "ParcelDetailFragment",
                            action = "loadParcelInfo.onDataChange",
                            errorMessage = e.stackTraceToString(),
                            extra = mapOf("parcelId" to parcelId, "scope" to scope)
                        )
                        if (isAdded && view != null) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "⚠️ Overview load failed: ${e.javaClass.simpleName}: ${e.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    // Attach the remarks listener only now that currentCreatedAt is set,
                    // so the synthetic "CREATED" entry in renderTimeline() is guaranteed
                    // to be available on the very first render (avoids a race where the
                    // remarks listener could fire before this callback finishes). Attached
                    // even if the try block above failed, so the timeline can still work
                    // independently of the overview card.
                    loadAndSubscribeTimeline()
                }
                override fun onCancelled(e: DatabaseError) {
                    if (!isAdded || view == null) return
                    progressBar.visibility = View.GONE
                    // Parcel info failed to load, but the remarks timeline can still
                    // work independently — attach it anyway (CREATED entry just won't
                    // show since currentCreatedAt stays 0).
                    loadAndSubscribeTimeline()
                }
            })
    }

    // ── Remarks timeline (live) ──────────────────────────────────────────────────

    private fun loadAndSubscribeTimeline() {
        viewLifecycleOwner.lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val deferred = kotlinx.coroutines.CompletableDeferred<List<org.json.JSONObject>>()
                SupabaseRemarkValidationWriter.fetchHistory(parcelId, "ParcelDetailFragment") { fetched ->
                    deferred.complete(fetched)
                }
                deferred.await()
            }
            if (!isAdded || view == null) return@launch
            timelineRows = rows.toMutableList()
            try {
                renderTimeline()
            } catch (e: Exception) {
                FirebaseErrorLogger.log(
                    screen = "ParcelDetailFragment", action = "renderTimeline",
                    errorMessage = e.stackTraceToString(),
                    extra = mapOf("parcelId" to parcelId, "scope" to scope)
                )
                if (isAdded && view != null) {
                    progressBar.visibility = View.GONE
                    android.widget.Toast.makeText(
                        requireContext(),
                        "⚠️ Timeline load failed: ${e.javaClass.simpleName}: ${e.message}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            try {
                resolveAgentPhoneIfNeeded()
            } catch (e: Exception) {
                FirebaseErrorLogger.log(
                    screen = "ParcelDetailFragment", action = "resolveAgentPhone",
                    errorMessage = e.message ?: "unknown",
                    extra = mapOf("parcelId" to parcelId)
                )
            }
            subscribeTimelineRealtime()
        }
    }

    /** Kept alive independently of the fragment's other listeners — a new INSERT just
     *  appends to timelineRows and re-renders, same incremental approach CallCenterFragment/
     *  WorkerSpaceFragment's Realtime handlers use (see refreshOneCcParcelFromSupabase). */
    private fun subscribeTimelineRealtime() {
        timelineRealtimeJob?.cancel()
        timelineRealtimeJob = SupabaseRealtimeManager.subscribeValidations(
            channelKey = "parcel_detail_$parcelId",
            filter = "consignment" to parcelId,
            scope = viewLifecycleOwner.lifecycleScope,
        ) { row ->
            viewLifecycleOwner.lifecycleScope.launch {
                if (!isAdded || view == null) return@launch
                val source = row.optStr("source").trim()
                row.optStr("remarks").trim().takeIf { it.isNotBlank() && source.isNotBlank() }?.let { en ->
                    SupabaseClientManager.resolveRemarkBnCached("ParcelDetailFragment", source, en)?.let { bn ->
                        row.put("remarks_bn", bn)
                    }
                }
                timelineRows.add(row)
                try {
                    renderTimeline()
                } catch (e: Exception) {
                    FirebaseErrorLogger.log(
                        screen = "ParcelDetailFragment", action = "renderTimeline_realtime",
                        errorMessage = e.stackTraceToString(),
                        extra = mapOf("parcelId" to parcelId, "scope" to scope)
                    )
                }
                resolveAgentPhoneIfNeeded()
            }
        }
    }

    /** Finds the most recent WORKER-sourced row's assigned_to_system_id and, if it's
     *  different from the last one resolved, looks up that worker's phone (targeted
     *  users_by_systemId/{id}/uid -> users/{uid}/profile/phone reads, not a full scan)
     *  and caches it in currentAgentPhone for the WhatsApp button. */
    private suspend fun resolveAgentPhoneIfNeeded() {
        val latestSystemId = timelineRows
            .filter { it.optStr("source").trim().equals("WORKER", ignoreCase = true) }
            .mapNotNull { r ->
                val sysId = r.optStr("assigned_to_system_id").trim()
                if (sysId.isBlank()) return@mapNotNull null
                val createdAt = SupabaseRemarkValidationWriter.parseCreatedAtMillis(r.optStr("created_at"))
                sysId to createdAt
            }
            .maxByOrNull { it.second }
            ?.first
            .orEmpty()

        if (latestSystemId.isBlank() || latestSystemId == lastResolvedAgentSystemId) return
        lastResolvedAgentSystemId = latestSystemId

        val phone = withContext(Dispatchers.IO) {
            runCatching {
                val uid = db.reference.child("users_by_systemId/$latestSystemId/uid")
                    .get().await().getValue(String::class.java)?.trim().orEmpty()
                if (uid.isBlank()) "" else {
                    db.reference.child("users/$uid/profile/phone")
                        .get().await().getValue(String::class.java)?.trim().orEmpty()
                }
            }.getOrDefault("")
        }
        if (isAdded) currentAgentPhone = phone
    }

    private suspend fun renderTimeline() {
        val ctx = context ?: return

        data class Entry(
            val status:   String,
            val remark:   String,
            val timeStr:  String,
            val author:   String,
            val role:     String,
            val photoUrl: String,
            val createdAt:Long,
            val callLogCount: Int = 0,
            val callLogTotalDurationSec: Int = 0
        )

        val sdf = SimpleDateFormat("dd-MM-yy  hh:mm a", Locale.getDefault())
        val lang = "bn"

        // Resolve display names + photos for any author system IDs we see — same shared
        // resolver WorkerSpaceFragment/CallCenterFragment's Journey Log dialogs use.
        // validations.author_system_id is the lookup key here (not a Firebase uid like the
        // old courier/remarks_by_consignment rows carried), resolved via users_by_systemId.
        val systemIdsToResolve = timelineRows
            .mapNotNull { it.optStr("author_system_id").trim().takeIf { id -> id.isNotBlank() } }
            .filter { !uidNameCache.containsKey(it) }
            .distinct()

        if (systemIdsToResolve.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                systemIdsToResolve.forEach { systemId ->
                    runCatching {
                        val uid = db.reference.child("users_by_systemId/$systemId/uid")
                            .get().await().getValue(String::class.java)?.trim().orEmpty()
                        if (uid.isNotBlank()) {
                            uidNameCache[systemId] = UserNameResolver.resolveName(uid)
                            uidPhotoCache[systemId] = UserNameResolver.resolvePhotoUrl(uid)
                        }
                    }
                }
            }
        }

        val entries = timelineRows
            .mapNotNull { r ->
                val rStatus = r.optStr("remarks_status").trim()
                val rRemarksRaw = r.optStr("remarks").trim()
                val rRemarks = if (r.has("remarks_bn")) r.optStr("remarks_bn").trim().ifBlank { rRemarksRaw } else rRemarksRaw
                val rNoteOnly = r.optStr("note").trim()
                if (rStatus.isBlank() && rRemarks.isBlank()) return@mapNotNull null
                val createdAt = SupabaseRemarkValidationWriter.parseCreatedAtMillis(r.optStr("created_at"))
                val timeStr = if (createdAt > 0) sdf.format(Date(createdAt)) else "—"
                val fromWorker = r.optStr("source").trim().equals("WORKER", ignoreCase = true)
                val authorSystemId = r.optStr("author_system_id").trim()
                val photoUrl = uidPhotoCache[authorSystemId].orEmpty()

                val resolvedName = uidNameCache[authorSystemId]?.takeIf { it.isNotBlank() && it != authorSystemId }
                val isCurrentUser = scope == "worker" && authorSystemId.isNotBlank() && authorSystemId == ownWorkerSystemId
                val author = when {
                    isCurrentUser        -> "You"
                    resolvedName != null -> resolvedName
                    fromWorker            -> "Delivery Agent"
                    else                  -> "CC Agent"
                }
                val role = if (fromWorker) "agent" else "cc"

                // Remarks + note combined — same rule as the long-press Journey Log
                // dialogs' rLabel (WorkerSpaceFragment/CallCenterFragment), falling back
                // to the status label only when there's no remark text at all.
                val display = when {
                    rRemarks.isNotBlank() && rNoteOnly.isNotBlank() -> "$rRemarks\nNote: $rNoteOnly"
                    rRemarks.isNotBlank() -> rRemarks
                    rNoteOnly.isNotBlank() -> rNoteOnly
                    rStatus.isNotBlank()  -> WorkerParcelAdapter.getStatusConfig(ctx, rStatus, lang).label
                    else                  -> ""
                }

                Entry(rStatus, display, timeStr, author, role, photoUrl, createdAt)
            }
            .sortedBy { it.createdAt }   // oldest first → timeline reads top-to-bottom

        // Always lead with the parcel's actual creation — matches the long-press
        // Journey Log dialog, which never shows an empty timeline for a parcel
        // that has no remarks yet (it still has a "CREATED" starting point).
        val allEntries = if (currentCreatedAt > 0) {
            listOf(
                Entry(
                    status = "",
                    remark = "Parcel তৈরি হয়েছে",
                    timeStr = sdf.format(Date(currentCreatedAt)),
                    author = "System",
                    role = "system",
                    photoUrl = "",
                    createdAt = currentCreatedAt
                )
            ) + entries
        } else entries

        // Reuse WorkerParcelAdapter.withResponseGaps() (same logic as the long-press
        // Journey Log dialog) instead of duplicating the handoff-gap calculation here.
        // It operates on HistoryEntry, so map Entry → HistoryEntry → back, keeping this
        // fragment's own Entry model unchanged everywhere else in this function.
        // Indexed (not keyed by createdAt) since two entries could share a timestamp.
        val gapByIndex: Map<Int, Long> = WorkerParcelAdapter.withResponseGaps(
            allEntries.map { e ->
                HistoryEntry(
                    action = e.status,
                    remark = e.remark,
                    time = e.timeStr,
                    author = e.author,
                    authorRole = e.role,
                    authorPhotoUrl = e.photoUrl,
                    createdAt = e.createdAt
                )
            }
        ).withIndex().mapNotNull { (i, h) -> h.responseGapMinutes?.let { i to it } }.toMap()

        withContext(Dispatchers.Main) {
            if (!isAdded || view == null) return@withContext
            layoutTimeline.removeAllViews()

            if (allEntries.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvRemarksCount.text = "0 entries"
                return@withContext
            }

            tvEmpty.visibility = View.GONE
            tvRemarksCount.text = "${entries.size} ${if (entries.size == 1) "entry" else "entries"}"

            val inflater = LayoutInflater.from(ctx)
            allEntries.forEachIndexed { index, entry ->
                val row = inflater.inflate(R.layout.item_timeline_entry, layoutTimeline, false)

                // Avatar
                val ivAvatar = row.findViewById<ShapeableImageView>(R.id.ivTimelineAvatar)
                if (entry.photoUrl.isNotBlank()) {
                    ivAvatar.load(entry.photoUrl) {
                        crossfade(true)
                        placeholder(R.drawable.bg_timeline_avatar_placeholder)
                        error(R.drawable.bg_timeline_avatar_placeholder)
                    }
                } else {
                    ivAvatar.setImageDrawable(null)
                    ivAvatar.setBackgroundResource(R.drawable.bg_timeline_avatar_placeholder)
                }

                // Connector line (hide on last entry)
                row.findViewById<View>(R.id.viewTimelineLine).visibility =
                    if (index < allEntries.size - 1) View.VISIBLE else View.GONE

                // Author name
                row.findViewById<TextView>(R.id.twTimelineAuthor).text =
                    "${entry.author}${if (entry.role == "cc") " · CC" else ""}"

                // Status badge
                val tvStatusBadge = row.findViewById<TextView>(R.id.twTimelineStatus)
                if (entry.status.isNotBlank()) {
                    val cfg = WorkerParcelAdapter.getStatusConfig(ctx, entry.status, lang)
                    tvStatusBadge.text = entry.status.uppercase()
                    tvStatusBadge.setTextColor(cfg.color)
                    tvStatusBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(cfg.bg)
                    tvStatusBadge.visibility = View.VISIBLE
                } else {
                    tvStatusBadge.visibility = View.GONE
                }

                // Remark text
                val tvRemark = row.findViewById<TextView>(R.id.twTimelineRemark)
                if (entry.remark.isNotBlank()) {
                    tvRemark.text = entry.remark
                    tvRemark.visibility = View.VISIBLE
                } else {
                    tvRemark.visibility = View.GONE
                }

                // Timestamp
                row.findViewById<TextView>(R.id.twTimelineMeta).text = entry.timeStr

                // Response-time chip — only shown on the entry that starts a new
                // worker↔CC handoff block (see WorkerParcelAdapter.withResponseGaps).
                val tvGap = row.findViewById<TextView>(R.id.twTimelineGap)
                val gapMin = gapByIndex[index]
                if (gapMin != null) {
                    tvGap.text = "⏱ ${gapMin}m response"
                    tvGap.visibility = View.VISIBLE
                } else {
                    tvGap.visibility = View.GONE
                }

                // Call attempts on this entry — matches CallCenterFragment's Journey Log dialog.
                val tvCallLogs = row.findViewById<TextView>(R.id.twTimelineCallLogs)
                if (entry.callLogCount > 0) {
                    tvCallLogs.text = "📞 ${entry.callLogCount} call${if (entry.callLogCount == 1) "" else "s"}, ${entry.callLogTotalDurationSec}s total"
                    tvCallLogs.visibility = View.VISIBLE
                } else {
                    tvCallLogs.visibility = View.GONE
                }

                layoutTimeline.addView(row)
            }
        }
    }

    // ── Age formatting (matches WorkerSpaceFragment's long-press dialog) ─────────

    // ── Collectable amount — Firebase has stored this as String, Long, or Double
    // depending on which code path wrote it, so a single-type read can throw
    // DatabaseException ("Failed to convert value to Long"). Same fallback chain as
    // WorkerSpaceFragment.readCod().
    private fun readCod(snap: DataSnapshot): Int {
        return snap.child("collectableAmount").getValue(String::class.java)
            ?.toDoubleOrNull()?.toInt()
            ?: snap.child("collectableAmount").getValue(Long::class.java)?.toInt()
            ?: snap.child("collectableAmount").getValue(Double::class.java)?.toInt()
            ?: 0
    }

    private fun readAttempt(snap: DataSnapshot): Int {
        return snap.child("attempt").getValue(String::class.java)?.toDoubleOrNull()?.toInt()
            ?: snap.child("attempt").getValue(Long::class.java)?.toInt()
            ?: snap.child("attempt").getValue(Double::class.java)?.toInt()
            ?: 0
    }

    private fun formatAge(createdAt: Long, updatedAt: Long): String {
        if (createdAt <= 0L) return "—"
        val end = if (updatedAt > 0L) updatedAt else System.currentTimeMillis()
        val diffMs = (end - createdAt).coerceAtLeast(0L)
        val days = diffMs / (24 * 60 * 60 * 1000)
        val hours = diffMs / (60 * 60 * 1000)
        val minutes = diffMs / (60 * 1000)
        return when {
            days >= 1  -> "$days ${if (days == 1L) "Day" else "Days"}"
            hours >= 1 -> "$hours ${if (hours == 1L) "Hour" else "Hours"}"
            minutes >= 1 -> "$minutes ${if (minutes == 1L) "Minute" else "Minutes"}"
            else -> "Just now"
        }
    }
}
