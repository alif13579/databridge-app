package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
class HistoryAdapter(private val callback: HistoryCallback) :
    ListAdapter<HistoryItem, RecyclerView.ViewHolder>(DiffCallback()) {

    private val expandedIds = mutableSetOf<String>()
    var showActionsGlobal = false

    fun setGlobalShowActions(show: Boolean) {
        showActionsGlobal = show
        if (!show) expandedIds.clear()
        notifyDataSetChanged()
    }

    interface HistoryCallback {
        fun onCopy(text: String, record: CallRecord)
        fun onDial(cleaned: String, record: CallRecord)
        fun onRemark(record: CallRecord)
        fun onDelete(record: CallRecord)
        fun onShowActions(record: CallRecord)
    }

    companion object {
        const val TYPE_DIVIDER = 0
        const val TYPE_RECORD = 1
    }

    class DividerHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvDate: TextView = v.findViewById(R.id.tvDate)
    }

    class RecordHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvData: TextView = v.findViewById(R.id.tvData)
        val tvType: TextView = v.findViewById(R.id.tvType)
        val tvMeta: TextView = v.findViewById(R.id.tvMeta)
        val ivCopy: ImageView = v.findViewById(R.id.ivCopy)
        val ivDial: ImageView = v.findViewById(R.id.ivDial)
        val ivRemark: ImageView = v.findViewById(R.id.ivRemark)
        val ivDelete: ImageView = v.findViewById(R.id.ivDelete)
        val ivExpand: ImageView = v.findViewById(R.id.ivExpand)
        val layoutActionLog: LinearLayout = v.findViewById(R.id.layoutActionLog)
        val tvActionLog: TextView = v.findViewById(R.id.tvActionLog)
        val btnShowAllActions: TextView? = v.findViewById(R.id.btnShowAllActions)
    }

    override fun getItemViewType(position: Int): Int = when(getItem(position)) {
        is HistoryItem.DateDivider -> TYPE_DIVIDER
        is HistoryItem.RecordItem -> TYPE_RECORD
    }

    override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
        return if (type == TYPE_DIVIDER) DividerHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_date_divider, parent, false))
        else RecordHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_history_card, parent, false))
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when(holder) {
            is DividerHolder -> holder.tvDate.text = (getItem(position) as HistoryItem.DateDivider).date
            is RecordHolder -> bindRecord(holder, (getItem(position) as HistoryItem.RecordItem).record)
        }
    }

    private fun bindRecord(h: RecordHolder, r: CallRecord) {
        h.tvData.text = r.text
        h.tvType.text = r.type.replaceFirstChar { it.uppercase() }
        h.tvMeta.text = SimpleDateFormat("h:mm a · d MMM, yyyy", Locale.getDefault()).format(Date(r.received_at))
        h.ivDial.visibility = if (r.type == "phone") View.VISIBLE else View.GONE

        val hasActions = parseAndDisplayActions(h, r)

        val isExpanded = hasActions && (showActionsGlobal || r.id in expandedIds)
        h.layoutActionLog.visibility = if (isExpanded) View.VISIBLE else View.GONE
        h.ivExpand.visibility = if (hasActions) View.VISIBLE else View.INVISIBLE
        h.ivExpand.rotation = if (isExpanded) 180f else 0f

        h.ivExpand.setOnClickListener {
            if (!hasActions || showActionsGlobal) return@setOnClickListener
            if (r.id in expandedIds) expandedIds.remove(r.id) else expandedIds.add(r.id)
            val nowExpanded = r.id in expandedIds
            h.layoutActionLog.visibility = if (nowExpanded) View.VISIBLE else View.GONE
            h.ivExpand.rotation = if (nowExpanded) 180f else 0f
        }

        h.btnShowAllActions?.setOnClickListener { callback.onShowActions(r) }
        h.ivCopy.setOnClickListener { callback.onCopy(r.text, r) }
        h.ivDial.setOnClickListener { callback.onDial(r.cleaned, r) }
        h.ivRemark.setOnClickListener { callback.onRemark(r) }
        h.ivDelete.setOnClickListener { callback.onDelete(r) }
    }

    private fun parseAndDisplayActions(h: RecordHolder, record: CallRecord): Boolean {
        try {
            val json = record.actions.trim()
            if (json.isBlank() || json == "{}" || json == "[]") {
                h.tvActionLog.text = "No actions yet"; h.btnShowAllActions?.visibility = View.GONE; return false
            }
            val items = ActionsJson.parseActionItems(json)
            if (items.isEmpty()) { h.tvActionLog.text = "No actions yet"; h.btnShowAllActions?.visibility = View.GONE; return false }

            val lines = mutableListOf<String>()
            var c = false; var d = false; var r = false
            items.take(3).forEach { item ->
                val tm = if (item.timestamp > 0) SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(item.timestamp)) else ""

                when (item.type) {
                    "copy" -> { c = true; lines.add("📋 Copied $tm") }
                    "dial" -> { d = true; lines.add("📞 Dialed $tm") }
                    "remark" -> {
                        r = true
                        val n = if (item.remarks.isNotBlank()) " \"${item.remarks.take(20)}${if (item.remarks.length > 20) "..." else ""}\"" else ""
                        lines.add("📝 Remark$n $tm")
                    }
                }
            }

            h.tvActionLog.text = lines.joinToString("\n")
            if (items.size > 3) {
                h.btnShowAllActions?.apply { visibility = View.VISIBLE; text = "View all ${items.size} actions" }
            } else h.btnShowAllActions?.visibility = View.GONE

            if (c) h.ivCopy.setColorFilter(ContextCompat.getColor(h.itemView.context, android.R.color.holo_blue_light)) else h.ivCopy.clearColorFilter()
            if (d) h.ivDial.setColorFilter(ContextCompat.getColor(h.itemView.context, android.R.color.holo_green_light)) else h.ivDial.clearColorFilter()
            if (r) h.ivRemark.setColorFilter(ContextCompat.getColor(h.itemView.context, android.R.color.holo_orange_light)) else h.ivRemark.clearColorFilter()
            return lines.isNotEmpty()
        } catch (_: Exception) {
            h.tvActionLog.text = "Error loading actions"
            h.btnShowAllActions?.visibility = View.GONE
            return false
        }
    }

    class DiffCallback : DiffUtil.ItemCallback<HistoryItem>() {
        override fun areItemsTheSame(o: HistoryItem, n: HistoryItem) = when {
            o is HistoryItem.DateDivider && n is HistoryItem.DateDivider -> o.date == n.date
            o is HistoryItem.RecordItem && n is HistoryItem.RecordItem -> o.record.id == n.record.id
            else -> false
        }
        override fun areContentsTheSame(o: HistoryItem, n: HistoryItem) = o == n
    }
}