package com.cloudx.databridge

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Check-In — hub check-in/out log. Today this hosts the van arrival/departure
 * log (Supabase public.check_ins); the screen is deliberately named
 * generic so future check-ins (e.g. employee check-in) can live here as
 * sections/tabs without a rename.
 *
 * Top: today's movements for this branch — "Inside now" rows (check-in time +
 * elapsed, each with a Check Out button) above the completed history (in/out
 * + duration). The ＋ Check In button opens the static fleet picker
 * ([VanCatalog]); tapping a van checks it in at the picked date+time
 * (default now, past allowed, future blocked). Check Out opens a date+time
 * confirm (default now, never before its own check-in, never future).
 *
 * Permission-gated drawer entry (nav_checkin, admin-toggleable).
 */
class CheckInFragment : Fragment() {

    private var branchId: String = ""
    private var branchName: String = ""

    private lateinit var layoutInside: LinearLayout
    private lateinit var layoutHistory: LinearLayout
    private lateinit var tvInsideHeader: TextView
    private lateinit var pbLoading: View
    private lateinit var layoutError: View

    private val timeFormat = SimpleDateFormat("hh:mm a", Locale.getDefault())
    private val dayFormat = SimpleDateFormat("dd MMM", Locale.getDefault())

    companion object {
        private const val ARG_BRANCH_ID = "branch_id"
        private const val ARG_BRANCH_NAME = "branch_name"
        fun newInstance(branchId: String, branchName: String = ""): CheckInFragment {
            val f = CheckInFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_BRANCH_NAME, branchName)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_check_in, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        branchName = arguments?.getString(ARG_BRANCH_NAME).orEmpty()

        view.findViewById<TextView>(R.id.tvVanTitle).text =
            if (branchName.isNotBlank()) "Check In — $branchName" else "Check In"
        view.findViewById<View>(R.id.btnVanBack).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        layoutInside = view.findViewById(R.id.layoutVanInside)
        layoutHistory = view.findViewById(R.id.layoutVanHistory)
        tvInsideHeader = view.findViewById(R.id.tvVanInsideHeader)
        pbLoading = view.findViewById(R.id.pbVanLoading)
        layoutError = view.findViewById(R.id.layoutVanError)
        view.findViewById<View>(R.id.btnVanRetry).setOnClickListener {
            if (branchId.isNotBlank()) load()
        }
        view.findViewById<Button>(R.id.btnVanCheckIn).setOnClickListener { showCheckInSheet() }

        if (branchId.isBlank()) {
            showError("No branch assigned to this account")
        } else {
            load()
        }
    }

    private fun load() {
        pbLoading.isVisible = true
        layoutError.isVisible = false
        lifecycleScope.launch {
            runCatching { SupabaseVanMovements.fetchMovements(branchId) }
                .onSuccess { render(it) }
                .onFailure {
                    pbLoading.isVisible = false
                    showError(it.message ?: "Couldn't load van movements")
                }
        }
    }

    private fun showError(message: String) {
        view?.findViewById<TextView>(R.id.tvVanError)?.text = message
        layoutError.isVisible = true
    }

    private fun render(movements: List<VanMovement>) {
        pbLoading.isVisible = false
        layoutError.isVisible = false
        val inside = movements.filter { it.isInside }.sortedByDescending { it.checkInAt }
        val done = movements.filter { !it.isInside }.sortedByDescending { it.checkInAt }

        tvInsideHeader.text = "Inside now (${inside.size})"
        layoutInside.removeAllViews()
        if (inside.isEmpty()) {
            layoutInside.addView(emptyRow("No vans inside right now."))
        } else {
            inside.forEach { layoutInside.addView(insideRow(it)) }
        }

        layoutHistory.removeAllViews()
        if (done.isEmpty()) {
            layoutHistory.addView(emptyRow("No completed visits today yet."))
        } else {
            done.forEach { layoutHistory.addView(historyRow(it)) }
        }
    }

    private fun emptyRow(text: String): TextView = TextView(requireContext()).apply {
        this.text = text
        textSize = 13f
        setTextColor(0xFF94A3B8.toInt())
        setPadding(dp(4), dp(10), dp(4), dp(10))
    }

