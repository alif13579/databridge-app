package com.cloudx.databridge

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

data class WorkerParcelItem(
    val id: String,
    val customer: String,
    val phone: String,
    val address: String,
    val cod: Int,
    val status: String,
    val remarks: String,
    // The latest remark's own status key (e.g. "verify_req") — independent of `status`
    // (the parcel's real delivery status). Never written to courier/consignments/{id}/status.
    val remarkStatus: String = "",
    val validationRequest: Boolean,
    val validationNote: String,
    val time: String,
    val ccRemark: String = "",
    val ccRemarkTime: String = "",
    val ccRemarkAuthor: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val attemptCount: Int = 0,
    val history: List<HistoryEntry> = emptyList()
)

data class HistoryEntry(
    val action: String,
    val remark: String,
    val time: String,
    val author: String,
    val authorRole: String,
    val authorPhotoUrl: String = ""
)

class WorkerParcelAdapter(
    private val onCall: (WorkerParcelItem) -> Unit,
    private val onSetRemarks: (WorkerParcelItem) -> Unit,
    private val onLongPress: (WorkerParcelItem) -> Unit
) : ListAdapter<WorkerParcelItem, WorkerParcelAdapter.Holder>(Diff()) {

    var expandedItemId: String? = null
    var statusLang: String = "bn"
    private var previousExpandedPosition: Int? = null

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val tvCustomer: TextView = view.findViewById(R.id.tvParcelCustomer)
        val tvValidationBadge: TextView = view.findViewById(R.id.tvParcelValidationBadge)
        val tvMeta: TextView = view.findViewById(R.id.tvParcelMeta)
        val tvAddress: TextView = view.findViewById(R.id.tvParcelAddress)
        val tvCod: TextView = view.findViewById(R.id.tvParcelCod)
        val tvAge: TextView = view.findViewById(R.id.tvParcelAge)
        val tvStatusBadge: TextView = view.findViewById(R.id.tvParcelStatusBadge)
        val tvRemarks: TextView = view.findViewById(R.id.tvParcelRemarks)
        val tvValidationNote: TextView = view.findViewById(R.id.tvParcelValidationNote)
        val tvCcRemarkBlock: View = view.findViewById(R.id.layoutCcRemarkBlock)
        val tvCcRemarkLabel: TextView = view.findViewById(R.id.tvCcRemarkLabel)
        val tvCcRemarkText: TextView = view.findViewById(R.id.tvCcRemarkText)
        val layoutActions: View = view.findViewById(R.id.layoutParcelActions)
        val btnCall: View = view.findViewById(R.id.btnParcelCall)
        val btnSetRemarks: View = view.findViewById(R.id.btnParcelSetRemarks)
        val viewGroupStripe: View = view.findViewById(R.id.viewGroupStripe)
    }

    // Color used for the left stripe on same-phone grouped cards
    private val GROUP_STRIPE_COLOR = android.graphics.Color.parseColor("#3B82F6")

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_parcel_card, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val isExpanded = item.id == expandedItemId
        val ctx = holder.itemView.context

        // ── Group stripe: detect same-phone neighbours ──────────────
        val normalizedPhone = item.phone.filter { it.isDigit() }.takeLast(10)
        val prevPhone = if (position > 0) getItem(position - 1).phone.filter { it.isDigit() }.takeLast(10) else ""
        val nextPhone = if (position < currentList.size - 1) getItem(position + 1).phone.filter { it.isDigit() }.takeLast(10) else ""
        val hasPrev = normalizedPhone.isNotBlank() && normalizedPhone == prevPhone
        val hasNext = normalizedPhone.isNotBlank() && normalizedPhone == nextPhone
        val inGroup = hasPrev || hasNext

        // Count the group size for the "X/N" badge
        val attemptSuffix = "  ·  A${item.attemptCount}"
        if (inGroup) {
            var groupStart = position
            while (groupStart > 0 && getItem(groupStart - 1).phone.filter { it.isDigit() }.takeLast(10) == normalizedPhone) groupStart--
            var groupEnd = position
            while (groupEnd < currentList.size - 1 && getItem(groupEnd + 1).phone.filter { it.isDigit() }.takeLast(10) == normalizedPhone) groupEnd++
            val groupSize = groupEnd - groupStart + 1
            val groupPos  = position - groupStart + 1
            holder.tvAge.text = "${formatAgeCompact(item.createdAt)}  $groupPos/$groupSize$attemptSuffix"
        } else {
            holder.tvAge.text = "${formatAgeCompact(item.createdAt)}$attemptSuffix"
        }
        val (ageColor, ageBold) = ageColorFor(item.createdAt)
        holder.tvAge.setTextColor(ageColor)
        holder.tvAge.setTypeface(null, if (ageBold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        holder.tvAge.textSize = if (ageBold) 11f else 10f

        // Stripe: show on all cards in a group, tighten margin between grouped cards
        holder.viewGroupStripe.visibility = if (inGroup) View.VISIBLE else View.GONE
        holder.viewGroupStripe.setBackgroundColor(GROUP_STRIPE_COLOR)
        val rootParams = holder.itemView.layoutParams as? android.view.ViewGroup.MarginLayoutParams
        rootParams?.bottomMargin = if (hasNext) 4 else 10   // tight spacing within group
        holder.itemView.layoutParams = rootParams

        holder.tvCustomer.text = item.customer

        holder.tvMeta.text = buildString {
            append(item.id); append(" · "); append(item.phone); append(" · "); append(item.time)
        }

        holder.tvAddress.text = "\uD83D\uDCCD ${item.address}"
        holder.tvCod.text = "৳${item.cod}"

        val cfg = getStatusConfig(ctx, item.status, statusLang)
        holder.tvStatusBadge.text = cfg.label
        holder.tvStatusBadge.setTextColor(cfg.color)
        holder.tvStatusBadge.setBackgroundColor(cfg.bg)

        holder.tvValidationBadge.visibility = if (item.validationRequest) View.VISIBLE else View.GONE

        // Agent remarks
        // Don't show remarks text if it's the same as the parcel status badge above —
        // that would just show the same thing twice (e.g. both "delivered").
        if (item.remarks.isNotBlank() && item.remarkStatus != item.status) {
            holder.tvRemarks.text = "\uD83D\uDCAC ${item.remarks}"
            holder.tvRemarks.visibility = View.VISIBLE
        } else {
            holder.tvRemarks.visibility = View.GONE
        }

        // Validation note
        if (item.validationNote.isNotBlank()) {
            holder.tvValidationNote.text = "⚡ ${item.validationNote}"
            holder.tvValidationNote.visibility = View.VISIBLE
        } else {
            holder.tvValidationNote.visibility = View.GONE
        }

        // CC Remark block (highlighted)
        if (item.ccRemark.isNotBlank()) {
            holder.tvCcRemarkBlock.visibility = View.VISIBLE
            holder.tvCcRemarkLabel.text = "CC · ${item.ccRemarkAuthor} · ${item.ccRemarkTime}"
            holder.tvCcRemarkText.text = item.ccRemark
        } else {
            holder.tvCcRemarkBlock.visibility = View.GONE
        }

        // Expanded actions
        holder.layoutActions.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.btnCall.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.btnSetRemarks.visibility = if (isExpanded) View.VISIBLE else View.GONE

        holder.itemView.setOnClickListener {
            val previousId = expandedItemId
            val previousPos = previousExpandedPosition

            if (isExpanded) {
                // Collapse current
                expandedItemId = null
                previousExpandedPosition = null
                notifyItemChanged(position)
            } else {
                // Expand new, collapse previous
                expandedItemId = item.id
                previousExpandedPosition = position
                notifyItemChanged(position)
                // Collapse previously expanded item (if any)
                if (previousId != null && previousPos != null && previousPos != position) {
                    notifyItemChanged(previousPos)
                }
            }
        }
        holder.itemView.setOnLongClickListener { onLongPress(item); true }

        holder.btnCall.setOnClickListener { onCall(item) }
        holder.btnSetRemarks.setOnClickListener { onSetRemarks(item) }
    }

    companion object {
        data class StatusConfig(val color: Int, val bg: Int, val label: String)

        /**
         * Compact age badge — shows how old a parcel is:
         *   Xm  = less than 1 hour (minutes)
         *   Xh  = less than 1 day (hours)
         *   XD  = 1 or more days (minimum 1D — never 0D)
         * If createdAt is missing/zero (field not synced yet), returns "1D" as a safe fallback.
         */
        fun formatAgeCompact(createdAt: Long): String {
            if (createdAt <= 0L) return "1D"
            val diffMs = (System.currentTimeMillis() - createdAt).coerceAtLeast(0L)
            val minutes = diffMs / (60 * 1000)
            val hours   = diffMs / (60 * 60 * 1000)
            val days    = diffMs / (24 * 60 * 60 * 1000)
            return when {
                minutes < 60  -> "${minutes}m"
                hours   < 24  -> "${hours}h"
                else          -> "${days.coerceAtLeast(1)}D"
            }
        }

        /**
         * Age-based color for the corner badge and journey-log "Age" summary line.
         *   < 2 days (0–48h)  -> grey
         *   2 days (48–72h)   -> yellow
         *   3 days (72–96h)   -> red
         *   4+ days (96h+)    -> red, bold
         * Returns (colorInt, isBold).
         */
        /**
         * Sorts parcels so that:
         *  1. Same-phone groups stay adjacent (never split apart by the sort)
         *  2. Groups are ordered by their OLDEST parcel's age — the group containing the
         *     most-aged parcel comes first
         *  3. Within a group, parcels are ordered oldest-first too
         *
         * Example: phone A has a 10-day-old parcel and a 1-day-old parcel; phone B has a
         * single 5-day-old parcel. Result: [A-10day, A-1day, B-5day] — A's group leads
         * because its oldest member (10 days) is older than B's (5 days), and within A the
         * 10-day parcel still comes before the 1-day one.
         */
        fun sortByGroupAge(parcels: List<WorkerParcelItem>): List<WorkerParcelItem> {
            fun effectiveAge(p: WorkerParcelItem): Long = if (p.createdAt <= 0L) Long.MAX_VALUE else p.createdAt
            val groups = parcels.groupBy { p -> p.phone.filter { c -> c.isDigit() }.takeLast(10) }
            return groups.values
                .sortedBy { group -> group.minOf { p -> effectiveAge(p) } }
                .flatMap { group -> group.sortedBy { p -> effectiveAge(p) } }
        }

        /**
         * Same grouping guarantee as sortByGroupAge (same-phone parcels stay adjacent),
         * but ordered by attempt count descending — the group containing the
         * most-attempted parcel comes first, since repeated failed attempts need the
         * most urgent resolution. Ties within/across groups fall back to oldest-first.
         */
        fun sortByAttempt(parcels: List<WorkerParcelItem>): List<WorkerParcelItem> {
            fun effectiveAge(p: WorkerParcelItem): Long = if (p.createdAt <= 0L) Long.MAX_VALUE else p.createdAt
            val groups = parcels.groupBy { p -> p.phone.filter { c -> c.isDigit() }.takeLast(10) }
            return groups.values
                .sortedWith(
                    compareByDescending<List<WorkerParcelItem>> { group -> group.maxOf { p -> p.attemptCount } }
                        .thenBy { group -> group.minOf { p -> effectiveAge(p) } }
                )
                .flatMap { group ->
                    group.sortedWith(compareByDescending<WorkerParcelItem> { it.attemptCount }.thenBy { effectiveAge(it) })
                }
        }

        fun ageColorFor(createdAt: Long): Pair<Int, Boolean> {
            val grey   = android.graphics.Color.parseColor("#6B7280")
            val yellow = android.graphics.Color.parseColor("#F59E0B")
            val red    = android.graphics.Color.parseColor("#EF4444")
            if (createdAt <= 0L) return grey to false
            val diffMs = (System.currentTimeMillis() - createdAt).coerceAtLeast(0L)
            val days = diffMs / (24 * 60 * 60 * 1000)
            return when {
                days >= 4 -> red to true
                days >= 3 -> red to false
                days >= 2 -> yellow to false
                else      -> grey to false
            }
        }

        fun getStatusConfig(context: android.content.Context, status: String, statusLang: String = "bn"): StatusConfig {
            StatusMetaCache.entries[status]?.let { entry ->
                val label = if (statusLang == "en") entry.en else entry.bn
                return StatusConfig(entry.color, entry.bg, label.ifBlank { status })
            }
            // Cache miss — StatusMetaCache not yet loaded or this status not in config.
            // Show raw status key in neutral gray so nothing is silently hidden.
            val neutral   = android.graphics.Color.parseColor("#6B7280")
            val neutralBg = android.graphics.Color.parseColor("#F3F4F6")
            return StatusConfig(neutral, neutralBg, status)
        }
    }

    class Diff : DiffUtil.ItemCallback<WorkerParcelItem>() {
        override fun areItemsTheSame(a: WorkerParcelItem, b: WorkerParcelItem) = a.id == b.id
        override fun areContentsTheSame(a: WorkerParcelItem, b: WorkerParcelItem) = a == b
    }
}
