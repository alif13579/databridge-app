package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 📋 "What's New" — version-wise changelog, opened by tapping the app-version text in
 * Settings.
 *
 * Firebase: config/changelog/{versionCode}/
 *   versionName : String   — e.g. "5.22.14", shown as the section header
 *   releasedAt  : Long     — epoch millis, shown as a short date under the header
 *   entries/{pushId}/
 *     type  : String  — "fix" | "feature" | "improvement" (anything else falls back to a
 *             neutral bullet — see typeIcon() below, so a typo'd type never hides an entry)
 *     text  : String  — the user-facing line itself, written by whoever ships the change
 *     order : Long    — ascending sort key within the same version (ties broken by push key)
 *
 * Config-driven like every other feature in this app: new entries can be added straight
 * in Firebase console for any shipped version without an app rebuild. versionCode keys are
 * read back as Int and sorted descending (newest first) purely by that key — this fragment
 * does not care whether the keys are contiguous, so skipping a build's changelog entirely
 * is fine.
 */
class ChangelogFragment : Fragment() {

    private val db = FirebaseDatabase.getInstance()

    private lateinit var layoutContent: LinearLayout
    private lateinit var tvEmpty: TextView
    private lateinit var progressBar: android.widget.ProgressBar

    data class ChangelogEntry(
        val type: String,
        val text: String,
        val order: Long
    )

    data class ChangelogVersion(
        val versionCode: Int,
        val versionName: String,
        val releasedAt: Long,
        val entries: List<ChangelogEntry>
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_changelog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        layoutContent = view.findViewById(R.id.layoutChangelogContent)
        tvEmpty       = view.findViewById(R.id.tvChangelogEmpty)
        progressBar   = view.findViewById(R.id.pbChangelogLoad)

        view.findViewById<View>(R.id.btnChangelogBack).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        loadChangelog()
    }

    private fun loadChangelog() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                progressBar.visibility = View.VISIBLE
                val snap = db.reference.child("config/changelog").get().await()
                if (!isAdded || view == null) return@launch
                progressBar.visibility = View.GONE

                val versions = snap.children
                    .mapNotNull { versionSnap -> parseVersion(versionSnap) }
                    .sortedByDescending { it.versionCode }

                if (versions.isEmpty()) {
                    tvEmpty.visibility = View.VISIBLE
                    return@launch
                }
                tvEmpty.visibility = View.GONE
                renderVersions(versions)
            } catch (e: Exception) {
                if (!isAdded || view == null) return@launch
                progressBar.visibility = View.GONE
                tvEmpty.text = "⚠ Changelog load failed: ${e.message ?: "unknown"}"
                tvEmpty.visibility = View.VISIBLE
            }
        }
    }

    /** Returns null (skipping this version node entirely) only when the key itself isn't a
     *  valid version code — a malformed individual entry inside an otherwise-valid version
     *  is simply dropped from that version's list rather than discarding the whole version. */
    private fun parseVersion(versionSnap: DataSnapshot): ChangelogVersion? {
        val versionCode = versionSnap.key?.toIntOrNull() ?: return null
        val versionName = versionSnap.child("versionName").getValue(String::class.java)
            ?.trim().orEmpty().ifBlank { versionCode.toString() }
        val releasedAt = versionSnap.child("releasedAt").getValue(Long::class.java) ?: 0L

        val entries = versionSnap.child("entries").children
            .mapNotNull { entrySnap ->
                val text = entrySnap.child("text").getValue(String::class.java)?.trim()
                if (text.isNullOrBlank()) return@mapNotNull null
                val type = entrySnap.child("type").getValue(String::class.java)?.trim().orEmpty()
                val order = entrySnap.child("order").getValue(Long::class.java) ?: 0L
                ChangelogEntry(type, text, order)
            }
            .sortedBy { it.order }

        return ChangelogVersion(versionCode, versionName, releasedAt, entries)
    }

    private fun typeIcon(type: String): String = when (type.lowercase()) {
        "fix"         -> "🐛"
        "feature"     -> "✨"
        "improvement" -> "⚡"
        else          -> "•"
    }

    private fun formatReleaseDate(releasedAt: Long): String {
        if (releasedAt <= 0L) return ""
        return java.text.SimpleDateFormat("dd MMM yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(releasedAt))
    }

    private fun renderVersions(versions: List<ChangelogVersion>) {
        val ctx = context ?: return
        layoutContent.removeAllViews()
        val dp = resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()

        versions.forEach { version ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16.dp(), 14.dp(), 16.dp(), 14.dp())
                setBackgroundColor(android.graphics.Color.parseColor("#F1F5F9"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = 14.dp() }
            }

            val headerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            headerRow.addView(TextView(ctx).apply {
                text = "v${version.versionName}"
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(android.graphics.Color.parseColor("#0F172A"))
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            val dateText = formatReleaseDate(version.releasedAt)
            if (dateText.isNotBlank()) {
                headerRow.addView(TextView(ctx).apply {
                    text = dateText
                    textSize = 11f
                    setTextColor(android.graphics.Color.parseColor("#64748B"))
                })
            }
            card.addView(headerRow)

            if (version.entries.isEmpty()) {
                card.addView(TextView(ctx).apply {
                    text = "কোনো changelog note নেই এই version-এর জন্য।"
                    textSize = 12f
                    setTextColor(android.graphics.Color.parseColor("#94A3B8"))
                    setPadding(0, 8.dp(), 0, 0)
                })
            } else {
                version.entries.forEach { entry ->
                    card.addView(TextView(ctx).apply {
                        text = "${typeIcon(entry.type)}  ${entry.text}"
                        textSize = 13f
                        setTextColor(android.graphics.Color.parseColor("#1E293B"))
                        setPadding(0, 6.dp(), 0, 0)
                    })
                }
            }

            layoutContent.addView(card)
        }
    }
}