    /** Open-visit row: van + check-in time + elapsed, with a Check Out button. */
    private fun insideRow(m: VanMovement): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            (layoutParams as? ViewGroup.MarginLayoutParams)?.bottomMargin = dp(8)
        }
        val info = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = "${m.vehicleNumber} · ${m.vehicleType}" +
                (if (m.driverName.isNotBlank()) "\nDriver: ${m.driverName}" else "") +
                "\nIn: ${formatDateTime(m.checkInAt)} · ${elapsedSince(m.checkInAt)} inside"
            textSize = 13f
            setTextColor(0xFF0F172A.toInt())
        }
        val btn = Button(ctx).apply {
            text = "Check Out"
            textSize = 12f
            setOnClickListener { showCheckOutSheet(m) }
        }
        row.addView(info)
        row.addView(btn)
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = dp(8)
        row.layoutParams = lp
        return row
    }

    /** Completed-visit row: in → out + duration. */
    private fun historyRow(m: VanMovement): View {
        val duration = if (m.checkInAt > 0L && m.checkOutAt > m.checkInAt) {
            " · ${formatDuration(m.checkOutAt - m.checkInAt)}"
        } else ""
        return TextView(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            setBackgroundResource(android.R.drawable.dialog_holo_light_frame)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            text = "${m.vehicleNumber} · ${m.vehicleType}" +
                (if (m.driverName.isNotBlank()) " · ${m.driverName}" else "") +
                "\n${formatDateTime(m.checkInAt)} → ${formatDateTime(m.checkOutAt)}$duration" +
                (if (m.note.isNotBlank()) "\n${m.note}" else "")
            textSize = 13f
            setTextColor(0xFF0F172A.toInt())
        }
    }

    /** Fleet picker sheet: date+time (default now, past allowed, future
     *  blocked) + optional driver/note, then tap a van = check in at the
     *  picked moment. Late taps backdate; the future can never be picked. */
    private fun showCheckInSheet() {
        val ctx = requireContext()
        var pickedMillis = System.currentTimeMillis()
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        sheet.addView(TextView(ctx).apply {
            text = "Select Vehicle"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0F172A.toInt())
            setPadding(0, 0, 0, dp(12))
        })
        val tvDateTime = TextView(ctx).apply {
            textSize = 14f
            setTextColor(0xFF0F172A.toInt())
            setPadding(dp(4), dp(4), dp(4), dp(12))
            setOnClickListener { pickDateTime(pickedMillis, maxMillis = System.currentTimeMillis(), minMillis = 0L) { pickedMillis = it; text = formatDateTime(it) } }
        }
        tvDateTime.text = formatDateTime(pickedMillis)
        sheet.addView(TextView(ctx).apply {
            text = "Check-in time (tap to change)"
            textSize = 12f
            setTextColor(0xFF64748B.toInt())
        })
        sheet.addView(tvDateTime)
        val etDriver = EditText(ctx).apply {
            hint = "Driver name (optional)"
            textSize = 14f
            setSingleLine()
        }
        sheet.addView(etDriver)
        val etNote = EditText(ctx).apply {
            hint = "Note (optional)"
            textSize = 14f
            setSingleLine()
        }
        sheet.addView(etNote)

        val dialog = AlertDialog.Builder(ctx).setView(sheet).create()
        VanCatalog.VANS.forEach { van ->
            val row = TextView(ctx).apply {
                text = "🚐 ${van.number} · ${van.type}"
                textSize = 15f
                setTextColor(0xFF0F172A.toInt())
                setPadding(dp(4), dp(14), dp(4), dp(14))
                setOnClickListener {
                    dialog.dismiss()
                    doCheckIn(van, pickedMillis, etDriver.text?.toString()?.trim().orEmpty(), etNote.text?.toString()?.trim().orEmpty())
                }
            }
            sheet.addView(row)
        }
        dialog.show()
    }

    private fun doCheckIn(van: VanCatalog.Van, atMillis: Long, driver: String, note: String) {
        if (atMillis > System.currentTimeMillis() + 60_000L) {
            Toast.makeText(requireContext(), "Check-in time cannot be in the future", Toast.LENGTH_SHORT).show()
            return
        }
        pbLoading.isVisible = true
        lifecycleScope.launch {
            runCatching {
                SupabaseVanMovements.checkIn(branchId, van.number, van.type, driver, note, atMillis)
            }.onSuccess {
                Toast.makeText(requireContext(), "✓ ${van.number} checked in", Toast.LENGTH_SHORT).show()
                load()
            }.onFailure {
                pbLoading.isVisible = false
                Toast.makeText(requireContext(), it.message ?: "Check-in failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Check-out confirm: date+time default now, never before its own
     *  check-in, never future. */
    private fun showCheckOutSheet(m: VanMovement) {
        val ctx = requireContext()
        var pickedMillis = System.currentTimeMillis()
        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }
        sheet.addView(TextView(ctx).apply {
            text = "Check out ${m.vehicleNumber}?"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0F172A.toInt())
            setPadding(0, 0, 0, dp(4))
        })
        sheet.addView(TextView(ctx).apply {
            text = "Checked in: ${formatDateTime(m.checkInAt)}"
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 0, 0, dp(8))
        })
        val tvDateTime = TextView(ctx).apply {
            textSize = 15f
            setTextColor(0xFF0F172A.toInt())
            setPadding(dp(4), dp(8), dp(4), dp(12))
            setOnClickListener {
                pickDateTime(pickedMillis, maxMillis = System.currentTimeMillis(), minMillis = m.checkInAt) { pickedMillis = it; text = formatDateTime(it) }
            }
        }
        tvDateTime.text = formatDateTime(pickedMillis)
        sheet.addView(tvDateTime)
        val dialog = AlertDialog.Builder(ctx).setView(sheet)
            .setPositiveButton("Check Out") { _, _ -> doCheckOut(m, pickedMillis) }
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
    }

    private fun doCheckOut(m: VanMovement, atMillis: Long) {
        if (atMillis < m.checkInAt) {
            Toast.makeText(requireContext(), "Check-out cannot be before check-in", Toast.LENGTH_SHORT).show()
            return
        }
        if (atMillis > System.currentTimeMillis() + 60_000L) {
            Toast.makeText(requireContext(), "Check-out time cannot be in the future", Toast.LENGTH_SHORT).show()
            return
        }
        pbLoading.isVisible = true
        lifecycleScope.launch {
            runCatching { SupabaseVanMovements.checkOut(m.id, atMillis) }
                .onSuccess { load() }
                .onFailure {
                    pbLoading.isVisible = false
                    Toast.makeText(requireContext(), it.message ?: "Check-out failed", Toast.LENGTH_LONG).show()
                }
        }
    }

    /**
     * Date picker followed by time picker, clamped to [minMillis, maxMillis]
     * (0 = no bound on that side). Calls [onPicked] with the combined instant.
     */
    private fun pickDateTime(currentMillis: Long, maxMillis: Long, minMillis: Long, onPicked: (Long) -> Unit) {
        val ctx = requireContext()
        val base = java.util.Calendar.getInstance().apply { timeInMillis = currentMillis }
        android.app.DatePickerDialog(
            ctx,
            { _, y, mo, d ->
                val dayStart = java.util.Calendar.getInstance().apply {
                    set(y, mo, d, 0, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
                }.timeInMillis
                val t = java.util.Calendar.getInstance().apply { timeInMillis = currentMillis }
                android.app.TimePickerDialog(
                    ctx,
                    { _, h, mi ->
                        val picked = dayStart + h * 3_600_000L + mi * 60_000L
                        onPicked(picked.coerceIn(minMillis.takeIf { it > 0L } ?: Long.MIN_VALUE, maxMillis.takeIf { it > 0L } ?: Long.MAX_VALUE))
                    },
                    t.get(java.util.Calendar.HOUR_OF_DAY), t.get(java.util.Calendar.MINUTE), false
                ).show()
            },
            base.get(java.util.Calendar.YEAR), base.get(java.util.Calendar.MONTH), base.get(java.util.Calendar.DAY_OF_MONTH)
        ).apply {
            if (maxMillis > 0L) datePicker.maxDate = maxMillis
            if (minMillis > 0L) datePicker.minDate = minMillis
        }.show()
    }

    private fun formatTime(millis: Long): String =
        if (millis <= 0L) "—" else timeFormat.format(Date(millis))

    private fun formatDateTime(millis: Long): String =
        if (millis <= 0L) "—"
        else java.text.SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(millis))

    private fun elapsedSince(sinceMillis: Long): String {
        if (sinceMillis <= 0L) return "—"
        return formatDuration(System.currentTimeMillis() - sinceMillis)
    }

    private fun formatDuration(millis: Long): String {
        if (millis < 0L) return "—"
        val mins = millis / 60_000L
        if (mins < 60L) return "${mins}m"
        val hours = mins / 60L
        return if (hours < 24L) "${hours}h ${mins % 60L}m" else "${hours / 24L}d ${hours % 24L}h"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
