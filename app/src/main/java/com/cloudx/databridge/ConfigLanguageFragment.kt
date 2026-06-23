package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * 🌐 Config → Language tab
 * TODO: Worker / CC fragment language dropdowns (bn_bn, bn_en, en_en, en_bn)
 */
class ConfigLanguageFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return TextView(requireContext()).apply {
            text = "🌐 Language settings — coming soon"
            textSize = 15f
            setPadding(48, 48, 48, 48)
            setTextColor(resources.getColor(R.color.theme_text_secondary, requireContext().theme))
        }
    }
}
