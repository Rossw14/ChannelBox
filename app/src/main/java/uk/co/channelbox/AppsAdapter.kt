package uk.co.channelbox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Tiles for the Apps tab. */
class AppsAdapter(private val listener: Listener) : RecyclerView.Adapter<AppsAdapter.Holder>() {

    interface Listener {
        fun onAppClick(app: TvApp)
        fun onAppFocused(app: TvApp)
    }

    var items: List<TvApp> = emptyList()
        private set

    fun submit(list: List<TvApp>) {
        items = list
        notifyDataSetChanged()
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.app_art)
        val label: TextView = view.findViewById(R.id.app_label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        val holder = Holder(view)
        view.setOnClickListener {
            items.getOrNull(holder.bindingAdapterPosition)?.let { listener.onAppClick(it) }
        }
        view.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) items.getOrNull(holder.bindingAdapterPosition)?.let { listener.onAppFocused(it) }
        }
        return holder
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = items[position]
        holder.label.text = app.label
        if (app.packageName == AppsRepository.SEARCH_TILE) {
            holder.art.setImageResource(R.drawable.search_tile)
        } else {
            holder.art.setImageDrawable(AppsRepository.artwork(holder.itemView.context, app))
        }
    }

    override fun getItemCount(): Int = items.size
}
