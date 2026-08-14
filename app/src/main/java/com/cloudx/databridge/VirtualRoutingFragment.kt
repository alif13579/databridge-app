package com.cloudx.databridge

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Virtual Routing — lets a call center agent manually assign/override a
 * parcel's delivery area from a card list (see the mockup this was built
 * from). A parcel starts in the Pending Queue with no area; picking one via
 * SelectAreaBottomSheet routes it — it disappears from Pending Queue and
 * appears grouped under that area in the Routed tab (VirtualRoutingParcel's
 * doc explains why routed/pending isn't a separate field to keep in sync).
 *
 * Routed tab has three views over the SAME routed parcels, switched by the
 * By Area / By Status / All segmented control:
 *   - By Area: accordion grouped by areaGroup (renderByAreaGroups()).
 *   - By Status: accordion grouped by currentStatus (renderByStatusGroups())
 *     — same accordion rendering code as By Area, different grouping key.
 *   - All: flat list, no accordion (renderAllFlat()).
 * All three share layoutRoutedRows as their container — switching segments
 * clears and re-renders it rather than keeping three parallel view trees.
 *
 * DEMO STAGE — as requested, this is demo data only, ready for wiring later:
 *   - demoParcels() below stands in for a real query. When wiring this up,
 *     the natural source is the same courier/runs_by_branchId -> run_routes
 *     -> consignments chain CallCenterFragment and the extension's exports
 *     already use.
 *   - DemoAreaCatalog (in VirtualRoutingAdapter.kt) stands in for a real
 *     per-branch/area config node.
 *   - Confirming an area in the bottom sheet only updates allParcels' in-
 *     memory copy (see routeParcel() below) — nothing is persisted. Real
 *     wiring needs to decide where the chosen area is written (a new field
 *     on the consignment, or a remark — whichever the rest of the schema
 *     expects) and write it there instead.
 *   - The top-right filter icon (ivVrFilter) is a visual placeholder only —
 *     no filter behavior is wired to it yet.
 *   - The Sort button (Pending Queue) is a visual placeholder only — the
 *     mockup doesn't show what it sorts by, so no sort is actually applied.
 *   - The 3-dot menu on a routed row (btnRprMenu) currently offers only
 *     "Move back to Pending Queue" (moveToPendingQueue()) — the mockup
 *     doesn't show this menu open, so its other options (if any) aren't
 *     known; this is the one action that follows unambiguously from the
 *     screens that ARE shown.
 */
class VirtualRoutingFragment : Fragment() {

    private lateinit var rootView: View

    // Tabs
    private lateinit var layoutTabPending: View
    private lateinit var layoutTabRouted: View
    private lateinit var tvTabPendingLabel: TextView
    private lateinit var tvTabRoutedLabel: TextView
    private lateinit var tvTabPendingBadge: TextView
    private lateinit var tvTabRoutedBadge: TextView
    private lateinit var indicatorPending: View
    private lateinit var indicatorRouted: View
    private lateinit var pendingContent: View
    private lateinit var routedContent: View

    // Pending Queue
    private lateinit var rv: RecyclerView
    private lateinit var etSearch: EditText
    private lateinit var tvEmpty: TextView
    private lateinit var tvPendingCountLabel: TextView
    private lateinit var adapter: VirtualRoutingAdapter

    // Routed
    private lateinit var tvSegByArea: TextView
    private lateinit var tvSegByStatus: TextView
    private lateinit var tvSegAll: TextView
    private lateinit var etRoutedSearch: EditText
    private lateinit var tvRoutedEmpty: TextView
    private lateinit var layoutRoutedRows: LinearLayout

    private enum class RoutedSegment { BY_AREA, BY_STATUS, ALL }
    private var routedSegment = RoutedSegment.BY_AREA
    /** Which accordion groups are expanded, by group name — persists across
     *  re-renders (routing a parcel, searching) within the same segment, but
     *  is naturally irrelevant to the All segment (no groups there). */
    private val expandedGroups = mutableSetOf<String>()

    private val timeFormat = SimpleDateFormat("h:mm a", Locale.US)

    /** Full, unfiltered demo list — both pending and routed parcels live
     *  here together; which tab/group a parcel shows up in is derived from
     *  its own state (isRouted, areaGroup, currentStatus), not tracked
     *  separately. Both applyPendingFilter() and renderRouted() read a view
     *  of this rather than a separate list, so routing/un-routing a parcel
     *  never needs to move it between two lists. */
    private val allParcels = mutableListOf<VirtualRoutingParcel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_virtual_routing, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rootView = view

        layoutTabPending = view.findViewById(R.id.layoutTabPending)
        layoutTabRouted = view.findViewById(R.id.layoutTabRouted)
        tvTabPendingLabel = view.findViewById(R.id.tvTabPendingLabel)
        tvTabRoutedLabel = view.findViewById(R.id.tvTabRoutedLabel)
        tvTabPendingBadge = view.findViewById(R.id.tvTabPendingBadge)
        tvTabRoutedBadge = view.findViewById(R.id.tvTabRoutedBadge)
        indicatorPending = view.findViewById(R.id.indicatorPending)
        indicatorRouted = view.findViewById(R.id.indicatorRouted)
        pendingContent = view.findViewById(R.id.pendingContent)
        routedContent = view.findViewById(R.id.routedContent)

        rv = view.findViewById(R.id.rvVirtualRouting)
        etSearch = view.findViewById(R.id.etVrSearch)
        tvEmpty = view.findViewById(R.id.tvVrEmpty)
        tvPendingCountLabel = view.findViewById(R.id.tvVrPendingCountLabel)

        tvSegByArea = view.findViewById(R.id.tvSegByArea)
        tvSegByStatus = view.findViewById(R.id.tvSegByStatus)
        tvSegAll = view.findViewById(R.id.tvSegAll)
        etRoutedSearch = view.findViewById(R.id.etVrRoutedSearch)
        tvRoutedEmpty = view.findViewById(R.id.tvVrRoutedEmpty)
        layoutRoutedRows = view.findViewById(R.id.layoutRoutedRows)

        adapter = VirtualRoutingAdapter(onAreaDropdownClick = { parcel -> openAreaSheet(parcel) })
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        allParcels.addAll(demoParcels())

        layoutTabPending.setOnClickListener { showTab(pending = true) }
        layoutTabRouted.setOnClickListener { showTab(pending = false) }
        showTab(pending = true)

        etSearch.addTextChangedListener(simpleWatcher { applyPendingFilter(it) })
        etRoutedSearch.addTextChangedListener(simpleWatcher { renderRouted(it) })

        tvSegByArea.setOnClickListener { setRoutedSegment(RoutedSegment.BY_AREA) }
        tvSegByStatus.setOnClickListener { setRoutedSegment(RoutedSegment.BY_STATUS) }
        tvSegAll.setOnClickListener { setRoutedSegment(RoutedSegment.ALL) }

        refreshAll()
    }

    private fun simpleWatcher(onChanged: (String) -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) = onChanged(s?.toString().orEmpty())
    }

    // ── Tab switching ──────────────────────────────────────────────────────

    private fun showTab(pending: Boolean) {
        pendingContent.visibility = if (pending) View.VISIBLE else View.GONE
        routedContent.visibility = if (pending) View.GONE else View.VISIBLE

        val ctx = requireContext()
        val active = ctx.getColor(R.color.theme_purple)
        val inactive = ctx.getColor(R.color.theme_text_secondary)

        tvTabPendingLabel.setTextColor(if (pending) active else inactive)
        tvTabPendingLabel.setTypeface(null, if (pending) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        indicatorPending.visibility = if (pending) View.VISIBLE else View.INVISIBLE

        tvTabRoutedLabel.setTextColor(if (pending) inactive else active)
        tvTabRoutedLabel.setTypeface(null, if (pending) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
        indicatorRouted.visibility = if (pending) View.INVISIBLE else View.VISIBLE

        if (!pending) renderRouted(etRoutedSearch.text?.toString().orEmpty())
    }

    /** Recomputes both tab badges and re-applies whichever tab's filter is
     *  currently live — called after every change to allParcels (routing,
     *  un-routing) so both tabs always reflect the latest state even though
     *  only one is visible at a time. */
    private fun refreshAll() {
        val pendingCount = allParcels.count { !it.isRouted }
        val routedCount = allParcels.count { it.isRouted }
        tvTabPendingBadge.text = pendingCount.toString()
        tvTabRoutedBadge.text = routedCount.toString()
        tvPendingCountLabel.text = "$pendingCount Parcels in Pending Queue"

        applyPendingFilter(etSearch.text?.toString().orEmpty())
        if (routedContent.visibility == View.VISIBLE) {
            renderRouted(etRoutedSearch.text?.toString().orEmpty())
        }
    }

    // ── Pending Queue ──────────────────────────────────────────────────────

    private fun applyPendingFilter(query: String) {
        val q = query.trim()
        val pending = allParcels.filter { !it.isRouted }
        val filtered = if (q.isBlank()) pending
            else pending.filter { it.consignmentId.contains(q, ignoreCase = true) }
        adapter.submitList(filtered.toList())
        tvEmpty.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openAreaSheet(parcel: VirtualRoutingParcel) {
        val areaList = DemoAreaCatalog.areasByGroup[parcel.areaGroup].orEmpty()
        val sheet = SelectAreaBottomSheet().apply {
            areas = areaList
            currentSelection = parcel.selectedArea.orEmpty()
            onAreaSelected = { chosen -> routeParcel(parcel.consignmentId, chosen) }
        }
        sheet.show(childFragmentManager, "select_area")
    }

    /** Routes a parcel: sets its area + timestamp, which alone is what moves
     *  it out of the Pending Queue and into the Routed tab (see
     *  VirtualRoutingParcel.isRouted) — then refreshes both tabs and shows
     *  the mockup's success Snackbar. */
    private fun routeParcel(consignmentId: String, area: String) {
        val index = allParcels.indexOfFirst { it.consignmentId == consignmentId }
        if (index == -1) return
        allParcels[index] = allParcels[index].copy(
            selectedArea = area,
            routedAt = System.currentTimeMillis()
        )
        refreshAll()

        Snackbar.make(rootView, "✓ Routed successfully to $area", Snackbar.LENGTH_LONG)
            .setBackgroundTint(requireContext().getColor(R.color.theme_green))
            .setTextColor(requireContext().getColor(R.color.theme_text_inverse))
            .show()
    }

    /** The one action the 3-dot menu on a routed row offers — see the class
     *  doc for why nothing else is in that menu yet. Clears selectedArea/
     *  routedAt, which alone moves the parcel back into the Pending Queue. */
    private fun moveToPendingQueue(consignmentId: String) {
        val index = allParcels.indexOfFirst { it.consignmentId == consignmentId }
        if (index == -1) return
        allParcels[index] = allParcels[index].copy(selectedArea = null, routedAt = null)
        refreshAll()
    }

    // ── Routed tab ─────────────────────────────────────────────────────────

    private fun setRoutedSegment(segment: RoutedSegment) {
        if (routedSegment == segment) return
        routedSegment = segment

        val ctx = requireContext()
        val active = ctx.getColor(R.color.theme_text_inverse)
        val inactive = ctx.getColor(R.color.theme_text_secondary)
        for ((tab, view) in listOf(RoutedSegment.BY_AREA to tvSegByArea, RoutedSegment.BY_STATUS to tvSegByStatus, RoutedSegment.ALL to tvSegAll)) {
            val isActive = tab == segment
            view.setTextColor(if (isActive) active else inactive)
            view.setTypeface(null, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            view.setBackgroundResource(if (isActive) R.drawable.bg_segment_active else 0)
        }

        etRoutedSearch.hint = when (segment) {
            RoutedSegment.BY_AREA -> "Search area"
            RoutedSegment.BY_STATUS -> "Search status"
            RoutedSegment.ALL -> "Search by Consignment ID"
        }
        etRoutedSearch.text?.clear() // clearing re-triggers renderRouted via the TextWatcher

        renderRouted("")
    }

    private fun renderRouted(query: String) {
        val q = query.trim()
        val routed = allParcels.filter { it.isRouted }

        when (routedSegment) {
            RoutedSegment.BY_AREA -> renderGrouped(
                routed, q,
                groupKeyOf = { it.areaGroup },
                // Matches the mockup's own ordering, which isn't pure count-
                // descending (Others:2 sits after Badda:1) — reads as a fixed
                // catalog order instead, with the fallback "Others" bucket
                // always last. DemoAreaCatalog.areasByGroup is already
                // defined in exactly that order.
                catalogOrder = DemoAreaCatalog.areasByGroup.keys.toList()
            )
            RoutedSegment.BY_STATUS -> renderGrouped(
                routed, q,
                groupKeyOf = { it.currentStatus },
                catalogOrder = null // no catalog for statuses — falls back to count-descending
            )
            RoutedSegment.ALL -> renderFlat(
                if (q.isBlank()) routed else routed.filter { it.consignmentId.contains(q, ignoreCase = true) }
            )
        }
    }

    /** Shared accordion renderer for By Area and By Status — only the
     *  grouping key (and how groups are ORDERED — catalogOrder or count)
     *  differs; see renderRouted(). The search box filters which GROUPS
     *  show (by group name), not individual parcels within a group,
     *  matching the mockup's "Search area" placement above the accordion
     *  rather than inside each group.
     *
     *  Sorts a List<Map.Entry>, not a sorted Map with a custom comparator —
     *  a TreeMap/toSortedMap comparator that returns 0 for two DIFFERENT
     *  keys (e.g. two groups tied on count) treats them as duplicates and
     *  silently drops one, which a count-based comparator hits constantly
     *  with small demo data (Rampura/Mirpur 10/Badda would all tie at 1).
     *  Sorting entries in a List has no such uniqueness constraint. */
    private fun renderGrouped(
        routed: List<VirtualRoutingParcel>,
        query: String,
        groupKeyOf: (VirtualRoutingParcel) -> String,
        catalogOrder: List<String>?
    ) {
        layoutRoutedRows.removeAllViews()
        val ctx = requireContext()

        val grouped = routed.groupBy(groupKeyOf)
            .filterKeys { query.isBlank() || it.contains(query, ignoreCase = true) }

        val orderedEntries = if (catalogOrder != null) {
            grouped.entries.sortedBy { (name, _) ->
                catalogOrder.indexOf(name).let { if (it == -1) Int.MAX_VALUE else it }
            }
        } else {
            grouped.entries.sortedByDescending { (_, parcelsInGroup) -> parcelsInGroup.size }
        }

        tvRoutedEmpty.visibility = if (orderedEntries.isEmpty()) View.VISIBLE else View.GONE

        for ((groupName, parcelsInGroup) in orderedEntries) {
            val header = LayoutInflater.from(ctx).inflate(R.layout.item_routed_group_header, layoutRoutedRows, false)
            val tvName = header.findViewById<TextView>(R.id.tvRghName)
            val tvCount = header.findViewById<TextView>(R.id.tvRghCount)
            val tvChevron = header.findViewById<TextView>(R.id.tvRghChevron)

            tvName.text = groupName
            tvCount.text = parcelsInGroup.size.toString()

            val rowsContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = if (groupName in expandedGroups) View.VISIBLE else View.GONE
            }
            tvChevron.text = if (groupName in expandedGroups) "▼" else "▶"

            parcelsInGroup.sortedByDescending { it.routedAt ?: 0L }.forEach { parcel ->
                rowsContainer.addView(buildRoutedRow(parcel))
            }

            header.setOnClickListener {
                val nowExpanded = groupName !in expandedGroups
                if (nowExpanded) expandedGroups.add(groupName) else expandedGroups.remove(groupName)
                rowsContainer.visibility = if (nowExpanded) View.VISIBLE else View.GONE
                tvChevron.text = if (nowExpanded) "▼" else "▶"
            }

            layoutRoutedRows.addView(header)
            layoutRoutedRows.addView(rowsContainer)
        }
    }

    private fun renderFlat(parcels: List<VirtualRoutingParcel>) {
        layoutRoutedRows.removeAllViews()
        tvRoutedEmpty.visibility = if (parcels.isEmpty()) View.VISIBLE else View.GONE
        parcels.sortedByDescending { it.routedAt ?: 0L }.forEach { parcel ->
            layoutRoutedRows.addView(buildRoutedRow(parcel))
        }
    }

    private fun buildRoutedRow(parcel: VirtualRoutingParcel): View {
        val ctx = requireContext()
        val row = LayoutInflater.from(ctx).inflate(R.layout.item_routed_parcel_row, layoutRoutedRows, false)
        row.findViewById<TextView>(R.id.tvRprConsignmentId).text = parcel.consignmentId
        row.findViewById<TextView>(R.id.tvRprCustomer).text = parcel.customerName
        row.findViewById<TextView>(R.id.tvRprRoutedAt).text =
            parcel.routedAt?.let { timeFormat.format(it) } ?: "—"

        row.findViewById<ImageButton>(R.id.btnRprMenu).setOnClickListener { anchor ->
            PopupMenu(ctx, anchor).apply {
                menu.add("Move back to Pending Queue")
                setOnMenuItemClickListener {
                    moveToPendingQueue(parcel.consignmentId)
                    true
                }
            }.show()
        }
        return row
    }

    /** Hardcoded demo data — see class doc. The first three parcels match
     *  the mockup exactly (consignment id, customer, destination, status)
     *  and start unrouted, same as the mockup's Pending Queue screen; the
     *  rest are invented but plausible, some pre-routed so the Routed tab
     *  has something to show immediately. */
    private fun demoParcels(): List<VirtualRoutingParcel> {
        val now = System.currentTimeMillis()
        val minute = 60_000L
        return listOf(
            VirtualRoutingParcel(
                consignmentId = "BD1234567890",
                customerName = "Rafiq Ahmed",
                destination = "Mirpur, Dhaka",
                currentStatus = "In Transit",
                areaGroup = "Dhanmondi"
            ),
            VirtualRoutingParcel(
                consignmentId = "BD1234567891",
                customerName = "Nusrat Jahan",
                destination = "Uttara, Dhaka",
                currentStatus = "In Transit",
                areaGroup = "Uttara Sector 7"
            ),
            VirtualRoutingParcel(
                consignmentId = "BD1234567892",
                customerName = "Mahmudul Hasan",
                destination = "Gulshan, Dhaka",
                currentStatus = "On Hold",
                areaGroup = "Gulshan 1"
            ),
            VirtualRoutingParcel(
                consignmentId = "BD1234567893",
                customerName = "Farhana Akter",
                destination = "Rampura, Dhaka",
                currentStatus = "In Transit",
                areaGroup = "Rampura"
            ),
            VirtualRoutingParcel(
                consignmentId = "BD1234567894",
                customerName = "Imran Kabir",
                destination = "Banani, Dhaka",
                currentStatus = "In Transit",
                areaGroup = "Banani"
            ),
            VirtualRoutingParcel(
                consignmentId = "BD1234567896",
                customerName = "Tania Islam",
                destination = "Mohammadpur, Dhaka",
                currentStatus = "On Hold",
                areaGroup = "Mohammadpur"
            ),
            // Pre-routed, so the Routed tab isn't empty on first open, and
            // spans multiple area groups so the By Area accordion actually
            // demonstrates more than one group without any interaction.
            VirtualRoutingParcel(
                consignmentId = "BD1234567895",
                customerName = "Sadia Akter",
                destination = "Dhanmondi, Dhaka",
                currentStatus = "In Transit",
                areaGroup = "Dhanmondi",
                selectedArea = "Dhanmondi",
                routedAt = now - 25 * minute
            ),
            VirtualRoutingParcel(
                consignmentId = "BD1234567898",
                customerName = "Tanvir Hasan",
                destination = "Dhanmondi, Dhaka",
                currentStatus = "Delivered",
                areaGroup = "Dhanmondi",
                selectedArea = "Dhanmondi",
                routedAt = now - 60 * minute
            ),
            VirtualRoutingParcel(
                consignmentId = "BD1234567899",
                customerName = "Kamal Uddin",
                destination = "Uttara, Dhaka",
                currentStatus = "In Transit",
                areaGroup = "Uttara Sector 7",
                selectedArea = "Uttara Sector 7",
                routedAt = now - 40 * minute
            ),
            VirtualRoutingParcel(
                consignmentId = "BD1234567900",
                customerName = "Nabila Haque",
                destination = "Gulshan, Dhaka",
                currentStatus = "In Transit",
                areaGroup = "Gulshan 1",
                selectedArea = "Gulshan 2",
                routedAt = now - 15 * minute
            ),
            VirtualRoutingParcel(
                consignmentId = "BD1234567901",
                customerName = "Rakib Chowdhury",
                destination = "Rampura, Dhaka",
                currentStatus = "Delivered",
                areaGroup = "Rampura",
                selectedArea = "Banasree",
                routedAt = now - 90 * minute
            ),
            VirtualRoutingParcel(
                consignmentId = "BD1234567902",
                customerName = "Jannatul Ferdous",
                destination = "Savar, Dhaka",
                currentStatus = "In Transit",
                areaGroup = "Others",
                selectedArea = "Outside Dhaka",
                routedAt = now - 10 * minute
            )
        )
    }
}
