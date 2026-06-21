package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class SalarySlabAdapter(
    private val slabs: MutableList<SalarySlab>,
    private val onRemove: (Int) -> Unit
) : RecyclerView.Adapter<SalarySlabAdapter.SlabViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SlabViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_slab_row, parent, false)
        return SlabViewHolder(v)
    }

    override fun getItemCount(): Int = slabs.size

    override fun onBindViewHolder(holder: SlabViewHolder, position: Int) {
        val slab = slabs[position]
        val maxText = if (slab.max <= 0) "+" else "-${slab.max}"
        holder.tvRange.text = "${slab.min}$maxText"
        holder.tvRate.text = "৳${slab.rate}".replace(".0", "")
        holder.btnRemove.setOnClickListener { onRemove(position) }
    }

    fun refresh() {
        notifyDataSetChanged()
    }

    class SlabViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvRange: TextView = itemView.findViewById(R.id.tvSlabRange)
        val tvRate: TextView = itemView.findViewById(R.id.tvSlabRate)
        val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemoveSlab)
    }
}
