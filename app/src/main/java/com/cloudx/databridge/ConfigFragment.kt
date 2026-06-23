package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * ⚙️ Config Fragment
 * Access: admin, supervisor, staff (nav_config permission)
 *
 * Sections:
 *  - Remarks   → status-wise remark management
 *  - Language  → Worker / CC fragment language settings
 *  - Statuses  → custom status add/edit/delete
 *  - Sheet     → branch-wise Google Sheet connect & column mapping
 *
 * Firebase paths:
 *  config/remarks/...
 *  config/sheets/{branch_id}/current/   ← active config
 *  config/sheets/{branch_id}/history/   ← audit log
 *  config/sheets/{branch_id}/data/      ← synced sheet rows
 *
 * TODO: implement full UI (see plan)
 */
class ConfigFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val tv = TextView(requireContext()).apply {
            text = "Config — Coming Soon"
            textSize = 18f
            setPadding(48, 48, 48, 48)
        }
        return tv
    }
}
