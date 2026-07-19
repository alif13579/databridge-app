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

        tvParcelId.text = parcelId
        view.findViewById<View>(R.id.btnPdBack).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        loadParcelInfo()
        attachRemarkListener()
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

                    val sdf = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault())
                    val createdStr = if (createdAt > 0) sdf.format(Date(createdAt)) else "—"
                    val updatedStr = if (updatedAt > 0 && updatedAt != createdAt) "  ·  Updated ${sdf.format(Date(updatedAt))}" else ""
                    tvDates.text = "Created: $createdStr$updatedStr"
                }
                override fun onCancelled(e: DatabaseError) {
                    progressBar.visibility = View.GONE
                }
            })
    }

    // ── Remarks timeline (live) ──────────────────────────────────────────────────

    private fun attachRemarkListener() {
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
                    renderTimeline(snap)
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

        withContext(Dispatchers.Main) {
            if (!isAdded || view == null) return@withContext
            layoutTimeline.removeAllViews()

            if (entries.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                tvRemarksCount.text = "0 entries"
                return@withContext
            }

            tvEmpty.visibility = View.GONE
            tvRemarksCount.text = "${entries.size} ${if (entries.size == 1) "entry" else "entries"}"

            val inflater = LayoutInflater.from(ctx)
            entries.forEachIndexed { index, entry ->
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
                    if (index < entries.size - 1) View.VISIBLE else View.GONE

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

                layoutTimeline.addView(row)
            }
        }
    }
}
