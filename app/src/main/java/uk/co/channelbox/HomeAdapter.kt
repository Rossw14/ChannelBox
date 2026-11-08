package uk.co.channelbox

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Something on the home page. */
sealed class HomeItem {
    /** A live channel; [list] is what CH+/CH- moves through after choosing it. */
    class Live(val channel: Channel, val list: List<Channel>) : HomeItem()
    class App(val app: TvApp) : HomeItem()
    class Film(val title: Title) : HomeItem()
    class Action(val kind: Kind, val label: String, val description: String) : HomeItem() {
        enum class Kind { GUIDE, SEARCH, SETTINGS }
    }
}

/** One horizontal row of tiles on the home page. */
class HomeRowAdapter(
    private val items: List<HomeItem>,
    private val listener: Listener,
    private val nowOn: (Channel) -> String?,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    interface Listener {
        fun onHomeClick(item: HomeItem)
        fun onHomeFocus(item: HomeItem)
    }

    private class WideHolder(view: View) : RecyclerView.ViewHolder(view) {
        val art: ImageView = view.findViewById(R.id.home_art)
        val initial: TextView = view.findViewById(R.id.home_initial)
        val title: TextView = view.findViewById(R.id.home_title)
        val subtitle: TextView = view.findViewById(R.id.home_subtitle)
    }

    private class PosterHolder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.home_poster)
        val title: TextView = view.findViewById(R.id.home_poster_title)
    }

    override fun getItemViewType(position: Int): Int = if (items[position] is HomeItem.Film) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val holder: RecyclerView.ViewHolder = if (viewType == 1) {
            PosterHolder(inflater.inflate(R.layout.item_home_poster, parent, false))
        } else {
            WideHolder(inflater.inflate(R.layout.item_home_wide, parent, false))
        }
        holder.itemView.setOnClickListener {
            items.getOrNull(holder.bindingAdapterPosition)?.let { listener.onHomeClick(it) }
        }
        holder.itemView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) items.getOrNull(holder.bindingAdapterPosition)?.let { listener.onHomeFocus(it) }
        }
        return holder
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val context = holder.itemView.context
        when (val item = items[position]) {
            is HomeItem.Film -> {
                val h = holder as PosterHolder
                h.title.text = item.title.name
                LogoLoader.load(item.title.posterUrl, h.poster, targetSize = 300)
            }
            is HomeItem.Live -> {
                val h = holder as WideHolder
                h.title.text = item.channel.name
                h.subtitle.text = nowOn(item.channel) ?: item.channel.groups.joinToString(", ")
                h.initial.text = item.channel.name.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: ""
                LogoLoader.load(item.channel.logo, h.art, targetSize = 200)
            }
            is HomeItem.App -> {
                val h = holder as WideHolder
                h.title.text = item.app.label
                h.subtitle.text = ""
                h.initial.text = ""
                h.art.setTag(R.id.logo_url_tag, null)
                if (item.app.packageName == AppsRepository.SEARCH_TILE) {
                    h.art.setImageResource(R.drawable.search_tile)
                } else {
                    h.art.setImageDrawable(AppsRepository.artwork(context, item.app))
                }
            }
            is HomeItem.Action -> {
                val h = holder as WideHolder
                h.title.text = item.label
                h.subtitle.text = ""
                h.initial.text = ""
                h.art.setTag(R.id.logo_url_tag, null)
                h.art.setImageResource(
                    when (item.kind) {
                        HomeItem.Action.Kind.GUIDE -> R.drawable.home_guide
                        HomeItem.Action.Kind.SEARCH -> R.drawable.search_tile
                        HomeItem.Action.Kind.SETTINGS -> R.drawable.home_settings
                    }
                )
            }
        }
    }

    override fun getItemCount(): Int = items.size
}
