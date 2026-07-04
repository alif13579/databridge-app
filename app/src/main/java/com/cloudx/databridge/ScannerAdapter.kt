package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Adapter that supports two view types:
 * - TYPE_DATE_DIVIDER: Date separator header
 * - TYPE_ITEM: Scan item row
 */
class ScannerAdapter(
    private val onDelete: (ScanItem) -> Unit,
    private val onEdit: (ScanItem) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_DATE_DIVIDER = 0
        private const val TYPE_ITEM = 1
        private val TIME_FMT = SimpleDateFormat("h:mm a", Locale.getDefault())
        private val DATE_FMT = SimpleDateFormat("d MMM yyyy, EEEE", Locale.getDefault())
    }

    sealed class ListItem {
        data class DateDivider(val dateLabel: String, val timestamp: Long) : ListItem()
        data class ScanRow(val item: ScanItem) : ListItem()
    }

    var items: List<ScanItem> = emptyList()
        set(value) {
            field = value
            flattenedItems = buildFlattenedList(value)
            notifyDataSetChanged()
        }

    var flattenedItems: List<ListItem> = emptyList()
        private set

    private fun buildFlattenedList(scans: List<ScanItem>): List<ListItem> {
        if (scans.isEmpty()) return emptyList()

        val result = mutableListOf<ListItem>()
        val sorted = scans.sortedByDescending { it.scanAt }

        var prevDate: String? = null
        for (item in sorted) {
            val dateLabel = getDateLabel(item.scanAt)
            if (dateLabel != prevDate) {
                val label = getRelativeDateLabel(item.scanAt, dateLabel)
                result.add(ListItem.DateDivider(label, item.scanAt))
                prevDate = dateLabel
            }
            result.add(ListItem.ScanRow(item))
        }

        return result
    }

    private fun getDateLabel(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ts))
    }

    private fun getRelativeDateLabel(ts: Long, dateLabel: String): String {
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        val yesterday = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis() - 86400000))

        val formatted = DATE_FMT.format(Date(ts))
        return when (dateLabel) {
            today -> "Today, $formatted"
            yesterday -> "Yesterday, $formatted"
            else -> formatted
        }
    }

    override fun getItemViewType(position: Int): Int {
        return when (flattenedItems.getOrNull(position)) {
            is ListItem.DateDivider -> TYPE_DATE_DIVIDER
            else -> TYPE_ITEM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_DATE_DIVIDER -> {
                val view = inflater.inflate(R.layout.item_date_divider, parent, false)
                DateDividerHolder(view)
            }
            else -> {
                val view = inflater.inflate(R.layout.item_scan_row, parent, false)
                ScanHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is DateDividerHolder -> {
                val divider = flattenedItems[position] as ListItem.DateDivider
                holder.tvDate.text = divider.dateLabel
            }
            is ScanHolder -> {
                val row = flattenedItems[position] as ListItem.ScanRow
                val item = row.item
                holder.tvIcon.text = if (item.manual) "✏️" else "📷"
                holder.tvCode.text = item.code
                holder.tvMeta.text = "${TIME_FMT.format(Date(item.scanAt))} • ${if (item.manual) "Manual" else "Scanned"}"
                holder.btnMenu.setOnClickListener { anchor ->
                    val popup = android.widget.PopupMenu(anchor.context, anchor)
                    popup.menu.add(0, 1, 0, "Edit")
                    popup.menu.add(0, 2, 1, "Delete")
                    popup.setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            1 -> { onEdit(item); true }
                            2 -> { onDelete(item); true }
                            else -> false
                        }
                    }
                    popup.show()
                }
                holder.itemView.alpha = 1.0f
            }
        }
    }

    override fun getItemCount(): Int = flattenedItems.size

    class ScanHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIcon: TextView = view.findViewById(R.id.tvScanIcon)
        val tvCode: TextView = view.findViewById(R.id.tvScanCode)
        val tvMeta: TextView = view.findViewById(R.id.tvScanMeta)
        val btnMenu: View = view.findViewById(R.id.btnScanMenu)
    }

    class DateDividerHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView = view.findViewById(R.id.tvDate)
    }
}
