package com.cloudx.databridge

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
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
    val history: List<HistoryEntry> = emptyList()
)

data class HistoryEntry(
    val action: String,
    val remark: String,
    val time: String,
    val author: String,
    val authorRole: String
)

class WorkerParcelAdapter(
    private val onCall: (WorkerParcelItem) -> Unit,
    private val onSetRemarks: (WorkerParcelItem) -> Unit,
    private val onViewLog: (WorkerParcelItem) -> Unit
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
        val tvStatusBadge: TextView = view.findViewById(R.id.tvParcelStatusBadge)
        val tvRemarks: TextView = view.findViewById(R.id.tvParcelRemarks)
        val tvValidationNote: TextView = view.findViewById(R.id.tvParcelValidationNote)
        val tvCcRemarkBlock: View = view.findViewById(R.id.layoutCcRemarkBlock)
        val tvCcRemarkLabel: TextView = view.findViewById(R.id.tvCcRemarkLabel)
        val tvCcRemarkText: TextView = view.findViewById(R.id.tvCcRemarkText)
        val layoutActions: View = view.findViewById(R.id.layoutParcelActions)
        val btnCall: View = view.findViewById(R.id.btnParcelCall)
        val btnSetRemarks: View = view.findViewById(R.id.btnParcelSetRemarks)
        val btnViewLog: View = view.findViewById(R.id.btnParcelViewLog)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_parcel_card, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val isExpanded = item.id == expandedItemId
        val ctx = holder.itemView.context

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
        holder.btnViewLog.visibility = if (isExpanded) View.VISIBLE else View.GONE

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

        holder.btnCall.setOnClickListener { onCall(item) }
        holder.btnSetRemarks.setOnClickListener { onSetRemarks(item) }
        holder.btnViewLog.setOnClickListener { onViewLog(item) }
    }

    companion object {
        data class StatusConfig(val color: Int, val bg: Int, val label: String)

        fun getStatusConfig(context: android.content.Context, status: String, statusLang: String = "bn"): StatusConfig {
            StatusMetaCache.entries[status]?.let { entry ->
                val label = if (statusLang == "en") entry.en else entry.bn
                return StatusConfig(entry.color, entry.bg, label.ifBlank { status })
            }
            return getHardcodedStatusConfig(context, status)
        }

        private fun getHardcodedStatusConfig(context: android.content.Context, status: String): StatusConfig = when (status) {
            "confirmed" -> StatusConfig(
                ContextCompat.getColor(context, R.color.theme_green),
                ContextCompat.getColor(context, R.color.theme_bg_green),
                "✓ Confirmed"
            )
            "rejected" -> StatusConfig(
                ContextCompat.getColor(context, R.color.theme_red),
                ContextCompat.getColor(context, R.color.theme_bg_red),
                "✗ Rejected"
            )
            "pending" -> StatusConfig(
                ContextCompat.getColor(context, R.color.theme_yellow),
                ContextCompat.getColor(context, R.color.theme_bg_yellow),
                "◌ Pending"
            )
            "delivered" -> StatusConfig(
                ContextCompat.getColor(context, R.color.theme_green),
                ContextCompat.getColor(context, R.color.theme_bg_green),
                "✓ Delivered"
            )
            "delivery_req" -> StatusConfig(
                ContextCompat.getColor(context, R.color.theme_accent),
                ContextCompat.getColor(context, R.color.theme_bg_accent),
                "🚚 Delivery Request"
            )
            "hold_req" -> StatusConfig(
                ContextCompat.getColor(context, R.color.theme_orange),
                ContextCompat.getColor(context, R.color.theme_bg_orange),
                "⏸ Hold Request"
            )
            "return_req" -> StatusConfig(
                ContextCompat.getColor(context, R.color.theme_red),
                ContextCompat.getColor(context, R.color.theme_bg_red),
                "↩ Return Request"
            )
            "verify_req" -> StatusConfig(
                ContextCompat.getColor(context, R.color.theme_purple),
                ContextCompat.getColor(context, R.color.theme_bg_validation),
                "⚡ Verify Request"
            )
            else -> StatusConfig(
                ContextCompat.getColor(context, R.color.theme_text_secondary),
                ContextCompat.getColor(context, R.color.theme_bg_inner),
                status
            )
        }
    }

    class Diff : DiffUtil.ItemCallback<WorkerParcelItem>() {
        override fun areItemsTheSame(a: WorkerParcelItem, b: WorkerParcelItem) = a.id == b.id
        override fun areContentsTheSame(a: WorkerParcelItem, b: WorkerParcelItem) = a == b
    }
}
