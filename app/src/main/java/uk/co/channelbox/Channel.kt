package uk.co.channelbox

/** One live channel from an M3U playlist. */
data class Channel(
    val name: String,
    val url: String,
    val logo: String?,
    val groups: List<String>,
    val tvgId: String?,
    val userAgent: String?,
    val referrer: String?,
) {
    /** Name without the trailing "(720p)" / "[Geo-blocked]" labels. */
    val displayName: String
    /** Labels pulled off the end of the name, e.g. "720p", "Geo-blocked". */
    val tags: List<String>

    init {
        var rest = name.trim()
        val found = ArrayList<String>()
        while (true) {
            val m = TRAILING_TAG.find(rest) ?: break
            val tag = (m.groups[1] ?: m.groups[2])?.value?.trim().orEmpty()
            if (tag.isNotEmpty()) found.add(0, tag)
            rest = rest.substring(0, m.range.first).trim()
        }
        displayName = rest.ifEmpty { name.trim() }
        tags = found
    }

    companion object {
        // Matches a trailing "[Anything]" or "(1080p)" style label.
        private val TRAILING_TAG = Regex("""\s*(?:\[([^\[\]]*)]|\((\d{3,4}[pi])\))\s*$""")
    }
}

/** A row in the left-hand list: Favourites, All channels, or a category. */
data class Group(
    val name: String,
    val channels: List<Channel>,
    val kind: Kind,
) {
    enum class Kind { FAVOURITES, ALL, CATEGORY }
}
