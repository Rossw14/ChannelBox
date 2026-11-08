package uk.co.channelbox

/**
 * Turns playlist entries into one channel each, so "Sky News (720p)" and
 * "Sky News (1080p)" become a single "Sky News" with two streams behind it.
 * Entries are matched by their tvg-id, or by name when there isn't one.
 */
object ChannelMerger {

    private val QUALITY = Regex("""^(\d{3,4})[pi]$""")

    fun merge(entries: List<PlaylistEntry>): List<Channel> {
        val buckets = LinkedHashMap<String, MutableList<PlaylistEntry>>()
        for (entry in entries) {
            buckets.getOrPut(keyFor(entry)) { ArrayList() }.add(entry)
        }
        return buckets.map { (key, list) -> build(key, list) }
    }

    private fun baseId(tvgId: String?): String? =
        tvgId?.substringBefore('@')?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    private fun keyFor(entry: PlaylistEntry): String {
        baseId(entry.tvgId)?.let { return "id:$it" }
        val simple = Names.split(entry.name).first.lowercase().filter { it.isLetterOrDigit() }
        return if (simple.isNotEmpty()) "name:$simple" else "url:${entry.url}"
    }

    private fun build(key: String, list: List<PlaylistEntry>): Channel {
        val first = list.first()

        val groups = LinkedHashSet<String>()
        list.forEach { groups.addAll(it.groups) }
        if (groups.size > 1) groups.remove(M3uParser.OTHER)

        val seenUrls = HashSet<String>()
        val streams = list
            .filter { seenUrls.add(it.url) }
            .map { Stream(it.url, it.userAgent, it.referrer, Names.split(it.name).second) }
            .sortedBy { score(it) }

        return Channel(
            id = key,
            name = Names.split(first.name).first,
            logo = list.firstNotNullOfOrNull { it.logo },
            groups = groups.toList(),
            guideId = list.firstNotNullOfOrNull { baseId(it.tvgId) },
            streams = streams,
        )
    }

    /** Lower is better: prefer HD (up to 1080p) and streams that run all day. */
    private fun score(stream: Stream): Int {
        val quality = stream.tags.firstNotNullOfOrNull { QUALITY.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        var score = -(quality?.coerceAtMost(1080) ?: 500)
        if (stream.tags.any { it.equals("Not 24/7", ignoreCase = true) }) score += 5000
        return score
    }
}
