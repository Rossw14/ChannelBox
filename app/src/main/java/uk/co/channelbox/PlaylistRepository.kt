package uk.co.channelbox

import android.content.Context
import java.io.File

data class PlaylistSource(val id: String, val label: String, val url: String)

/**
 * Downloads the chosen playlists, keeps a copy on the TV so the app still
 * starts if a list can't be reached, and merges them into one channel list.
 */
object PlaylistRepository {

    private const val BASE = "https://iptv-org.github.io/iptv"

    /** Free, publicly available channel lists maintained by the iptv-org project. */
    val BUILT_IN = listOf(
        PlaylistSource("uk", "UK channels", "$BASE/countries/uk.m3u"),
        PlaylistSource("ie", "Ireland channels", "$BASE/countries/ie.m3u"),
        PlaylistSource("news", "News channels (worldwide)", "$BASE/categories/news.m3u"),
        PlaylistSource("eng", "English-language channels (worldwide, large)", "$BASE/languages/eng.m3u"),
    )

    private const val CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1000 // 6 hours

    data class LoadResult(val channels: List<Channel>, val errors: List<String>)

    fun load(context: Context, prefs: Prefs, forceRefresh: Boolean): LoadResult {
        val sources = ArrayList<PlaylistSource>()
        val custom = prefs.customUrl.trim()
        if (custom.isNotEmpty()) sources.add(PlaylistSource("custom", "Your playlist", custom))
        val enabled = prefs.enabledSources
        sources.addAll(BUILT_IN.filter { it.id in enabled })

        val byUrl = LinkedHashMap<String, Channel>()
        val errors = ArrayList<String>()

        for (source in sources) {
            val cache = File(context.filesDir, "playlist_${source.id}_${source.url.hashCode()}.m3u")
            val isFresh = cache.exists() &&
                System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS

            val text: String? = if (!forceRefresh && isFresh) {
                cache.readText()
            } else {
                try {
                    val downloaded = String(Net.get(source.url), Charsets.UTF_8)
                    if (!M3uParser.looksLikePlaylist(downloaded)) {
                        throw IllegalStateException("that link isn't an M3U playlist")
                    }
                    cache.writeText(downloaded)
                    downloaded
                } catch (e: Exception) {
                    errors.add("${source.label}: ${e.message ?: "couldn't download"}")
                    if (cache.exists()) cache.readText() else null
                }
            }

            if (text != null) {
                for (channel in M3uParser.parse(text)) {
                    if (!byUrl.containsKey(channel.url)) byUrl[channel.url] = channel
                }
            }
        }
        return LoadResult(byUrl.values.toList(), errors)
    }
}
