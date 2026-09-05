package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.IgnoreExtraProperties
import kotlinx.coroutines.launch

@IgnoreExtraProperties
data class Area(
    val id: String = "",
    val areaId: String = "",
    val name: String = "",
    // Supabase public.areas (branch-wise directory). Firebase rows predate
    // these fields, so everything below blanks there — never rely on them
    // for Firebase reads.
    val branchId: String = "",
    val branchName: String = "",
    val areaType: String = "",
    val zone: String = ""
) {
    /** True for pickers of this usage ("pickup" matches pickup+both, etc.). */
    fun matchesUsage(usage: String): Boolean =
        areaType.isBlank() || areaType == "both" || areaType == usage
}

/**
 * Picker dedupe: the same area can exist in several branches (backfill copies
 * courier-wide areas everywhere), so a multi-branch picker would list one name
 * many times. Rule: same name collapses — EXCEPT across types, where a Pickup
 * row and a Delivery row sharing a name stay as two entries (one delivery,
 * one pickup), since they serve different pickers. Keeps first-seen order.
 *
 * [usage] set (claim pickers): collapse strictly by name, preferring the
 * usage-specific row over a 'both'/legacy twin — a single-usage list must
 * never show one name twice. Blank (store picker): bucket by name+type.
 */
fun dedupeAreasForPicker(areas: List<Area>, usage: String = ""): List<Area> {
    if (usage.isBlank()) {
        val out = mutableListOf<Area>()
        val seen = mutableSetOf<String>()
        for (a in areas) {
            // 'both' is its own bucket, so a both-row never hides a
            // pickup/delivery-specific twin.
            val bucket = a.name.lowercase() + "::" + a.areaType.ifBlank { "both" }.lowercase()
            if (seen.add(bucket)) out.add(a)
        }
        return out
    }
    return areas.groupBy { it.name.lowercase() }.values.mapNotNull { group ->
        group.firstOrNull { it.areaType == usage }
            ?: group.firstOrNull { it.areaType.isBlank() || it.areaType == "both" }
            ?: group.firstOrNull()
    }
}

/** Display label with zone + a type tag only when the same name needed two
 *  entries (twin types) — keeps ordinary single rows exactly as before. */
fun areaPickerLabel(a: Area, needsTypeTag: Boolean): String {
    var label = a.name
    if (a.zone.isNotBlank()) label += " · ${a.zone}"
    if (needsTypeTag && a.areaType.isNotBlank() && a.areaType != "both") {
        label += " · " + a.areaType.replaceFirstChar { it.uppercase() }
    }
    return label
}

/**
 * Config — Areas tab. Manages the branch-wise area directory
 * (public.areas, Supabase-first since the areas cutover):
 *   branch  — which branch this area belongs to (branch picker on top)
 *   type    — pickup | delivery | both (which pickers list it)
 *   zone    — free-text zone/group label for area grip
 *   area id + name — same human-assigned shape as Store ID.
 *
 * One tab, two usages — a Delivery/Pickup segmented toggle filters which
 * rows are shown; the type picker in the panel decides what a row serves.
 * Claims (From/To) and pickup Stores read from here, scoped to the branch.
 *
 * Same inline-panel add/edit pattern as ConfigStoresFragment. Writes go
 * through SupabaseAreaWriter (admin/manager-gated server-side, Firebase
 * backup mirror included) — the old Firebase courier/areas writes are gone.
 */
class ConfigAreasFragment : Fragment() {

    private enum class AreaType(val label: String, val usage: String) {
        DELIVERY("Delivery Areas", "delivery"),
        PICKUP("Pickup Areas", "pickup"),
    }

    private var activeType = AreaType.DELIVERY
    private var selectedBranchId: String = ""
    private var branchNames: Map<String, String> = emptyMap()
    private var selectedAreaType: String = "both"

    private lateinit var tvSegDelivery: TextView
    private lateinit var tvSegPickup: TextView
    private lateinit var tvListTitle: TextView
    private lateinit var tvDescription: TextView
    private lateinit var tvBranchSelected: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var inlinePanel: View
    private lateinit var tvPanelTitle: TextView
    private lateinit var etAreaId: EditText
    private lateinit var etName: EditText
    private lateinit var tvTypeSelected: TextView
    private lateinit var etZone: EditText
    private lateinit var tvError: TextView
    private lateinit var busyOverlay: View
    private lateinit var tvBusy: TextView

