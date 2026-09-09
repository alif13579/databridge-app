package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Incharge queue row: checkbox (bulk) + per-scan approve/reject. */
class ScanQueueAdapter(
    private val onApprove: (QueuedScan) -> Unit,
    private val onReject: (QueuedScan) -> Unit,
    private val onSelectionChanged: (Int) -> Unit
) : RecyclerView.Adapter<ScanQueueAdapter.Holder>() {

    private val timeFmt = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    var items: List<QueuedScan> = emptyList()
        set(value) {
            field = value
            selected.clear()
            notifyDataSetChanged()
            onSelectionChanged(0)
        }

    private val selected = mutableSetOf<String>()

    fun selectedScans(): List<QueuedScan> = items.filter { key(it) in selected }

    fun selectAll() {
        selected.clear()
        items.forEach { selected.add(key(it)) }
        notifyDataSetChanged()
        onSelectionChanged(selected.size)
    }

    fun clearSelection() {
        selected.clear()
        notifyDataSetChanged()
        onSelectionChanged(0)
    }

    private fun key(s: QueuedScan) = "${s.ownerUid}/${s.firebaseKey}"

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_scan_queue_row, parent, false)
        return Holder(v)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val scan = items[position]
        val k = key(scan)
        holder.tvCode.text = scan.code
        val who = scan.agentName.ifBlank { scan.employeeId.ifBlank { "Agent" } }
        val emp = if (scan.employeeId.isNotBlank()) " (${scan.employeeId})" else ""
        val mode = if (scan.manual) " • Manual" else " • Scanned"
        holder.tvMeta.text = "$who$emp • ${timeFmt.format(Date(scan.scanAt))}$mode"
        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.isChecked = k in selected
        holder.cb.setOnCheckedChangeListener { _, checked ->
            if (checked) selected.add(k) else selected.remove(k)
            onSelectionChanged(selected.size)
        }
        val isPending = scan.status.equals("pending", ignoreCase = true)
        holder.btnApprove.visibility = if (isPending) View.VISIBLE else View.GONE
        holder.btnReject.visibility = if (isPending) View.VISIBLE else View.GONE
        holder.tvState.visibility = if (isPending) View.GONE else View.VISIBLE
        if (!isPending) {
            holder.tvState.text = if (scan.status.equals("approved", ignoreCase = true)) "✓ Approved" else "✕ Rejected"
        }
        holder.btnApprove.setOnClickListener { onApprove(scan) }
        holder.btnReject.setOnClickListener { onReject(scan) }
    }

    class Holder(v: View) : RecyclerView.ViewHolder(v) {
        val cb: CheckBox = v.findViewById(R.id.cbScanQueueSelect)
        val tvCode: TextView = v.findViewById(R.id.tvScanQueueCode)
        val tvMeta: TextView = v.findViewById(R.id.tvScanQueueMeta)
        val btnApprove: Button = v.findViewById(R.id.btnScanQueueApprove)
        val btnReject: Button = v.findViewById(R.id.btnScanQueueReject)
        val tvState: TextView = v.findViewById(R.id.tvScanQueueState)
    }
}
