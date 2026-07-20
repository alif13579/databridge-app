package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import coil.load
import com.google.android.material.imageview.ShapeableImageView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen parcel detail view — like a product page in eCommerce.
 *
 * Top section  : parcel info (customer, phone, address, status, COD, hub, dates)
 * Bottom section: complete journey log / remarks timeline (live-updated)
 *
 * Entry point: create via [newInstance] and load via MainActivity.loadFragment().
 */
class ParcelDetailFragment : Fragment() {

    companion object {
        fun newInstance(parcelId: String, scope: String) = ParcelDetailFragment().apply {
            arguments = Bundle().apply {
                putString("parcel_id", parcelId)
                putString("scope", scope)
            }
        }
    }

    private val db   = FirebaseDatabase.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val parcelId by lazy { arguments?.getString("parcel_id") ?: "" }
    private val scope    by lazy { arguments?.getString("scope")     ?: "cc"  }

    // Views
    private lateinit var tvParcelId:     TextView
    private lateinit var tvStatus:       TextView
    private lateinit var tvCod:          TextView
    private lateinit var tvCustomer:     TextView
    private lateinit var tvMeta:         TextView
    private lateinit var tvAddress:      TextView
    private lateinit var tvHub:          TextView
    private lateinit var tvDates:        TextView
    private lateinit var tvRemarksCount: TextView
    private lateinit var tvEmpty:        TextView
    private lateinit var layoutTimeline: LinearLayout
    private lateinit var progressBar:    ProgressBar
    private lateinit var tvOverviewStatus:    TextView
    private lateinit var tvOverviewCreatedAt: TextView
    private lateinit var tvOverviewUpdatedAt: TextView
    private lateinit var tvOverviewAge:       TextView

    // Fetched from courier/consignments/{id} — cached so tap-to-call and the
    // Overview age counter both have the values without re-reading Firebase.
    private var currentPhone: String = ""
    private var currentCreatedAt: Long = 0L
    private var currentUpdatedAt: Long = 0L

    // Live remark listener
    private var remarkListener: ValueEventListener? = null
    private val remarkRef get() = db.reference.child("courier/remarks_by_consignment/$parcelId")

    // Cache: uid → display name (resolved lazily from Firebase)
    private val uidNameCache = mutableMapOf<String, String>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_parcel_detail, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tvParcelId     = view.findViewById(R.id.tvPdParcelId)
        tvStatus       = view.findViewById(R.id.tvPdStatus)
        tvCod          = view.findViewById(R.id.tvPdCod)
        tvCustomer     = view.findViewById(R.id.tvPdCustomer)
        tvMeta         = view.findViewById(R.id.tvPdMeta)
        tvAddress      = view.findViewById(R.id.tvPdAddress)
        tvHub          = view.findViewById(R.id.tvPdHub)
        tvDates        = view.findViewById(R.id.tvPdDates)
        tvRemarksCount = view.findViewById(R.id.tvPdRemarksCount)
        tvEmpty        = view.findViewById(R.id.tvPdRemarksEmpty)
        layoutTimeline = view.findViewById(R.id.layoutPdTimeline)
        progressBar    = view.findViewById(R.id.pdProgressBar)
        tvOverviewStatus    = view.findViewById(R.id.tvPdOverviewStatus)
        tvOverviewCreatedAt = view.findViewById(R.id.tvPdOverviewCreatedAt)
        tvOverviewUpdatedAt = view.findViewById(R.id.tvPdOverviewUpdatedAt)
        tvOverviewAge       = view.findViewById(R.id.tvPdOverviewAge)

