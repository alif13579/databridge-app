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
import kotlinx.coroutines.launch

/**
 * Config — Stores tab (renamed and expanded from the earlier "Merchants"
 * tab; ConfigMerchantsFragment/fragment_config_merchants.xml/Merchant are
 * retired in favor of this). Manages the store directory Petty Cash's
 * Request Create screen reads from when category == Pickup (see
 * PettyCashRequestCreateFragment.loadStores()). Firebase rules restrict
 * writes here to admin/manager (see database.rules.json), matching who
 * can reach the Config screen at all — this fragment doesn't duplicate
 * that check, since a non-privileged user attempting to write would just
 * get rejected by the rules regardless of what this screen shows them.
 *
 * Unlike the old Merchant (name only), a Store carries a full address
 * book entry: a human-assigned Store ID (distinct from the Supabase row id —
 * the Store ID is what field/ops staff actually recognize a store by),
 * name, address, an Area picked from this branch set's Pickup areas
 * (public.areas via SupabaseClaimsReader.fetchAreas — a store is a place a
 * pickup run collects from, so Pickup usage is the correct scope rather
 * than Delivery), and a phone number.
 *
 * Follows the same inline-panel add/edit pattern as
 * ConfigMerchantsFragment/ConfigAreasFragment did, just with more fields
 * per entry and one dropdown (Area) instead of all plain text fields.
 */
class ConfigStoresFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var inlinePanel: View
    private lateinit var tvPanelTitle: TextView
    private lateinit var etStoreId: EditText
    private lateinit var etStoreName: EditText
    private lateinit var etStoreAddress: EditText
    private lateinit var layoutStoreArea: View
    private lateinit var tvStoreAreaSelected: TextView
    private lateinit var etStorePhone: EditText
    private lateinit var etStoreConveyanceAmount: EditText
    private lateinit var tvError: TextView
    private lateinit var busyOverlay: View
    private lateinit var tvBusy: TextView

    private var stores: List<Store> = emptyList()
    private var pickupAreas: List<Area> = emptyList()
    private var editingStorePK: String = "" // Firebase push key of the store being edited; blank = creating new
    private var selectedAreaId: String = ""
    private var selectedAreaName: String = ""

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_stores, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listContainer = view.findViewById(R.id.storeListContainer)
        tvEmpty = view.findViewById(R.id.tvStoreEmpty)
        inlinePanel = view.findViewById(R.id.inlineCreateStorePanel)
        tvPanelTitle = view.findViewById(R.id.tvCreateStoreTitle)
        etStoreId = view.findViewById(R.id.etStoreId)
        etStoreName = view.findViewById(R.id.etStoreName)
        etStoreAddress = view.findViewById(R.id.etStoreAddress)
        layoutStoreArea = view.findViewById(R.id.layoutStoreArea)
        tvStoreAreaSelected = view.findViewById(R.id.tvStoreAreaSelected)
        etStorePhone = view.findViewById(R.id.etStorePhone)
        etStoreConveyanceAmount = view.findViewById(R.id.etStoreConveyanceAmount)
        tvError = view.findViewById(R.id.tvCreateStoreError)
        busyOverlay = view.findViewById(R.id.storeBusyOverlay)
        tvBusy = view.findViewById(R.id.tvStoreBusy)

        view.findViewById<View>(R.id.btnOpenCreateStore).setOnClickListener { openCreatePanel() }
        view.findViewById<View>(R.id.btnCancelCreateStore).setOnClickListener { closePanel() }
        view.findViewById<View>(R.id.btnSaveStore).setOnClickListener { saveStore() }
        layoutStoreArea.setOnClickListener { showAreaPicker() }

        loadPickupAreas()
        loadStores()
    }

    private fun setBusy(busy: Boolean, message: String = "Loading...") {
        busyOverlay.isVisible = busy
        tvBusy.text = message
    }

    private fun loadPickupAreas() {
        lifecycleScope.launch {
            try {
                // Pickup areas come from Supabase now (public.areas, pickup
                // usage across the admin's branches) — same directory the
                // claim form reads. A store's area is a display snapshot.
                pickupAreas = SupabaseClaimsReader.fetchAreas(
                    branchIds = RbacManager.current.branchIds,
                    usages = listOf("pickup"),
                ).sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load pickup areas: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadStores() {
        setBusy(true, "Loading stores...")
        lifecycleScope.launch {
            try {
                // Store directory lives in Supabase now (public.stores) —
                // same screen the request form's picker reads from.
                stores = SupabaseClaimsReader.fetchStores()
                renderList()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load stores: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        tvEmpty.isVisible = stores.isEmpty()

        stores.forEach { store ->
            val row = layoutInflater.inflate(R.layout.item_store_row, listContainer, false)
            row.findViewById<TextView>(R.id.tvStoreName).text = store.name
            val subtitleParts = listOfNotNull(
                store.storeId.takeIf { it.isNotBlank() },
                store.areaName.takeIf { it.isNotBlank() },
                store.conveyanceAmount.takeIf { it > 0 }?.let { "৳${it.toLong()}" }
            )
            row.findViewById<TextView>(R.id.tvStoreSubtitle).text = subtitleParts.joinToString(" · ")
            row.findViewById<View>(R.id.btnEditStore).setOnClickListener { openEditPanel(store) }
            row.findViewById<View>(R.id.btnDeleteStore).setOnClickListener { confirmDelete(store) }
            listContainer.addView(row)
        }
    }

    private fun openCreatePanel() {
        editingStorePK = ""
        tvPanelTitle.text = "+ New Store"
        etStoreId.setText("")
        etStoreName.setText("")
        etStoreAddress.setText("")
        etStorePhone.setText("")
        etStoreConveyanceAmount.setText("")
        clearAreaSelection()
        tvError.isVisible = false
        inlinePanel.isVisible = true
    }

    private fun openEditPanel(store: Store) {
        editingStorePK = store.id
        tvPanelTitle.text = "Edit Store"
        etStoreId.setText(store.storeId)
        etStoreName.setText(store.name)
        etStoreAddress.setText(store.address)
        etStorePhone.setText(store.phone)
        etStoreConveyanceAmount.setText(
            if (store.conveyanceAmount > 0) store.conveyanceAmount.toLong().toString() else "")
        if (store.areaId.isNotBlank()) {
            selectedAreaId = store.areaId
            selectedAreaName = store.areaName
            tvStoreAreaSelected.text = store.areaName.ifBlank { "Selected area" }
            tvStoreAreaSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
        } else {
            clearAreaSelection()
        }
        tvError.isVisible = false
        inlinePanel.isVisible = true
    }

    private fun clearAreaSelection() {
        selectedAreaId = ""
        selectedAreaName = ""
        tvStoreAreaSelected.text = "Select Area"
        tvStoreAreaSelected.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
    }

    private fun closePanel() {
        inlinePanel.isVisible = false
        editingStorePK = ""
    }

    private fun showAreaPicker() {
        if (pickupAreas.isEmpty()) {
            Toast.makeText(requireContext(), "No pickup areas configured yet — add one in the Areas tab first", Toast.LENGTH_LONG).show()
            return
        }
        // Label with zone where present — same area name can exist in several
        // branches. Saves the human area_id (not the row id): the claim form
        // matches store areas against the area directory by area_id.
        // Deduped by name+type: cross-branch copies collapse, while a Pickup
        // twin and a Delivery twin sharing a name stay as two tagged entries.
        val options = dedupeAreasForPicker(pickupAreas)
        val multiNames = options.groupingBy { it.name.lowercase() }.eachCount()
            .filterValues { it > 1 }.keys
        val labels = options.map { areaPickerLabel(it, it.name.lowercase() in multiNames) }.toTypedArray()
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Select Area")
            .setItems(labels) { _, index ->
                selectedAreaId = options[index].areaId
                selectedAreaName = options[index].name
                tvStoreAreaSelected.text = selectedAreaName
                tvStoreAreaSelected.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            }
            .show()
    }

    private fun saveStore() {
        val storeId = etStoreId.text?.toString()?.trim().orEmpty()
        val name = etStoreName.text?.toString()?.trim().orEmpty()
        val address = etStoreAddress.text?.toString()?.trim().orEmpty()
        val phone = etStorePhone.text?.toString()?.trim().orEmpty()
        // Fixed pickup conveyance payout (optional): blank/0 = not set —
        // the request form then keeps old behavior for this store.
        val conveyanceAmount = etStoreConveyanceAmount.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0

        if (storeId.isBlank()) {
            tvError.text = "Enter a store ID"
            tvError.isVisible = true
            return
        }
        if (name.isBlank()) {
            tvError.text = "Enter a store name"
            tvError.isVisible = true
            return
        }
        if (address.isBlank()) {
            tvError.text = "Enter a store address"
            tvError.isVisible = true
            return
        }
        if (selectedAreaId.isBlank()) {
            tvError.text = "Select a store area"
            tvError.isVisible = true
            return
        }
        if (phone.isBlank()) {
            tvError.text = "Enter a store phone number"
            tvError.isVisible = true
            return
        }
        // Guard against accidental duplicate entries by Store ID
        // (case-insensitive) -- Store ID is the human-facing identifier
        // field/ops staff actually use, so that's the meaningful
        // uniqueness check here, not the Firebase push key or the name
        // (two different stores can share a name across areas).
        val duplicate = stores.any { it.storeId.equals(storeId, ignoreCase = true) && it.id != editingStorePK }
        if (duplicate) {
            tvError.text = "A store with this Store ID already exists"
            tvError.isVisible = true
            return
        }
        tvError.isVisible = false

        setBusy(true, "Saving...")
        lifecycleScope.launch {
            try {
                // Store directory persists ONLY to Supabase now (store_upsert,
                // admin/manager-gated server-side) — the old Firebase
                // courier/stores write is removed, same as the branch cutover.
                val store = Store(
                    id = storeId,
                    storeId = storeId,
                    name = name,
                    address = address,
                    areaId = selectedAreaId,
                    areaName = selectedAreaName,
                    phone = phone,
                    conveyanceAmount = conveyanceAmount
                )
                SupabaseStoreWriter.save(store)
                closePanel()
                loadStores()
            } catch (e: Exception) {
                setBusy(false)
                tvError.text = "Save failed: ${e.message}"
                tvError.isVisible = true
            }
        }
    }

    private fun confirmDelete(store: Store) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete ${store.name}?")
            .setMessage("This removes the store from the Pickup category picker. Existing requests that already reference it are unaffected.")
            .setPositiveButton("Delete") { _, _ -> deleteStore(store) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteStore(store: Store) {
        setBusy(true, "Deleting...")
        lifecycleScope.launch {
            try {
                SupabaseStoreWriter.delete(store.storeId)
                loadStores()
            } catch (e: Exception) {
                setBusy(false)
                Toast.makeText(requireContext(), "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
