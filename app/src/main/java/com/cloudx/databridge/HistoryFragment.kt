package com.cloudx.databridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class HistoryFragment : Fragment() {

    private var _binding: View? = null
    private val binding get() = _binding!!

    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvEmptyState: TextView
    private lateinit var coordinatorLayout: CoordinatorLayout
    private lateinit var adapter: HistoryAdapter
    private lateinit var repository: CallRepository
    private lateinit var appPrefs: AppPreferences
    private lateinit var historyFetcher: UnifiedHistoryFetcher
    private lateinit var tvSortToggle: TextView
    private lateinit var tvShowActionsToggle: TextView
    private lateinit var etHistorySearch: EditText
    private var sortDescending = true
    private var showActionsEnabled = false
    private var searchQuery = ""
    private val auth = FirebaseAuth.getInstance()

    private val recordsCache = mutableMapOf<String, CallRecord>()
    private val actionsListenerRefs = mutableMapOf<String, Pair<ValueEventListener, String>>()
    // auto-copy: skip first batch (existing records), only copy truly new arrivals
    private var isInitialLoad = true

    private val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        val uid = firebaseAuth.currentUser?.uid
        lifecycleScope.launch {
            val extId = appPrefs.getCurrentExtensionId()
            Log.d("HistoryFragment", "authStateChanged: uid=$uid extId=$extId")
            historyFetcher.startFetching(uid, extId)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = inflater.inflate(R.layout.fragment_history, container, false)
        return binding
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initViews()
        setupRecyclerView()
        setupSwipeToDelete()
        setupPullToRefresh()
        setupSortToggle()
        setupShowActionsToggle()
        setupSearchBox()
        observeHistoryRealtime()
    }

    private fun initViews() {
        swipeRefreshLayout = binding.findViewById(R.id.swipeRefreshLayout)
        coordinatorLayout = binding.findViewById(R.id.coordinatorLayout)
        recyclerView = binding.findViewById(R.id.rvHistory)
        progressBar = binding.findViewById(R.id.pbLoading)
        tvEmptyState = binding.findViewById(R.id.tvEmptyState)
        tvSortToggle = binding.findViewById(R.id.tvSortToggle)
        tvShowActionsToggle = binding.findViewById(R.id.tvShowActionsToggle)
        etHistorySearch = binding.findViewById(R.id.etHistorySearch)
        val database = CallDatabase.getDatabase(requireContext())
        repository = CallRepository(database.callDao())
        appPrefs = AppPreferences(requireContext())
        historyFetcher = UnifiedHistoryFetcher()
    }

    private fun setupRecyclerView() {
        adapter = HistoryAdapter(object : HistoryAdapter.HistoryCallback {
            override fun onCopy(text: String, record: CallRecord) = copyToClipboard(text, record)
            override fun onDial(cleaned: String, record: CallRecord) = dialNumber(cleaned, record)
            override fun onRemark(record: CallRecord) = saveRemark(record)
            override fun onDelete(record: CallRecord) = deleteRecord(record)
            override fun onShowActions(record: CallRecord) = showActionLog(record)
        })
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    private fun setupSwipeToDelete() {
        val swipeHandler = object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val item = adapter.currentList[position]
                    if (item is HistoryItem.RecordItem) deleteRecordWithUndo(item.record)
                }
            }
            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean
            ) {
                val itemView = viewHolder.itemView
                val background = ColorDrawable(Color.parseColor("#FF4444"))
                if (dX > 0) background.setBounds(itemView.left, itemView.top, itemView.left + dX.toInt(), itemView.bottom)
                else if (dX < 0) background.setBounds(itemView.right + dX.toInt(), itemView.top, itemView.right, itemView.bottom)
                background.draw(c)
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(swipeHandler).attachToRecyclerView(recyclerView)
    }

    private fun setupPullToRefresh() {
        swipeRefreshLayout.setOnRefreshListener {
            lifecycleScope.launch {
                val uid = auth.currentUser?.uid
                val extId = appPrefs.getCurrentExtensionId()
                historyFetcher.stopFetching()
                historyFetcher.startFetching(uid, extId)
                swipeRefreshLayout.isRefreshing = false
            }
        }
    }

    private fun observeHistoryRealtime() {
        lifecycleScope.launch {
            historyFetcher.recordsFlow.collectLatest { records ->
                mergeRecordsFromFlow(records)
                lifecycleScope.launch {
                    val uid = auth.currentUser?.uid
                    val extId = appPrefs.getCurrentExtensionId()
                    attachActionsListeners(uid, extId)
                }
            }
        }
    }

    // Timestamp when this fragment instance was created — only copy records newer than this
    private val fragmentCreatedAt = System.currentTimeMillis()

    private fun mergeRecordsFromFlow(records: List<CallRecord>) {
        val incomingIds = records.map { it.id }.toSet()

        // Auto-copy: only for records that arrived AFTER this fragment instance was created
        if (!isInitialLoad) {
            val newRecords = records.filter { it.id !in recordsCache }
            if (newRecords.isNotEmpty()) {
                val latest = newRecords.maxByOrNull { it.received_at } ?: newRecords.first()
                // Only copy if record arrived after this fragment was created (not pre-existing)
                if (latest.received_at > fragmentCreatedAt) {
                    autoCopyIfEnabled(latest)
                }
            }
        }

        records.forEach { incoming ->
            val existing = recordsCache[incoming.id]
            val mergedActions = pickActions(incoming.actions, existing?.actions)
            recordsCache[incoming.id] = incoming.copy(actions = mergedActions)
        }
        recordsCache.keys.filter { it !in incomingIds }.forEach { recordsCache.remove(it) }

        isInitialLoad = false  // first batch processed

        val snapshot = recordsCache.values.toList()
        lifecycleScope.launch {
            snapshot.forEach { repository.insertCall(it) }
        }
        refreshUi()
    }

    /** Copy record.cleaned to clipboard if auto_copy setting is ON and record arrived after fragment was created */
    private fun autoCopyIfEnabled(record: CallRecord) {
        if (!isAdded || _binding == null) return
        val autoCopy = requireContext()
            .getSharedPreferences("databridge_toggles", android.content.Context.MODE_PRIVATE)
            .getBoolean("auto_copy", false)
        if (!autoCopy || record.cleaned.isBlank()) return

        val clipboard = requireContext()
            .getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("DataBridge", record.cleaned)
        )
        android.widget.Toast.makeText(
            requireContext(),
            "📋 Copied: ${record.cleaned}",
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun pickActions(incoming: String, cached: String?): String {
        val inc = incoming.trim()
        val cache = cached?.trim().orEmpty()
        if (inc.isNotBlank() && inc != "[]" && inc != "{}") return inc
        if (cache.isNotBlank() && cache != "[]" && cache != "{}") return cache
        return if (inc.isBlank()) ActionsJson.emptyObject() else inc
    }

    private fun setupSortToggle() {
        tvSortToggle.setOnClickListener {
            sortDescending = !sortDescending
            tvSortToggle.text = if (sortDescending) "Newest first ↓" else "Oldest first ↑"
            refreshUi()
        }
    }

    private fun setupShowActionsToggle() {
        tvShowActionsToggle.setOnClickListener {
            showActionsEnabled = !showActionsEnabled
            tvShowActionsToggle.text = if (showActionsEnabled) "Actions ●" else "Actions ○"
            tvShowActionsToggle.setTextColor(
                if (showActionsEnabled)
                    android.graphics.Color.parseColor("#00d4ff")
                else
                    android.graphics.Color.parseColor("#888888")
            )
            adapter.setGlobalShowActions(showActionsEnabled)
        }
    }

    private fun setupSearchBox() {
        etHistorySearch.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString().orEmpty().trim()
                refreshUi()
            }
        })
    }

    private fun refreshUi() {
        if (!isAdded || _binding == null) return
        val q = searchQuery.lowercase()
        val filtered = if (q.isBlank()) recordsCache.values.toList()
        else recordsCache.values.filter {
            it.text.contains(q, ignoreCase = true) ||
            it.cleaned.contains(q, ignoreCase = true)
        }
        val sorted = if (sortDescending) filtered.sortedByDescending { it.received_at }
                     else filtered.sortedBy { it.received_at }
        val finalList = buildSimpleListWithDividers(sorted)
        adapter.submitList(finalList)
        tvEmptyState.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (sorted.isEmpty()) View.GONE else View.VISIBLE
        progressBar.visibility = View.GONE
    }

    private fun attachActionsListeners(uid: String?, extId: String?) {
        detachAllActionsListeners()
        recordsCache.values.forEach { record ->
            val route = DataRouteManager.resolveForExistingRecord(record, uid, extId) ?: return@forEach
            val path = route.actionsPath
            val ref = FirebaseDatabase.getInstance().getReference(path)
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!isAdded) return
                    val jsonStr = ActionsJson.fromSnapshot(snapshot)
                    val updated = (recordsCache[record.id] ?: record).copy(actions = jsonStr)
                    recordsCache[record.id] = updated
                    lifecycleScope.launch {
                        repository.insertCall(updated)
                        refreshUi()
                    }
                }
                override fun onCancelled(error: DatabaseError) {
                    Log.e("HistoryFragment", "Actions listener cancelled: ${error.message}")
                }
            }
            ref.addValueEventListener(listener)
            actionsListenerRefs[record.id] = listener to path
        }
    }

    private fun detachAllActionsListeners() {
        actionsListenerRefs.forEach { (_, pair) ->
            FirebaseDatabase.getInstance().getReference(pair.second).removeEventListener(pair.first)
        }
        actionsListenerRefs.clear()
    }

    private fun buildSimpleListWithDividers(records: List<CallRecord>): List<HistoryItem> {
        if (records.isEmpty()) return emptyList()
        val grouped = records.groupBy { getGroupKey(it.received_at) }
        val result = mutableListOf<HistoryItem>()
        val sortedKeys = if (sortDescending) grouped.keys.sortedDescending() else grouped.keys.sorted()
        sortedKeys.forEach { dateKey ->
            val items = grouped[dateKey] ?: return@forEach
            result.add(HistoryItem.DateDivider(getDateLabel(items.first().received_at)))
            items.forEach { result.add(HistoryItem.RecordItem(it)) }
        }
        return result
    }

    private fun getDateLabel(timestamp: Long): String {
        if (timestamp <= 0) return "Unknown"
        val recordKey   = getGroupKey(timestamp)
        val todayKey    = getGroupKey(System.currentTimeMillis())
        val yCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
        val yesterdayKey = getGroupKey(yCal.timeInMillis)
        return when (recordKey) {
            todayKey     -> "Today"
            yesterdayKey -> "Yesterday"
            else         -> SimpleDateFormat("d MMMM, yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }

    private fun getGroupKey(timestamp: Long): String {
        if (timestamp <= 0) return "1970-01-01"
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))
    }

    private fun copyToClipboard(text: String, record: CallRecord) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("DataBridge", text))
        Toast.makeText(requireContext(), "Copied!", Toast.LENGTH_SHORT).show()
        saveAction(record, "copy", "")
    }

    private fun dialNumber(number: String, record: CallRecord) {
        AutoDialHelper.dial(this, number) // ✅ auto-dial / dialpad / SIM chooser
        saveAction(record, "dial", "")
    }

    private fun saveRemark(record: CallRecord) {
        val remarks = listOf("Will receive parcel", "Requested callback", "Not reachable", "Wrong number")
        AlertDialog.Builder(requireContext())
            .setTitle("Add Remark for ${record.text}")
            .setItems(remarks.toTypedArray()) { _, which -> saveAction(record, "remark", remarks[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun saveAction(record: CallRecord, actionType: String, remarks: String) {
        lifecycleScope.launch {
            try {
                val actionId = IdUtils.generateActionId()
                val remarksText = remarks.ifBlank { "$actionType action" }
                val uid = auth.currentUser?.uid
                val extId = appPrefs.getCurrentExtensionId()
                FirebaseActionSync.saveAction(record, uid, extId, actionId, actionType, remarksText, "app")
                mergeActionLocally(record.id, actionId, actionType, remarksText)
            } catch (e: Exception) {
                Log.e("HistoryFragment", "Action save failed: ${e.message}")
                Toast.makeText(requireContext(), "Action save failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun mergeActionLocally(recordId: String, actionId: String, type: String, remarks: String) {
        val record = recordsCache[recordId] ?: return
        val obj = try {
            JSONObject(record.actions.ifBlank { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }
        obj.put(
            actionId,
            JSONObject().apply {
                put("remarks", remarks)
                put("timestamp", System.currentTimeMillis())
                put("type", type)
                put("source", "app")
            }
        )
        recordsCache[recordId] = record.copy(actions = obj.toString())
        lifecycleScope.launch { repository.insertCall(recordsCache[recordId]!!) }
        refreshUi()
    }

    private fun deleteRecordWithUndo(record: CallRecord) {
        lifecycleScope.launch {
            recordsCache.remove(record.id)
            repository.deleteCall(record)
            refreshUi()
            Snackbar.make(coordinatorLayout, "Item deleted", Snackbar.LENGTH_LONG)
                .setAction("UNDO") {
                    lifecycleScope.launch {
                        recordsCache[record.id] = record
                        repository.insertCall(record)
                        refreshUi()
                    }
                }
                .show()
        }
    }

    private fun deleteRecord(record: CallRecord) {
        lifecycleScope.launch {
            try {
                recordsCache.remove(record.id)
                repository.deleteCall(record)
                val uid = auth.currentUser?.uid
                val extId = appPrefs.getCurrentExtensionId()
                val route = DataRouteManager.resolveForExistingRecord(record, uid, extId) ?: return@launch
                FirebaseDatabase.getInstance().getReference(route.recordPath).removeValue()
                refreshUi()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Delete failed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showActionLog(record: CallRecord) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_action_log, null)
        val rv = dialogView.findViewById<RecyclerView>(R.id.rvActions)
        val pb = dialogView.findViewById<ProgressBar>(R.id.pbLoading)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvEmpty)
        val dialogAdapter = ActionLogAdapter(
            items = emptyList(),
            onEdit = { item -> editAction(record, item) },
            onDelete = { item -> confirmDeleteAction(record, item) }
        )
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = dialogAdapter

        var dialogListener: ValueEventListener? = null
        dialogView.findViewById<TextView>(R.id.tvDialogClose).setOnClickListener { }
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.setOnShowListener {
            dialogView.findViewById<TextView>(R.id.tvDialogClose).setOnClickListener { dialog.dismiss() }
            pb.visibility = View.VISIBLE
            lifecycleScope.launch {
                val uid = auth.currentUser?.uid
                val extId = appPrefs.getCurrentExtensionId()
                val route = DataRouteManager.resolveForExistingRecord(record, uid, extId) ?: return@launch
                val ref = FirebaseDatabase.getInstance().getReference(route.actionsPath)
                dialogListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (!dialog.isShowing || !isAdded) return
                        val list = ActionsJson.parseActionItems(ActionsJson.fromSnapshot(snapshot))
                        dialogView.post {
                            if (!dialog.isShowing) return@post
                            pb.visibility = View.GONE
                            tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                            dialogAdapter.updateList(list)
                        }
                    }
                    override fun onCancelled(error: DatabaseError) {
                        if (!isAdded) return
                        dialogView.post {
                            pb.visibility = View.GONE
                            tvEmpty.text = "Error: ${error.message}"
                            tvEmpty.visibility = View.VISIBLE
                        }
                    }
                }
                ref.addValueEventListener(dialogListener!!)
            }
        }
        dialog.setOnDismissListener {
            val listener = dialogListener
            dialogListener = null
            if (listener != null) {
                lifecycleScope.launch {
                    val uid = auth.currentUser?.uid
                    val extId = appPrefs.getCurrentExtensionId()
                    val route = DataRouteManager.resolveForExistingRecord(record, uid, extId) ?: return@launch
                    FirebaseDatabase.getInstance().getReference(route.actionsPath)
                        .removeEventListener(listener)
                }
            }
        }
        dialog.show()
    }

    private fun editAction(record: CallRecord, item: ActionItem) {
        val input = EditText(requireContext()).apply {
            setText(item.remarks)
            hint = "Remark"
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Edit action")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val uid = auth.currentUser?.uid
                        val extId = appPrefs.getCurrentExtensionId()
                        FirebaseActionSync.updateAction(
                            record, uid, extId, item.id,
                            input.text.toString().trim(), item.type
                        )
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Update failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteAction(record: CallRecord, item: ActionItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("Delete action?")
            .setMessage("${item.type} — ${item.remarks}")
            .setPositiveButton("Delete") { _, _ ->
                lifecycleScope.launch {
                    try {
                        val uid = auth.currentUser?.uid
                        val extId = appPrefs.getCurrentExtensionId()
                        FirebaseActionSync.deleteAction(record, uid, extId, item.id)
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        auth.addAuthStateListener(authStateListener)
    }

    override fun onPause() {
        super.onPause()
        auth.removeAuthStateListener(authStateListener)
        historyFetcher.stopFetching()
        detachAllActionsListeners()
        isInitialLoad = true  // reset so next resume doesn't copy old records
    }

    override fun onDestroyView() {
        super.onDestroyView()
        historyFetcher.stopFetching()
        detachAllActionsListeners()
        recordsCache.clear()
        _binding = null
    }
}

class ActionLogAdapter(
    private var items: List<ActionItem>,
    private val onEdit: (ActionItem) -> Unit,
    private val onDelete: (ActionItem) -> Unit
) : RecyclerView.Adapter<ActionLogAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvAction: TextView = view.findViewById(R.id.tvAction)
        val tvRemark: TextView = view.findViewById(R.id.tvRemark)
        val tvTime: TextView = view.findViewById(R.id.tvTime)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return ViewHolder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_action_log_row, parent, false)
        )
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val action = items[position]
        holder.tvAction.text = "${action.type.uppercase()} • ${action.source}"
        holder.tvRemark.text = if (action.remarks.isNotBlank()) "\"${action.remarks}\"" else "—"
        holder.tvTime.text = SimpleDateFormat("h:mm a · d MMM", Locale.getDefault()).format(Date(action.timestamp))
        holder.itemView.setOnClickListener { onEdit(action) }
        holder.itemView.setOnLongClickListener {
            onDelete(action)
            true
        }
    }

    override fun getItemCount() = items.size

    fun updateList(new: List<ActionItem>) {
        items = new
        notifyDataSetChanged()
    }
}
