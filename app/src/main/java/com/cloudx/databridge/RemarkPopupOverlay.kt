package com.cloudx.databridge

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.util.Locale

/**
 * Truecaller-style overlay for a NEW phone number synced into History (not an incoming
 * call -- see IncomingCallOverlay for that). Shows customer/parcel info, a Call button,
 * and quick-remark chips + a note field so the agent can save a remark directly from the
 * popup, with no need to open CallCenterFragment at all.
 *
 * Gated entirely by DataBridgeService's caller (nav_call_center permission +
 * "lookup_from_cc" toggle) -- this object assumes both are already true by the time
 * show() is called.
 *
 * Saving requires knowing which worker the parcel is currently assigned to. There's no
 * cheap way to compute that from Firebase route/run data outside CallCenterFragment's own
 * (expensive, multi-step) resolution -- so instead this reads it from Supabase directly:
 * the most recent validations row for the consignment already carries
 * assigned_to_system_id (written by whichever CC/worker action last touched it). If the
 * parcel has never had a remark yet (no row exists), the agent can't be resolved and the
 * popup falls back to a "no agent info -- use Call Center" message instead of a Save
 * button, so nothing gets written with a guessed/wrong agent id.
 */
object RemarkPopupOverlay {

    private val overlayScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var loadJob: Job? = null

    /** [isAutoDialing] controls whether the "Calling…" badge shows and whether the call
     *  is fired automatically -- true when triggered by the auto_dial background path,
     *  false when the agent will tap Call themselves. */
    fun show(context: Context, match: CallerMatch, isAutoDialing: Boolean) {
        val appContext = context.applicationContext
        if (!android.provider.Settings.canDrawOverlays(appContext)) return
        mainHandler.post { showInternal(appContext, match, isAutoDialing) }
    }

    fun dismiss() {
        mainHandler.post { dismissInternal() }
    }

    private fun showInternal(context: Context, match: CallerMatch, isAutoDialing: Boolean) {
        dismissInternal() // never stack two

        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        val view = LayoutInflater.from(context).inflate(R.layout.overlay_history_remark, null)

        view.findViewById<TextView>(R.id.tvHrInitial).text = match.name.trim().firstOrNull()?.uppercase() ?: "?"
        view.findViewById<TextView>(R.id.tvHrName).text = match.name.ifBlank { "Unknown customer" }
        view.findViewById<TextView>(R.id.tvHrPhone).text = match.phone
        view.findViewById<TextView>(R.id.tvHrConsignment).text = match.consignmentId
        view.findViewById<TextView>(R.id.tvHrAddress).text = match.address.ifBlank { "—" }
        val nf = NumberFormat.getNumberInstance(Locale.US)
        view.findViewById<TextView>(R.id.tvHrCod).text = if (match.cod > 0) "৳" + nf.format(match.cod) else "—"
        view.findViewById<TextView>(R.id.tvHrCallingBadge).isVisible = isAutoDialing

        view.findViewById<View>(R.id.btnHrClose).setOnClickListener { dismissInternal() }
        view.findViewById<View>(R.id.btnHrDismiss).setOnClickListener { dismissInternal() }

        val btnCall = view.findViewById<TextView>(R.id.btnHrCall)
        btnCall.setOnClickListener { placeCall(context, match.phone) }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 80
        }

        try {
            wm.addView(view, params)
        } catch (_: Exception) {
            return
        }
        windowManager = wm
        overlayView = view

        // Auto-dial: fire the call immediately, popup stays up alongside it.
        if (isAutoDialing) placeCall(context, match.phone)

