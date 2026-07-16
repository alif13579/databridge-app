package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * ⚙️ Config Fragment
 * Access: admin / supervisor / staff  (permission key: nav_config)
 *
 * Four tabs:
 *   💬 Remarks  – status-wise remark management
 *   🌐 Language – Worker / CC fragment language settings
 *   🏷️ Statuses – custom status add / edit / delete
 *   📊 Sheet    – branch-wise Google Sheet connect, row/column config, mapping, sync
 *
 * Firebase layout:
 *   config/remarks/...
 *   config/sheets/{branch_id}/current/   ← active config + audit fields
 *   config/sheets/{branch_id}/history/   ← immutable audit log
 *   config/sheets/{branch_id}/data/      ← synced sheet rows (WorkerFragment reads this)
 */
class ConfigFragment : Fragment() {

    // ── Tab ids ──────────────────────────────────────────────────────────────
    private enum class Tab { REMARKS, LANGUAGE, STATUSES, SHEET, CONNECTORS, WHATSAPP }
    private var activeTab = Tab.REMARKS

    // ── Views ────────────────────────────────────────────────────────────────
    private lateinit var tabRemarks:    TextView
    private lateinit var tabLanguage:   TextView
    private lateinit var tabStatuses:   TextView
    private lateinit var tabSheet:      TextView
    private lateinit var tabConnectors: TextView
    private lateinit var tabWhatsApp:   TextView

    private lateinit var indRemarks:    View
    private lateinit var indLanguage:   View
    private lateinit var indStatuses:   View
    private lateinit var indSheet:      View
    private lateinit var indConnectors: View
    private lateinit var indWhatsApp:   View

    private lateinit var contentFrame:  ViewGroup

    // ── Inflate ──────────────────────────────────────────────────────────────
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_config, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tabRemarks  = view.findViewById(R.id.tabRemarks)
        tabLanguage = view.findViewById(R.id.tabLanguage)
        tabStatuses = view.findViewById(R.id.tabStatuses)
        tabSheet    = view.findViewById(R.id.tabSheet)
        tabConnectors = view.findViewById(R.id.tabConnectors)
        tabWhatsApp = view.findViewById(R.id.tabWhatsApp)

        indRemarks  = view.findViewById(R.id.indicatorRemarks)
        indLanguage = view.findViewById(R.id.indicatorLanguage)
        indStatuses = view.findViewById(R.id.indicatorStatuses)
        indSheet    = view.findViewById(R.id.indicatorSheet)
        indConnectors = view.findViewById(R.id.indicatorConnectors)
        indWhatsApp = view.findViewById(R.id.indicatorWhatsApp)

        contentFrame = view.findViewById(R.id.configContentFrame)

        // Visual hint that the tab row scrolls further right (hidden once fully scrolled)
        val scrollTabs    = view.findViewById<android.widget.HorizontalScrollView>(R.id.scrollConfigTabs)
        val scrollHint    = view.findViewById<TextView>(R.id.tvConfigTabsScrollHint)
        fun updateScrollHint() {
            val child = scrollTabs.getChildAt(0) ?: return
            val canScrollMore = child.width > scrollTabs.width &&
                scrollTabs.scrollX + scrollTabs.width < child.width - 4
            scrollHint.visibility = if (canScrollMore) View.VISIBLE else View.INVISIBLE
        }
        scrollTabs.viewTreeObserver.addOnGlobalLayoutListener { updateScrollHint() }
        scrollTabs.setOnScrollChangeListener { _, _, _, _, _ -> updateScrollHint() }

        tabRemarks .setOnClickListener { switchTab(Tab.REMARKS) }
        tabLanguage.setOnClickListener { switchTab(Tab.LANGUAGE) }
        tabStatuses.setOnClickListener { switchTab(Tab.STATUSES) }
        tabSheet   .setOnClickListener { switchTab(Tab.SHEET) }
        tabConnectors.setOnClickListener { switchTab(Tab.CONNECTORS) }
        tabWhatsApp.setOnClickListener { switchTab(Tab.WHATSAPP) }

        switchTab(activeTab)
    }

    // ── Tab switching ────────────────────────────────────────────────────────
    private fun switchTab(tab: Tab) {
        activeTab = tab

        // Update tab text colours
        val primary   = resources.getColor(R.color.theme_text_primary,   requireContext().theme)
        val secondary = resources.getColor(R.color.theme_text_secondary,  requireContext().theme)

        tabRemarks .setTextColor(if (tab == Tab.REMARKS)     primary else secondary)
        tabLanguage.setTextColor(if (tab == Tab.LANGUAGE)    primary else secondary)
        tabStatuses.setTextColor(if (tab == Tab.STATUSES)    primary else secondary)
        tabSheet   .setTextColor(if (tab == Tab.SHEET)       primary else secondary)
        tabConnectors.setTextColor(if (tab == Tab.CONNECTORS) primary else secondary)
        tabWhatsApp.setTextColor(if (tab == Tab.WHATSAPP)    primary else secondary)

        // Update indicators
        indRemarks   .visibility = if (tab == Tab.REMARKS)     View.VISIBLE else View.INVISIBLE
        indLanguage  .visibility = if (tab == Tab.LANGUAGE)    View.VISIBLE else View.INVISIBLE
        indStatuses  .visibility = if (tab == Tab.STATUSES)    View.VISIBLE else View.INVISIBLE
        indSheet     .visibility = if (tab == Tab.SHEET)       View.VISIBLE else View.INVISIBLE
        indConnectors.visibility = if (tab == Tab.CONNECTORS)  View.VISIBLE else View.INVISIBLE
        indWhatsApp  .visibility = if (tab == Tab.WHATSAPP)    View.VISIBLE else View.INVISIBLE

        // Load child fragment into contentFrame
        val fragment: Fragment = when (tab) {
            Tab.REMARKS     -> ConfigRemarksFragment()
            Tab.LANGUAGE    -> ConfigLanguageFragment()
            Tab.STATUSES    -> ConfigStatusesFragment()
            Tab.SHEET       -> ConfigSheetFragment()
            Tab.CONNECTORS  -> ConfigConnectorsFragment()
            Tab.WHATSAPP    -> ConfigWhatsAppFragment()
        }

        childFragmentManager.beginTransaction()
            .replace(R.id.configContentFrame, fragment)
            .commit()
    }
}
