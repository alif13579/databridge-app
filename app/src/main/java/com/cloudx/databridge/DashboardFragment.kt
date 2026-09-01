package com.cloudx.databridge

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * App home screen. Replaced the old date-chip/branch-dropdown/run-cards/earnings/
 * status-breakdown/agent-performance view with the "Verify & Delivery Request" funnel
 * (see VerifyDeliveryDashboardViewModel's doc comment for the full classification logic
 * and Supabase remarks_status values this depends on).
 */
class DashboardFragment : Fragment() {

    private val vm: VerifyDeliveryDashboardViewModel by viewModels()

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var pbLoading: View
    private lateinit var tvError: TextView
    private lateinit var tvDateRange: TextView
    private lateinit var tvAgentFilter: TextView
    private lateinit var tvQuickSummary: TextView

    private data class MetricCardViews(val icon: TextView, val label: TextView, val value: TextView, val subtitle: TextView)
    private lateinit var cardTotalAssign: MetricCardViews
    private lateinit var cardVerifyRequest: MetricCardViews
    private lateinit var cardHoldReturn: MetricCardViews
    private lateinit var cardDeliveryRequest: MetricCardViews

    private data class SubMetricViews(val icon: TextView, val value: TextView, val subtitle: TextView)
    private lateinit var subConfirmed: SubMetricViews
    private lateinit var subDelivered: SubMetricViews
    private lateinit var subPending: SubMetricViews

    private lateinit var tvFunnelTotalValue: TextView
    private lateinit var tvFunnelVerifyValue: TextView
    private lateinit var tvFunnelHoldReturnValue: TextView
    private lateinit var tvFunnelDeliveryReqValue: TextView
    private lateinit var tvFunnelConfirmedValue: TextView
    private lateinit var tvFunnelDeliveredValue: TextView
    private lateinit var tvFunnelPendingValue: TextView

    // Date range state -- default "This Week" (last 7 days including today).
    private var rangeStartMs: Long = 0L
    private var rangeEndMs: Long = 0L
    private var rangeLabel: String = ""
    private var selectedAgentSystemId: String? = null
    private var selectedAgentName: String = "All Agents"
    private var latestAgentOptions: List<FunnelAgentOption> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_dashboard, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        swipeRefresh   = view.findViewById(R.id.swipeVdRefresh)
        pbLoading      = view.findViewById(R.id.pbVdLoading)
        tvError        = view.findViewById(R.id.tvVdError)
        tvDateRange    = view.findViewById(R.id.tvVdDateRange)
        tvAgentFilter  = view.findViewById(R.id.tvVdAgentFilter)
        tvQuickSummary = view.findViewById(R.id.tvVdQuickSummary)

        cardTotalAssign = bindMetricCard(view.findViewById(R.id.cardVdTotalAssign))
        cardVerifyRequest = bindMetricCard(view.findViewById(R.id.cardVdVerifyRequest))
        cardHoldReturn = bindMetricCard(view.findViewById(R.id.cardVdHoldReturn))
        cardDeliveryRequest = bindMetricCard(view.findViewById(R.id.cardVdDeliveryRequest))

        subConfirmed = bindSubMetric(view.findViewById(R.id.subVdConfirmed))
        subDelivered = bindSubMetric(view.findViewById(R.id.subVdDelivered))
        subPending = bindSubMetric(view.findViewById(R.id.subVdPending))

        tvFunnelTotalValue = view.findViewById(R.id.tvVdFunnelTotalValue)
        tvFunnelVerifyValue = view.findViewById(R.id.tvVdFunnelVerifyValue)
        tvFunnelHoldReturnValue = view.findViewById(R.id.tvVdFunnelHoldReturnValue)
        tvFunnelDeliveryReqValue = view.findViewById(R.id.tvVdFunnelDeliveryReqValue)
        tvFunnelConfirmedValue = view.findViewById(R.id.tvVdFunnelConfirmedValue)
        tvFunnelDeliveredValue = view.findViewById(R.id.tvVdFunnelDeliveredValue)
        tvFunnelPendingValue = view.findViewById(R.id.tvVdFunnelPendingValue)

        cardTotalAssign.icon.text = "👥"
        cardTotalAssign.label.text = "মোট অ্যাসাইন"
        cardVerifyRequest.icon.text = "📞"
        cardVerifyRequest.label.text = "ভেরিফাই রিকোয়েস্ট"
        cardHoldReturn.icon.text = "🛡️"
        cardHoldReturn.label.text = "ভেরিফাইড (Hold/Return)"
        cardDeliveryRequest.icon.text = "🚚"
        cardDeliveryRequest.label.text = "ডেলিভারি রিকোয়েস্ট"

