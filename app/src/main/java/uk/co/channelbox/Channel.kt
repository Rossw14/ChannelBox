package uk.co.channelbox

/** One way of watching a channel. A channel can have several (backups). */
data class Stream(
    val url: String,
    val userAgent: String?,
    val referrer: String?,
    /** Labels from the playlist, e.g. "720p", "Not 24/7". */
    val tags: List<String>,
) {
    /** Picture height from a label like "720p", if the playlist says. */
    val height: Int?
        get() = tags.firstNotNullOfOrNull { QUALITY.find(it)?.groupValues?.get(1)?.toIntOrNull() }

    private companion object {
        val QUALITY = Regex("""^(\d{3,4})[pi]$""")
    }
}

/** A single channel in the list, with all of its streams merged together. */
class Channel(
    /** Stable key used for favourites, "recently watched" and "last watched". */
    val id: String,
    val name: String,
    val logo: String?,
    /** Categories, e.g. News, Kids. */
    val groups: List<String>,
    /** Countries it's listed under, e.g. "United Kingdom". */
    val countries: List<String>,
    /** True if it only comes from your own playlist (always shown, whatever countries you pick). */
    val isCustom: Boolean,
    /** Base tvg-id (lower case, no "@feed") used to find TV guide listings. */
    val guideId: String?,
    /** Best stream first. */
    val streams: List<Stream>,
) {
    fun hasUrl(urls: Set<String>): Boolean = streams.any { it.url in urls }

    /** Lower-case text that search looks through. */
    val searchText: String by lazy {
        (listOf(name) + groups + countries).joinToString(" ").lowercase()
    }
}

/** A row in the left-hand list. */
data class Group(
    /** Stable identity, e.g. "country:United Kingdom" or "cat:News". */
    val key: String,
    /** What's shown on screen. */
    val label: String,
    val channels: List<Channel>,
    val kind: Kind,
) {
    enum class Kind { FAVOURITES, RECENT, SEARCH, ALL, COUNTRY, MY_PLAYLIST, BACK, CATEGORY }
}

object Names {
    // A trailing "[Anything]" or "(1080p)" style label.
    private val TRAILING_TAG = Regex("""\s*(?:\[([^\[\]]*)]|\((\d{3,4}[pi])\))\s*$""")

    /** "Sky News (1080p) [Not 24/7]" -> ("Sky News", ["1080p", "Not 24/7"]) */
    fun split(name: String): Pair<String, List<String>> {
        var rest = name.trim()
        val tags = ArrayList<String>()
        while (true) {
            val m = TRAILING_TAG.find(rest) ?: break
            val tag = (m.groups[1] ?: m.groups[2])?.value?.trim().orEmpty()
            if (tag.isNotEmpty()) tags.add(0, tag)
            rest = rest.substring(0, m.range.first).trim()
        }
        return Pair(rest.ifEmpty { name.trim() }, tags)
    }
}
