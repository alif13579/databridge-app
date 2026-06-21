package com.cloudx.databridge

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.journeyapps.barcodescanner.CaptureActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ConnectFragment : Fragment() {
    private var _binding: View? = null; val binding get() = _binding!!
    private lateinit var appPrefs: AppPreferences
    private val db = FirebaseDatabase.getInstance(); val auth = FirebaseAuth.getInstance()
    private var currentExtensionId: String? = null
    private var isProcessingDisconnect = false

    private lateinit var layoutDisconnected: LinearLayout; lateinit var tvStatus: TextView; lateinit var progressBar: ProgressBar
    private lateinit var layoutQrSection: LinearLayout; lateinit var btnScanQR: Button; lateinit var tvManualToggle: TextView
    private lateinit var layoutManualSection: LinearLayout; lateinit var etExtIdInput: EditText; lateinit var btnConnectManual: Button
    private lateinit var tvQrToggle: TextView; lateinit var layoutConnected: LinearLayout
    private lateinit var tvConnectedName: TextView; lateinit var tvConnectedExtId: TextView; lateinit var btnDisconnect: Button

    private val qrLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val id = res.data?.getStringExtra("SCAN_RESULT")
            if (!id.isNullOrEmpty()) handleExtensionConnection(id) else Toast.makeText(requireContext(), "Invalid QR", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = inflater.inflate(R.layout.fragment_connect, container, false); appPrefs = AppPreferences(requireContext())
        initViews(); setupListeners(); checkSavedState(); return binding
    }

    private fun initViews() {
        layoutDisconnected = binding.findViewById(R.id.layoutDisconnected); tvStatus = binding.findViewById(R.id.tvStatus)
        progressBar = binding.findViewById(R.id.progressBar); layoutQrSection = binding.findViewById(R.id.layoutQrSection)
        btnScanQR = binding.findViewById(R.id.btnScanQR); tvManualToggle = binding.findViewById(R.id.tvManualToggle)
        layoutManualSection = binding.findViewById(R.id.layoutManualSection); etExtIdInput = binding.findViewById(R.id.etUidInput)
        btnConnectManual = binding.findViewById(R.id.btnConnectManual); tvQrToggle = binding.findViewById(R.id.tvQrToggle)
        layoutConnected = binding.findViewById(R.id.layoutConnected); tvConnectedName = binding.findViewById(R.id.tvConnectedName)
        tvConnectedExtId = binding.findViewById(R.id.tvConnectedUid); btnDisconnect = binding.findViewById(R.id.btnDisconnect)
    }

    private fun setupListeners() {
        tvManualToggle.setOnClickListener { layoutQrSection.visibility = View.GONE; layoutManualSection.visibility = View.VISIBLE }
        tvQrToggle.setOnClickListener { layoutManualSection.visibility = View.GONE; layoutQrSection.visibility = View.VISIBLE; etExtIdInput.text.clear() }
        btnScanQR.setOnClickListener { qrLauncher.launch(Intent(requireContext(), CaptureActivity::class.java)) }
        btnConnectManual.setOnClickListener { val id = etExtIdInput.text.toString().trim(); if (id.isNotEmpty()) handleExtensionConnection(id) else Toast.makeText(requireContext(), "Enter ID", Toast.LENGTH_SHORT).show() }
        btnDisconnect.setOnClickListener { disconnectExtension() }
    }

    private fun checkSavedState() {
        viewLifecycleOwner.lifecycleScope.launch {
            val id = appPrefs.getCurrentExtensionId()
            Log.d("ConnectFrag", "checkSavedState: savedId=$id")
            if (!id.isNullOrEmpty()) { currentExtensionId = id; showConnectedScreen(id, "Connected"); (activity as? MainActivity)?.startSessionMonitor(id) }
            else showDisconnectedScreen()
        }
    }

    private fun handleExtensionConnection(extId: String) {
        if (isProcessingDisconnect) return; currentExtensionId = extId
        viewLifecycleOwner.lifecycleScope.launch { appPrefs.setCurrentExtensionId(extId) }
        showLoading(true); tvStatus.text = "Connecting..."

        val now = System.currentTimeMillis(); val user = auth.currentUser; val isPerm = user != null
        val androidId = Settings.Secure.getString(requireContext().contentResolver, Settings.Secure.ANDROID_ID)
        val model = "${Build.MANUFACTURER} ${Build.MODEL}"

        db.reference.child("sessions/$extId/meta").setValue(mapOf(
            "status" to "connected", "type" to if (isPerm) "permanent" else "temporary",
            "user_id" to (user?.uid ?: ""), "android_id" to androidId, "device_info" to model,
            "created_at" to now, "updated_at" to now
        )).addOnSuccessListener {
            if (isPerm) {
                db.reference.child("sessions/$extId/meta").onDisconnect().updateChildren(mapOf("status" to "disconnected", "updated_at" to System.currentTimeMillis()))
                viewLifecycleOwner.lifecycleScope.launch { try { UserRepository(user!!.uid).saveExtensionConnection(extId, "permanent", androidId) } catch (_: Exception) {} }
            } else db.reference.child("sessions/$extId").onDisconnect().removeValue()
            showConnectedScreen(extId, model); (activity as? MainActivity)?.startSessionMonitor(extId); DataBridgeService.start(requireContext())
            showLoading(false); Toast.makeText(requireContext(), "✅ Connected ($model)", Toast.LENGTH_SHORT).show()
            (activity as? MainActivity)?.navigateToHistory()
        }.addOnFailureListener { e -> showError("Failed: ${e.message}") }
    }

    fun onExternalDisconnect() {
        Log.d("ConnectFrag", "onExternalDisconnect called")
        disconnectExtension()
    }

    private fun disconnectExtension() {
        if (isProcessingDisconnect) return; isProcessingDisconnect = true
        (activity as? MainActivity)?.stopSessionMonitor()
        val extId = currentExtensionId ?: run { finishDisconnect(); return }
        finishDisconnect()
        // Background cleanup (lifecycleScope survives view detach)
        lifecycleScope.launch {
            try {
                db.reference.child("sessions/$extId/meta/status").setValue("disconnected").await()
                val type = db.reference.child("sessions/$extId/meta/type").get().await().getValue(String::class.java)
                val uid  = db.reference.child("sessions/$extId/meta/user_id").get().await().getValue(String::class.java)
                if (type == "permanent" && !uid.isNullOrEmpty()) FirebaseContainerManager.verifyAndMigrate(extId, uid)
                else db.reference.child("sessions/$extId").removeValue().await()
            } catch (_: Exception) {
                try { db.reference.child("sessions/$extId").removeValue().await() } catch (_: Exception) {}
            }
        }
    }

    private fun finishDisconnect() {
        lifecycleScope.launch { try { appPrefs.clearExtensionId() } catch (_: Exception) {} }
        DataBridgeService.stop(requireContext()); isProcessingDisconnect = false; currentExtensionId = null
        if (isAdded && _binding != null) { showLoading(false); showDisconnectedScreen() }
    }

    private fun showConnectedScreen(id: String, name: String) {
        layoutDisconnected.visibility = View.GONE; layoutConnected.visibility = View.VISIBLE
        tvConnectedName.text = name; tvConnectedExtId.text = "ID: $id"
        (activity as? MainActivity)?.updateConnectionStatus(true)
    }
    private fun showDisconnectedScreen() {
        layoutConnected.visibility = View.GONE; layoutDisconnected.visibility = View.VISIBLE
        layoutManualSection.visibility = View.GONE; layoutQrSection.visibility = View.VISIBLE
        tvStatus.text = "Disconnected"; tvStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_light))
        etExtIdInput.text.clear(); currentExtensionId = null; showLoading(false)
        (activity as? MainActivity)?.updateConnectionStatus(false)
    }
    private fun showError(msg: String) { tvStatus.text = "Error"; tvStatus.setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_red_light)); Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show(); showLoading(false) }
    private fun showLoading(l: Boolean) { progressBar.visibility = if (l) View.VISIBLE else View.GONE; btnScanQR.isEnabled = !l; btnConnectManual.isEnabled = !l; etExtIdInput.isEnabled = !l; tvManualToggle.isEnabled = !l; tvQrToggle.isEnabled = !l }
    override fun onDestroyView() { super.onDestroyView(); _binding = null }
}