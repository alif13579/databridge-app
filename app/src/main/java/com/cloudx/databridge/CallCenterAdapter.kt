package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * RecyclerView-backed adapter (worker group headers + parcel cards as a flat row list).
 * Replaces the old renderInto(LinearLayout)-based inflate-everything approach so that
 * views are recycled instead of all being inflated/measured at once — this is what
 * gives smooth scroll and low memory use on large lists (matches WorkerParcelAdapter's pattern).
 */
class CallCenterAdapter(
    private val onCall: (CallCenterParcelItem) -> Unit,
    private val onSetRemarks: (CallCenterParcelItem) -> Unit,
    private val onValidate: (CallCenterParcelItem) -> Unit,
    private val onGroupClick: ((WorkerGroup) -> Unit)? = null
) : ListAdapter<CallCenterAdapter.Row, RecyclerView.ViewHolder>(RowDiff()) {

    var expandedItemId: String? = null
        private set
    var statusLang: String = "bn"

    // consignmentId -> glow color (null/absent = no glow). Set by the fragment as the
    // Auto Call sequence progresses (queued/calling/done) or a card is manually dialed.
    var callStates: Map<String, Int> = emptyMap()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    /** Matches the previous behavior: collapse any expanded card (e.g. on a fresh filter/data pass). */
    fun collapseExpanded() {
        expandedItemId = null
    }

    // Worker group headers
    data class WorkerGroup(
        val workerName: String,
        val branch: String,
        val parcels: List<CallCenterParcelItem>
    )

    sealed class Row {
        abstract val stableKey: String

        data class HeaderRow(val group: WorkerGroup) : Row() {
            override val stableKey get() = "header:${group.workerName}"
        }

        data class CardRow(val parcel: CallCenterParcelItem, val isExpanded: Boolean) : Row() {
            override val stableKey get() = "card:${parcel.id}"
        }
    }

    /** Call this with the flat parcel list; builds header+card rows and diffs against the current list. */
    fun submitParcels(items: List<CallCenterParcelItem>) {
        val map = linkedMapOf<String, MutableList<CallCenterParcelItem>>()
        items.forEach { parcel ->
            map.getOrPut(parcel.worker) { mutableListOf() }.add(parcel)
        }
        val rows = mutableListOf<Row>()
        for ((worker, parcels) in map) {
            val group = WorkerGroup(worker, parcels.firstOrNull()?.branch ?: "", parcels)
            rows.add(Row.HeaderRow(group))
            parcels.forEach { parcel ->
                rows.add(Row.CardRow(parcel, isExpanded = parcel.id == expandedItemId))
            }
        }
        submitList(rows)
    }

    private fun toggleExpanded(id: String) {
        expandedItemId = if (expandedItemId == id) null else id
        // Rebuild rows with updated isExpanded flags from the currently shown list.
        val parcels = currentList.filterIsInstance<Row.CardRow>().map { it.parcel }
        submitParcels(parcels)
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is Row.HeaderRow -> VIEW_TYPE_HEADER
        is Row.CardRow -> VIEW_TYPE_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_HEADER) {
            HeaderHolder(inflater.inflate(R.layout.item_worker_group_header, parent, false))
        } else {
            CardHolder(inflater.inflate(R.layout.item_parcel_agent_card, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is Row.HeaderRow -> (holder as HeaderHolder).bind(row.group, onGroupClick)
            is Row.CardRow -> (holder as CardHolder).bind(
                row.parcel,
                row.isExpanded,
                statusLang = statusLang,
                glowColor = callStates[row.parcel.id],
                onToggleExpand = { toggleExpanded(row.parcel.id) },
                onCall = onCall,
                onSetRemarks = onSetRemarks,
                onValidate = onValidate
            )
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvWorkerName: TextView = view.findViewById(R.id.tvGroupWorkerName)
        private val tvWorkerMeta: TextView = view.findViewById(R.id.tvGroupWorkerMeta)
        private val tvConfirmed: TextView = view.findViewById(R.id.tvGroupConfirmed)
        private val tvPending: TextView = view.findViewById(R.id.tvGroupPending)

        fun bind(group: WorkerGroup, onGroupClick: ((WorkerGroup) -> Unit)?) {
            tvWorkerName.text = group.workerName
            tvWorkerMeta.text = "${group.branch} · ${group.parcels.size} parcels"

            val confirmedCount = group.parcels.count { it.status == "confirmed" }
            val pendingCount = group.parcels.count { it.status == "pending" }
            tvConfirmed.text = "$confirmedCount ✓"
            tvPending.text = "$pendingCount ◌"

            itemView.setOnClickListener { onGroupClick?.invoke(group) }
        }
    }

    class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvCustomer: TextView = view.findViewById(R.id.tvAgtCustomer)
        private val tvValidationBadge: TextView = view.findViewById(R.id.tvAgtValidationBadge)
        private val tvMeta: TextView = view.findViewById(R.id.tvAgtMeta)
        private val tvAddress: TextView = view.findViewById(R.id.tvAgtAddress)
        private val tvCod: TextView = view.findViewById(R.id.tvAgtCod)
        private val tvStatusBadge: TextView = view.findViewById(R.id.tvAgtStatusBadge)
        private val tvRemarks: TextView = view.findViewById(R.id.tvAgtRemarks)
        private val tvValidationNote: TextView = view.findViewById(R.id.tvAgtValidationNote)
        private val layoutActions: LinearLayout = view.findViewById(R.id.layoutAgtActions)
        private val btnCall: TextView = view.findViewById(R.id.btnAgtCall)
        private val btnSetRemarks: TextView = view.findViewById(R.id.btnAgtSetRemarks)
        private val btnValidate: TextView = view.findViewById(R.id.btnAgtValidate)

        fun bind(
            item: CallCenterParcelItem,
            isExpanded: Boolean,
            statusLang: String,
            glowColor: Int?,
            onToggleExpand: () -> Unit,
            onCall: (CallCenterParcelItem) -> Unit,
            onSetRemarks: (CallCenterParcelItem) -> Unit,
            onValidate: (CallCenterParcelItem) -> Unit
        ) {
            tvCustomer.text = item.customer
            tvMeta.text = "${item.id} · ${item.phone}"
            tvAddress.text = "📍 ${item.address}"
            tvCod.text = "৳${item.cod}"

            applyCallStateGlow(itemView, glowColor)

            val cfg = WorkerParcelAdapter.getStatusConfig(tvStatusBadge.context, item.effectiveStatus, statusLang)
            tvStatusBadge.text = cfg.label
            tvStatusBadge.setTextColor(cfg.color)
            tvStatusBadge.setBackgroundColor(cfg.bg)

            tvValidationBadge.visibility = if (item.validationRequest) View.VISIBLE else View.GONE

            // Badge shows the effective status; this line shows the actual remark text
            // written by whoever set it, so the agent can read exactly what was said
            // (not just the status label). Colored to match the same status for quick
            // visual scanning — a red remark jumps out as much as the red badge does.
            if (item.remarks.isNotBlank()) {
                tvRemarks.text = "💬 ${item.remarks}"
                tvRemarks.visibility = View.VISIBLE
                tvRemarks.setTextColor(cfg.color)
                tvRemarks.backgroundTintList = android.content.res.ColorStateList.valueOf(cfg.bg)
            } else {
                tvRemarks.visibility = View.GONE
            }

            if (item.validationNote.isNotBlank()) {
                tvValidationNote.text = "⚡ Worker: ${item.validationNote}"
                tvValidationNote.visibility = View.VISIBLE
            } else {
                tvValidationNote.visibility = View.GONE
            }

            layoutActions.visibility = if (isExpanded) View.VISIBLE else View.GONE
            btnValidate.visibility = if (isExpanded && item.validationRequest) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onToggleExpand() }
            btnCall.setOnClickListener { onCall(item) }
            btnSetRemarks.setOnClickListener { onSetRemarks(item) }
            btnValidate.setOnClickListener { onValidate(item) }
        }
    }

    class RowDiff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.stableKey == b.stableKey
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_CARD = 1
    }
}

