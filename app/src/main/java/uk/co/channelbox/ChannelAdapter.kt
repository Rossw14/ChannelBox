package uk.co.channelbox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(
    private val listener: Listener,
    private val describe: (Channel) -> String,
    private val isOffline: (Channel) -> Boolean,
    private val isFavourite: (Channel) -> Boolean,
) : RecyclerView.Adapter<ChannelAdapter.Holder>() {

    interface Listener {
        fun onChannelClick(position: Int)
        fun onChannelLongClick(position: Int)
        fun onChannelFocused(position: Int)
    }

    var items: List<Channel> = emptyList()
        private set
    var playingId: String? = null

    fun submit(list: List<Channel>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.channel_number)
        val initial: TextView = view.findViewById(R.id.channel_initial)
        val logo: ImageView = view.findViewById(R.id.channel_logo)
        val name: TextView = view.findViewById(R.id.channel_name)
        val meta: TextView = view.findViewById(R.id.channel_meta)
        val star: TextView = view.findViewById(R.id.channel_star)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_channel, parent, false)
        val holder = Holder(view)
        view.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onChannelClick(pos)
        }
        view.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) listener.onChannelLongClick(pos)
            true
        }
        view.setOnFocusChangeListener { _, hasFocus ->
            val pos = holder.bindingAdapterPosition
            if (hasFocus && pos != RecyclerView.NO_POSITION) listener.onChannelFocused(pos)
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        holder.number.text = (position + 1).toString()
        holder.name.text = channel.name
        holder.initial.text = channel.name.firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "?"
        LogoLoader.load(channel.logo, holder.logo)
        holder.meta.text = describe(channel)
        holder.star.visibility = if (isFavourite(channel)) View.VISIBLE else View.GONE
        holder.itemView.isActivated = channel.id == playingId
        holder.itemView.alpha = if (isOffline(channel)) 0.55f else 1f
    }

    override fun getItemCount(): Int = items.size
}
