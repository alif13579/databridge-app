package com.cloudx.databridge

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Parcel row for View Orders search results. */
data class ViewOrderParcel(
    val id: String,
    val customer: String,
    val phone: String,
    val address: String,
    val cod: Int,
    val status: String,
    val remarkStatus: String = "",
    val remarks: String = "",
    val remarksAt: Long = 0L,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
) {
    val effectiveStatus: String get() = remarkStatus.ifBlank { status }
}

/**
 * View Orders — Google-like search over all parcels.
 *
 * Type a phone number (any format) or consignment ID, tap Search: every
 * matching parcel from courier/consignments shows as a card with its latest
 * Supabase remark. Tap a card to expand/collapse, tap-and-hold for the same
 * Journey Log bottom sheet Call Center uses (full remark history).
 */
class ViewOrdersFragment : Fragment() {

    private lateinit var etSearch: EditText
    private lateinit var tvClear: TextView
    private lateinit var tvSearchBtn: TextView
    private lateinit var tvCount: TextView
    private lateinit var layoutLoading: View
    private lateinit var tvEmpty: TextView
    private lateinit var rvList: RecyclerView
    private lateinit var adapter: ViewOrdersAdapter

    private var searchJob: Job? = null
    private var debounceJob: Job? = null
    private var searchGeneration = 0

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_view_orders, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        etSearch = view.findViewById(R.id.etVoSearch)
        tvClear = view.findViewById(R.id.tvVoClear)
        tvSearchBtn = view.findViewById(R.id.tvVoSearchBtn)
        tvCount = view.findViewById(R.id.tvVoCount)
        layoutLoading = view.findViewById(R.id.layoutVoLoading)
        tvEmpty = view.findViewById(R.id.tvVoEmpty)
        rvList = view.findViewById(R.id.rvVoList)

        adapter = ViewOrdersAdapter(
            onToggleExpand = { /* handled inside adapter */ },
            onLongPress = { showJourneyDialog(it) }
        )
        rvList.layoutManager = LinearLayoutManager(requireContext())
        rvList.adapter = adapter

