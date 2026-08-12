package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

/**
 * One parcel card in Virtual Routing.
 *
 * [areaGroup] is a stable key picking which area list the Select Area bottom
 * sheet shows for this card (see DemoAreaCatalog) — it does NOT change when
 * the agent re-picks a different sub-area, only [selectedArea] does.
 *
 * All of this is DEMO DATA — see VirtualRoutingFragment's class doc for what
 * wiring to real Firebase data will need to change.
 */
data class VirtualRoutingParcel(
    val consignmentId: String,
    val customerName: String,
    val destination: String,
    val currentStatus: String, // "In Transit" | "On Hold" | ... — demo values only for now
    val areaGroup: String,
    val selectedArea: String
)

/**
 * Demo-only area catalog for the Select Area bottom sheet, grouped by each
 * card's [VirtualRoutingParcel.areaGroup]. Once wired, this comes from
 * Firebase (branch/area config) instead of a hardcoded map.
 */
object DemoAreaCatalog {
    val areasByGroup: Map<String, List<String>> = mapOf(
        "Dhanmondi" to listOf(
            "Dhanmondi", "Dhanmondi 15", "Dhanmondi 27", "Dhanmondi 32",
            "Dhanmondi Lake Area", "Dhanmondi R/A"
        ),
        "Uttara Sector 7" to listOf(
            "Uttara Sector 1", "Uttara Sector 3", "Uttara Sector 4",
            "Uttara Sector 7", "Uttara Sector 10", "Uttara Sector 13"
        ),
        "Gulshan 1" to listOf(
            "Gulshan 1", "Gulshan 2", "Gulshan Avenue",
            "Gulshan Circle 1", "Gulshan Circle 2"
        ),
        "Rampura" to listOf(
            "Rampura", "Banasree", "Aftab Nagar", "East Rampura", "DIT Project"
        ),
        "Banani" to listOf(
            "Banani", "Banani DOHS", "Banani Chairman Bari", "Banani Road 11"
        ),
        "Mohammadpur" to listOf(
            "Mohammadpur", "Mohammadpur Town Hall", "Shyamoli", "Adabor", "Ring Road"
        )
    )
}

class VirtualRoutingAdapter(
    private val onAreaDropdownClick: (VirtualRoutingParcel) -> Unit
) : ListAdapter<VirtualRoutingParcel, VirtualRoutingAdapter.Holder>(Diff()) {

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val tvConsignmentId: TextView = view.findViewById(R.id.tvVrcConsignmentId)
        val layoutAreaDropdown: LinearLayout = view.findViewById(R.id.layoutVrcAreaDropdown)
        val tvAreaChip: TextView = view.findViewById(R.id.tvVrcAreaChip)
        val tvCustomer: TextView = view.findViewById(R.id.tvVrcCustomer)
        val tvStatusBadge: TextView = view.findViewById(R.id.tvVrcStatusBadge)
        val tvDestination: TextView = view.findViewById(R.id.tvVrcDestination)
        val tvSelectedArea: TextView = view.findViewById(R.id.tvVrcSelectedArea)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_virtual_routing_card, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        val ctx = holder.itemView.context

        holder.tvConsignmentId.text = item.consignmentId
        holder.tvCustomer.text = item.customerName
        holder.tvDestination.text = item.destination
        // The dropdown chip and the "Selected Area" row both mirror the same
        // selectedArea — they're always kept in sync (see the mockup).
        holder.tvAreaChip.text = item.selectedArea
        holder.tvSelectedArea.text = item.selectedArea

        holder.tvStatusBadge.text = item.currentStatus
        // Demo-only status colors, reusing the app's existing status palette
        // (theme_accent for in-progress states, theme_orange for on-hold).
        // Once wired to real data, swap for StatusMetaCache /
        // WorkerParcelAdapter.getStatusConfig() like the rest of the app does.
        val (fg, bg) = when (item.currentStatus) {
            "On Hold" -> ctx.getColor(R.color.theme_orange) to ctx.getColor(R.color.theme_bg_orange)
            else      -> ctx.getColor(R.color.theme_accent) to ctx.getColor(R.color.theme_bg_accent)
        }
        holder.tvStatusBadge.setTextColor(fg)
        holder.tvStatusBadge.setBackgroundColor(bg)

        holder.layoutAreaDropdown.setOnClickListener { onAreaDropdownClick(item) }
    }

    class Diff : DiffUtil.ItemCallback<VirtualRoutingParcel>() {
        override fun areItemsTheSame(a: VirtualRoutingParcel, b: VirtualRoutingParcel) =
            a.consignmentId == b.consignmentId
        override fun areContentsTheSame(a: VirtualRoutingParcel, b: VirtualRoutingParcel) = a == b
    }
}
