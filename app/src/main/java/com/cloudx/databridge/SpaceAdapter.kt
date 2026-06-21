package com.cloudx.databridge

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpaceAdapter : ListAdapter<SpaceConsignmentItem, SpaceAdapter.Holder>(Diff()) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val tvConsignment: TextView = view.findViewById(R.id.tvSpaceConsignmentId)
        val tvPhone: TextView = view.findViewById(R.id.tvSpacePhone)
        val tvStatus: TextView = view.findViewById(R.id.tvSpaceStatus)
        val tvRun: TextView = view.findViewById(R.id.tvSpaceRunId)
        val tvValidity: TextView = view.findViewById(R.id.tvSpaceValidity)
        val tvRemarks: TextView = view.findViewById(R.id.tvSpaceRemarks)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_space_card, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.tvConsignment.text = item.consignmentId
        holder.tvPhone.text = item.phone
        holder.tvStatus.text = item.status.ifBlank { "—" }
        val runAt = IdUtils.parseRunTimestampMs(item.runId)?.takeIf { it > 0L } ?: item.runCreatedAt
        val runTime = if (runAt > 0L) {
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(runAt))
        } else {
            item.runId
        }
        val runLabel = item.runStatus.ifBlank { "open" }
        holder.tvRun.text = "Run $runTime ($runLabel)"

        if (item.isValid) {
            holder.tvValidity.text = "Valid"
            holder.tvValidity.setTextColor(Color.parseColor("#22c55e"))
        } else {
            holder.tvValidity.text = "Invalid"
            holder.tvValidity.setTextColor(Color.parseColor("#ef4444"))
        }

        val latest = item.numberEntries.firstOrNull()
        holder.tvRemarks.text = when {
            latest == null -> "No remarks in numbers"
            latest.remarks.isBlank() -> "Remark entry (empty text)"
            else -> {
                val time = if (latest.timestamp > 0L) {
                    SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(latest.timestamp))
                } else ""
                if (time.isNotBlank()) "\"${latest.remarks}\" · $time" else "\"${latest.remarks}\""
            }
        }
    }

    class Diff : DiffUtil.ItemCallback<SpaceConsignmentItem>() {
        override fun areItemsTheSame(a: SpaceConsignmentItem, b: SpaceConsignmentItem) =
            a.runId == b.runId && a.consignmentId == b.consignmentId

        override fun areContentsTheSame(a: SpaceConsignmentItem, b: SpaceConsignmentItem) = a == b
    }
}
