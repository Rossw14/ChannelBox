package uk.co.channelbox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class GroupAdapter(private val listener: Listener) :
    RecyclerView.Adapter<GroupAdapter.Holder>() {

    interface Listener {
        fun onGroupFocused(position: Int)
        fun onGroupClick(position: Int)
    }

    var items: List<Group> = emptyList()
        private set
    var selected: Int = 0

    fun submit(list: List<Group>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.group_name)
        val count: TextView = view.findViewById(R.id.group_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        val holder = Holder(view)
        view.setOnFocusChangeListener { _, hasFocus ->
            val pos = holder.bindingAdapterPosition
            if (hasFocus && pos != RecyclerView.NO_POSITION) listener.onGroupFocused(pos)
        }
        view.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onGroupClick(pos)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val group = items[position]
        holder.name.text = group.label
        val showCount = group.kind != Group.Kind.BACK && group.kind != Group.Kind.SEARCH
        holder.count.text = when {
            !showCount -> ""
            group.kind == Group.Kind.COUNTRY -> "${group.channels.size}  ›"
            else -> group.channels.size.toString()
        }
        holder.itemView.isActivated = position == selected
    }

    override fun getItemCount(): Int = items.size
}
