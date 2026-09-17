package com.cloudx.databridge

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Recording Cleanup — admin panel for freeing R2 space. Manual only, no
 * auto-delete anywhere: the admin picks a DATE RANGE, sees exactly what
 * matches (count + total MB), gets an undo-nei warning, then bulk-deletes.
 * Per-row ✕ works anytime. Each delete removes the R2 object first
 * (best-effort presigned DELETE) and then the call_recordings row — a row
 * failure keeps the row so the file never becomes an invisible orphan.
 *
 * Access via drawer, gated by "nav_recording_cleanup" (Access Manager).
 */
class RecordingCleanupFragment : Fragment() {

    data class Row(
        val id: String,
        val consignment: String,
        val dateMs: Long,
        val durationSec: Int,
        val sizeBytes: Long,
        val author: String,
        val r2Key: String,
    )

    private lateinit var tvStats: TextView
    private lateinit var tvOldest: TextView
    private lateinit var btnFrom: TextView
    private lateinit var btnTo: TextView
    private lateinit var tvRangeInfo: TextView
    private lateinit var btnDeleteAll: TextView
    private lateinit var pb: ProgressBar
    private lateinit var tvEmpty: TextView
    private lateinit var rv: RecyclerView
    private lateinit var adapter: CleanupAdapter

    private var fromMs: Long = 0L
    private var toMs: Long = 0L
    private var rows: List<Row> = emptyList()
    private var busy = false

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_recording_cleanup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvStats = view.findViewById(R.id.tvCleanupStats)
        tvOldest = view.findViewById(R.id.tvCleanupOldest)
        btnFrom = view.findViewById(R.id.btnCleanupFrom)
        btnTo = view.findViewById(R.id.btnCleanupTo)
        tvRangeInfo = view.findViewById(R.id.tvCleanupRangeInfo)
        btnDeleteAll = view.findViewById(R.id.btnCleanupDeleteAll)
        pb = view.findViewById(R.id.pbCleanup)
        tvEmpty = view.findViewById(R.id.tvCleanupEmpty)
        rv = view.findViewById(R.id.rvCleanup)

        adapter = CleanupAdapter { row -> confirmDeleteOne(row) }
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter

        // Default range: last 90 days → now (Dhaka day boundaries).
        val now = System.currentTimeMillis()
        toMs = DhakaTime.dayEndMillis(now)
        fromMs = DhakaTime.dayStartMillis(now - 90L * 24 * 60 * 60 * 1000)
        refreshDateLabels()

        btnFrom.setOnClickListener { pickDate(isFrom = true) }
        btnTo.setOnClickListener { pickDate(isFrom = false) }
        btnDeleteAll.setOnClickListener { confirmDeleteAll() }

