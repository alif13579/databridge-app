package com.cloudx.databridge

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment

open class PagePlaceholderFragment(
    private val pageTitle: String
) : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_page_placeholder, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.tvPageTitle).text = pageTitle
    }
}

class DashboardFragment : PagePlaceholderFragment("Dashboard")
class MyTasksFragment : PagePlaceholderFragment("My Tasks")
class ReportsFragment : PagePlaceholderFragment("Reports")
class SupportFragment : PagePlaceholderFragment("Support")
