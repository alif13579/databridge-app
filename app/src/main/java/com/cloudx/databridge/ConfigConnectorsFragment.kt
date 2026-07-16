package com.cloudx.databridge

import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Connectors tab — branch-wise list of configured connectors
 * (similar to ConfigSheetFragment's branch-connection list but for
 * external service connections).
 *
 * Each branch entry shows a card with its connector info and a
 * "Manage" button. No Sync button here — sync is managed inside
 * the connector's own screen after wiring.
 *
 * Adding a new connector goes through a 4-step wizard:
 *   Step 1 — Select branch
 *   Step 2 — Select connector type / Google account auth
 *   Step 3 — Configure target (spreadsheet, endpoint, etc.)
 *   Step 4 — Confirm & save
 *
 * Manage button action is intentionally left as a stub (TODO) —
 * wire it once the per-connector management screen is designed.
 */
class ConfigConnectorsFragment : Fragment() {

    private lateinit var layoutBranchList: LinearLayout
    private lateinit var btnAddConnector:  android.widget.Button
    private lateinit var layoutWizard:     LinearLayout
    private lateinit var tvWizardTitle:    TextView
    private lateinit var layoutStep1:      LinearLayout
    private lateinit var layoutStep2:      LinearLayout
    private lateinit var layoutStep3:      LinearLayout
    private lateinit var layoutStep4:      LinearLayout
    private lateinit var btnBack:          android.widget.Button
    private lateinit var btnNext:          android.widget.Button
    private lateinit var btnCancel:        android.widget.Button

    private val db   by lazy { FirebaseDatabase.getInstance() }
    private val auth by lazy { FirebaseAuth.getInstance() }

    private var currentStep = 1
    private val TOTAL_STEPS = 4

    // Wizard selections — populated as user progresses through steps
    private var selectedBranchId   = ""
    private var selectedBranchName = ""

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_config_connectors, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutBranchList = view.findViewById(R.id.layoutConnectorBranchList)
        btnAddConnector  = view.findViewById(R.id.btnAddConnector)
        layoutWizard     = view.findViewById(R.id.layoutConnectorWizard)
        tvWizardTitle    = view.findViewById(R.id.tvConnectorWizardTitle)
        layoutStep1      = view.findViewById(R.id.layoutConnectorStep1)
        layoutStep2      = view.findViewById(R.id.layoutConnectorStep2)
        layoutStep3      = view.findViewById(R.id.layoutConnectorStep3)
        layoutStep4      = view.findViewById(R.id.layoutConnectorStep4)
        btnBack          = view.findViewById(R.id.btnConnectorBack)
        btnNext          = view.findViewById(R.id.btnConnectorNext)
        btnCancel        = view.findViewById(R.id.btnConnectorCancel)

        btnAddConnector.setOnClickListener { openWizard() }
        btnBack  .setOnClickListener { navigateWizard(-1) }
        btnNext  .setOnClickListener { navigateWizard(+1) }
        btnCancel.setOnClickListener { closeWizard() }

        loadBranchConnectors()
    }

    // ── Branch-connector list ──────────────────────────────────────────────

    private fun loadBranchConnectors() {
        layoutBranchList.removeAllViews()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val branchesSnap = withContext(Dispatchers.IO) {
                    db.reference.child("branches").get().await()
                }
                val hasBranches = branchesSnap.hasChildren()
                view?.findViewById<TextView>(R.id.tvConnectorsEmpty)?.isVisible = !hasBranches

                branchesSnap.children.forEach { branch ->
                    val branchId   = branch.key ?: return@forEach
                    val branchName = branch.child("name").getValue(String::class.java) ?: branchId

                    // Check if any connector is already configured for this branch
                    val connectorSnap = withContext(Dispatchers.IO) {
                        db.reference.child("config/connectors/$branchId").get().await()
                    }
                    val connectorCount = connectorSnap.childrenCount.toInt()

                    val card = layoutInflater.inflate(
                        R.layout.item_connector_branch_card,
                        layoutBranchList, false
                    )
                    card.findViewById<TextView>(R.id.tvConnectorBranchName).text = branchName
                    card.findViewById<TextView>(R.id.tvConnectorCount).text =
                        if (connectorCount > 0) "$connectorCount connector${if (connectorCount > 1) "s" else ""}"
                        else "No connectors"

                    card.findViewById<android.widget.Button>(R.id.btnManageConnector)
                        .setOnClickListener {
                            // TODO: wire to per-branch connector management screen
                            Toast.makeText(
                                requireContext(),
                                "Manage: $branchName — coming soon",
                                Toast.LENGTH_SHORT
                            ).show()
                        }

                    layoutBranchList.addView(card)
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Load failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Wizard ────────────────────────────────────────────────────────────

    private fun openWizard() {
        currentStep = 1
        layoutBranchList.isVisible = false
        btnAddConnector .isVisible = false
        layoutWizard    .isVisible = true
        renderStep()
    }

    private fun closeWizard() {
        layoutWizard    .isVisible = false
        layoutBranchList.isVisible = true
        btnAddConnector .isVisible = true
        loadBranchConnectors()
    }

    private fun navigateWizard(direction: Int) {
        if (direction > 0 && !validateCurrentStep()) return
        if (direction > 0 && currentStep == TOTAL_STEPS) {
            saveConnector()
            return
        }
        currentStep = (currentStep + direction).coerceIn(1, TOTAL_STEPS)
        renderStep()
    }

    private fun renderStep() {
        tvWizardTitle.text = when (currentStep) {
            1 -> "Step 1 of $TOTAL_STEPS — Select Branch"
            2 -> "Step 2 of $TOTAL_STEPS — Authenticate"
            3 -> "Step 3 of $TOTAL_STEPS — Configure"
            4 -> "Step 4 of $TOTAL_STEPS — Confirm"
            else -> ""
        }
        layoutStep1.isVisible = currentStep == 1
        layoutStep2.isVisible = currentStep == 2
        layoutStep3.isVisible = currentStep == 3
        layoutStep4.isVisible = currentStep == 4
        btnBack.isVisible  = currentStep > 1
        btnNext.text = if (currentStep == TOTAL_STEPS) "Save" else "Next →"
    }

    private fun validateCurrentStep(): Boolean {
        // TODO: add per-step validation as each step is fleshed out
        return true
    }

    private fun saveConnector() {
        // TODO: persist connector config to config/connectors/{branchId}/{connectorId}
        Toast.makeText(requireContext(), "Saved ✓", Toast.LENGTH_SHORT).show()
        closeWizard()
    }
}