    private var areas: List<Area> = emptyList()
    private var editingAreaId: String = "" // area_id being edited; blank = creating new

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_areas, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvSegDelivery = view.findViewById(R.id.tvAreaSegDelivery)
        tvSegPickup = view.findViewById(R.id.tvAreaSegPickup)
        tvListTitle = view.findViewById(R.id.tvAreaListTitle)
        tvDescription = view.findViewById(R.id.tvAreaDescription)
        tvBranchSelected = view.findViewById(R.id.tvAreaBranchSelected)
        listContainer = view.findViewById(R.id.areaListContainer)
        tvEmpty = view.findViewById(R.id.tvAreaEmpty)
        inlinePanel = view.findViewById(R.id.inlineCreateAreaPanel)
        tvPanelTitle = view.findViewById(R.id.tvCreateAreaTitle)
        etAreaId = view.findViewById(R.id.etAreaId)
        etName = view.findViewById(R.id.etAreaName)
        tvTypeSelected = view.findViewById(R.id.tvAreaTypeSelected)
        etZone = view.findViewById(R.id.etAreaZone)
        tvError = view.findViewById(R.id.tvCreateAreaError)
        busyOverlay = view.findViewById(R.id.areaBusyOverlay)
        tvBusy = view.findViewById(R.id.tvAreaBusy)

        view.findViewById<View>(R.id.btnOpenCreateArea).setOnClickListener { openCreatePanel() }
        view.findViewById<View>(R.id.btnCancelCreateArea).setOnClickListener { closePanel() }
        view.findViewById<View>(R.id.btnSaveArea).setOnClickListener { saveArea() }
        view.findViewById<View>(R.id.layoutAreaBranch).setOnClickListener { showBranchPicker() }
        view.findViewById<View>(R.id.layoutAreaType).setOnClickListener { showTypePicker() }

        tvSegDelivery.setOnClickListener { switchType(AreaType.DELIVERY) }
        tvSegPickup.setOnClickListener { switchType(AreaType.PICKUP) }

