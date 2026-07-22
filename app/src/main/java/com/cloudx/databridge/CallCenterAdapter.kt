package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.transform.CircleCropTransformation

/**
 * RecyclerView-backed adapter (worker group headers + parcel cards as a flat row list).
 * Replaces the old renderInto(LinearLayout)-based inflate-everything approach so that
 * views are recycled instead of all being inflated/measured at once — this is what
 * gives smooth scroll and low memory use on large lists (matches WorkerParcelAdapter's pattern).
 */
class CallCenterAdapter(
    private val onCall: (CallCenterParcelItem) -> Unit,
    private val onSetRemarks: (CallCenterParcelItem) -> Unit,
    private val onLongPress: (CallCenterParcelItem) -> Unit,
    private val onGroupClick: ((WorkerGroup) -> Unit)? = null,
    /** Fired when a card transitions collapsed -> expanded — see WorkerParcelAdapter's
     *  matching parameter / EngagedStateManager's doc comment. */
    private val onExpand: (CallCenterParcelItem) -> Unit = {},
    /** Fired when a card transitions expanded -> collapsed, including when switching
     *  straight to a different card (the previously-expanded one collapses too). */
    private val onCollapse: (CallCenterParcelItem) -> Unit = {}
) : ListAdapter<CallCenterAdapter.Row, RecyclerView.ViewHolder>(RowDiff()) {

    var expandedItemId: String? = null
    /** Normalized (last-10-digit) phone numbers whose parcels are split across more than
     *  one agent — set by the fragment before submitParcels(). See CardRow.isConflicted. */
    var conflictedPhones: Set<String> = emptySet()

    private fun String.normalizedPhone(): String = filter { it.isDigit() }.takeLast(10)
    var statusLang: String = "bn"
    /** "attempt" (default, most-attempted first) or "aging" (oldest first) — same options as
     *  Worker Fragment's Sort By dropdown. Read by submitParcels() each call. */
    var sortMode: String = "attempt"

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
        val parcels: List<CallCenterParcelItem>,
        val workerPhotoUrl: String = ""
    )

    sealed class Row {
        abstract val stableKey: String

        data class HeaderRow(val group: WorkerGroup) : Row() {
            override val stableKey get() = "header:${group.workerName}"
        }

        data class CardRow(val parcel: CallCenterParcelItem, val isExpanded: Boolean, val isConflicted: Boolean) : Row() {
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
        for ((worker, rawParcels) in map) {
            // Same-phone parcels stay adjacent, ordered per the active sort mode —
            // applied per worker so each agent's own section is sorted independently.
            val parcels = when (sortMode) {
                "aging" -> sortByGroupAge(rawParcels)
                else    -> sortByAttempt(rawParcels)
            }
            val group = WorkerGroup(worker, parcels.firstOrNull()?.branch ?: "", parcels, parcels.firstOrNull()?.workerPhotoUrl ?: "")
            rows.add(Row.HeaderRow(group))
            parcels.forEach { parcel ->
                rows.add(Row.CardRow(
                    parcel,
                    isExpanded = parcel.id == expandedItemId,
                    isConflicted = parcel.phone.normalizedPhone() in conflictedPhones
                ))
            }
        }
        submitList(rows)
    }

    /** Returns the parcel at [position] if that row is a card row, else null (e.g. a header row). */
    fun parcelAt(position: Int): CallCenterParcelItem? =
        (currentList.getOrNull(position) as? Row.CardRow)?.parcel

    /** True if the row at [position] is swipeable (a card, not a worker group header). */
    fun isCardRow(position: Int): Boolean = currentList.getOrNull(position) is Row.CardRow

    private fun toggleExpanded(id: String) {
        val previousId = expandedItemId
        val wasCollapsed = expandedItemId != id
        expandedItemId = if (expandedItemId == id) null else id
        // Rebuild rows with updated isExpanded flags from the currently shown list.
        val parcels = currentList.filterIsInstance<Row.CardRow>().map { it.parcel }
        submitParcels(parcels)
        if (wasCollapsed) {
            parcels.firstOrNull { it.id == id }?.let { onExpand(it) }
            // Switching straight to a different card also collapses whatever was open.
            if (previousId != null && previousId != id) {
                parcels.firstOrNull { it.id == previousId }?.let { onCollapse(it) }
            }
        } else {
            parcels.firstOrNull { it.id == id }?.let { onCollapse(it) }
        }
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
                row.isConflicted,
                statusLang = statusLang,
                glowColor = callStates[row.parcel.id],
                onToggleExpand = { toggleExpanded(row.parcel.id) },
                onCall = onCall,
                onSetRemarks = onSetRemarks,
                onLongPress = onLongPress
            )
        }
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvWorkerIcon: TextView = view.findViewById(R.id.tvGroupWorkerIcon)
        private val ivWorkerAvatar: ImageView = view.findViewById(R.id.ivGroupWorkerAvatar)
        private val tvWorkerName: TextView = view.findViewById(R.id.tvGroupWorkerName)
        private val tvWorkerMeta: TextView = view.findViewById(R.id.tvGroupWorkerMeta)
        private val tvConfirmed: TextView = view.findViewById(R.id.tvGroupConfirmed)
        private val tvPending: TextView = view.findViewById(R.id.tvGroupPending)

        fun bind(group: WorkerGroup, onGroupClick: ((WorkerGroup) -> Unit)?) {
            tvWorkerName.text = group.workerName
            tvWorkerMeta.text = "${group.branch} · ${group.parcels.size} parcels"

            // Photo when we have one (same load/fallback pattern as item_user_card.xml in
            // EmployeeFragment); otherwise keep the generic 👤 placeholder icon.
            if (group.workerPhotoUrl.isNotBlank()) {
                ivWorkerAvatar.visibility = View.VISIBLE
                tvWorkerIcon.visibility = View.GONE
                ivWorkerAvatar.load(group.workerPhotoUrl) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    error(android.R.drawable.ic_menu_myplaces)
                }
            } else {
                ivWorkerAvatar.visibility = View.GONE
                tvWorkerIcon.visibility = View.VISIBLE
            }

            val confirmedCount = group.parcels.count { it.status == "confirmed" }
            val pendingCount = group.parcels.count { it.status == "pending" }
            tvConfirmed.text = "$confirmedCount ✓"
            tvPending.text = "$pendingCount ◌"

            itemView.setOnClickListener { onGroupClick?.invoke(group) }
        }
    }

    class CardHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val tvCustomer: TextView = view.findViewById(R.id.tvAgtCustomer)

        private val tvMeta: TextView = view.findViewById(R.id.tvAgtMeta)
        private val tvAddress: TextView = view.findViewById(R.id.tvAgtAddress)
        private val tvCod: TextView = view.findViewById(R.id.tvAgtCod)
        private val tvAge: TextView = view.findViewById(R.id.tvAgtAge)
        private val tvSplitWarning: TextView = view.findViewById(R.id.tvAgtSplitWarning)
        private val tvStatusBadge: TextView = view.findViewById(R.id.tvAgtStatusBadge)
        private val remarksBox: View = view.findViewById(R.id.layoutAgtRemarksBox)
        private val tvRemarks: TextView = view.findViewById(R.id.tvAgtRemarks)
        private val tvRemarksTime: TextView = view.findViewById(R.id.tvAgtRemarksTime)
        private val engagedRing: EngagedRingView = view.findViewById(R.id.viewEngagedRing)
        private val layoutActions: LinearLayout = view.findViewById(R.id.layoutAgtActions)
        private val btnCall: TextView = view.findViewById(R.id.btnAgtCall)
        private val btnSetRemarks: TextView = view.findViewById(R.id.btnAgtSetRemarks)

        fun bind(
            item: CallCenterParcelItem,
            isExpanded: Boolean,
            isConflicted: Boolean,
            statusLang: String,
            glowColor: Int?,
            onToggleExpand: () -> Unit,
            onCall: (CallCenterParcelItem) -> Unit,
            onSetRemarks: (CallCenterParcelItem) -> Unit,
            onLongPress: (CallCenterParcelItem) -> Unit
        ) {
            tvCustomer.text = item.customer
            tvMeta.text = "${item.id} · ${item.phone}"
            tvAddress.text = "📍 ${item.address}"
            tvCod.text = "৳${item.cod}"
            tvSplitWarning.visibility = if (isConflicted) View.VISIBLE else View.GONE
            tvAge.text = "${WorkerParcelAdapter.formatAgeCompact(item.createdAt)}  ·  A${item.attemptCount}"
            val (ageColor, ageBold) = WorkerParcelAdapter.ageColorFor(item.createdAt)
            tvAge.setTextColor(ageColor)
            tvAge.setTypeface(null, if (ageBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tvAge.textSize = if (ageBold) 11f else 10f

            // ✅ Get status config FIRST — needed for card border, badge, and glow
            val cfg = WorkerParcelAdapter.getStatusConfig(tvStatusBadge.context, item.effectiveStatus, statusLang)
            tvStatusBadge.text = cfg.label
            tvStatusBadge.setTextColor(cfg.color)
            tvStatusBadge.setBackgroundColor(cfg.bg)

            // Remark's own status color (if the remark has a specific status recorded).
            // Computed here — before the card border/glow block below and the remark
            // tint further down — since both of those need it.
            val remarkColor: Int? = if (item.remarks.isNotBlank() && item.remarkStatus.isNotBlank()) {
                StatusMetaCache.entries[item.remarkStatus]?.color
            } else null

            // Card border/glow — when a remark is present use its color for the border so
            // the card draws attention (same behaviour as WorkerParcelAdapter). Active-call
            // glowColor always wins over the remark color (handled inside applyCallStateGlow).
            val borderColor = remarkColor ?: cfg.color
            applyCallStateGlow(itemView, glowColor, if (item.remarks.isNotBlank()) borderColor else null)

            // Badge shows the effective status; this line shows the actual remark text
            // written by whoever set it, so the agent can read exactly what was said.
            // When a remark exists, tint the remark box background with the status color
            // at ~15% alpha (same as Worker card) and color the text fully — so the remark
            // visually pops without being hard to read.
            if (item.remarks.isNotBlank()) {
                tvRemarks.text = "💬 ${item.remarks}"
                remarksBox.visibility = View.VISIBLE
                val tintColor = remarkColor ?: cfg.color
                val tintedBg = android.graphics.Color.argb(
                    38, // ~15% alpha
                    android.graphics.Color.red(tintColor),
                    android.graphics.Color.green(tintColor),
                    android.graphics.Color.blue(tintColor)
                )
                remarksBox.background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 8f * tvRemarks.context.resources.displayMetrics.density
                    setColor(tintedBg)
                }
                tvRemarks.setTextColor(tintColor)
                // Elapsed time since this specific remark was left — lets the CC agent see at
                // a glance how long a worker's remark has been pending. Only meaningful when
                // we actually have a timestamp for it; older/legacy data may not carry remarksAt.
                val elapsed = WorkerParcelAdapter.formatElapsedHM(item.remarksAt)
                if (elapsed.isNotEmpty()) {
                    tvRemarksTime.text = "🕐 $elapsed"
                    tvRemarksTime.visibility = View.VISIBLE
                } else {
                    tvRemarksTime.visibility = View.GONE
                }
            } else {
                remarksBox.visibility = View.GONE
            }

            // Engaged ring — spins while someone (either side) has this parcel's card expanded
            // and that engagement hasn't gone stale (see EngagedStateManager).
            if (EngagedStateManager.isFresh(item.engagedAt)) {
                engagedRing.start()
            } else {
                engagedRing.stop()
            }

            layoutActions.visibility = if (isExpanded) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onToggleExpand() }
            itemView.setOnLongClickListener { onLongPress(item); true }
            btnCall.setOnClickListener { onCall(item) }
            btnSetRemarks.setOnClickListener { onSetRemarks(item) }
        }
    }

    class RowDiff : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(a: Row, b: Row) = a.stableKey == b.stableKey
        override fun areContentsTheSame(a: Row, b: Row) = a == b
    }

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_CARD = 1

        /**
         * Same grouping/ordering rule as WorkerParcelAdapter.sortByGroupAge(), applied within
         * a single worker's parcels: same-phone parcels stay adjacent, the group containing
         * the oldest parcel leads, and within a group the oldest parcel comes first.
         * Call this on each worker's parcel list BEFORE handing it to submitParcels(), since
         * submitParcels() preserves whatever order it's given within each worker group.
         */
        fun sortByGroupAge(parcels: List<CallCenterParcelItem>): List<CallCenterParcelItem> {
            fun effectiveAge(p: CallCenterParcelItem): Long = if (p.createdAt <= 0L) Long.MAX_VALUE else p.createdAt
            val groups = parcels.groupBy { p -> p.phone.filter { c -> c.isDigit() }.takeLast(10) }
            return groups.values
                .sortedBy { group -> group.minOf { p -> effectiveAge(p) } }
                .flatMap { group -> group.sortedBy { p -> effectiveAge(p) } }
        }

        /**
         * Same grouping guarantee as sortByGroupAge (same-phone parcels stay adjacent), but
         * ordered by attempt count descending — the group containing the most-attempted
         * parcel leads. Ties within/across groups fall back to oldest-first.
         */
        fun sortByAttempt(parcels: List<CallCenterParcelItem>): List<CallCenterParcelItem> {
            fun effectiveAge(p: CallCenterParcelItem): Long = if (p.createdAt <= 0L) Long.MAX_VALUE else p.createdAt
            val groups = parcels.groupBy { p -> p.phone.filter { c -> c.isDigit() }.takeLast(10) }
            return groups.values
                .sortedWith(
                    compareByDescending<List<CallCenterParcelItem>> { group -> group.maxOf { p -> p.attemptCount } }
                        .thenBy { group -> group.minOf { p -> effectiveAge(p) } }
                )
                .flatMap { group ->
                    group.sortedWith(compareByDescending<CallCenterParcelItem> { it.attemptCount }.thenBy { effectiveAge(it) })
                }
        }
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
fun applyCallStateGlow(view: View, glowColor: Int?, statusColor: Int? = null) {
    val density = view.resources.displayMetrics.density
    val cornerRadiusPx = 14f * density
    val fillColor = androidx.core.content.ContextCompat.getColor(view.context, R.color.theme_bg_card)

    if (glowColor == null) {
        val normal = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx
            setColor(fillColor)
            val borderColor = statusColor ?: androidx.core.content.ContextCompat.getColor(view.context, R.color.theme_border)
            setStroke((2f * density).toInt(), borderColor)
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
        setStroke((3f * density).toInt(), glowColor)
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