        loadJob = overlayScope.launch {
            val (remarkOptions, assignedAgentSystemId) = withContext(Dispatchers.IO) {
                fetchRemarkOptions() to resolveAssignedAgent(match.consignmentId)
            }
            if (overlayView !== view) return@launch // dismissed while loading

            renderRemarkChips(context, view, remarkOptions, match, assignedAgentSystemId)
        }
    }

    private fun placeCall(context: Context, phone: String) {
        val hasCallPerm = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        try {
            if (hasCallPerm) {
                context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            } else {
                context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        } catch (_: Exception) {
        }
    }

    private data class RemarkOption(val label: String, val englishLabel: String, val targetStatus: String)

    /** Same config/remarks_call_center + config/language/ccLang source CallCenterFragment's
     *  loadCcRemarkOptions() reads -- kept intentionally minimal (no templates/priority
     *  sorting) since the popup only needs label + target_status to save a remark. */
    private suspend fun fetchRemarkOptions(): List<RemarkOption> {
        return try {
            val db = com.google.firebase.database.FirebaseDatabase.getInstance().reference
            val langValue = db.child("config/language/ccLang").get().await()
                .getValue(String::class.java)?.trim().orEmpty().ifBlank { "bn_en" }
            val remarkLang = langValue.substringBefore("_").ifBlank { "bn" }
            val remarksSnap = db.child("config/remarks_call_center").get().await()
            val options = mutableListOf<RemarkOption>()
            remarksSnap.children.forEach { groupSnap ->
                groupSnap.children.forEach { r ->
                    val textBn = r.child("text_bn").getValue(String::class.java)?.trim().orEmpty()
                    val textEn = r.child("text_en").getValue(String::class.java)?.trim().orEmpty()
                    val label = if (remarkLang == "en") textEn.ifBlank { textBn } else textBn.ifBlank { textEn }
                    if (label.isBlank()) return@forEach
                    val target = r.child("target_status").getValue(String::class.java)?.trim()
                        .orEmpty().ifBlank { groupSnap.key ?: return@forEach }
                    options.add(RemarkOption(label, textEn.ifBlank { textBn }, target))
                }
            }
            options
        } catch (e: Exception) {
            FirebaseErrorLogger.log("RemarkPopupOverlay", "fetch_remarks_failed", e.message ?: "")
            emptyList()
        }
    }

    /** The most recent validations row for this consignment already carries
     *  assigned_to_system_id -- read that directly instead of resolving it from Firebase
     *  route/run data. Returns null if no row exists yet (brand new parcel, never had a
     *  remark) -- caller shows the "no agent info" fallback in that case. */
    private suspend fun resolveAssignedAgent(consignmentId: String): String? {
        return try {
            val rows = SupabaseClientManager.fetchValidations(
                "RemarkPopupOverlay", "resolve_agent", listOf(
                    "consignment" to "eq.$consignmentId",
                    "order" to "created_at.desc",
                    "limit" to "1",
                )
            )
            rows.firstOrNull()?.optString("assigned_to_system_id")?.trim()?.ifBlank { null }
        } catch (e: Exception) {
            FirebaseErrorLogger.log("RemarkPopupOverlay", "resolve_agent_failed", e.message ?: "", mapOf("consignment" to consignmentId))
            null
        }
    }

    private fun renderRemarkChips(
        context: Context,
        view: View,
        options: List<RemarkOption>,
        match: CallerMatch,
        assignedAgentSystemId: String?,
    ) {
        val chipContainer = view.findViewById<LinearLayout>(R.id.llHrRemarkChips)
        val etNote = view.findViewById<EditText>(R.id.etHrNote)
        val tvAgentMissing = view.findViewById<TextView>(R.id.tvHrAgentMissing)
        val btnSave = view.findViewById<TextView>(R.id.btnHrSave)
        val tvConfirmation = view.findViewById<TextView>(R.id.tvHrConfirmation)

        var selected: RemarkOption? = null
        val chipViews = mutableListOf<TextView>()

        fun refreshChipStyles() {
            chipViews.forEach { chip ->
                val isSelected = chip.tag == selected
                chip.setBackgroundResource(
                    if (isSelected) R.drawable.bg_overlay_cta else R.drawable.bg_overlay_badge_status
                )
                chip.setTextColor(if (isSelected) 0xFFFFFFFF.toInt() else 0xFF0F172A.toInt())
            }
        }

        options.forEach { option ->
            val chip = TextView(context).apply {
                text = option.label
                textSize = 12f
                setPadding(28, 16, 28, 16)
                setBackgroundResource(R.drawable.bg_overlay_badge_status)
                setTextColor(0xFF0F172A.toInt())
                tag = option
                (layoutParams as? LinearLayout.LayoutParams)?.marginEnd = 8
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 8 }
            chip.layoutParams = lp
            chip.setOnClickListener {
                selected = if (selected == option) null else option
                refreshChipStyles()
            }
            chipContainer.addView(chip)
            chipViews.add(chip)
        }

        if (assignedAgentSystemId.isNullOrBlank()) {
            // Can't safely resolve who to assign the remark to -- no Save button, point
            // the agent at Call Center instead of risking a write with a guessed id.
            tvAgentMissing.isVisible = true
            btnSave.isVisible = false
            return
        }

        btnSave.setOnClickListener {
            val chosen = selected
            if (chosen == null) {
                android.widget.Toast.makeText(context, "একটি রিমার্কস বেছে নিন", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val branchId = RbacManager.current.branchIds.firstOrNull().orEmpty()
            if (branchId.isBlank()) {
                android.widget.Toast.makeText(context, "Branch তথ্য পাওয়া যায়নি", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            SupabaseRemarkValidationWriter.write(
                assignedAgentSystemId = assignedAgentSystemId,
                branchId = branchId,
                consignmentId = match.consignmentId,
                status = chosen.targetStatus,
                remarksText = chosen.englishLabel,
                noteText = etNote.text?.toString()?.trim().orEmpty(),
                source = "CC",
                screen = "RemarkPopupOverlay",
            )
            btnSave.isVisible = false
            chipContainer.isVisible = false
            etNote.isVisible = false
            tvConfirmation.isVisible = true
            mainHandler.postDelayed({ dismissInternal() }, 2000)
        }
    }

    private fun dismissInternal() {
        loadJob?.cancel()
        loadJob = null
        val view = overlayView ?: return
        try {
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
        windowManager = null
    }
}
