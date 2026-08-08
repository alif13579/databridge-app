package com.cloudx.databridge

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView

/**
 * Renders the flat + nested permission list. Permissions listed under
 * PermissionCatalog.childrenOf[parentKey] render indented directly below
 * their parent row, only visible while the parent is checked — unchecking
 * the parent hides and force-unchecks its children (a Requester permission
 * with no Petty Cash access wouldn't make sense to leave lingering true in
 * the saved state).
 */
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
        val children = PermissionCatalog.childrenOf[item.key].orEmpty()

        holder.tvTitle.text = item.title
        holder.tvDesc.text = item.description
        val checked = state[item.key] ?: false
        holder.cb.setOnCheckedChangeListener(null)
        holder.cb.isChecked = checked
        holder.cb.setOnCheckedChangeListener { _, isChecked ->
            state[item.key] = isChecked
            if (!isChecked) {
                // Unchecking a parent clears its children too, so the saved
                // permission map never has e.g. petty_cash_requester=true
                // sitting under a role that can't even see Petty Cash.
                children.forEach { child -> state[child.key] = false }
            }
            onToggle(item.key, isChecked)
            notifyItemChanged(holder.bindingAdapterPosition)
        }

        holder.layoutChildren.removeAllViews()
        if (children.isNotEmpty() && checked) {
            holder.layoutChildren.isVisible = true
            children.forEach { child ->
                val childView = LayoutInflater.from(holder.itemView.context)
                    .inflate(R.layout.item_permission_toggle_child, holder.layoutChildren, false)
                val tvChildTitle = childView.findViewById<TextView>(R.id.tvPermChildTitle)
                val tvChildDesc = childView.findViewById<TextView>(R.id.tvPermChildDesc)
                val cbChild = childView.findViewById<CheckBox>(R.id.cbPermChild)

                tvChildTitle.text = child.title
                tvChildDesc.text = child.description
                cbChild.setOnCheckedChangeListener(null)
                cbChild.isChecked = state[child.key] ?: false
                cbChild.setOnCheckedChangeListener { _, isChecked ->
                    state[child.key] = isChecked
                    onToggle(child.key, isChecked)
                }
                holder.layoutChildren.addView(childView)
            }
        } else {
            holder.layoutChildren.isVisible = false
        }
    }

    override fun getItemCount(): Int = items.size

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvPermTitle)
        val tvDesc: TextView = view.findViewById(R.id.tvPermDesc)
        val cb: CheckBox = view.findViewById(R.id.cbPerm)
        val layoutChildren: android.widget.LinearLayout = view.findViewById(R.id.layoutPermChildren)
    }
}
