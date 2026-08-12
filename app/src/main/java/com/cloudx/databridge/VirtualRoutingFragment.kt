package com.cloudx.databridge

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Virtual Routing — lets a call center agent manually assign/override a
 * parcel's delivery area from a card list (see the mockup this was built
 * from). Tapping a card's area dropdown opens SelectAreaBottomSheet;
 * confirming there updates that card's selected area in place (both the
 * dropdown chip and the "Selected Area" row stay in sync — see
 * VirtualRoutingAdapter.onBindViewHolder()).
 *
 * DEMO STAGE — as requested, this is demo data only, ready for wiring later:
 *   - demoParcels() below stands in for a real query. When wiring this up,
 *     the natural source is the same courier/runs_by_branchId -> run_routes
 *     -> consignments chain CallCenterFragment and the extension's exports
 *     already use.
 *   - DemoAreaCatalog (in VirtualRoutingAdapter.kt) stands in for a real
 *     per-branch/area config node.
 *   - Confirming an area in the bottom sheet only updates allParcels' in-
 *     memory copy (see openAreaSheet() below) — nothing is persisted. Real
 *     wiring needs to decide where the chosen area is written (a new field
 *     on the consignment, or a remark — whichever the rest of the schema
 *     expects) and write it there instead.
 *   - The top-right filter icon (ivVrFilter) is a visual placeholder only —
 *     no filter behavior is wired to it yet. The search bar (by Consignment
 *     ID) IS live and functional, filtering allParcels in-memory.
 */
class VirtualRoutingFragment : Fragment() {

    private lateinit var rv: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var tvEmpty: TextView
    private lateinit var adapter: VirtualRoutingAdapter

    /** Full, unfiltered demo list. The search bar filters a VIEW of this into
     *  the adapter — it never mutates this list directly except when an area
     *  gets picked (see openAreaSheet()), so re-filtering after that always
     *  starts from the up-to-date full set. */
    private val allParcels = mutableListOf<VirtualRoutingParcel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_virtual_routing, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rv = view.findViewById(R.id.rvVirtualRouting)
        etSearch = view.findViewById(R.id.etVrSearch)
        tvEmpty = view.findViewById(R.id.tvVrEmpty)

        adapter = VirtualRoutingAdapter(onAreaDropdownClick = { parcel -> openAreaSheet(parcel) })
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        allParcels.addAll(demoParcels())
        applyFilter(etSearch.text?.toString().orEmpty())

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applyFilter(s?.toString().orEmpty())
            }
        })
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        val filtered = if (q.isBlank()) allParcels
            else allParcels.filter { it.consignmentId.contains(q, ignoreCase = true) }
        adapter.submitList(filtered.toList())
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openAreaSheet(parcel: VirtualRoutingParcel) {
        val areaList = DemoAreaCatalog.areasByGroup[parcel.areaGroup].orEmpty()
        val sheet = SelectAreaBottomSheet().apply {
            areas = areaList
            currentSelection = parcel.selectedArea
            onAreaSelected = { chosen ->
                val index = allParcels.indexOfFirst { it.consignmentId == parcel.consignmentId }
                if (index != -1) {
                    allParcels[index] = allParcels[index].copy(selectedArea = chosen)
                    applyFilter(etSearch.text?.toString().orEmpty())
                }
            }
        }
        sheet.show(childFragmentManager, "select_area")
    }

    /** Hardcoded demo data — see class doc. The first three cards match the
     *  mockup exactly (consignment id, customer, destination, status, area);
     *  the rest are invented but plausible, added so the list actually
     *  demonstrates scrolling. */
    private fun demoParcels(): List<VirtualRoutingParcel> = listOf(
        VirtualRoutingParcel(
            consignmentId = "BD1234567890",
            customerName = "Rafiq Ahmed",
            destination = "Mirpur, Dhaka",
            currentStatus = "In Transit",
            areaGroup = "Dhanmondi",
            selectedArea = "Dhanmondi"
        ),
        VirtualRoutingParcel(
            consignmentId = "BD1234567891",
            customerName = "Nusrat Jahan",
            destination = "Uttara, Dhaka",
            currentStatus = "In Transit",
            areaGroup = "Uttara Sector 7",
            selectedArea = "Uttara Sector 7"
        ),
        VirtualRoutingParcel(
            consignmentId = "BD1234567892",
            customerName = "Mahmudul Hasan",
            destination = "Gulshan, Dhaka",
            currentStatus = "On Hold",
            areaGroup = "Gulshan 1",
            selectedArea = "Gulshan 1"
        ),
        VirtualRoutingParcel(
            consignmentId = "BD1234567893",
            customerName = "Farhana Akter",
            destination = "Rampura, Dhaka",
            currentStatus = "In Transit",
            areaGroup = "Rampura",
            selectedArea = "Rampura"
        ),
        VirtualRoutingParcel(
            consignmentId = "BD1234567894",
            customerName = "Imran Kabir",
            destination = "Banani, Dhaka",
            currentStatus = "In Transit",
            areaGroup = "Banani",
            selectedArea = "Banani"
        ),
        VirtualRoutingParcel(
            consignmentId = "BD1234567895",
            customerName = "Tania Islam",
            destination = "Mohammadpur, Dhaka",
            currentStatus = "On Hold",
            areaGroup = "Mohammadpur",
            selectedArea = "Mohammadpur"
        )
    )
}
