package uk.co.channelbox

/** One way of watching a channel. A channel can have several (backups). */
data class Stream(
    val url: String,
    val userAgent: String?,
    val referrer: String?,
    /** Labels from the playlist, e.g. "720p", "Not 24/7". */
    val tags: List<String>,
)

/** A single channel in the list, with all of its streams merged together. */
class Channel(
    /** Stable key used for favourites and "last watched". */
    val id: String,
    val name: String,
    val logo: String?,
    val groups: List<String>,
    /** Base tvg-id (lower case, no "@feed") used to find TV guide listings. */
    val guideId: String?,
    /** Best stream first. */
    val streams: List<Stream>,
) {
    fun hasUrl(urls: Set<String>): Boolean = streams.any { it.url in urls }
}

/** A row in the left-hand list: Favourites, All channels, or a category. */
data class Group(
    val name: String,
    val channels: List<Channel>,
    val kind: Kind,
) {
    enum class Kind { FAVOURITES, ALL, CATEGORY }
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
