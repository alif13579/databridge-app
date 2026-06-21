package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class PermissionsAdapter(
    private val onToggle: (String, Boolean) -> Unit
) : RecyclerView.Adapter<PermissionsAdapter.Holder>() {

    private val items = mutableListOf<PermissionCatalog.Perm>()
    private val state = mutableMapOf<String, Boolean>()

    fun submit(perms: List<PermissionCatalog.Perm>, checked: Map<String, Boolean>) {
        items.clear()
        items.addAll(perms)
        state.clear()
        state.putAll(checked)
        notifyDataSetChanged()
    }

    fun currentState(): Map<String, Boolean> = state.toMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_permission_toggle, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = item.title
        holder.tvDesc.text = item.description
        val checked = state[item.key] ?: false
        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.isChecked = checked
        holder.cb.setOnCheckedChangeListener { _, isChecked ->
            state[item.key] = isChecked
            onToggle(item.key, isChecked)
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvPermTitle)
        val tvDesc: TextView = view.findViewById(R.id.tvPermDesc)
        val cb: CheckBox = view.findViewById(R.id.cbPerm)
    }
}