        loadStats()
        loadRange()
    }

    private fun refreshDateLabels() {
        val fmt = DhakaTime.sdf("dd MMM yyyy")
        btnFrom.text = "From: ${fmt.format(java.util.Date(fromMs))}"
        btnTo.text = "To: ${fmt.format(java.util.Date(toMs))}"
    }

    private fun pickDate(isFrom: Boolean) {
        if (busy) return
        val cal = DhakaTime.calendar().apply { timeInMillis = if (isFrom) fromMs else toMs }
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                val picked = DhakaTime.calendar().apply {
                    set(y, m, d)
                    if (isFrom) {
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    } else {
                        set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59)
                        set(Calendar.SECOND, 59); set(Calendar.MILLISECOND, 999)
                    }
                }.timeInMillis
                if (isFrom) fromMs = picked else toMs = picked
                if (fromMs > toMs) {
                    Toast.makeText(requireContext(), "From date To-er pore hote parbe na", Toast.LENGTH_SHORT).show()
                    if (isFrom) fromMs = toMs else toMs = fromMs
                }
                refreshDateLabels()
                loadRange()
            },
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val s = withContext(Dispatchers.IO) {
                SupabaseCallRecordings.fetchStats("RecordingCleanup")
            }
            if (!isAdded) return@launch
            tvStats.text = "${s.count} recordings · ${JourneyLogUi.formatBytes(s.bytes)}"
            tvOldest.text = if (s.count == 0) "Kono recording nei."
            else "Oldest: ${JourneyLogUi.dayLabel(s.oldestMs)} · Newest: ${JourneyLogUi.dayLabel(s.newestMs)}"
        }
    }

    private fun loadRange() {
        if (busy) return
        pb.visibility = View.VISIBLE
        tvEmpty.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            val raw = withContext(Dispatchers.IO) {
                SupabaseCallRecordings.fetchRange(fromMs, toMs, 500, "RecordingCleanup")
            }
            // Best-effort author names (system_id → display name).
            val mapped = withContext(Dispatchers.IO) {
                raw.map { r ->
                    val sysId = r.optString("author_system_id").trim()
                    val name = if (sysId.isBlank()) "" else {
                        runCatching { UserNameResolver.resolveNameBySystemId(sysId) }.getOrNull().orEmpty()
                    }
                    Row(
                        id = r.optString("id"),
                        consignment = r.optString("consignment"),
                        dateMs = SupabaseRemarkValidationWriter.parseCreatedAtMillis(r.optString("created_at")),
                        durationSec = r.optInt("duration_sec"),
                        sizeBytes = r.optLong("file_size_bytes"),
                        author = name.ifBlank { sysId },
                        r2Key = r.optString("r2_key").trim(),
                    )
                }
            }
            if (!isAdded) return@launch
            pb.visibility = View.GONE
            rows = mapped
            adapter.submitList(mapped)
            val totalBytes = mapped.sumOf { it.sizeBytes }
            tvRangeInfo.text = if (mapped.isEmpty()) "Ei range-e kono recording nei."
            else "${mapped.size} recordings · ${JourneyLogUi.formatBytes(totalBytes)}" +
                (if (raw.size >= 500) " (showing first 500)" else "")
            tvEmpty.visibility = if (mapped.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun confirmDeleteOne(row: Row) {
        if (busy) return
        AlertDialog.Builder(requireContext())
            .setTitle("Delete recording?")
            .setMessage("${row.consignment}\n${JourneyLogUi.formatEpochFull(row.dateMs)} · ${JourneyLogUi.formatBytes(row.sizeBytes)}\n\nProof hisebe ar thakbe na — undo nei.")
            .setPositiveButton("Delete") { _, _ -> deleteRows(listOf(row)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteAll() {
        if (busy || rows.isEmpty()) return
        val totalBytes = rows.sumOf { it.sizeBytes }
        AlertDialog.Builder(requireContext())
            .setTitle("⚠ Delete ALL in range?")
            .setMessage("${rows.size} recordings · ${JourneyLogUi.formatBytes(totalBytes)} muche jabe.\n\nProof hisebe ar thakbe na — undo nei. Nischit?")
            .setPositiveButton("Yes, delete all") { _, _ -> deleteRows(rows.toList()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteRows(targets: List<Row>) {
        if (busy || targets.isEmpty()) return
        busy = true
        btnDeleteAll.isEnabled = false
        pb.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            var ok = 0
            var failed = 0
            for ((i, row) in targets.withIndex()) {
                if (!isAdded) break
                btnDeleteAll.text = "Deleting ${i + 1}/${targets.size}…"
                // R2 first (best-effort), row second — a row failure keeps the
                // row so the file never becomes an invisible orphan.
                if (row.r2Key.isNotBlank()) {
                    withContext(Dispatchers.IO) {
                        runCatching { AttachmentUploader.deleteObject(row.r2Key) }
                    }
                }
                val rowOk = withContext(Dispatchers.IO) {
                    SupabaseCallRecordings.deleteRow(row.id, "RecordingCleanup")
                }
                if (rowOk) ok++ else failed++
            }
            if (!isAdded) return@launch
            busy = false
            btnDeleteAll.isEnabled = true
            btnDeleteAll.text = "Delete all"
            pb.visibility = View.GONE
            Toast.makeText(
                requireContext(),
                if (failed == 0) "✅ $ok deleted, ${JourneyLogUi.formatBytes(targets.sumOf { it.sizeBytes })} freed"
                else "⚠ $ok deleted, $failed failed — try again",
                Toast.LENGTH_LONG
            ).show()
            loadStats()
            loadRange()
        }
    }

    class CleanupAdapter(
        private val onDelete: (Row) -> Unit,
    ) : ListAdapter<Row, CleanupAdapter.Holder>(Diff()) {

        class Diff : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
            override fun areContentsTheSame(a: Row, b: Row) = a == b
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val tvParcel: TextView = v.findViewById(R.id.tvCleanupRowParcel)
            val tvMeta: TextView = v.findViewById(R.id.tvCleanupRowMeta)
            val tvSize: TextView = v.findViewById(R.id.tvCleanupRowSize)
            val btnDelete: TextView = v.findViewById(R.id.btnCleanupRowDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_cleanup_recording, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            holder.tvParcel.text = row.consignment.ifBlank { row.id.take(8) }
            val dur = if (row.durationSec > 0) " · ${JourneyLogUi.formatDurationSec(row.durationSec)}" else ""
            holder.tvMeta.text = "${JourneyLogUi.formatEpochFull(row.dateMs)}$dur" +
                (if (row.author.isNotBlank()) " · ${row.author}" else "")
            holder.tvSize.text = JourneyLogUi.formatBytes(row.sizeBytes)
            holder.btnDelete.setOnClickListener { onDelete(row) }
        }
    }
}
