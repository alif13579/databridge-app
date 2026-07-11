package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryAdapter(
    private val items: MutableList<MemoryEntry>,
    private val onDelete: (MemoryEntry) -> Unit,
    private val onEdit: (MemoryEntry) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.MemViewHolder>() {

    private val fmt = SimpleDateFormat("dd-MMM-yy, EEE h:mm a", Locale.getDefault())

    // Tracks which entries are currently expanded, by MemoryEntry.id — survives scroll/
    // recycling since it's keyed by stable id, not adapter position.
    private val expandedIds = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_memory_row, parent, false)
        return MemViewHolder(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: MemViewHolder, position: Int) {
        val item = items[position]
        holder.tvDate.text = fmt.format(Date(item.createdAt))
        val pAssign = if (item.parcelAssigned > 0) " / ${item.parcelAssigned}" else ""
        val dAssign = if (item.documentAssigned > 0) " / ${item.documentAssigned}" else ""
        val pRate = if (item.parcelAssigned > 0) {
            val r = (item.parcelDelivery.toDouble() / item.parcelAssigned).coerceAtMost(1.0)
            " (${(r * 100).toInt()}%)"
        } else ""
        val dRate = if (item.documentAssigned > 0) {
            val r = (item.documentDelivery.toDouble() / item.documentAssigned).coerceAtMost(1.0)
            " (${(r * 100).toInt()}%)"
        } else ""
        val ppAssign = if (item.parcelPickupAssigned > 0) " / ${item.parcelPickupAssigned}" else ""
        val dpAssign = if (item.documentPickupAssigned > 0) " / ${item.documentPickupAssigned}" else ""
        val ppRate = if (item.parcelPickupAssigned > 0) {
            val r = (item.parcelPickup.toDouble() / item.parcelPickupAssigned).coerceAtMost(1.0)
            " (${(r * 100).toInt()}%)"
        } else ""
        val dpRate = if (item.documentPickupAssigned > 0) {
            val r = (item.documentPickup.toDouble() / item.documentPickupAssigned).coerceAtMost(1.0)
            " (${(r * 100).toInt()}%)"
        } else ""
        holder.tvCounts.text = "Parcel: ${item.parcelDelivery}$pAssign$pRate, Doc: ${item.documentDelivery}$dAssign$dRate\nPickup: ${item.parcelPickup}$ppAssign$ppRate, Doc: ${item.documentPickup}$dpAssign$dpRate"
        holder.tvEarnings.text = "৳${(item.parcelCommission + item.documentCommission + item.parcelPickupCommission + item.documentPickupCommission).toInt()}"

        val isExpanded = item.id in expandedIds
        holder.layoutDetail.visibility = if (isExpanded) View.VISIBLE else View.GONE
        holder.ivArrow.rotation = if (isExpanded) 180f else 0f
        if (isExpanded) {
            holder.tvDetail.text = buildDetailText(item)
        }

        val toggle = {
            if (isExpanded) expandedIds.remove(item.id) else expandedIds.add(item.id)
            notifyItemChanged(holder.bindingAdapterPosition)
        }
        holder.ivArrow.setOnClickListener { toggle() }
        holder.itemView.setOnClickListener { toggle() }
        holder.btnDelete.setOnClickListener { onDelete(item) }
        holder.btnEdit.setOnClickListener { onEdit(item) }
    }

    private fun buildDetailText(entry: MemoryEntry): String {
        val total = entry.parcelCommission + entry.documentCommission + entry.parcelPickupCommission + entry.documentPickupCommission
        return buildString {
            if (entry.model.isNotBlank()) append("Model: ${entry.model}\n")
            append("Delivery — Parcel: ${entry.parcelDelivery}/${entry.parcelAssigned} (${(entry.parcelSuccessRate * 100).toInt()}%)\n")
            append("Delivery — Doc: ${entry.documentDelivery}/${entry.documentAssigned} (${(entry.documentSuccessRate * 100).toInt()}%)\n")
            append("Pickup — Parcel: ${entry.parcelPickup}/${entry.parcelPickupAssigned} (${(entry.parcelPickupSuccessRate * 100).toInt()}%)\n")
            append("Pickup — Doc: ${entry.documentPickup}/${entry.documentPickupAssigned} (${(entry.documentPickupSuccessRate * 100).toInt()}%)\n\n")
            append("Earnings\n")
            append("Delivery: ৳${(entry.parcelCommission + entry.documentCommission).toInt()}\n")
            append("Pickup: ৳${(entry.parcelPickupCommission + entry.documentPickupCommission).toInt()}\n")
            append("Total: ৳${total.toInt()}")
        }
    }

    fun refresh() { notifyDataSetChanged() }

    class MemViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate: TextView = v.findViewById(R.id.tvMemoryDate)
        val tvCounts: TextView = v.findViewById(R.id.tvMemoryCounts)
        val tvEarnings: TextView = v.findViewById(R.id.tvMemoryEarnings)
        val btnEdit: ImageButton = v.findViewById(R.id.btnEditMemory)
        val btnDelete: ImageButton = v.findViewById(R.id.btnDeleteMemory)
        val ivArrow: ImageView = v.findViewById(R.id.ivMemoryArrow)
        val layoutDetail: View = v.findViewById(R.id.layoutMemoryDetail)
        val tvDetail: TextView = v.findViewById(R.id.tvMemoryDetailText)
    }
}
