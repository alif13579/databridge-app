package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 📋 "What's New" — version-wise changelog, opened by tapping the app-version text in
 * Settings.
 *
 * HARDCODED — no Firebase read at all. The list in [HARDCODED_CHANGELOG] below is the
 * single source of truth; add a new [ChangelogVersion] entry there (newest at the top)
 * whenever a build ships. Kept in-app rather than config-driven so this can never show
 * stale/wrong data from a missed Firebase write, and works with zero network dependency.
 */
class ChangelogFragment : Fragment() {

    private lateinit var layoutContent: LinearLayout
    private lateinit var tvEmpty: TextView

    data class ChangelogEntry(
        val type: String,   // "fix" | "feature" | "improvement" — anything else falls back
        val text: String    // to a neutral bullet, see typeIcon() below
    )

    data class ChangelogVersion(
        val versionName: String,   // e.g. "5.22.14" — shown as the section header
        val releasedDate: String,  // e.g. "23 Jul 2026" — plain display string, not parsed
        val entries: List<ChangelogEntry>
    )

    companion object {
        /** Newest version first. Add a new entry here per release — nothing else to update. */
        private val HARDCODED_CHANGELOG = listOf(
            ChangelogVersion(
                versionName = "5.22.14",
                releasedDate = "23 Jul 2026",
                entries = listOf(
                    ChangelogEntry("feature", "\"What's New\" changelog screen — tap the version number in Settings to see it"),
                    ChangelogEntry("feature", "Call Center: split-assignment warning badge when the same phone number's parcels are spread across multiple agents"),
                    ChangelogEntry("feature", "Applying a remark now offers to apply it to that customer's other parcels too (with a confirm step)"),
                    ChangelogEntry("feature", "Engaged/on-call glow now fans out to a customer's other parcels, not just the one being called"),
                    ChangelogEntry("improvement", "Worker and Call Center screens load faster — several Firebase reads that ran one-after-another now run in parallel"),
                    ChangelogEntry("improvement", "Call Center: fewer duplicate Firebase reads when the same branch shows up for multiple agents"),
                    ChangelogEntry("fix", "Status badge position corrected on both Worker and Call Center parcel cards"),
                    ChangelogEntry("fix", "Parcel detail screen: failures now show a clear on-screen message instead of a silently blank screen"),
                )
            ),
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_changelog, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        layoutContent = view.findViewById(R.id.layoutChangelogContent)
        tvEmpty       = view.findViewById(R.id.tvChangelogEmpty)
        view.findViewById<View>(R.id.pbChangelogLoad)?.visibility = View.GONE

        view.findViewById<View>(R.id.btnChangelogBack).setOnClickListener {
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        if (HARDCODED_CHANGELOG.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
        } else {
            tvEmpty.visibility = View.GONE
            renderVersions(HARDCODED_CHANGELOG)
        }
    }

    private fun typeIcon(type: String): String = when (type.lowercase()) {
        "fix"         -> "🐛"
        "feature"     -> "✨"
        "improvement" -> "⚡"
        else          -> "•"
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
            if (version.releasedDate.isNotBlank()) {
                headerRow.addView(TextView(ctx).apply {
                    text = version.releasedDate
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
