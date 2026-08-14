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
 * One parcel in Virtual Routing.
 *
 * [selectedArea] is null while the parcel sits in the Pending Queue — the
 * SINGLE thing that distinguishes pending from routed (see the mockup's own
 * note: "Routed parcels are removed from Pending Queue and grouped under the
 * selected Area"). Setting it (and [routedAt] alongside it) IS what routing
 * means; there's no separate status enum to keep in sync. Reverting to
 * pending — see VirtualRoutingFragment.moveToPendingQueue() — clears both
 * back to null.
 *
 * [areaGroup] is a stable key picking which area list the Select Area bottom
 * sheet shows for this parcel (see DemoAreaCatalog), AND which accordion
 * group it lands under in the Routed tab's "By Area" view — it does NOT
 * change when the agent re-picks a different sub-area within that group,
 * only [selectedArea] does. It's set from the moment a parcel is created
 * (destination is already known before anyone routes it), independent of
 * whether routing has actually happened yet.
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
    val selectedArea: String? = null,
    val routedAt: Long? = null
) {
    val isRouted: Boolean get() = selectedArea != null
}

/**
 * Demo-only area catalog for the Select Area bottom sheet, grouped by each
 * parcel's [VirtualRoutingParcel.areaGroup] — also the grouping key the
 * Routed tab's "By Area" view uses. Once wired, this comes from Firebase
 * (branch/area config) instead of a hardcoded map. "Others" is a genuine
 * catalog entry like any other, not special-cased in code — a fallback
 * bucket for destinations outside the other named zones.
 */
object DemoAreaCatalog {
    val areasByGroup: Map<String, List<String>> = mapOf(
        "Dhanmondi" to listOf(
            "Dhanmondi", "Dhanmondi 15", "Dhanmondi 27", "Dhanmondi 32",
            "Dhanmondi Lake Area", "Dhanmondi R/A", "Dhanmondi Housing", "Dhanmondi Govt. Area"
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
        "Mirpur 10" to listOf(
            "Mirpur 10", "Mirpur 1", "Mirpur 2", "Mirpur 11", "Mirpur 12", "Kazipara"
        ),
        "Badda" to listOf(
            "Badda", "North Badda", "South Badda", "Shahjadpur", "Nurer Chala"
        ),
        "Banani" to listOf(
            "Banani", "Banani DOHS", "Banani Chairman Bari", "Banani Road 11"
        ),
        "Mohammadpur" to listOf(
            "Mohammadpur", "Mohammadpur Town Hall", "Shyamoli", "Adabor", "Ring Road"
        ),
        "Others" to listOf(
            "Other Area", "Outside Dhaka"
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

        // This adapter only ever renders Pending Queue items in practice (a
        // parcel leaves it the moment it's routed — see the fragment), so
        // selectedArea is null here in the normal case. Both states are
        // still handled correctly rather than assumed, since "pending" is
        // exactly what a null selectedArea means on the model itself.
        if (item.selectedArea != null) {
            holder.tvAreaChip.text = item.selectedArea
            holder.tvAreaChip.setTextColor(ctx.getColor(R.color.theme_purple))
            holder.tvSelectedArea.text = item.selectedArea
        } else {
            holder.tvAreaChip.text = "Select Area"
            holder.tvAreaChip.setTextColor(ctx.getColor(R.color.theme_text_secondary))
            holder.tvSelectedArea.text = "—"
        }

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
