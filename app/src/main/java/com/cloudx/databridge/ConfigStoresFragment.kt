package com.cloudx.databridge

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
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
 * book entry: name, address, an Area picked from this branch set's Pickup areas
 * (public.areas via SupabaseClaimsReader.fetchAreas — a store is a place a
 * pickup run collects from, so Pickup usage is the correct scope rather
 * than Delivery), and a phone number. The Store ID is automatic (Edge
 * allocates the next number) — staff never type it.
 *
 * Create/edit happens in a popup dialog carrying all the fields (ID shown as
 * Auto on create, read-only on edit), same pattern as ConfigAreasFragment.
 */
class ConfigStoresFragment : Fragment() {

    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var busyOverlay: View
    private lateinit var tvBusy: TextView
    private var storeDialog: AlertDialog? = null

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
        busyOverlay = view.findViewById(R.id.storeBusyOverlay)
        tvBusy = view.findViewById(R.id.tvStoreBusy)

        view.findViewById<View>(R.id.btnOpenCreateStore).setOnClickListener { openCreatePanel() }

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
        selectedAreaId = ""
        selectedAreaName = ""
        showStoreDialog(null)
    }

    private fun openEditPanel(store: Store) {
        selectedAreaId = store.areaId
        selectedAreaName = store.areaName
        showStoreDialog(store)
    }

    private fun closePanel() {
        storeDialog?.dismiss()
        storeDialog = null
        editingStorePK = ""
    }

    /** Create/edit popup carrying every field: ID (Auto on create, read-only on
     *  edit) + Name + Address + Area + Phone + Conveyance amount. Uniqueness is
     *  by name+area now that the ID is system-assigned (two merchants may still
     *  share a name across different areas). */
    private fun showStoreDialog(store: Store?) {
        val ctx = requireContext()
        val isEdit = store != null
        editingStorePK = store?.id.orEmpty()
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()
        fun label(text: String) = TextView(ctx).apply {
            this.text = text
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#64748B"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 8.dp() }
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(), 8.dp(), 24.dp(), 8.dp())
        }
        root.addView(label(if (isEdit) "ID: ${store!!.storeId}  (automatic, locked)"
            else "ID: Auto  (assigned on save)"))
        val etName = EditText(ctx).apply {
            hint = "Store name"
            setText(store?.name.orEmpty())
        }
        root.addView(etName)
        val etAddress = EditText(ctx).apply {
            hint = "Address"
            setText(store?.address.orEmpty())
        }
        root.addView(etAddress)
        root.addView(label("Area"))
        val tvArea = TextView(ctx).apply {
            text = selectedAreaName.ifBlank { "Select Area" }
            textSize = 15f
            setTextColor(android.graphics.Color.parseColor(
                if (selectedAreaName.isBlank()) "#94A3B8" else "#0F172A"))
            setPadding(0, 8.dp(), 0, 8.dp())
        }
        tvArea.setOnClickListener { showAreaPicker(tvArea) }
        root.addView(tvArea)
        val etPhone = EditText(ctx).apply {
            hint = "Phone number"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(store?.phone.orEmpty())
        }
        root.addView(etPhone)
        val etAmount = EditText(ctx).apply {
            hint = "Conveyance amount ৳ (optional)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(if ((store?.conveyanceAmount ?: 0.0) > 0) store!!.conveyanceAmount.toLong().toString() else "")
        }
        root.addView(etAmount)
        val tvErr = TextView(ctx).apply {
            setTextColor(android.graphics.Color.parseColor("#DC2626"))
            textSize = 12f
            visibility = View.GONE
        }
        root.addView(tvErr)
        val scroll = ScrollView(ctx).apply { addView(root) }

        var saving = false
        val dialog = AlertDialog.Builder(ctx)
            .setTitle(if (isEdit) "Edit Store" else "+ New Store")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            val btnSave = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            btnSave.setOnClickListener {
                if (saving) return@setOnClickListener
                val name = etName.text?.toString()?.trim().orEmpty()
                val address = etAddress.text?.toString()?.trim().orEmpty()
                val phone = etPhone.text?.toString()?.trim().orEmpty()
                val conveyanceAmount = etAmount.text?.toString()?.trim()?.toDoubleOrNull() ?: 0.0
                fun fail(msg: String) {
                    tvErr.text = msg
                    tvErr.visibility = View.VISIBLE
                }
                if (name.isBlank()) { fail("Enter a store name"); return@setOnClickListener }
                if (address.isBlank()) { fail("Enter a store address"); return@setOnClickListener }
                if (selectedAreaId.isBlank()) { fail("Select a store area"); return@setOnClickListener }
                if (phone.isBlank()) { fail("Enter a store phone number"); return@setOnClickListener }
                val duplicate = stores.any {
                    it.name.equals(name, ignoreCase = true) && it.areaId == selectedAreaId &&
                        it.id != editingStorePK
                }
                if (duplicate) { fail("This area already has a store with this name"); return@setOnClickListener }
                saving = true
                btnSave.isEnabled = false
                btnSave.text = "Saving…"
                tvErr.visibility = View.GONE
                lifecycleScope.launch {
                    try {
                        SupabaseStoreWriter.save(Store(
                            id = if (isEdit) editingStorePK else "",
                            storeId = if (isEdit) editingStorePK else "",
                            name = name,
                            address = address,
                            areaId = selectedAreaId,
                            areaName = selectedAreaName,
                            phone = phone,
                            conveyanceAmount = conveyanceAmount
                        ))
                        closePanel()
                        loadStores()
                    } catch (e: Exception) {
                        fail("Save failed: ${e.message}")
                        saving = false
                        btnSave.isEnabled = true
                        btnSave.text = "Save"
                    }
                }
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { closePanel() }
        }
        dialog.setOnDismissListener { editingStorePK = ""; storeDialog = null }
        storeDialog = dialog
        dialog.show()
    }

    private fun showAreaPicker(tvArea: TextView) {
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
                tvArea.text = selectedAreaName
                tvArea.setTextColor(android.graphics.Color.parseColor("#0F172A"))
            }
            .show()
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
