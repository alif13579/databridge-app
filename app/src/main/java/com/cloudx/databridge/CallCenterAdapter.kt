package com.cloudx.databridge

import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

class CallCenterAdapter(
    private val onCall: (CallCenterParcelItem) -> Unit,
    private val onSetRemarks: (CallCenterParcelItem) -> Unit,
    private val onValidate: (CallCenterParcelItem) -> Unit
) {

    var expandedItemId: String? = null
    var items: List<CallCenterParcelItem> = emptyList()

    // Worker group headers
    data class WorkerGroup(
        val workerName: String,
        val branch: String,
        val parcels: List<CallCenterParcelItem>
    )

    fun buildGroups(): List<WorkerGroup> {
        val map = linkedMapOf<String, MutableList<CallCenterParcelItem>>()
        items.forEach { parcel ->
            val key = parcel.worker
            if (!map.containsKey(key)) map[key] = mutableListOf()
            map[key]!!.add(parcel)
        }
        return map.map { (worker, parcels) ->
            WorkerGroup(worker, parcels.firstOrNull()?.branch ?: "", parcels)
        }
    }

    fun renderInto(
        container: LinearLayout,
        onGroupClick: ((WorkerGroup) -> Unit)? = null
    ) {
        container.removeAllViews()
        val groups = buildGroups()
        val inflater = LayoutInflater.from(container.context)

        for (group in groups) {
            // Worker group header
            val headerView = inflater.inflate(R.layout.item_worker_group_header, container, false)
            val tvWorkerName = headerView.findViewById<TextView>(R.id.tvGroupWorkerName)
            val tvWorkerMeta = headerView.findViewById<TextView>(R.id.tvGroupWorkerMeta)
            val tvConfirmed = headerView.findViewById<TextView>(R.id.tvGroupConfirmed)
            val tvPending = headerView.findViewById<TextView>(R.id.tvGroupPending)

            tvWorkerName.text = group.workerName
            tvWorkerMeta.text = "${group.branch} · ${group.parcels.size} parcels"

            val confirmedCount = group.parcels.count { it.status == "confirmed" }
            val pendingCount = group.parcels.count { it.status == "pending" }
            tvConfirmed.text = "$confirmedCount ✓"
            tvPending.text = "$pendingCount ◌"

            headerView.setOnClickListener {
                onGroupClick?.invoke(group)
            }
            container.addView(headerView)

            // Parcel cards
            for ((i, parcel) in group.parcels.withIndex()) {
                val cardView = inflater.inflate(R.layout.item_parcel_agent_card, container, false)
                bindParcelCard(cardView, parcel)
                container.addView(cardView)
            }
        }
    }

    private fun bindParcelCard(view: View, item: CallCenterParcelItem) {
        val isExpanded = item.id == expandedItemId
        val ctx = view.context

        val tvCustomer = view.findViewById<TextView>(R.id.tvAgtCustomer)
        val tvValidationBadge = view.findViewById<TextView>(R.id.tvAgtValidationBadge)
        val tvMeta = view.findViewById<TextView>(R.id.tvAgtMeta)
        val tvAddress = view.findViewById<TextView>(R.id.tvAgtAddress)
        val tvCod = view.findViewById<TextView>(R.id.tvAgtCod)
        val tvStatusBadge = view.findViewById<TextView>(R.id.tvAgtStatusBadge)
        val tvRemarks = view.findViewById<TextView>(R.id.tvAgtRemarks)
        val tvValidationNote = view.findViewById<TextView>(R.id.tvAgtValidationNote)
        val layoutActions = view.findViewById<LinearLayout>(R.id.layoutAgtActions)
        val btnCall = view.findViewById<TextView>(R.id.btnAgtCall)
        val btnSetRemarks = view.findViewById<TextView>(R.id.btnAgtSetRemarks)
        val btnValidate = view.findViewById<TextView>(R.id.btnAgtValidate)

        tvCustomer.text = item.customer
        tvMeta.text = "${item.id} · ${item.phone}"
        tvAddress.text = "📍 ${item.address}"
        tvCod.text = "৳${item.cod}"

        val cfg = WorkerParcelAdapter.getStatusConfig(tvStatusBadge.context, item.status)
        tvStatusBadge.text = cfg.label
        tvStatusBadge.setTextColor(cfg.color)
        tvStatusBadge.setBackgroundColor(cfg.bg)

        tvValidationBadge.visibility = if (item.validationRequest) View.VISIBLE else View.GONE

        if (item.remarks.isNotBlank()) {
            tvRemarks.text = "💬 ${item.remarks}"
            tvRemarks.visibility = View.VISIBLE
        } else {
            tvRemarks.visibility = View.GONE
        }

        if (item.validationNote.isNotBlank()) {
            tvValidationNote.text = "⚡ Worker: ${item.validationNote}"
            tvValidationNote.visibility = View.VISIBLE
        } else {
            tvValidationNote.visibility = View.GONE
        }

        layoutActions.visibility = if (isExpanded) View.VISIBLE else View.GONE
        btnValidate.visibility = if (isExpanded && item.validationRequest) View.VISIBLE else View.GONE

        view.setOnClickListener {
            expandedItemId = if (isExpanded) null else item.id
            // Re-render since expanded state changed
            val parent = view.parent as? LinearLayout
            if (parent != null) {
                renderInto(parent)
            }
        }

        btnCall.setOnClickListener { onCall(item) }
        btnSetRemarks.setOnClickListener { onSetRemarks(item) }
        btnValidate.setOnClickListener { onValidate(item) }
    }
}
