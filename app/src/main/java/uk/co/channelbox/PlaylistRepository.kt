package uk.co.channelbox

import android.content.Context
import java.io.File

/**
 * Downloads the channel lists, keeps a copy on the TV so the app still starts
 * if a list can't be reached, and merges everything into one channel list.
 *
 * The free channels come from iptv-org's full list (about 10,000 channels).
 * Two versions of it are downloaded: one grouped by category and one grouped
 * by country. They're joined by stream link so every channel knows both.
 */
object PlaylistRepository {

    private const val BASE = "https://iptv-org.github.io/iptv"
    private const val BY_CATEGORY_URL = "$BASE/index.m3u"
    private const val BY_COUNTRY_URL = "$BASE/index.country.m3u"

    /** The Free-TV project's list of channels broadcasters provide for free. */
    private const val FREE_TV_URL = "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"
    private val BARE_IP_HOST = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
    private val FREE_TV_MARKS = Regex("[ⒼⓈⓎ]")

    private const val CACHE_MAX_AGE_MS = 12L * 60 * 60 * 1000 // 12 hours
    private const val COUNTRIES_FILE = "countries.txt"

    data class LoadResult(
        val channels: List<Channel>,
        val guideUrls: List<String>,
        val errors: List<String>,
    )

    fun load(context: Context, prefs: Prefs, forceRefresh: Boolean): LoadResult {
        val entries = ArrayList<PlaylistEntry>()
        val guideUrls = ArrayList<String>()
        val errors = ArrayList<String>()
        val customUrls = HashSet<String>()
        val countryByUrl = HashMap<String, MutableSet<String>>()

        val custom = prefs.customUrl.trim()
        if (prefs.customEnabled && custom.isNotEmpty()) {
            fetch(context, "custom", custom, "Your playlist", forceRefresh, errors)?.let { text ->
                val parsed = M3uParser.parse(text)
                entries.addAll(parsed.entries)
                parsed.entries.forEach { customUrls.add(it.url) }
                guideUrls.addAll(parsed.guideUrls)
            }
        }

        if (prefs.iptvOrgEnabled) {
            fetch(context, "iptvorg_category", BY_CATEGORY_URL, "Free channels", forceRefresh, errors)?.let {
                entries.addAll(M3uParser.parse(it).entries)
            }
            fetch(context, "iptvorg_country", BY_COUNTRY_URL, "Free channels by country", forceRefresh, errors)?.let {
                for (e in M3uParser.parse(it).entries) {
                    countryByUrl.getOrPut(e.url) { LinkedHashSet() }.addAll(e.groups)
                }
            }
        }

        if (prefs.freeTvEnabled) {
            fetch(context, "freetv", FREE_TV_URL, "Free-TV list", forceRefresh, errors)?.let { text ->
                for ((entry, country) in parseFreeTv(text)) {
                    entries.add(entry)
                    if (country != null) countryByUrl.getOrPut(entry.url) { LinkedHashSet() }.add(country)
                }
            }
        }

        val channels = ChannelMerger.merge(entries, countryByUrl, customUrls)
        saveCountryList(context, channels)
        return LoadResult(channels, guideUrls.distinct(), errors)
    }

    /**
     * Free-TV groups its list by country rather than category, marks channels
     * with symbols (Ⓖ geo-blocked etc.) and includes some on-demand sections.
     * Keep the live channels, skip links that point at a bare IP address
     * (re-broadcasts rather than the broadcaster's own service), and work out
     * each channel's country from its tvg-country code.
     */
    private fun parseFreeTv(text: String): List<Pair<PlaylistEntry, String?>> {
        val result = ArrayList<Pair<PlaylistEntry, String?>>()
        val countryCodes = HashMap<String, String>()
        for (line in text.lineSequence()) {
            if (!line.startsWith("#EXTINF")) continue
            val code = Regex("""tvg-country="([^"]*)"""").find(line)?.groupValues?.get(1)
            val name = line.substringAfterLast(',').trim()
            if (code != null) countryCodes[name] = code
        }
        for (e in M3uParser.parse(text).entries) {
            if (e.groups.any { it.startsWith("VOD", ignoreCase = true) }) continue
            val host = try { java.net.URI(e.url).host.orEmpty() } catch (ex: Exception) { "" }
            if (host.isEmpty() || BARE_IP_HOST.matches(host)) continue

            val cleanName = FREE_TV_MARKS.replace(e.name, "").trim()
            val country = countryCodes[e.name.trim()]?.let { countryName(it) }
                ?: e.groups.firstOrNull()?.takeIf { it != M3uParser.OTHER }
            val category = if (cleanName.contains("radio", ignoreCase = true)) "Radio" else "General"
            result.add(Pair(e.copy(name = cleanName, groups = listOf(category)), country))
        }
        return result
    }

    /** "GB" -> "United Kingdom", matching how iptv-org names countries. */
    private fun countryName(code: String): String? {
        val upper = code.trim().uppercase()
        if (upper.length != 2) return null
        if (upper == "UK" || upper == "GB") return "United Kingdom"
        val name = java.util.Locale("", upper).getDisplayCountry(java.util.Locale.ENGLISH)
        return name.takeIf { it.isNotBlank() && !it.equals(upper, ignoreCase = true) }
    }

    /** Countries and how many channels each has, for the Settings screen. */
    fun savedCountries(context: Context): List<Pair<String, Int>> = try {
        File(context.filesDir, COUNTRIES_FILE).readLines().mapNotNull { line ->
            val name = line.substringBefore('\t')
            val count = line.substringAfter('\t', "").toIntOrNull()
            if (name.isEmpty() || count == null) null else Pair(name, count)
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveCountryList(context: Context, channels: List<Channel>) {
        val counts = HashMap<String, Int>()
        for (c in channels) for (country in c.countries) counts[country] = (counts[country] ?: 0) + 1
        if (counts.isEmpty()) return
        try {
            File(context.filesDir, COUNTRIES_FILE).writeText(
                counts.entries.sortedBy { it.key.lowercase() }
                    .joinToString("\n") { "${it.key}\t${it.value}" }
            )
        } catch (e: Exception) {
            // Settings will fall back to a short default list.
        }
    }

    private fun fetch(
        context: Context,
        id: String,
        url: String,
        label: String,
        forceRefresh: Boolean,
        errors: MutableList<String>,
    ): String? {
        val cache = File(context.filesDir, "playlist_${id}_${url.hashCode()}.m3u")
        val isFresh = cache.exists() &&
            System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS
        if (!forceRefresh && isFresh) return cache.readText()
        return try {
            val downloaded = String(Net.get(url), Charsets.UTF_8)
            if (!M3uParser.looksLikePlaylist(downloaded)) {
                throw IllegalStateException("that link isn't an M3U playlist")
            }
            cache.writeText(downloaded)
            downloaded
        } catch (e: Exception) {
            errors.add("$label: ${e.message ?: "couldn't download"}")
            if (cache.exists()) cache.readText() else null
        }
    }
}
