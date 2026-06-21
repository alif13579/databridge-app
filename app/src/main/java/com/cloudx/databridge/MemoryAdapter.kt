package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryAdapter(
    private val items: MutableList<MemoryEntry>,
    private val onDelete: (MemoryEntry) -> Unit,
    private val onEdit: (MemoryEntry) -> Unit,
    private val onView: (MemoryEntry) -> Unit
) : RecyclerView.Adapter<MemoryAdapter.MemViewHolder>() {

    private val fmt = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())

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
        holder.btnDelete.setOnClickListener { onDelete(item) }
        holder.btnEdit.setOnClickListener { onEdit(item) }
        holder.itemView.setOnClickListener { onView(item) }
    }

    fun refresh() { notifyDataSetChanged() }

    class MemViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate: TextView = v.findViewById(R.id.tvMemoryDate)
        val tvCounts: TextView = v.findViewById(R.id.tvMemoryCounts)
        val tvEarnings: TextView = v.findViewById(R.id.tvMemoryEarnings)
        val btnEdit: ImageButton = v.findViewById(R.id.btnEditMemory)
        val btnDelete: ImageButton = v.findViewById(R.id.btnDeleteMemory)
    }
}
