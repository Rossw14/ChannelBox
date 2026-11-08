package uk.co.channelbox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class ChannelAdapter(private val listener: Listener) :
    RecyclerView.Adapter<ChannelAdapter.Holder>() {

    interface Listener {
        fun onChannelClick(position: Int)
        fun onChannelLongClick(position: Int)
    }

    var items: List<Channel> = emptyList()
        private set
    var favourites: Set<String> = emptySet()
    var failedUrls: Set<String> = emptySet()
    var playingUrl: String? = null

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
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = items[position]
        holder.number.text = (position + 1).toString()
        holder.name.text = channel.displayName
        holder.initial.text = channel.displayName.firstOrNull { it.isLetterOrDigit() }
            ?.uppercaseChar()?.toString() ?: "?"
        LogoLoader.load(channel.logo, holder.logo)

        val failed = channel.url in failedUrls
        val metaParts = ArrayList<String>()
        if (failed) metaParts.add(holder.itemView.context.getString(R.string.unavailable))
        metaParts.add(channel.groups.joinToString(", "))
        metaParts.addAll(channel.tags)
        holder.meta.text = metaParts.joinToString("  ·  ")

        holder.star.visibility = if (channel.url in favourites) View.VISIBLE else View.GONE
        holder.itemView.isActivated = channel.url == playingUrl
        holder.itemView.alpha = if (failed) 0.55f else 1f
    }

    override fun getItemCount(): Int = items.size
}
