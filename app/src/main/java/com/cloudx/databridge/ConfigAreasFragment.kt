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
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.IgnoreExtraProperties
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

@IgnoreExtraProperties
data class Area(
    val id: String = "",
    val name: String = ""
)

/**
 * Config — Areas tab. Manages the two courier-wide area directories:
 *   courier/areas/delivery_area — destinations a parcel can be routed to;
 *     this is what Virtual Routing's Select Area picker is meant to read
 *     once it's wired to real data (it currently uses DemoAreaCatalog — see
 *     VirtualRoutingFragment's class doc).
 *   courier/areas/pickup_area   — zones a pickup run collects from.
 * Same admin/manager Firebase-rules write restriction as Merchants (see
 * database.rules.json) — this fragment doesn't duplicate that check itself,
 * since an unprivileged write would just get rejected by the rules
 * regardless of what this screen shows.
 *
 * One tab, not two — a Delivery/Pickup segmented toggle (AreaType below)
 * switches which of the two directories the list reads/writes; both sides
 * share the exact same list-render/add/edit/delete code, parameterized by
 * activeType, rather than duplicating this fragment per category. Follows
 * the same inline-panel add/edit pattern as ConfigMerchantsFragment,
 * simplified the same way Merchants is — an area is just a name.
 */
class ConfigAreasFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()

    private enum class AreaType(
        val label: String,
        val description: String,
        val listPath: String,
        val itemPath: (String) -> String
    ) {
        DELIVERY(
            "Delivery Areas",
            "Destinations a parcel can be routed to for delivery — read by Virtual Routing's Select Area picker.",
            FirebasePaths.deliveryAreas(),
            { id -> FirebasePaths.deliveryArea(id) }
        ),
        PICKUP(
            "Pickup Areas",
            "Zones a pickup run collects from.",
            FirebasePaths.pickupAreas(),
            { id -> FirebasePaths.pickupArea(id) }
        )
    }

    private var activeType = AreaType.DELIVERY

    private lateinit var tvSegDelivery: TextView
    private lateinit var tvSegPickup: TextView
    private lateinit var tvListTitle: TextView
    private lateinit var tvDescription: TextView
    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var inlinePanel: View
    private lateinit var tvPanelTitle: TextView
    private lateinit var etName: EditText
    private lateinit var tvError: TextView
    private lateinit var busyOverlay: View
    private lateinit var tvBusy: TextView

    private var areas: List<Area> = emptyList()
    private var editingAreaId: String = "" // blank = creating new

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_areas, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvSegDelivery = view.findViewById(R.id.tvAreaSegDelivery)
        tvSegPickup = view.findViewById(R.id.tvAreaSegPickup)
        tvListTitle = view.findViewById(R.id.tvAreaListTitle)
        tvDescription = view.findViewById(R.id.tvAreaDescription)
        listContainer = view.findViewById(R.id.areaListContainer)
        tvEmpty = view.findViewById(R.id.tvAreaEmpty)
        inlinePanel = view.findViewById(R.id.inlineCreateAreaPanel)
        tvPanelTitle = view.findViewById(R.id.tvCreateAreaTitle)
        etName = view.findViewById(R.id.etAreaName)
        tvError = view.findViewById(R.id.tvCreateAreaError)
        busyOverlay = view.findViewById(R.id.areaBusyOverlay)
        tvBusy = view.findViewById(R.id.tvAreaBusy)

        view.findViewById<View>(R.id.btnOpenCreateArea).setOnClickListener { openCreatePanel() }
        view.findViewById<View>(R.id.btnCancelCreateArea).setOnClickListener { closePanel() }
        view.findViewById<View>(R.id.btnSaveArea).setOnClickListener { saveArea() }

        tvSegDelivery.setOnClickListener { switchType(AreaType.DELIVERY) }
        tvSegPickup.setOnClickListener { switchType(AreaType.PICKUP) }

        switchType(activeType)
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
        tvDescription.text = type.description

        loadAreas()
    }

    private fun setBusy(busy: Boolean, message: String = "Loading...") {
        busyOverlay.isVisible = busy
        tvBusy.text = message
    }

    private fun loadAreas() {
        setBusy(true, "Loading areas...")
        val requestedType = activeType // captured so a late response can't clobber a since-switched tab
        lifecycleScope.launch {
            try {
                val snap = db.reference.child(requestedType.listPath).get().await()
                if (activeType != requestedType) return@launch
                areas = snap.children
                    .mapNotNull { it.getValue(Area::class.java)?.copy(id = it.key.orEmpty()) }
                    .sortedBy { it.name.lowercase() }
                renderList()
            } catch (e: Exception) {
                if (activeType == requestedType) {
                    Toast.makeText(requireContext(), "Failed to load areas: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                if (activeType == requestedType) setBusy(false)
            }
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        tvEmpty.isVisible = areas.isEmpty()

        areas.forEach { area ->
            val row = layoutInflater.inflate(R.layout.item_area_row, listContainer, false)
            row.findViewById<TextView>(R.id.tvAreaName).text = area.name
            row.findViewById<View>(R.id.btnEditArea).setOnClickListener { openEditPanel(area) }
            row.findViewById<View>(R.id.btnDeleteArea).setOnClickListener { confirmDelete(area) }
            listContainer.addView(row)
        }
    }

    private fun openCreatePanel() {
        editingAreaId = ""
        tvPanelTitle.text = "+ New ${if (activeType == AreaType.DELIVERY) "Delivery" else "Pickup"} Area"
        etName.setText("")
        tvError.isVisible = false
        inlinePanel.isVisible = true
    }

    private fun openEditPanel(area: Area) {
        editingAreaId = area.id
        tvPanelTitle.text = "Edit Area"
        etName.setText(area.name)
        tvError.isVisible = false
        inlinePanel.isVisible = true
    }

    private fun closePanel() {
        inlinePanel.isVisible = false
        editingAreaId = ""
    }

    private fun saveArea() {
        val name = etName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            tvError.text = "Enter an area name"
            tvError.isVisible = true
            return
        }
        // Guard against accidental duplicate entries (case-insensitive) WITHIN
        // the current type — the same name is fine across delivery vs pickup,
        // since those are two separate directories serving different purposes.
        val duplicate = areas.any { it.name.equals(name, ignoreCase = true) && it.id != editingAreaId }
        if (duplicate) {
            tvError.text = "An area with this name already exists"
            tvError.isVisible = true
            return
        }
        tvError.isVisible = false

        val type = activeType
        setBusy(true, "Saving...")
        lifecycleScope.launch {
            try {
                val id = editingAreaId.ifBlank { db.reference.child(type.listPath).push().key.orEmpty() }
                if (id.isBlank()) throw IllegalStateException("Could not generate area id")
                db.reference.child(type.itemPath(id)).setValue(Area(id = id, name = name)).await()
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
        val target = activeType
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete ${area.name}?")
            .setMessage(
                if (target == AreaType.DELIVERY)
                    "This removes the area from Virtual Routing's Select Area picker. Parcels already routed to it are unaffected."
                else
                    "This removes the area from the Pickup Areas list."
            )
            .setPositiveButton("Delete") { _, _ -> deleteArea(area, target) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteArea(area: Area, type: AreaType) {
        setBusy(true, "Deleting...")
        lifecycleScope.launch {
            try {
                db.reference.child(type.itemPath(area.id)).removeValue().await()
                loadAreas()
            } catch (e: Exception) {
                setBusy(false)
                Toast.makeText(requireContext(), "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