        tvSearchBtn.setOnClickListener { runSearch() }
        tvClear.setOnClickListener {
            etSearch.setText("")
            clearResults()
        }
        etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch()
                true
            } else false
        }
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                tvClear.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                // Google-like live search: debounce, minimum 3 characters.
                debounceJob?.cancel()
                val q = s?.toString()?.trim().orEmpty()
                if (q.length < 3) {
                    if (q.isEmpty()) clearResults()
                    return
                }
                debounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(600)
                    if (isAdded) runSearch()
                }
            }
        })
    }

    private fun clearResults() {
        searchJob?.cancel()
        adapter.submitList(emptyList())
        tvCount.visibility = View.GONE
        layoutLoading.visibility = View.GONE
        tvEmpty.visibility = View.VISIBLE
        tvEmpty.text = "🔍\n\nType a phone number or consignment ID above and tap Search.\n\nTap and hold any parcel card to see its Journey Log."
    }

    private fun runSearch() {
        val query = etSearch.text?.toString()?.trim().orEmpty()
        if (query.length < 3) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "Type at least 3 characters of the phone number or consignment ID."
            return
        }
        searchJob?.cancel()
        val generation = ++searchGeneration
        tvEmpty.visibility = View.GONE
        tvCount.visibility = View.GONE
        layoutLoading.visibility = View.VISIBLE

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            val parcels = withContext(Dispatchers.IO) { searchParcels(query) }
            if (!isAdded || generation != searchGeneration) return@launch
            layoutLoading.visibility = View.GONE
            adapter.submitList(parcels)
            if (parcels.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "No parcels found for \"$query\""
                tvCount.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                tvCount.visibility = View.VISIBLE
                tvCount.text = "${parcels.size} result${if (parcels.size > 1) "s" else ""} found"
            }
        }
    }

    /**
     * One Firebase scan of courier/consignments filtered client-side by
     * consignment ID substring or phone digits (any format), then enriched
     * with each parcel's latest Supabase remark for the card badge.
     * Capped at 50 cards so a short query can't flood the list.
     */
    private suspend fun searchParcels(query: String): List<ViewOrderParcel> {
        return try {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance()
            val q = query.lowercase()
            val qDigits = q.filter { it.isDigit() }

            val snap = withTimeoutOrNull(25_000) {
                db.reference.child("courier/consignments").get().await()
            } ?: return emptyList()

            data class Hit(val id: String, val rank: Int, val snap: com.google.firebase.database.DataSnapshot)
            val hits = mutableListOf<Hit>()
            for (child in snap.children) {
                val cId = child.key ?: continue
                val phone = child.child("recipientPhone").getValue(String::class.java).orEmpty()
                val phoneDigits = phone.filter { it.isDigit() }
                val idLower = cId.lowercase()
                val rank = when {
                    idLower == q -> 0
                    idLower.startsWith(q) -> 1
                    idLower.contains(q) -> 2
                    qDigits.length >= 3 && phoneDigits == qDigits -> 3
                    qDigits.length >= 3 && phoneDigits.endsWith(qDigits) -> 4
                    phone.contains(query) -> 5
                    qDigits.length >= 3 && phoneDigits.contains(qDigits) -> 6
                    else -> continue
                }
                hits.add(Hit(cId, rank, child))
                if (hits.size >= 400) break
            }
            val top = hits.sortedWith(compareBy({ it.rank }, { it.id })).take(50)
            if (top.isEmpty()) return emptyList()

            // Latest Supabase remark per parcel for the card badge/status.
            val ids = top.map { it.id }
            val latestById = try {
                val deferred = CompletableDeferred<List<org.json.JSONObject>>()
                SupabaseRemarkValidationWriter.fetchNewRemarksSince(ids, 0L, "ViewOrdersFragment") { rows ->
                    deferred.complete(rows)
                }
                val rows = withTimeoutOrNull(20_000) { deferred.await() }.orEmpty()
                rows.groupBy { it.optString("consignment") }
                    .mapValues { (_, v) -> v.maxByOrNull { it.optString("created_at") } }
            } catch (_: Exception) {
                emptyMap()
            }

            top.map { hit ->
                val s = hit.snap
                val name = s.child("recipientName").getValue(String::class.java).orEmpty()
                val phone = s.child("recipientPhone").getValue(String::class.java).orEmpty()
                val address = s.child("recipientAddress").getValue(String::class.java).orEmpty()
                val cod = s.child("collectableAmount").getValue(String::class.java)
                    ?.toDoubleOrNull()?.toInt()
                    ?: s.child("collectableAmount").getValue(Long::class.java)?.toInt() ?: 0
                val status = s.child("status").getValue(String::class.java).orEmpty()
                val createdAt = s.child("createdAt").getValue(Long::class.java) ?: 0L
                val updatedAt = s.child("updatedAt").getValue(Long::class.java) ?: 0L
                val latest = latestById[hit.id]
                val remarkStatus = latest?.optString("remarks_status")?.trim().orEmpty()
                val remarkText = latest?.optString("remarks_bn")?.trim()
                    ?.ifBlank { latest.optString("remarks").trim() }
                    .orEmpty()
                val note = latest?.optString("note")?.trim().orEmpty()
                val remarks = listOf(
                    remarkText.takeIf { it.isNotBlank() },
                    note.takeIf { it.isNotBlank() }?.let { "Note: $it" }
                ).filterNotNull().joinToString("\n")
                val remarksAt = latest?.optString("created_at")
                    ?.let { SupabaseRemarkValidationWriter.parseCreatedAtMillis(it) } ?: 0L
                ViewOrderParcel(
                    id = hit.id, customer = name, phone = phone, address = address,
                    cod = cod, status = status, remarkStatus = remarkStatus,
                    remarks = remarks, remarksAt = remarksAt,
                    createdAt = createdAt, updatedAt = updatedAt
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- Journey Log (same sheet + timeline Call Center uses) ----

    private fun showJourneyDialog(item: ViewOrderParcel) {
        val (dialog, dialogView) = renderJourneyDialog(item, isLoading = true)
        fun load() {
            renderJourneyDialog(item, isLoading = true, existing = dialog to dialogView)
            viewLifecycleOwner.lifecycleScope.launch {
                val rows = kotlin.runCatching {
                    withTimeoutOrNull(20_000) {
                        withContext(Dispatchers.IO) {
                            val deferred = CompletableDeferred<List<org.json.JSONObject>>()
                            SupabaseRemarkValidationWriter.fetchHistory(item.id, "ViewOrdersFragment") { fetched ->
                                deferred.complete(fetched)
                            }
                            deferred.await()
                        }
                    }
                }.getOrNull()
                if (!isAdded || !dialog.isShowing) return@launch
                if (rows == null) {
                    renderJourneyDialog(
                        item, isLoading = false, hasFailed = true,
                        existing = dialog to dialogView, onRetry = { load() }
                    )
                    return@launch
                }
                renderJourneyDialog(
                    item, isLoading = false,
                    entries = buildJourneyEntries(rows),
                    existing = dialog to dialogView
                )
            }
        }
        load()
    }

    private fun buildJourneyEntries(rows: List<org.json.JSONObject>): List<HistoryEntry> {
        return rows.mapNotNull { r ->
            val status = r.optString("remarks_status").trim()
            val noteRaw = r.optString("note").trim()
            val remarkBn = r.optString("remarks_bn").trim().ifBlank { r.optString("remarks").trim() }
            val remarks = listOf(
                remarkBn.takeIf { it.isNotBlank() },
                noteRaw.takeIf { it.isNotBlank() }?.let { "Note: $it" }
            ).filterNotNull().filter { it.isNotBlank() }.joinToString("\n")
            if (status.isBlank() && remarks.isBlank()) return@mapNotNull null
            val createdAt = SupabaseRemarkValidationWriter.parseCreatedAtMillis(r.optString("created_at"))
            val authorSystemId = r.optString("author_system_id").trim()
            val fromWorker = r.optString("source").trim().equals("WORKER", ignoreCase = true)
            val authorUser = r.optJSONObject("author")
            val authorName = authorUser?.optString("name")?.trim().orEmpty()
                .ifBlank { authorSystemId }
            HistoryEntry(
                action = status.ifBlank { "NOTE" }.uppercase(),
                remark = remarks,
                time = java.text.SimpleDateFormat("dd-MM-yy hh:mm:ss a", java.util.Locale.getDefault())
                    .format(java.util.Date(createdAt)),
                author = authorLabel(authorName, fromWorker),
                authorRole = if (fromWorker) "agent" else "cc",
                authorPhotoUrl = authorUser?.optString("photo_url")?.trim().orEmpty(),
                createdAt = createdAt
            )
        }.sortedBy { it.createdAt }
    }

    private fun authorLabel(name: String, fromWorker: Boolean): String =
        if (name.isBlank()) (if (fromWorker) "Agent" else "CC")
        else "$name${if (fromWorker) "" else " · CC"}"

    private fun renderJourneyDialog(
        item: ViewOrderParcel,
        isLoading: Boolean = false,
        hasFailed: Boolean = false,
        entries: List<HistoryEntry> = emptyList(),
        existing: Pair<BottomSheetDialog, View>? = null,
        onRetry: (() -> Unit)? = null
    ): Pair<BottomSheetDialog, View> {
        val dialog = existing?.first ?: BottomSheetDialog(requireContext())
        val view = existing?.second ?: layoutInflater.inflate(R.layout.bottom_sheet_action_history, null)
        val tvTitle = view.findViewById<TextView>(R.id.twHistoryTitle)
        val tvSub = view.findViewById<TextView>(R.id.twHistorySub)
        val layoutTimeline = view.findViewById<LinearLayout>(R.id.layoutTimeline)
        val layoutLoading = view.findViewById<View>(R.id.layoutHistoryLoading)
        val scrollTimeline = view.findViewById<View>(R.id.scrollHistoryTimeline)
        val pbLoading = view.findViewById<android.widget.ProgressBar>(R.id.pbHistoryLoading)
        val tvLoadingLabel = view.findViewById<TextView>(R.id.twHistoryLoadingLabel)
        val btnRetry = view.findViewById<TextView>(R.id.btnHistoryRetry)
        val tvOvStatus = view.findViewById<TextView>(R.id.twOverviewStatus)
        val tvOvCreatedAt = view.findViewById<TextView>(R.id.twOverviewCreatedAt)
        val tvOvUpdatedAt = view.findViewById<TextView>(R.id.twOverviewUpdatedAt)
        val tvOvAge = view.findViewById<TextView>(R.id.twOverviewAge)

        tvTitle.text = "Journey Log"
        tvSub.text = "${item.id} · ${item.customer}"

        val cfg = WorkerParcelAdapter.getStatusConfig(requireContext(), item.effectiveStatus, "en")
        tvOvStatus.text = cfg.label
        tvOvStatus.setTextColor(cfg.color)
        val fullFmt = java.text.SimpleDateFormat("dd-MM-yy hh:mm:ss a", java.util.Locale.getDefault())
        tvOvCreatedAt.text = if (item.createdAt > 0) fullFmt.format(java.util.Date(item.createdAt)) else "—"
        tvOvUpdatedAt.text = if (item.updatedAt > 0) fullFmt.format(java.util.Date(item.updatedAt)) else "—"
        tvOvAge.text = formatAge(item.createdAt, item.updatedAt)
        val (ovAgeColor, _) = WorkerParcelAdapter.ageColorFor(item.createdAt)
        tvOvAge.setTextColor(ovAgeColor)

        layoutTimeline.removeAllViews()
        layoutLoading.visibility = if (isLoading || hasFailed) View.VISIBLE else View.GONE
        scrollTimeline.visibility = if (isLoading || hasFailed) View.GONE else View.VISIBLE
        pbLoading.visibility = if (isLoading && !hasFailed) View.VISIBLE else View.GONE
        tvLoadingLabel.visibility = if (isLoading && !hasFailed) View.VISIBLE else View.GONE
        if (hasFailed) {
            btnRetry.visibility = View.VISIBLE
            btnRetry.setOnClickListener { onRetry?.invoke() }
        } else {
            btnRetry.visibility = View.GONE
            btnRetry.setOnClickListener(null)
        }
        if (isLoading || hasFailed) {
            if (existing == null) {
                view.findViewById<TextView>(R.id.btnHistoryClose).setOnClickListener { dialog.dismiss() }
                dialog.setContentView(view)
                dialog.show()
            }
            return dialog to view
        }

        val all = mutableListOf<HistoryEntry>()
        if (item.createdAt > 0) {
            all.add(
                HistoryEntry(
                    action = "CREATED", remark = "Parcel created",
                    time = fullFmt.format(java.util.Date(item.createdAt)),
                    author = "System", authorRole = "system"
                )
            )
        }
        all.addAll(entries)
        val withGaps = WorkerParcelAdapter.withResponseGaps(all)

        if (withGaps.isEmpty()) {
            layoutTimeline.addView(
                LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_timeline_empty, layoutTimeline, false)
            )
        } else {
            for ((index, entry) in withGaps.withIndex()) {
                val tv = layoutInflater.inflate(R.layout.item_timeline_entry, layoutTimeline, false)
                val statusCfg = WorkerParcelAdapter.getStatusConfig(
                    requireContext(),
                    entry.action.lowercase().replace(" ", "_"), "en"
                )
                val ivAvatar = tv.findViewById<android.widget.ImageView>(R.id.ivTimelineAvatar)
                val tvLine = tv.findViewById<View>(R.id.viewTimelineLine)
                if (entry.authorPhotoUrl.isNotBlank()) {
                    ivAvatar.load(entry.authorPhotoUrl) {
                        crossfade(true)
                        placeholder(R.drawable.bg_timeline_avatar_placeholder)
                        error(R.drawable.bg_timeline_avatar_placeholder)
                    }
                } else {
                    ivAvatar.setImageDrawable(null)
                    ivAvatar.setBackgroundResource(R.drawable.bg_timeline_avatar_placeholder)
                }
                tvLine.visibility = if (index < withGaps.size - 1) View.VISIBLE else View.GONE
                tv.findViewById<TextView>(R.id.twTimelineAuthor).text = entry.author
                val tvStatus = tv.findViewById<TextView>(R.id.twTimelineStatus)
                tvStatus.text = entry.action
                tvStatus.setTextColor(statusCfg.color)
                tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(statusCfg.bg)
                val tvRemark = tv.findViewById<TextView>(R.id.twTimelineRemark)
                tvRemark.text = entry.remark
                tvRemark.visibility = if (entry.remark.isNotBlank()) View.VISIBLE else View.GONE
                tv.findViewById<TextView>(R.id.twTimelineMeta).text = entry.time
                val tvGap = tv.findViewById<TextView>(R.id.twTimelineGap)
                if (entry.responseGapMinutes != null) {
                    tvGap.text = "⏱ ${entry.responseGapMinutes}m response"
                    tvGap.visibility = View.VISIBLE
                } else {
                    tvGap.visibility = View.GONE
                }
                tv.findViewById<TextView>(R.id.twTimelineCallLogs).visibility = View.GONE
                layoutTimeline.addView(tv)
            }
        }

        if (existing == null) {
            view.findViewById<TextView>(R.id.btnHistoryClose).setOnClickListener { dialog.dismiss() }
            dialog.setContentView(view)
            dialog.show()
        }
        return dialog to view
    }

    private fun formatAge(createdAt: Long, updatedAt: Long): String {
        if (createdAt <= 0L) return "—"
        val end = if (updatedAt > 0L) updatedAt else System.currentTimeMillis()
        val diffMs = (end - createdAt).coerceAtLeast(0L)
        val days = diffMs / (24 * 60 * 60 * 1000)
        val hours = diffMs / (60 * 60 * 1000)
        val minutes = diffMs / (60 * 1000)
        return when {
            days >= 1 -> "$days ${if (days == 1L) "Day" else "Days"}"
            hours >= 1 -> "$hours ${if (hours == 1L) "Hour" else "Hours"}"
            minutes >= 1 -> "$minutes ${if (minutes == 1L) "Minute" else "Minutes"}"
            else -> "Just now"
        }
    }

    /** Simple card adapter: tap expands/collapses, long-press opens Journey Log. */
    class ViewOrdersAdapter(
        private val onToggleExpand: () -> Unit,
        private val onLongPress: (ViewOrderParcel) -> Unit
    ) : ListAdapter<ViewOrderParcel, ViewOrdersAdapter.Holder>(Diff()) {

        var expandedId: String? = null

        class Diff : DiffUtil.ItemCallback<ViewOrderParcel>() {
            override fun areItemsTheSame(a: ViewOrderParcel, b: ViewOrderParcel) = a.id == b.id
            override fun areContentsTheSame(a: ViewOrderParcel, b: ViewOrderParcel) = a == b
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val tvCustomer: TextView = v.findViewById(R.id.tvVoCustomer)
            val tvStatus: TextView = v.findViewById(R.id.tvVoStatus)
            val tvMeta: TextView = v.findViewById(R.id.tvVoMeta)
            val tvAddress: TextView = v.findViewById(R.id.tvVoAddress)
            val tvCod: TextView = v.findViewById(R.id.tvVoCod)
            val remarksBox: View = v.findViewById(R.id.layoutVoRemarksBox)
            val tvRemarks: TextView = v.findViewById(R.id.tvVoRemarks)
            val tvRemarksTime: TextView = v.findViewById(R.id.tvVoRemarksTime)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_view_order_card, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = getItem(position)
            val ctx = holder.itemView.context
            holder.tvCustomer.text = item.customer.ifBlank { item.id }
            holder.tvMeta.text = "${item.id} · ${item.phone}"
            holder.tvAddress.text = item.address
            holder.tvAddress.visibility = if (item.address.isBlank()) View.GONE else View.VISIBLE
            holder.tvCod.text = "COD: ৳${item.cod}"

            val cfg = WorkerParcelAdapter.getStatusConfig(ctx, item.effectiveStatus, "en")
            holder.tvStatus.text = cfg.label
            holder.tvStatus.setTextColor(cfg.color)
            holder.tvStatus.backgroundTintList = android.content.res.ColorStateList.valueOf(cfg.bg)

            if (item.remarks.isNotBlank()) {
                holder.remarksBox.visibility = View.VISIBLE
                holder.tvRemarks.text = item.remarks
                holder.tvRemarksTime.text = if (item.remarksAt > 0) {
                    java.text.SimpleDateFormat("dd-MM-yy hh:mm a", java.util.Locale.getDefault())
                        .format(java.util.Date(item.remarksAt))
                } else ""
                holder.tvRemarksTime.visibility =
                    if (item.remarksAt > 0) View.VISIBLE else View.GONE
            } else {
                holder.remarksBox.visibility = View.GONE
            }

            val isExpanded = expandedId == item.id
            holder.tvAddress.maxLines = if (isExpanded) Int.MAX_VALUE else 2

            holder.itemView.setOnClickListener {
                expandedId = if (isExpanded) null else item.id
                notifyItemChanged(position)
                onToggleExpand()
            }
            holder.itemView.setOnLongClickListener { onLongPress(item); true }
        }
    }
}