/**
 * Applies (or clears) a colored glow to a call-progress card, indicating Auto Call state:
 *   green  = call done (auto-dialed or manually dialed)
 *   yellow = queued, waiting its turn
 *   purple = currently being dialed
 *   null   = never called — no glow, normal card look
 *
 * Uses a colored border on every API level (universal), plus a real colored shadow on
 * API 28+ where View.outlineSpotShadowColor/outlineAmbientShadowColor are available —
 * older devices still clearly show the colored border, just without the soft shadow blur.
 */
fun applyCallStateGlow(view: View, glowColor: Int?) {
    val density = view.resources.displayMetrics.density
    val cornerRadiusPx = 14f * density
    val fillColor = androidx.core.content.ContextCompat.getColor(view.context, R.color.theme_bg_card)

    if (glowColor == null) {
        val normal = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(fillColor)
            setStroke((1 * density).toInt(), androidx.core.content.ContextCompat.getColor(view.context, R.color.theme_border))
        }
        view.background = normal
        view.elevation = 0f
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            view.outlineSpotShadowColor = android.graphics.Color.BLACK
            view.outlineAmbientShadowColor = android.graphics.Color.BLACK
        }
        return
    }

    val glow = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = cornerRadiusPx
        setColor(fillColor)
        setStroke((2.5f * density).toInt(), glowColor)
    }
    view.background = glow

    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
        view.outlineSpotShadowColor = glowColor
        view.outlineAmbientShadowColor = glowColor
        view.elevation = 10f * density
    } else {
        view.elevation = 0f
    }
}