        tvParcelId.text = parcelId
        view.findViewById<View>(R.id.btnPdBack).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }
        view.findViewById<View>(R.id.btnPdCall).setOnClickListener {
            if (currentPhone.isNotBlank()) {
                AutoDialHelper.dial(this, currentPhone)
            }
        }

        loadParcelInfo()
    }

    override fun onDestroyView() {
        remarkListener?.let { remarkRef.removeEventListener(it) }
        super.onDestroyView()
    }

    // ── Parcel info ─────────────────────────────────────────────────────────────

    private fun loadParcelInfo() {
        progressBar.visibility = View.VISIBLE
        db.reference.child("courier/consignments/$parcelId")
            .addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snap: DataSnapshot) {
                    if (!isAdded || view == null) return
                    // Defensive: this crash has resisted several targeted fixes already
                    // (isAdded/view races, transaction timing). Until the real cause is
                    // caught red-handed via a logged stack trace, don't let ANY exception
                    // here take down the whole app — log it and fail gracefully instead.
                    try {
                        progressBar.visibility = View.GONE

                        val ctx          = context ?: return
                        val lang         = if (scope == "worker") "bn" else "bn"
                        val customer     = snap.child("recipientName").getValue(String::class.java) ?: "—"
                        val phone        = snap.child("recipientPhone").getValue(String::class.java) ?: "—"
                        val address      = snap.child("recipientAddress").getValue(String::class.java) ?: "—"
                        val hub          = snap.child("deliveryHub").getValue(String::class.java) ?: "—"
                        val cod          = snap.child("collectableAmount").getValue(Long::class.java) ?: 0L
                        val status       = snap.child("status").getValue(String::class.java) ?: "pending"
                        val createdAt    = snap.child("createdAt").getValue(Long::class.java) ?: 0L
                        val updatedAt    = snap.child("updatedAt").getValue(Long::class.java) ?: 0L

                        val cfg = WorkerParcelAdapter.getStatusConfig(ctx, status, lang)
                        tvStatus.text = cfg.label
                        tvStatus.setTextColor(cfg.color)
                        tvStatus.setBackgroundColor(cfg.bg)

                        tvCod.text      = "৳$cod"
                        tvCustomer.text = customer
                        tvMeta.text     = "$parcelId · $phone"
                        tvAddress.text  = "📍 $address"
                        tvHub.text      = "🏢 $hub"

                        currentPhone     = phone.takeIf { it != "—" } ?: ""
                        currentCreatedAt = createdAt
                        currentUpdatedAt = updatedAt

                        // Overview card — same fields shown in the long-press Journey Log dialog.
                        tvOverviewStatus.text = cfg.label
                        tvOverviewStatus.setTextColor(cfg.color)
                        val fullFmt = SimpleDateFormat("dd-MM-yy hh:mm:ss a", Locale.getDefault())
                        tvOverviewCreatedAt.text = if (createdAt > 0) fullFmt.format(Date(createdAt)) else "—"
                        tvOverviewUpdatedAt.text = if (updatedAt > 0) fullFmt.format(Date(updatedAt)) else "—"
                        tvOverviewAge.text = formatAge(createdAt, updatedAt)
                        val (ageColor, _) = WorkerParcelAdapter.ageColorFor(createdAt)
                        tvOverviewAge.setTextColor(ageColor)

                        val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                        val createdStr = if (createdAt > 0) sdf.format(Date(createdAt)) else "—"
                        val updatedStr = if (updatedAt > 0 && updatedAt != createdAt) "  ·  Updated ${sdf.format(Date(updatedAt))}" else ""
                        tvDates.text = "Created: $createdStr$updatedStr"
                    } catch (e: Exception) {
                        FirebaseErrorLogger.log(
                            screen = "ParcelDetailFragment",
                            action = "loadParcelInfo.onDataChange",
                            errorMessage = e.stackTraceToString(),
                            extra = mapOf("parcelId" to parcelId, "scope" to scope)
                        )
                        if (isAdded && view != null) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                "⚠️ Overview load failed: ${e.javaClass.simpleName}: ${e.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                    // Attach the remarks listener only now that currentCreatedAt is set,
                    // so the synthetic "CREATED" entry in renderTimeline() is guaranteed
                    // to be available on the very first render (avoids a race where the
                    // remarks listener could fire before this callback finishes). Attached
                    // even if the try block above failed, so the timeline can still work
                    // independently of the overview card.
                    attachRemarkListener()
                }
                override fun onCancelled(e: DatabaseError) {
                    if (!isAdded || view == null) return
                    progressBar.visibility = View.GONE
                    // Parcel info failed to load, but the remarks timeline can still
                    // work independently — attach it anyway (CREATED entry just won't
                    // show since currentCreatedAt stays 0).
                    attachRemarkListener()
                }
            })
    }

    // ── Remarks timeline (live) ──────────────────────────────────────────────────

    private fun attachRemarkListener() {
        // Defensive: loadParcelInfo() uses addListenerForSingleValueEvent so this
        // normally fires exactly once, but guard against a double-attach anyway
        // (e.g. a future retry path) by detaching any existing listener first.
        remarkListener?.let { remarkRef.removeEventListener(it) }
        remarkListener = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                // isAdded can still be true for a brief window after the fragment's
                // view has been destroyed (e.g. rapid back-navigation or a second
                // notification tap while this fragment is mid-transition). Accessing
                // viewLifecycleOwner in that window throws IllegalStateException, so
                // guard on `view != null` — the real signal that the view is alive —
                // instead of `isAdded` alone.
                if (!isAdded || view == null) return
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        renderTimeline(snap)
                    } catch (e: Exception) {
                        FirebaseErrorLogger.log(
                            screen = "ParcelDetailFragment",
                            action = "renderTimeline",
                            errorMessage = e.stackTraceToString(),
                            extra = mapOf("parcelId" to parcelId, "scope" to scope)
                        )
                        if (isAdded && view != null) {
                            progressBar.visibility = View.GONE
                            android.widget.Toast.makeText(
                                requireContext(),
                                "⚠️ Timeline load failed: ${e.javaClass.simpleName}: ${e.message}",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
            }
            override fun onCancelled(e: DatabaseError) {}
        }
        remarkRef.addValueEventListener(remarkListener!!)
    }

    private suspend fun renderTimeline(snap: DataSnapshot) {
        val ctx = context ?: return

        data class Entry(
            val status:   String,
            val remark:   String,
            val timeStr:  String,
            val author:   String,
            val role:     String,
            val photoUrl: String,
            val createdAt:Long
        )

        val sdf = SimpleDateFormat("dd-MM-yy  hh:mm a", Locale.getDefault())
        val lang = "bn"

        // Resolve display names for any uids we see
        val uidsToResolve = snap.children
            .mapNotNull { it.child("user_id").getValue(String::class.java)?.trim() }
            .filter { it.isNotBlank() && !uidNameCache.containsKey(it) }
            .distinct()

        if (uidsToResolve.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                uidsToResolve.forEach { uid ->
                    runCatching {
                        val nameSnap = db.reference.child("users/$uid/name").get().await()
                        val name = nameSnap.getValue(String::class.java)?.trim() ?: ""
                        if (name.isNotBlank()) uidNameCache[uid] = name
                    }
                }
            }
        }

        val entries = snap.children
            .mapNotNull { r ->
                val rStatus    = r.child("status").getValue(String::class.java)?.trim().orEmpty()
                val rNote      = r.child("remarks").getValue(String::class.java)?.trim().orEmpty()
                if (rStatus.isBlank() && rNote.isBlank()) return@mapNotNull null
                val createdAt  = r.child("createdAt").getValue(Long::class.java) ?: 0L
                val timeStr    = if (createdAt > 0) sdf.format(Date(createdAt)) else "—"
                val remarkedBy = r.child("remarked_by").getValue(String::class.java)?.trim().orEmpty()
                val uid        = r.child("user_id").getValue(String::class.java)?.trim().orEmpty()
                val photoUrl   = r.child("user_photo").getValue(String::class.java)?.trim().orEmpty()

                val resolvedName = uidNameCache[uid]
                val isCurrentUser = uid.isNotBlank() && uid == auth.currentUser?.uid
                val author = when {
                    isCurrentUser         -> "You"
                    resolvedName != null  -> resolvedName
                    remarkedBy == "support" -> "CC Agent"
                    else                  -> "Delivery Agent"
                }
                val role = if (remarkedBy == "support") "cc" else "agent"

                // Prefer actual remark text; fall back to status label
                val display = when {
                    rNote.isNotBlank()   -> rNote
                    rStatus.isNotBlank() -> WorkerParcelAdapter.getStatusConfig(ctx, rStatus, lang).label
                    else                 -> ""
                }

                Entry(rStatus, display, timeStr, author, role, photoUrl, createdAt)
            }
            .sortedBy { it.createdAt }   // oldest first → timeline reads top-to-bottom

        // Always lead with the parcel's actual creation — matches the long-press
        // Journey Log dialog, which never shows an empty timeline for a parcel
        // that has no remarks yet (it still has a "CREATED" starting point).
        val allEntries = if (currentCreatedAt > 0) {
            listOf(
                Entry(
                    status = "",
                    remark = "Parcel তৈরি হয়েছে",
                    timeStr = sdf.format(Date(currentCreatedAt)),
                    author = "System",
                    role = "system",
                    photoUrl = "",
                    createdAt = currentCreatedAt
                )
            ) + entries
        } else entries

        // Reuse WorkerParcelAdapter.withResponseGaps() (same logic as the long-press
        // Journey Log dialog) instead of duplicating the handoff-gap calculation here.
        // It operates on HistoryEntry, so map Entry → HistoryEntry → back, keeping this
        // fragment's own Entry model unchanged everywhere else in this function.
        // Indexed (not keyed by createdAt) since two entries could share a timestamp.
        val gapByIndex: Map<Int, Long> = WorkerParcelAdapter.withResponseGaps(
            allEntries.map { e ->
                HistoryEntry(
                    action = e.status,
                    remark = e.remark,
                    time = e.timeStr,
                    author = e.author,
                    authorRole = e.role,
                    authorPhotoUrl = e.photoUrl,
                    createdAt = e.createdAt
                )
            }
        ).withIndex().mapNotNull { (i, h) -> h.responseGapMinutes?.let { i to it } }.toMap()

        withContext(Dispatchers.Main) {
            if (!isAdded || view == null) return@withContext
            layoutTimeline.removeAllViews()

            if (allEntries.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvRemarksCount.text = "0 entries"
                return@withContext
            }

            tvEmpty.visibility = View.GONE
            tvRemarksCount.text = "${entries.size} ${if (entries.size == 1) "entry" else "entries"}"

            val inflater = LayoutInflater.from(ctx)
            allEntries.forEachIndexed { index, entry ->
                val row = inflater.inflate(R.layout.item_timeline_entry, layoutTimeline, false)

                // Avatar
                val ivAvatar = row.findViewById<ShapeableImageView>(R.id.ivTimelineAvatar)
                if (entry.photoUrl.isNotBlank()) {
                    ivAvatar.load(entry.photoUrl) {
                        crossfade(true)
                        placeholder(R.drawable.bg_timeline_avatar_placeholder)
                        error(R.drawable.bg_timeline_avatar_placeholder)
                    }
                } else {
                    ivAvatar.setImageDrawable(null)
                    ivAvatar.setBackgroundResource(R.drawable.bg_timeline_avatar_placeholder)
                }

                // Connector line (hide on last entry)
                row.findViewById<View>(R.id.viewTimelineLine).visibility =
                    if (index < allEntries.size - 1) View.VISIBLE else View.GONE

                // Author name
                row.findViewById<TextView>(R.id.twTimelineAuthor).text =
                    "${entry.author}${if (entry.role == "cc") " · CC" else ""}"

                // Status badge
                val tvStatusBadge = row.findViewById<TextView>(R.id.twTimelineStatus)
                if (entry.status.isNotBlank()) {
                    val cfg = WorkerParcelAdapter.getStatusConfig(ctx, entry.status, lang)
                    tvStatusBadge.text = entry.status.uppercase()
                    tvStatusBadge.setTextColor(cfg.color)
                    tvStatusBadge.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(cfg.bg)
                    tvStatusBadge.visibility = View.VISIBLE
                } else {
                    tvStatusBadge.visibility = View.GONE
                }

                // Remark text
                val tvRemark = row.findViewById<TextView>(R.id.twTimelineRemark)
                if (entry.remark.isNotBlank()) {
                    tvRemark.text = entry.remark
                    tvRemark.visibility = View.VISIBLE
                } else {
                    tvRemark.visibility = View.GONE
                }

                // Timestamp
                row.findViewById<TextView>(R.id.twTimelineMeta).text = entry.timeStr

                // Response-time chip — only shown on the entry that starts a new
                // worker↔CC handoff block (see WorkerParcelAdapter.withResponseGaps).
                val tvGap = row.findViewById<TextView>(R.id.twTimelineGap)
                val gapMin = gapByIndex[index]
                if (gapMin != null) {
                    tvGap.text = "⏱ ${gapMin}m response"
                    tvGap.visibility = View.VISIBLE
                } else {
                    tvGap.visibility = View.GONE
                }

                layoutTimeline.addView(row)
            }
        }
    }

    // ── Age formatting (matches WorkerSpaceFragment's long-press dialog) ─────────

    private fun formatAge(createdAt: Long, updatedAt: Long): String {
        if (createdAt <= 0L) return "—"
        val end = if (updatedAt > 0L) updatedAt else System.currentTimeMillis()
        val diffMs = (end - createdAt).coerceAtLeast(0L)
        val days = diffMs / (24 * 60 * 60 * 1000)
        val hours = diffMs / (60 * 60 * 1000)
        val minutes = diffMs / (60 * 1000)
        return when {
            days >= 1  -> "$days ${if (days == 1L) "Day" else "Days"}"
            hours >= 1 -> "$hours ${if (hours == 1L) "Hour" else "Hours"}"
            minutes >= 1 -> "$minutes ${if (minutes == 1L) "Minute" else "Minutes"}"
            else -> "Just now"
        }
    }
}
