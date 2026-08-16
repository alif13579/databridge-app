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
                versionName = "5.22.81",
                releasedDate = "15 Aug 2026",
                entries = listOf(
                    ChangelogEntry("feature", "Petty Cash: added the mockup's bottom action bar (Dashboard/Requests/+/Reports/More) to the Dashboard and My Requests screens — local to Petty Cash, separate from the app's main bottom nav. Reports and More show a 'coming soon' toast since neither has a screen built yet"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.79",
                releasedDate = "15 Aug 2026",
                entries = listOf(
                    ChangelogEntry("feature", "Petty Cash Accounts Dashboard: stat cards (Pending Approval / Approved (Settlement) / Settled This Month) now show the request count alongside the taka total, matching the mockup"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.77",
                releasedDate = "15 Aug 2026",
                entries = listOf(
                    ChangelogEntry("feature", "Petty Cash Dashboard: added a worker-initial avatar circle to Pending For Approval rows, matching the mockup — reviewed Cash POC dashboard, which already shared the Team Aligned fix"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.76",
                releasedDate = "15 Aug 2026",
                entries = listOf(
                    ChangelogEntry("fix", "Petty Cash Dashboard: 'View all' under Pending For Approval (Team Aligned / Cash POC) now opens the same filtered list as the Pending stat card, instead of an unfiltered browse-all screen"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.75",
                releasedDate = "15 Aug 2026",
                entries = listOf(
                    ChangelogEntry("feature", "Petty Cash My Requests (Requester screen) redesigned to match the mockup: 'My Petty Cash Summary' hero card with this month's approved total, plus My Requests/Pending/Approved/Settled mini stat tiles, and a sticky '+ New Petty Cash Request' button pinned to the bottom"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.74",
                releasedDate = "15 Aug 2026",
                entries = listOf(
                    ChangelogEntry("fix", "Drawer: Petty Cash menu item now shows an actual wallet icon instead of a generic gallery icon (was ic_menu_gallery, an unrelated stock Android icon)"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.73",
                releasedDate = "15 Aug 2026",
                entries = listOf(
                    ChangelogEntry("feature", "Petty Cash Dashboard: added a role-based greeting (\"Hi Accountant, welcome back\" / \"Hi Petty Cash POC, welcome back\" / \"Hi Team Aligned, welcome back\") so you can confirm at a glance which role was detected for the current branch"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.72",
                releasedDate = "14 Aug 2026",
                entries = listOf(
                    ChangelogEntry("fix", "Petty Cash Dashboard: fixed Deposit Fund silently disappearing for Accounts when viewing a branch where they hold no petty cash role — now shows a clear \"no role for this branch\" message instead of a misleading fake Accounts view"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.71",
                releasedDate = "14 Aug 2026",
                entries = listOf(
                    ChangelogEntry("fix", "Petty Cash: the branch you pick on Dashboard/My Requests is now remembered across sessions instead of resetting every time"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.70",
                releasedDate = "14 Aug 2026",
                entries = listOf(
                    ChangelogEntry("feature", "Petty Cash: added a branch switcher (same as Cash Management) for users assigned to more than one branch, on both the Dashboard and My Requests screens"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.69",
                releasedDate = "14 Aug 2026",
                entries = listOf(
                    ChangelogEntry("feature", "Petty Cash Dashboard now shows a summary suited to your role: Team Aligned and Cash POC see a Pending/Approved/Rejected request summary instead of Accounts' wallet balance"),
                    ChangelogEntry("feature", "Petty Cash Dashboard: if you hold more than one petty cash role (e.g. Cash POC and Accounts), a switcher at the top lets you toggle between each role's view"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.56",
                releasedDate = "08 Aug 2026",
                entries = listOf(
                    ChangelogEntry("fix", "Sheet sync: a run whose agent had no branch assigned yet at creation time was permanently missing from branch-wise run lists — later syncs now retry resolving the branch and backfill it instead of leaving it stuck"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.35",
                releasedDate = "02 Aug 2026",
                entries = listOf(
                    ChangelogEntry("feature", "Access Manager: roles now have a configurable rank (Level) — no more fixed 6-role list, add a new role and place it in the hierarchy without a code change"),
                    ChangelogEntry("feature", "Access Manager: each role can set \"Reports To\" — pick which other roles should see its employees' data (e.g. Delivery Agent reports to Incharge and Supervisor)"),
                    ChangelogEntry("feature", "Dashboard: the team list below your own stats now works for any role with people reporting to it, not just Manager"),
                    ChangelogEntry("feature", "Dashboard: tap anyone in your team list to drill into their own team, with a trail back up to where you started"),
                    ChangelogEntry("improvement", "Dashboard: switching between team view and individual view is now available wherever it's relevant, with clearer labels"),
                ),
            ),
            ChangelogVersion(
                versionName = "5.22.21",
                releasedDate = "27 Jul 2026",
                entries = listOf(
                    ChangelogEntry("fix", "Auto Call was dialing the next number while the current call was still active — now correctly waits for the call to actually end before moving on"),
                    ChangelogEntry("feature", "Call Center: parcel card now shows how many times you've called that number from this device"),
                    ChangelogEntry("feature", "New Dashboard — date-range stats, role-based views, per-status breakdown, Earnings card, open/closed run counts"),
                    ChangelogEntry("feature", "Call Center: worker's real profile photo now shown in the group header instead of a generic icon"),
                    ChangelogEntry("fix", "Fixed a sync error that was silently blocking the per-day remarks tracking (introduced in 5.22.16) from actually saving"),
                    ChangelogEntry("fix", "Parcel card: amount and status badge repositioned so neither gets hidden behind the age/attempt badge"),
                    ChangelogEntry("improvement", "Remarks box redesigned as a compact chat-style bubble with rounded corners, showing how long ago the remark was left"),
                    ChangelogEntry("fix", "Call Center: branch filter no longer breaks (shows zero parcels) if your assigned branches change"),
                    ChangelogEntry("fix", "Sheet sync: corrected a date-format bug that could cause mismatched entries"),
                    ChangelogEntry("fix", "Parcel detail's journey log timeline now matches what the tap-and-hold dialog shows"),
                    ChangelogEntry("improvement", "Parcel card auto-collapses after remarks are saved (Worker + Call Center)"),
                )
            ),
            ChangelogVersion(
                versionName = "5.22.16",
                releasedDate = "26 Jul 2026",
                entries = listOf(
                    ChangelogEntry("feature", "Groundwork for a personal \"my day\" report — every remark you save (Call Center or Worker) now also records a per-day entry against your own account"),
                    ChangelogEntry("improvement", "That entry's status now keeps itself correct afterward — if a parcel's status changes later (e.g. courier confirms delivery after your remark), your recorded summary for it updates automatically instead of staying frozen at what you first entered"),
                )
            ),
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