        subConfirmed.icon.text = "✅"
        subConfirmed.subtitle.text = "কনফার্মড"
        subDelivered.icon.text = "🚚"
        subDelivered.subtitle.text = "ডেলিভারড"
        subPending.icon.text = "⏰"
        subPending.subtitle.text = "পেন্ডিং"

        setRangeToThisWeek()
        tvDateRange.setOnClickListener { showDateRangePicker() }
        tvAgentFilter.setOnClickListener { showAgentPicker() }

        swipeRefresh.setOnRefreshListener { loadData() }

        vm.state.observe(viewLifecycleOwner) { state -> render(state) }

        loadData()
    }

    private fun bindMetricCard(root: View): MetricCardViews = MetricCardViews(
        icon = root.findViewById(R.id.tvVdMetricIcon),
        label = root.findViewById(R.id.tvVdMetricLabel),
        value = root.findViewById(R.id.tvVdMetricValue),
        subtitle = root.findViewById(R.id.tvVdMetricSubtitle),
    )

    private fun bindSubMetric(root: View): SubMetricViews = SubMetricViews(
        icon = root.findViewById(R.id.tvVdSubIcon),
        value = root.findViewById(R.id.tvVdSubValue),
        subtitle = root.findViewById(R.id.tvVdSubSubtitle),
    )

    private fun loadData() {
        vm.load(rangeStartMs, rangeEndMs, selectedAgentSystemId)
    }

    private fun render(state: VerifyDeliveryFunnelState) {
        swipeRefresh.isRefreshing = false
        pbLoading.isVisible = state.isLoading
        tvError.isVisible = state.error != null
        tvError.text = state.error?.let { "⚠ $it" }

        latestAgentOptions = state.agentOptions

        cardTotalAssign.value.text = state.totalAssign.toString()
        cardTotalAssign.subtitle.text = "100%"

        val verifyPct = pct(state.verifyRequest, state.totalAssign)
        cardVerifyRequest.value.text = state.verifyRequest.toString()
        cardVerifyRequest.subtitle.text = "$verifyPct% of Total Assign"

        val holdReturnPct = pct(state.holdReturn, state.verifyRequest)
        cardHoldReturn.value.text = state.holdReturn.toString()
        cardHoldReturn.subtitle.text = "$holdReturnPct% of Verify Request"

        val deliveryReqPct = pct(state.deliveryRequest, state.verifyRequest)
        cardDeliveryRequest.value.text = state.deliveryRequest.toString()
        cardDeliveryRequest.subtitle.text = "$deliveryReqPct% of Verify Request"

        val confirmedPct = pct(state.confirmed, state.deliveryRequest)
        val deliveredPct = pct(state.delivered, state.deliveryRequest)
        val pendingPct = pct(state.pending, state.deliveryRequest)
        subConfirmed.value.text = state.confirmed.toString()
        subConfirmed.subtitle.text = "কনফার্মড ($confirmedPct%)"
        subDelivered.value.text = state.delivered.toString()
        subDelivered.subtitle.text = "ডেলিভারড ($deliveredPct%)"
        subPending.value.text = state.pending.toString()
        subPending.subtitle.text = "পেন্ডিং ($pendingPct%)"

        tvFunnelTotalValue.text = "${state.totalAssign} (100%)"
        tvFunnelVerifyValue.text = "${state.verifyRequest} ($verifyPct% of Total Assign)"
        tvFunnelHoldReturnValue.text = "${state.holdReturn} ($holdReturnPct% of Verify Request)"
        tvFunnelDeliveryReqValue.text = "${state.deliveryRequest} ($deliveryReqPct% of Verify Request)"
        tvFunnelConfirmedValue.text = "${state.confirmed} ($confirmedPct%)"
        tvFunnelDeliveredValue.text = "${state.delivered} ($deliveredPct%)"
        tvFunnelPendingValue.text = "${state.pending} ($pendingPct%)"

        tvQuickSummary.text = buildString {
            append("• মোট অ্যাসাইন: ${state.totalAssign} (100%)\n")
            append("• ভেরিফাই রিকোয়েস্ট: ${state.verifyRequest} ($verifyPct% of Total Assign)\n")
            append("• ভেরিফাইড (Hold/Return): ${state.holdReturn} ($holdReturnPct% of Verify Request)\n")
            append("• ডেলিভারি রিকোয়েস্ট: ${state.deliveryRequest} ($deliveryReqPct% of Verify Request)\n")
            append("• কনফার্মড: ${state.confirmed} ($confirmedPct%), ডেলিভারড: ${state.delivered} ($deliveredPct%), পেন্ডিং: ${state.pending} ($pendingPct%)")
        }
    }

    private fun pct(part: Int, whole: Int): Int = if (whole <= 0) 0 else Math.round(part * 100f / whole)

    // ── Date range ──────────────────────────────────────────────────────────

    private fun startOfDay(cal: Calendar) {
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
    }

    private fun setRangeToThisWeek() {
        val end = Calendar.getInstance()
        val start = Calendar.getInstance().apply {
            add(Calendar.DAY_OF_YEAR, -6)
            startOfDay(this)
        }
        rangeStartMs = start.timeInMillis
        rangeEndMs = end.timeInMillis
        rangeLabel = "This Week"
        updateDateRangeLabel()
    }

    private fun updateDateRangeLabel() {
        val fmt = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH)
        tvDateRange.text = "📅 ${fmt.format(Date(rangeStartMs))} - ${fmt.format(Date(rangeEndMs))}"
    }

    private fun showDateRangePicker() {
        val options = arrayOf("Today", "Yesterday", "This Week", "Last 7 Days", "This Month", "Last 30 Days", "Custom Range")
        AlertDialog.Builder(requireContext())
            .setTitle("Date Range বেছে নিন")
            .setItems(options) { _, which ->
                val cal = Calendar.getInstance()
                when (which) {
                    0 -> { // Today
                        startOfDay(cal)
                        rangeStartMs = cal.timeInMillis
                        rangeEndMs = System.currentTimeMillis()
                        rangeLabel = "Today"
                    }
                    1 -> { // Yesterday
                        cal.add(Calendar.DAY_OF_YEAR, -1)
                        startOfDay(cal)
                        rangeStartMs = cal.timeInMillis
                        cal.add(Calendar.DAY_OF_YEAR, 1)
                        rangeEndMs = cal.timeInMillis - 1
                        rangeLabel = "Yesterday"
                    }
                    2 -> { setRangeToThisWeek(); loadData(); return@setItems }
                    3 -> { // Last 7 Days
                        rangeEndMs = System.currentTimeMillis()
                        cal.add(Calendar.DAY_OF_YEAR, -7)
                        startOfDay(cal)
                        rangeStartMs = cal.timeInMillis
                        rangeLabel = "Last 7 Days"
                    }
                    4 -> { // This Month
                        cal.set(Calendar.DAY_OF_MONTH, 1)
                        startOfDay(cal)
                        rangeStartMs = cal.timeInMillis
                        rangeEndMs = System.currentTimeMillis()
                        rangeLabel = "This Month"
                    }
                    5 -> { // Last 30 Days
                        rangeEndMs = System.currentTimeMillis()
                        cal.add(Calendar.DAY_OF_YEAR, -30)
                        startOfDay(cal)
                        rangeStartMs = cal.timeInMillis
                        rangeLabel = "Last 30 Days"
                    }
                    6 -> { showCustomRangePicker(); return@setItems }
                }
                updateDateRangeLabel()
                loadData()
            }
            .show()
    }

    private fun showCustomRangePicker() {
        val startCal = Calendar.getInstance().apply { if (rangeStartMs > 0L) timeInMillis = rangeStartMs }
        DatePickerDialog(requireContext(), { _, y, m, d ->
            val from = Calendar.getInstance().apply { set(y, m, d); startOfDay(this) }
            val endCal = Calendar.getInstance()
            DatePickerDialog(requireContext(), { _, y2, m2, d2 ->
                val to = Calendar.getInstance().apply {
                    set(y2, m2, d2); set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                }
                if (to.timeInMillis < from.timeInMillis) {
                    android.widget.Toast.makeText(requireContext(), "End date শুরুর তারিখের আগে হতে পারবে না", android.widget.Toast.LENGTH_SHORT).show()
                    return@DatePickerDialog
                }
                rangeStartMs = from.timeInMillis
                rangeEndMs = to.timeInMillis
                val fmt = SimpleDateFormat("dd MMM", Locale.ENGLISH)
                rangeLabel = "${fmt.format(from.time)} - ${fmt.format(to.time)}"
                updateDateRangeLabel()
                loadData()
            }, endCal.get(Calendar.YEAR), endCal.get(Calendar.MONTH), endCal.get(Calendar.DAY_OF_MONTH)).show()
        }, startCal.get(Calendar.YEAR), startCal.get(Calendar.MONTH), startCal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ── Agent filter ────────────────────────────────────────────────────────

    private fun showAgentPicker() {
        val names = listOf("All Agents") + latestAgentOptions.map { it.name }
        AlertDialog.Builder(requireContext())
            .setTitle("Call Center Agent বেছে নিন")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == 0) {
                    selectedAgentSystemId = null
                    selectedAgentName = "All Agents"
                } else {
                    val agent = latestAgentOptions[which - 1]
                    selectedAgentSystemId = agent.systemId
                    selectedAgentName = agent.name
                }
                tvAgentFilter.text = "👤 $selectedAgentName"
                loadData()
            }
            .show()
    }
}
