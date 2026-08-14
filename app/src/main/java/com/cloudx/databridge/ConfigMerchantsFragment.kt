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
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Config — Merchants tab. Manages courier/merchants, the directory Petty
 * Cash's Request Create screen reads from when category == Pickup (see
 * PettyCashRequestCreateFragment.loadMerchants()). Firebase rules restrict
 * writes here to admin/manager (see database.rules.json), matching who
 * can reach the Config screen at all — this fragment doesn't duplicate
 * that check, since a non-privileged user attempting to write would just
 * get rejected by the rules regardless of what this screen shows them.
 *
 * Follows the same inline-panel add/edit pattern as ConfigStatusesFragment,
 * simplified since a merchant is just a name — no multi-language fields,
 * priority, sort order, or color to manage.
 */
class ConfigMerchantsFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()

    private lateinit var listContainer: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var inlinePanel: View
    private lateinit var tvPanelTitle: TextView
    private lateinit var etName: EditText
    private lateinit var tvError: TextView
    private lateinit var busyOverlay: View
    private lateinit var tvBusy: TextView

    private var merchants: List<Merchant> = emptyList()
    private var editingMerchantId: String = "" // blank = creating new

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_config_merchants, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listContainer = view.findViewById(R.id.merchantListContainer)
        tvEmpty = view.findViewById(R.id.tvMerchantEmpty)
        inlinePanel = view.findViewById(R.id.inlineCreateMerchantPanel)
        tvPanelTitle = view.findViewById(R.id.tvCreateMerchantTitle)
        etName = view.findViewById(R.id.etMerchantName)
        tvError = view.findViewById(R.id.tvCreateMerchantError)
        busyOverlay = view.findViewById(R.id.merchantBusyOverlay)
        tvBusy = view.findViewById(R.id.tvMerchantBusy)

        view.findViewById<View>(R.id.btnOpenCreateMerchant).setOnClickListener { openCreatePanel() }
        view.findViewById<View>(R.id.btnCancelCreateMerchant).setOnClickListener { closePanel() }
        view.findViewById<View>(R.id.btnSaveMerchant).setOnClickListener { saveMerchant() }

        loadMerchants()
    }

    private fun setBusy(busy: Boolean, message: String = "Loading...") {
        busyOverlay.isVisible = busy
        tvBusy.text = message
    }

    private fun loadMerchants() {
        setBusy(true, "Loading merchants...")
        lifecycleScope.launch {
            try {
                val snap = db.reference.child(FirebasePaths.merchants()).get().await()
                merchants = snap.children
                    .mapNotNull { it.getValue(Merchant::class.java)?.copy(id = it.key.orEmpty()) }
                    .sortedBy { it.name.lowercase() }
                renderList()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Failed to load merchants: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                setBusy(false)
            }
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        tvEmpty.isVisible = merchants.isEmpty()

        merchants.forEach { merchant ->
            val row = layoutInflater.inflate(R.layout.item_merchant_row, listContainer, false)
            row.findViewById<TextView>(R.id.tvMerchantName).text = merchant.name
            row.findViewById<View>(R.id.btnEditMerchant).setOnClickListener { openEditPanel(merchant) }
            row.findViewById<View>(R.id.btnDeleteMerchant).setOnClickListener { confirmDelete(merchant) }
            listContainer.addView(row)
        }
    }

    private fun openCreatePanel() {
        editingMerchantId = ""
        tvPanelTitle.text = "+ New Merchant"
        etName.setText("")
        tvError.isVisible = false
        inlinePanel.isVisible = true
    }

    private fun openEditPanel(merchant: Merchant) {
        editingMerchantId = merchant.id
        tvPanelTitle.text = "Edit Merchant"
        etName.setText(merchant.name)
        tvError.isVisible = false
        inlinePanel.isVisible = true
    }

    private fun closePanel() {
        inlinePanel.isVisible = false
        editingMerchantId = ""
    }

    private fun saveMerchant() {
        val name = etName.text?.toString()?.trim().orEmpty()
        if (name.isBlank()) {
            tvError.text = "Enter a merchant name"
            tvError.isVisible = true
            return
        }
        // Guard against accidental duplicate entries (case-insensitive),
        // excluding the merchant currently being edited from the check.
        val duplicate = merchants.any { it.name.equals(name, ignoreCase = true) && it.id != editingMerchantId }
        if (duplicate) {
            tvError.text = "A merchant with this name already exists"
            tvError.isVisible = true
            return
        }
        tvError.isVisible = false

        setBusy(true, "Saving...")
        lifecycleScope.launch {
            try {
                val id = editingMerchantId.ifBlank { db.reference.child(FirebasePaths.merchants()).push().key.orEmpty() }
                if (id.isBlank()) throw IllegalStateException("Could not generate merchant id")
                db.reference.child(FirebasePaths.merchant(id)).setValue(Merchant(id = id, name = name)).await()
                closePanel()
                loadMerchants()
            } catch (e: Exception) {
                setBusy(false)
                tvError.text = "Save failed: ${e.message}"
                tvError.isVisible = true
            }
        }
    }

    private fun confirmDelete(merchant: Merchant) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete ${merchant.name}?")
            .setMessage("This removes the merchant from the Pickup category picker. Existing requests that already reference it are unaffected.")
            .setPositiveButton("Delete") { _, _ -> deleteMerchant(merchant) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteMerchant(merchant: Merchant) {
        setBusy(true, "Deleting...")
        lifecycleScope.launch {
            try {
                db.reference.child(FirebasePaths.merchant(merchant.id)).removeValue().await()
                loadMerchants()
            } catch (e: Exception) {
                setBusy(false)
                Toast.makeText(requireContext(), "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