        initBranch()
        switchType(activeType)
    }

    private fun initBranch() {
        val branchIds = RbacManager.current.branchIds
        selectedBranchId = branchIds.firstOrNull().orEmpty()
        if (RbacManager.current.branchName.isNotBlank() && selectedBranchId.isNotBlank()) {
            branchNames = mapOf(selectedBranchId to RbacManager.current.branchName)
        }
        updateBranchLabel()
        lifecycleScope.launch {
            runCatching { SupabaseClaimsReader.fetchBranches() }.onSuccess { options ->
                val byId = options.associate { it.branchId to it.name }
                branchNames = branchNames + branchIds
                    .filter { it !in branchNames }
                    .associateWith { id -> byId[id]?.takeIf { it.isNotBlank() } ?: id }
                updateBranchLabel()
            }
        }
    }

    private fun updateBranchLabel() {
        tvBranchSelected.text = branchNames[selectedBranchId] ?: selectedBranchId.ifBlank { "Select Branch" }
        tvBranchSelected.setTextColor(
            android.graphics.Color.parseColor(if (selectedBranchId.isBlank()) "#94A3B8" else "#0F172A")
        )
    }

    private fun showBranchPicker() {
        val branchIds = RbacManager.current.branchIds
        if (branchIds.isEmpty()) {
            Toast.makeText(requireContext(), "No branch assigned — contact your admin", Toast.LENGTH_LONG).show()
            return
        }
        val labels = branchIds.map { branchNames[it] ?: it }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Branch")
            .setItems(labels) { _, index ->
                selectedBranchId = branchIds[index]
                updateBranchLabel()
                loadAreas()
            }
            .show()
    }

    private fun showTypePicker() {
        val options = arrayOf("Pickup", "Delivery", "Both")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Area Type")
            .setItems(options) { _, index ->
                selectedAreaType = options[index].lowercase()
                tvTypeSelected.text = options[index]
            }
            .show()
    }

    private fun switchType(type: AreaType) {
        activeType = type
        closePanel() // avoid a confusing state where the panel is mid-edit for the OTHER category

        val ctx = requireContext()
        val activeColor = ctx.getColor(R.color.theme_bg_card) // white text on the filled segment
        val inactiveColor = ctx.getColor(R.color.theme_text_secondary)

        tvSegDelivery.setTextColor(if (type == AreaType.DELIVERY) activeColor else inactiveColor)
        tvSegDelivery.setBackgroundResource(if (type == AreaType.DELIVERY) R.drawable.bg_area_segment_active else 0)
        tvSegPickup.setTextColor(if (type == AreaType.PICKUP) activeColor else inactiveColor)
        tvSegPickup.setBackgroundResource(if (type == AreaType.PICKUP) R.drawable.bg_area_segment_active else 0)

        tvListTitle.text = type.label
        tvDescription.text = if (type == AreaType.DELIVERY)
            "Branch areas served for delivery — claim To picker reads these."
        else
            "Branch areas served for pickup — store form + claim From picker read these."

        loadAreas()
    }

    private fun setBusy(busy: Boolean, message: String = "Loading...") {
        busyOverlay.isVisible = busy
        tvBusy.text = message
    }

    private fun loadAreas() {
        if (selectedBranchId.isBlank()) {
            areas = emptyList()
            renderList()
            return
        }
        setBusy(true, "Loading areas...")
        val requestedBranch = selectedBranchId
        val requestedUsage = activeType.usage
        lifecycleScope.launch {
            try {
                // Areas live in Supabase now (public.areas, branch-wise) — same
                // screen the claim/store pickers read from.
                val result = SupabaseClaimsReader.fetchAreas(
                    branchIds = listOf(requestedBranch),
                    usages = listOf(requestedUsage),
                )
                if (selectedBranchId != requestedBranch || activeType.usage != requestedUsage) return@launch
                areas = result.sortedBy { it.name.lowercase() }
                renderList()
            } catch (e: Exception) {
                if (selectedBranchId == requestedBranch && activeType.usage == requestedUsage) {
                    Toast.makeText(requireContext(), "Failed to load areas: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                if (selectedBranchId == requestedBranch && activeType.usage == requestedUsage) setBusy(false)
            }
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        tvEmpty.isVisible = areas.isEmpty()

        areas.forEach { area ->
            val row = layoutInflater.inflate(R.layout.item_area_row, listContainer, false)
            row.findViewById<TextView>(R.id.tvAreaName).text = area.name
            val subtitleParts = listOfNotNull(
                area.areaId.takeIf { it.isNotBlank() },
                area.areaType.takeIf { it.isNotBlank() && it != "both" },
                area.zone.takeIf { it.isNotBlank() },
            )
            row.findViewById<TextView>(R.id.tvAreaSubtitle).text = subtitleParts.joinToString(" · ")
            row.findViewById<View>(R.id.btnEditArea).setOnClickListener { openEditPanel(area) }
            row.findViewById<View>(R.id.btnDeleteArea).setOnClickListener { confirmDelete(area) }
            listContainer.addView(row)
        }
    }

    private fun openCreatePanel() {
        editingAreaId = ""
        tvPanelTitle.text = "+ New ${if (activeType == AreaType.DELIVERY) "Delivery" else "Pickup"} Area"
        etAreaId.setText("")
        etName.setText("")
        etZone.setText("")
        selectedAreaType = if (activeType == AreaType.DELIVERY) "delivery" else "pickup"
        tvTypeSelected.text = if (activeType == AreaType.DELIVERY) "Delivery" else "Pickup"
        tvError.isVisible = false
        inlinePanel.isVisible = true
    }

    private fun openEditPanel(area: Area) {
        editingAreaId = area.areaId
        tvPanelTitle.text = "Edit Area"
        etAreaId.setText(area.areaId)
        etName.setText(area.name)
        etZone.setText(area.zone)
        selectedAreaType = area.areaType.ifBlank { "both" }
        tvTypeSelected.text = when (selectedAreaType) {
            "pickup" -> "Pickup"
            "delivery" -> "Delivery"
            else -> "Both"
        }
        tvError.isVisible = false
        inlinePanel.isVisible = true
    }

    private fun closePanel() {
        inlinePanel.isVisible = false
        editingAreaId = ""
    }

    private fun saveArea() {
        val areaId = etAreaId.text?.toString()?.trim().orEmpty()
        val name = etName.text?.toString()?.trim().orEmpty()
        val zone = etZone.text?.toString()?.trim().orEmpty()
        if (selectedBranchId.isBlank()) {
            tvError.text = "Select a branch first"
            tvError.isVisible = true
            return
        }
        if (areaId.isBlank()) {
            tvError.text = "Enter an area ID"
            tvError.isVisible = true
            return
        }
        if (name.isBlank()) {
            tvError.text = "Enter an area name"
            tvError.isVisible = true
            return
        }
        // Area ID is unique per branch across types (DB constraint) — check
        // against the branch's full list, not just this usage tab.
        if (areas.any { it.areaId.equals(areaId, ignoreCase = true) } &&
            !editingAreaId.equals(areaId, ignoreCase = true)) {
            tvError.text = "This branch already has this Area ID"
            tvError.isVisible = true
            return
        }
        tvError.isVisible = false

        setBusy(true, "Saving...")
        lifecycleScope.launch {
            try {
                // Supabase-first (area_upsert, admin/manager-gated server-side,
                // Firebase backup mirror included).
                SupabaseAreaWriter.save(
                    branchId = selectedBranchId,
                    areaId = areaId,
                    name = name,
                    areaType = selectedAreaType,
                    zone = zone,
                )
                closePanel()
                loadAreas()
            } catch (e: Exception) {
                setBusy(false)
                tvError.text = "Save failed: ${e.message}"
                tvError.isVisible = true
            }
        }
    }

    private fun confirmDelete(area: Area) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete ${area.name}?")
            .setMessage("This removes the area from this branch's pickers. Existing claims/stores that already reference it keep their saved names.")
            .setPositiveButton("Delete") { _, _ -> deleteArea(area) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteArea(area: Area) {
        setBusy(true, "Deleting...")
        lifecycleScope.launch {
            try {
                SupabaseAreaWriter.delete(selectedBranchId, area.areaId)
                loadAreas()
            } catch (e: Exception) {
                setBusy(false)
                Toast.makeText(requireContext(), "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
