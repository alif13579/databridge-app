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
 * Van Check-In — hub van arrival/departure log (Supabase public.van_movements).
 *
 * Top: today's movements for this branch — "Inside now" rows (check-in time +
 * elapsed, each with a Check Out button) above the completed history (in/out
 * + duration). The ＋ Check In button opens the static fleet picker
 * ([VanCatalog]); tapping a van checks it in immediately (optional driver +
 * note ride along). A van that's already inside can't check in twice — the
 * server 409-guards it and the DB partial unique index enforces it.
 *
 * Permission-gated drawer entry (nav_van_checkin, admin-toggleable).
 */
class VanCheckInFragment : Fragment() {

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
        fun newInstance(branchId: String, branchName: String = ""): VanCheckInFragment {
            val f = VanCheckInFragment()
            f.arguments = Bundle().apply {
                putString(ARG_BRANCH_ID, branchId)
                putString(ARG_BRANCH_NAME, branchName)
            }
            return f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_van_check_in, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        branchId = arguments?.getString(ARG_BRANCH_ID).orEmpty()
        branchName = arguments?.getString(ARG_BRANCH_NAME).orEmpty()

        view.findViewById<TextView>(R.id.tvVanTitle).text =
            if (branchName.isNotBlank()) "Van Check-In — $branchName" else "Van Check-In"
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
                "\nIn: ${formatTime(m.checkInAt)} · ${elapsedSince(m.checkInAt)} inside"
            textSize = 13f
            setTextColor(0xFF0F172A.toInt())
        }
        val btn = Button(ctx).apply {
            text = "Check Out"
            textSize = 12f
            setOnClickListener {
                isEnabled = false
                lifecycleScope.launch {
                    runCatching { SupabaseVanMovements.checkOut(m.id) }
                        .onSuccess { load() }
                        .onFailure {
                            isEnabled = true
                            Toast.makeText(ctx, it.message ?: "Check-out failed", Toast.LENGTH_SHORT).show()
                        }
                }
            }
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
                "\n${formatTime(m.checkInAt)} → ${formatTime(m.checkOutAt)}$duration" +
                (if (m.note.isNotBlank()) "\n${m.note}" else "")
            textSize = 13f
            setTextColor(0xFF0F172A.toInt())
        }
    }

    /** Fleet picker sheet: tap a van = instant check-in (optional driver + note ride along). */
    private fun showCheckInSheet() {
        val ctx = requireContext()
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
                    doCheckIn(van, etDriver.text?.toString()?.trim().orEmpty(), etNote.text?.toString()?.trim().orEmpty())
                }
            }
            sheet.addView(row)
        }
        dialog.show()
    }

    private fun doCheckIn(van: VanCatalog.Van, driver: String, note: String) {
        pbLoading.isVisible = true
        lifecycleScope.launch {
            runCatching {
                SupabaseVanMovements.checkIn(branchId, van.number, van.type, driver, note)
            }.onSuccess {
                Toast.makeText(requireContext(), "✓ ${van.number} checked in", Toast.LENGTH_SHORT).show()
                load()
            }.onFailure {
                pbLoading.isVisible = false
                Toast.makeText(requireContext(), it.message ?: "Check-in failed", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun formatTime(millis: Long): String =
        if (millis <= 0L) "—" else timeFormat.format(Date(millis))

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
