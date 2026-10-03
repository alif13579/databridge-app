package com.cloudx.databridge

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Supervisor live view: worker devices sharing location (LiveLocationService)
 * listed freshest-first with age + accuracy + map link. No map SDK — each row
 * opens the point in the device's map app (no API key needed).
 *
 * Drawer-gated by `nav_live_tracking` (AccessManager, admin-toggleable).
 */
class LiveTrackingFragment : Fragment() {

    data class AgentPoint(
        val systemId: String,
        val name: String,
        val branchId: String,
        val lat: Double,
        val lng: Double,
        val accuracy: Double,
        val timestamp: Long,
    )

    companion object {
        fun newInstance() = LiveTrackingFragment()
        private const val FRESH_MS = 5 * 60 * 1000L
        private const val RECENT_MS = 15 * 60 * 1000L

        fun timeAgo(ageMs: Long): String {
            if (ageMs < 0) return "just now"
            val mins = (ageMs / 60_000).toInt()
            if (mins < 1) return "just now"
            if (mins < 60) return "$mins min ago"
            val hrs = mins / 60
            if (hrs < 24) return "$hrs hr ago"
            val days = hrs / 24
            return if (days == 1) "yesterday" else "$days days ago"
        }
    }

    private lateinit var rvAgents: RecyclerView
    private lateinit var spBranch: Spinner
    private lateinit var tvCount: TextView
    private lateinit var tvEmpty: TextView
    private val adapter = AgentAdapter { openMap(it) }

    private var allPoints: List<AgentPoint> = emptyList()
    private var branchFilter: String = ""
    private var listener: ValueEventListener? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_live_tracking, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        rvAgents = view.findViewById(R.id.rvLiveAgents)
        spBranch = view.findViewById(R.id.spLiveBranch)
        tvCount = view.findViewById(R.id.tvLiveCount)
        tvEmpty = view.findViewById(R.id.tvLiveEmpty)
        rvAgents.layoutManager = LinearLayoutManager(requireContext())
        rvAgents.adapter = adapter
        view.findViewById<TextView>(R.id.tvLiveRefresh).setOnClickListener { render() }
        attachListener()
    }

    override fun onDestroyView() {
        try {
            listener?.let {
                FirebaseDatabase.getInstance().getReference("courier/live_locations")
                    .removeEventListener(it)
            }
        } catch (_: Exception) {}
        listener = null
        super.onDestroyView()
    }

    private fun attachListener() {
        val ref = FirebaseDatabase.getInstance().getReference("courier/live_locations")
        val l = object : ValueEventListener {
            override fun onDataChange(snap: DataSnapshot) {
                if (!isAdded) return
                allPoints = snap.children.mapNotNull { c ->
                    val ts = (c.child("timestamp").value as? Number)?.toLong() ?: 0L
                    val lat = (c.child("lat").value as? Number)?.toDouble() ?: return@mapNotNull null
                    val lng = (c.child("lng").value as? Number)?.toDouble() ?: return@mapNotNull null
                    AgentPoint(
                        systemId = c.key.orEmpty(),
                        name = c.child("name").getValue(String::class.java)?.trim().orEmpty()
                            .ifBlank { c.key.orEmpty() },
                        branchId = c.child("branch_id").getValue(String::class.java)?.trim().orEmpty(),
                        lat = lat, lng = lng,
                        accuracy = (c.child("accuracy").value as? Number)?.toDouble() ?: 0.0,
                        timestamp = ts
                    )
                }.sortedByDescending { it.timestamp }
                refreshBranchOptions()
                render()
            }

            override fun onCancelled(error: DatabaseError) {
                if (!isAdded) return
                tvEmpty.visibility = View.VISIBLE
                tvEmpty.text = "⚠ Load failed — ${error.message}"
            }
        }
        listener = l
        ref.addValueEventListener(l)
    }

    private fun refreshBranchOptions() {
        if (!isAdded) return
        val ids = allPoints.map { it.branchId }.filter { it.isNotBlank() }.distinct().sorted()
        val items = listOf("All branches") + ids
        val cur = if (branchFilter.isBlank()) "All branches" else branchFilter
        spBranch.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, items)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        spBranch.setSelection((items.indexOf(cur)).coerceAtLeast(0))
        spBranch.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                branchFilter = if (pos <= 0) "" else items[pos]
                render()
            }

            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun render() {
        if (!isAdded) return
        val list = if (branchFilter.isBlank()) allPoints
        else allPoints.filter { it.branchId == branchFilter }
        adapter.submitList(list)
        val live = list.count { System.currentTimeMillis() - it.timestamp < FRESH_MS }
        tvCount.text = "$live live · ${list.size} agents"
        tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openMap(p: AgentPoint) {
        try {
            val uri = Uri.parse("geo:${p.lat},${p.lng}?q=${p.lat},${p.lng}(${Uri.encode(p.name)})")
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: Exception) {
            try {
                Toast.makeText(requireContext(), "${p.lat}, ${p.lng}", Toast.LENGTH_LONG).show()
            } catch (_: Exception) {}
        }
    }

    private class AgentAdapter(
        private val onMap: (AgentPoint) -> Unit
    ) : ListAdapter<AgentPoint, AgentAdapter.Holder>(DIFF) {

        companion object {
            val DIFF = object : DiffUtil.ItemCallback<AgentPoint>() {
                override fun areItemsTheSame(a: AgentPoint, b: AgentPoint) = a.systemId == b.systemId
                override fun areContentsTheSame(a: AgentPoint, b: AgentPoint) = a == b
            }
        }

        class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val dot: TextView = v.findViewById(R.id.tvLiveDot)
            val name: TextView = v.findViewById(R.id.tvLiveName)
            val meta: TextView = v.findViewById(R.id.tvLiveMeta)
            val map: TextView = v.findViewById(R.id.btnLiveMap)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_live_agent, parent, false)
            return Holder(v)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val p = getItem(position)
            val age = System.currentTimeMillis() - p.timestamp
            h.dot.text = when {
                age < FRESH_MS -> "🟢"
                age < RECENT_MS -> "🟡"
                else -> "⚪"
            }
            h.name.text = p.name
            val acc = if (p.accuracy > 0) " · ±${p.accuracy.toInt()}m" else ""
            val branch = if (p.branchId.isNotBlank()) " · ${p.branchId}" else ""
            h.meta.text = "${timeAgo(age)}$acc$branch"
            h.map.setOnClickListener { onMap(p) }
        }
    }

}
