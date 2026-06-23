package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 💬 Config → Remarks tab
 * TODO: full remarks UI (status-wise remark list, add/edit/delete, target_status selector)
 */
class ConfigRemarksFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return TextView(requireContext()).apply {
            text = "💬 Remarks config — coming soon"
            textSize = 15f
            setPadding(48, 48, 48, 48)
            setTextColor(resources.getColor(R.color.theme_text_secondary, requireContext().theme))
        }
    }
}
