package com.cloudx.databridge

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class ScannerFragment : Fragment() {

    private val localItems = mutableListOf<ScanItem>()
    private val uploadedItems = mutableListOf<ScanItem>()

    private var currentTab = ScanTab.NEW_SCAN
    private var isBatchMode = false

    // Date range filter for "All Scans" tab (millis, null = no bound)
    private var filterFromDate: Long? = null
    private var filterToDate: Long? = null

    // UI
    private lateinit var rv: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnUpload: Button
    private lateinit var layoutDateFilter: View
    private lateinit var tvDateFilterLabel: TextView
    private lateinit var btnClearDateFilter: View
    private lateinit var btnExportCsv: View
    private lateinit var btnSingleScan: Button
    private lateinit var btnBatchScan: Button
    private lateinit var btnManual: Button
    private lateinit var tvTotalCount: TextView
    private lateinit var tvCameraCount: TextView
    private lateinit var tvManualCount: TextView
    private lateinit var chipNewScan: TextView
    private lateinit var chipAllScans: TextView
    private lateinit var adapter: ScannerAdapter

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseDatabase.getInstance()

    private val scanLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == android.app.Activity.RESULT_OK) {
            val code = res.data?.getStringExtra("SCAN_RESULT")
            if (!code.isNullOrBlank()) {
                addItem(code, manual = false)
                // Batch mode: auto re-launch after each successful scan
                if (isBatchMode) {
                    launchCamera()
                }
            } else {
                Toast.makeText(requireContext(), "No code found", Toast.LENGTH_SHORT).show()
            }
        } else if (res.resultCode == android.app.Activity.RESULT_CANCELED && isBatchMode) {
            // User pressed back on camera → stop batch mode
            isBatchMode = false
            updateBatchButtonStyle()
            Toast.makeText(requireContext(), "Batch scanning stopped", Toast.LENGTH_SHORT).show()
        }
    }

    enum class ScanTab { NEW_SCAN, ALL_SCANS }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_scanner, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupListeners()
        setupAdapter()
        updateChipStyles()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (currentTab == ScanTab.ALL_SCANS) {
            loadUploadedItems()
        }
    }

    private fun initViews(view: View) {
        rv = view.findViewById(R.id.rvScans)
        tvEmpty = view.findViewById(R.id.tvEmpty)
        btnUpload = view.findViewById(R.id.btnUpload)
        layoutDateFilter = view.findViewById(R.id.layoutDateFilter)
        tvDateFilterLabel = view.findViewById(R.id.tvDateFilterLabel)
        btnClearDateFilter = view.findViewById(R.id.btnClearDateFilter)
        btnExportCsv = view.findViewById(R.id.btnExportCsv)

        layoutDateFilter.setOnClickListener { showDateRangePickerDialog() }
        btnClearDateFilter.setOnClickListener {
            filterFromDate = null
            filterToDate = null
            updateDateFilterLabel()
            render()
        }
        btnExportCsv.setOnClickListener { exportScansToCsv() }
        tvTotalCount = view.findViewById(R.id.tvTotalCount)
        tvCameraCount = view.findViewById(R.id.tvCameraCount)
        tvManualCount = view.findViewById(R.id.tvManualCount)
        btnSingleScan = view.findViewById(R.id.btnSingleScan)
        btnBatchScan = view.findViewById(R.id.btnBatchScan)
        btnManual = view.findViewById(R.id.btnManual)
        chipNewScan = view.findViewById(R.id.chipNewScan)
        chipAllScans = view.findViewById(R.id.chipAllScans)
    }

    private fun setupListeners() {
        btnSingleScan.setOnClickListener {
            isBatchMode = false
            updateBatchButtonStyle()
            launchCamera()
        }
        btnBatchScan.setOnClickListener {
            if (isBatchMode) {
                // Already in batch mode - clicking again stops it
                isBatchMode = false
                updateBatchButtonStyle()
                Toast.makeText(requireContext(), "Batch scanning stopped", Toast.LENGTH_SHORT).show()
            } else {
                isBatchMode = true
                updateBatchButtonStyle()
                launchCamera()
            }
        }
        btnManual.setOnClickListener { showBottomSheetManual() }
        btnUpload.setOnClickListener { uploadAll() }

        chipNewScan.setOnClickListener {
            currentTab = ScanTab.NEW_SCAN
            updateChipStyles()
            render()
        }
        chipAllScans.setOnClickListener {
            currentTab = ScanTab.ALL_SCANS
            updateChipStyles()
            loadUploadedItems()
            render()
        }
    }

    private fun updateBatchButtonStyle() {
        if (isBatchMode) {
            btnBatchScan.text = "⏹ Stop Batch"
            btnBatchScan.setBackgroundResource(R.drawable.btn_delete_scan)
        } else {
            btnBatchScan.text = "⚡ Batch"
            btnBatchScan.setBackgroundResource(R.drawable.btn_mode_batch)
        }
    }

    private fun setupAdapter() {
        adapter = ScannerAdapter(
            onDelete = { item -> handleDelete(item) },
            onEdit = { item -> handleEdit(item) }
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
    }

    private fun updateChipStyles() {
        val isNew = currentTab == ScanTab.NEW_SCAN
        chipNewScan.setBackgroundResource(
            if (isNew) R.drawable.bg_filter_chip_active
            else R.drawable.bg_filter_chip_inactive
        )
        chipNewScan.setTextColor(
            requireContext().getColor(
                if (isNew) android.R.color.white else R.color.theme_text_secondary
            )
        )
        chipAllScans.setBackgroundResource(
            if (!isNew) R.drawable.bg_filter_chip_active_purple
            else R.drawable.bg_filter_chip_inactive
        )
        chipAllScans.setTextColor(
            requireContext().getColor(
                if (!isNew) android.R.color.white else R.color.theme_text_secondary
            )
        )
    }

    // ── Camera ─────────────────────────────────────────────────────────
    private fun launchCamera() {
        try {
            val intent = Intent(requireContext(), com.journeyapps.barcodescanner.CaptureActivity::class.java)
            scanLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Camera error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Bottom Sheet: Manual Entry ─────────────────────────────────────
    private fun showBottomSheetManual() {
        val dialog = BottomSheetDialog(requireContext())
        val root = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_manual_entry, null)
        dialog.setContentView(root)

        val etInput = root.findViewById<EditText>(R.id.etManualCode)
        val btnClose = root.findViewById<View>(R.id.btnManualClose)
        val btnAdd = root.findViewById<Button>(R.id.btnManualAdd)

        btnClose.setOnClickListener { dialog.dismiss() }
        btnAdd.setOnClickListener {
            val value = etInput.text.toString().trim()
            if (value.isNotBlank()) {
                addItem(value, manual = true)
                dialog.dismiss()
            }
        }

        dialog.show()
        etInput.requestFocus()
    }

    // ── Bottom Sheet: Edit Entry ───────────────────────────────────────
    private fun showBottomSheetEdit(item: ScanItem, isUploaded: Boolean) {
        val dialog = BottomSheetDialog(requireContext())
        val root = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_entry, null)
        dialog.setContentView(root)

        val etInput = root.findViewById<EditText>(R.id.etEditCode)
        val btnClose = root.findViewById<View>(R.id.btnEditClose)
        val btnSave = root.findViewById<Button>(R.id.btnEditSave)

        etInput.setText(item.code)
        etInput.setSelection(item.code.length)

        btnClose.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val newCode = etInput.text.toString().trim()
            if (newCode.isNotBlank()) {
                if (isUploaded) {
                    updateItemInFirebase(item, newCode)
                } else {
                    updateLocalItem(item.id, newCode)
                }
                dialog.dismiss()
            }
        }

        dialog.show()
        etInput.requestFocus()
    }

    // ── Core Logic ─────────────────────────────────────────────────────
    private fun addItem(code: String, manual: Boolean) {
        val trimmed = code.trim()

        // Duplicate check
        if (localItems.any { it.code == trimmed }) {
            Toast.makeText(requireContext(), "⚠ Duplicate: $trimmed", Toast.LENGTH_SHORT).show()
            return
        }

        val ts = System.currentTimeMillis()
        val id = ts + (localItems.size * 1000) + (Math.random() * 1000).toLong()
        localItems.add(0, ScanItem(id = id, code = trimmed, scanAt = ts, manual = manual))
        render()

        val modeLabel = if (manual) "✏️" else if (isBatchMode) "⚡" else "◎"
        val action = if (manual) "Added" else if (isBatchMode) "Scanned (batch)" else "Scanned"
        Toast.makeText(requireContext(), "$modeLabel $action: $trimmed", Toast.LENGTH_SHORT).show()
    }

    private fun updateLocalItem(id: Long, newCode: String) {
        val trimmed = newCode.trim()
        val index = localItems.indexOfFirst { it.id == id }
        if (index >= 0) {
            localItems[index] = localItems[index].copy(code = trimmed)
            render()
            Toast.makeText(requireContext(), "✏️ Updated", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeLocalItem(id: Long) {
        localItems.removeAll { it.id == id }
        render()
    }

    private fun handleDelete(item: ScanItem) {
        if (item.uploaded) {
            deleteFromFirebase(item)
        } else {
            removeLocalItem(item.id)
            Toast.makeText(requireContext(), "🗑️ Removed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleEdit(item: ScanItem) {
        showBottomSheetEdit(item, item.uploaded)
    }

    // ── Firebase Operations ────────────────────────────────────────────
    private fun uploadAll() {
        val user = auth.currentUser
        if (user == null) {
            Toast.makeText(requireContext(), "Login required", Toast.LENGTH_LONG).show()
            return
        }
        if (localItems.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing to upload", Toast.LENGTH_SHORT).show()
            return
        }

        btnUpload.isEnabled = false
        btnUpload.text = "⏳ Uploading ${localItems.size} parcels..."

        val updates = hashMapOf<String, Any>()
        localItems.forEach { item ->
            val ts = item.scanAt
            val scanKey = item.id.toString()
            updates[RunRoutePaths.scanItem(user.uid, scanKey)] = mapOf(
                "scan_text" to item.code,
                "scan_at" to ts,
                "manual" to item.manual,
                "user_id" to user.uid,
                "status" to "pending"
            )
        }

        db.reference.updateChildren(updates)
            .addOnSuccessListener {
                val count = localItems.size
                Toast.makeText(requireContext(), "✓ Uploaded $count parcel${if (count > 1) "s" else ""}!", Toast.LENGTH_SHORT).show()
                localItems.clear()
                currentTab = ScanTab.ALL_SCANS
                updateChipStyles()
                btnUpload.visibility = View.GONE
                btnUpload.text = "☁ Upload Parcels"
                render()
                loadUploadedItems()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Upload failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
            .addOnCompleteListener {
                btnUpload.isEnabled = true
            }
    }

    private fun loadUploadedItems() {
        val user = auth.currentUser ?: return
        uploadedItems.clear()

        db.reference.child(RunRoutePaths.userScans(user.uid))
            .get()
            .addOnSuccessListener { snapshot ->
                uploadedItems.clear()
                val tempList = mutableListOf<ScanItem>()
                snapshot.children.forEach { child ->
                    val scanText = child.child("scan_text").getValue(String::class.java) ?: ""
                    val scanAt = child.child("scan_at").getValue(Long::class.java) ?: 0L
                    val manual = child.child("manual").getValue(Boolean::class.java) ?: false
                    if (scanText.isNotBlank()) {
                        tempList.add(
                            ScanItem(
                                id = scanAt,
                                code = scanText,
                                scanAt = scanAt,
                                manual = manual,
                                uploaded = true,
                                firebaseKey = child.key ?: ""
                            )
                        )
                    }
                }
                uploadedItems.addAll(tempList.sortedByDescending { it.scanAt })
                if (currentTab == ScanTab.ALL_SCANS) {
                    render()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Load failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun updateItemInFirebase(item: ScanItem, newCode: String) {
        val user = auth.currentUser ?: return
        val key = item.firebaseKey
        if (key.isBlank()) return

        db.reference.child(RunRoutePaths.scanItem(user.uid, key)).child("scan_text").setValue(newCode.trim())
            .addOnSuccessListener {
                val index = uploadedItems.indexOfFirst { it.firebaseKey == key }
                if (index >= 0) {
                    uploadedItems[index] = uploadedItems[index].copy(code = newCode.trim())
                    render()
                }
                Toast.makeText(requireContext(), "✓ Updated in Firebase", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Update failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun deleteFromFirebase(item: ScanItem) {
        val user = auth.currentUser ?: return
        val key = item.firebaseKey
        if (key.isBlank()) return

        db.reference.child(RunRoutePaths.scanItem(user.uid, key)).removeValue()
            .addOnSuccessListener {
                uploadedItems.removeAll { it.firebaseKey == key }
                render()
                Toast.makeText(requireContext(), "🗑️ Deleted from Firebase", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(requireContext(), "Delete failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    // ── CSV export ────────────────────────────────────────────────────
    private fun exportScansToCsv() {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("CSV Export")
            .setItems(arrayOf("📱 WhatsApp এ পাঠান", "⬇️ Download করুন")) { _, which ->
                if (which == 0) promptWhatsAppNumber() else saveCsvAndDownload()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptWhatsAppNumber() {
        val ctx = requireContext()
        val input = EditText(ctx).apply {
            hint = "01XXXXXXXXX"
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setPadding(40, 24, 40, 24)
        }
        android.app.AlertDialog.Builder(ctx)
            .setTitle("WhatsApp Number")
            .setView(input)
            .setPositiveButton("Send") { _, _ ->
                val raw = input.text.toString().trim()
                if (raw.isBlank()) {
                    Toast.makeText(ctx, "⚠ Number দিন", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val normalized = normalizeWhatsAppNumber(raw)
                saveCsvAndSendToWhatsApp(normalized)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun normalizeWhatsAppNumber(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            digits.startsWith("880") && digits.length == 13 -> digits
            digits.startsWith("0") && digits.length == 11 -> "88$digits"
            digits.length == 10 -> "880$digits"
            else -> digits
        }
    }

    /** Builds the CSV content for the currently filtered scans. Returns null if empty. */
    private fun buildCsvContent(): Pair<String, Int>? {
        val from = filterFromDate
        val to   = filterToDate
        val items = uploadedItems.filter { item ->
            (from == null || item.scanAt >= from) && (to == null || item.scanAt <= to)
        }.sortedBy { it.scanAt }

        if (items.isEmpty()) return null

        val dateFmt = java.text.SimpleDateFormat("dd-MM-yyyy", java.util.Locale.getDefault())
        val timeFmt = java.text.SimpleDateFormat("hh:mm:ss a", java.util.Locale.getDefault())

        val csv = StringBuilder()
        csv.append("Timestamp,Date,Time,Scanned Data\n")
        items.forEach { item ->
            val ts = item.scanAt
            val date = dateFmt.format(java.util.Date(ts))
            val time = timeFmt.format(java.util.Date(ts))
            val code = item.code.replace("\"", "\"\"")
            csv.append("$ts,$date,$time,\"$code\"\n")
        }
        return csv.toString() to items.size
    }

    /** Saves CSV to app-specific cache dir (for sharing via FileProvider) — no storage permission needed. */
    private fun saveCsvToCache(csvContent: String): android.net.Uri? {
        return try {
            val fileName = "DataBridge_Scans_${System.currentTimeMillis()}.csv"
            val cacheDir = java.io.File(requireContext().cacheDir, "exports").apply { mkdirs() }
            val file = java.io.File(cacheDir, fileName)
            file.writeText(csvContent)
            androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCsvAndSendToWhatsApp(phone: String) {
        val (csvContent, count) = buildCsvContent() ?: run {
            Toast.makeText(requireContext(), "⚠ Export করার মতো কোনো scan নেই", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = saveCsvToCache(csvContent) ?: run {
            Toast.makeText(requireContext(), "⚠ File তৈরি করা যায়নি", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra("jid", "$phone@s.whatsapp.net")
                setPackage("com.whatsapp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
            Toast.makeText(requireContext(), "📤 CSV পাঠানো হচ্ছে ($count rows)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "⚠ WhatsApp খোলা যায়নি — installed আছে কিনা দেখুন", Toast.LENGTH_LONG).show()
        }
    }

    private fun saveCsvAndDownload() {
        val (csvContent, count) = buildCsvContent() ?: run {
            Toast.makeText(requireContext(), "⚠ Export করার মতো কোনো scan নেই", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = "DataBridge_Scans_${System.currentTimeMillis()}.csv"
        try {
            val resolver = requireContext().contentResolver
            val uri: android.net.Uri?
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                }
                uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                val file = java.io.File(downloadsDir, fileName)
                uri = android.net.Uri.fromFile(file)
            }
            if (uri == null) {
                Toast.makeText(requireContext(), "⚠ File তৈরি করা যায়নি", Toast.LENGTH_SHORT).show()
                return
            }
            resolver.openOutputStream(uri)?.use { out -> out.write(csvContent.toByteArray()) }
            Toast.makeText(requireContext(), "✅ CSV Downloads এ সেভ হয়েছে ($count rows)", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "⚠ Export failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ── Date range filter ─────────────────────────────────────────────
    private fun showDateRangePickerDialog() {
        val builder = com.google.android.material.datepicker.MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Date range বেছে নিন")

        if (filterFromDate != null && filterToDate != null) {
            builder.setSelection(
                androidx.core.util.Pair(filterFromDate, filterToDate)
            )
        }

        val picker = builder.build()
        picker.addOnPositiveButtonClickListener { selection ->
            // Normalize to full-day bounds: from = start of day, to = end of day
            val cal = java.util.Calendar.getInstance()
            cal.timeInMillis = selection.first
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
            filterFromDate = cal.timeInMillis

            cal.timeInMillis = selection.second
            cal.set(java.util.Calendar.HOUR_OF_DAY, 23); cal.set(java.util.Calendar.MINUTE, 59)
            cal.set(java.util.Calendar.SECOND, 59); cal.set(java.util.Calendar.MILLISECOND, 999)
            filterToDate = cal.timeInMillis

            updateDateFilterLabel()
            render()
        }
        picker.show(parentFragmentManager, "scan_date_range_picker")
    }

    private fun updateDateFilterLabel() {
        val from = filterFromDate
        val to   = filterToDate
        if (from == null || to == null) {
            tvDateFilterLabel.text = "All time"
            btnClearDateFilter.visibility = View.GONE
        } else {
            val fmt = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            tvDateFilterLabel.text = "${fmt.format(java.util.Date(from))} – ${fmt.format(java.util.Date(to))}"
            btnClearDateFilter.visibility = View.VISIBLE
        }
    }

    // ── Render ─────────────────────────────────────────────────────────
    private fun render() {
        val isNewTab = currentTab == ScanTab.NEW_SCAN
        val displayItems: List<ScanItem>

        if (isNewTab) {
            displayItems = localItems.toList()
            btnUpload.visibility = if (localItems.isNotEmpty()) View.VISIBLE else View.GONE
            tvEmpty.text = "📦\n\nNo parcels scanned yet\n\nUse Single, Batch or Manual to begin"
            layoutDateFilter.visibility = View.GONE
            btnExportCsv.visibility = View.GONE
        } else {
            displayItems = uploadedItems.filter { item ->
                val from = filterFromDate
                val to   = filterToDate
                (from == null || item.scanAt >= from) && (to == null || item.scanAt <= to)
            }
            btnUpload.visibility = View.GONE
            tvEmpty.text = if (filterFromDate != null || filterToDate != null)
                "📦\n\nএই সময়সীমায় কোনো scan নেই"
            else
                "📦\n\nNo uploaded scans found"
            layoutDateFilter.visibility = View.VISIBLE
            btnExportCsv.visibility = View.VISIBLE
        }

        adapter.items = displayItems
        tvEmpty.visibility = if (displayItems.isEmpty()) View.VISIBLE else View.GONE

        // Stats respect the date filter when active (All Scans tab), else show lifetime totals.
        val hasDateFilter = filterFromDate != null && filterToDate != null
        val allItems = if (hasDateFilter) {
            val from = filterFromDate!!; val to = filterToDate!!
            (localItems + uploadedItems).filter { it.scanAt in from..to }
        } else {
            localItems + uploadedItems
        }
        tvTotalCount.text = allItems.size.toString()
        tvCameraCount.text = allItems.count { !it.manual }.toString()
        tvManualCount.text = allItems.count { it.manual }.toString()

        if (btnUpload.visibility == View.VISIBLE) {
            btnUpload.text = "☁ Upload ${localItems.size} Parcel${if (localItems.size > 1) "s" else ""}"
        }
    }
}
