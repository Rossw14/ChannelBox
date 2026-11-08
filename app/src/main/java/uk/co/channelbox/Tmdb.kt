package uk.co.channelbox

import org.json.JSONObject
import java.net.URLEncoder

/** A film or series from TMDB. */
data class Title(
    val id: Int,
    val isMovie: Boolean,
    val name: String,
    val year: String?,
    val overview: String,
    val posterUrl: String?,
    val rating: Double,
)

/**
 * Film and series information from The Movie Database (TMDB), including
 * which UK streaming services have each title (TMDB gets that from JustWatch).
 * Needs a free personal API key, from Settings or the TMDB_API_KEY GitHub secret.
 */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"
    private const val POSTERS = "https://image.tmdb.org/t/p/w342"
    const val REGION = "GB"

    data class Provider(val id: Int, val name: String)

    fun key(prefs: Prefs): String = prefs.tmdbKey.trim().ifEmpty { BuildConfig.TMDB_KEY.trim() }

    private fun call(key: String, path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val all = LinkedHashMap(params)
        all["language"] = "en-GB"
        val headers = HashMap<String, String>()
        // Short keys are the classic "API key"; long ones are the newer read-access token.
        if (key.length > 40) headers["Authorization"] = "Bearer $key" else all["api_key"] = key
        val query = all.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val bytes = Net.get("$API/$path?$query", maxBytes = 4 * 1024 * 1024, headers = headers)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun titles(json: JSONObject, isMovie: Boolean): List<Title> {
        val results = json.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Title>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val name = if (isMovie) o.optString("title") else o.optString("name")
            val date = if (isMovie) o.optString("release_date") else o.optString("first_air_date")
            val poster = o.optString("poster_path").takeIf { it.isNotEmpty() && it != "null" }
            if (name.isBlank() || poster == null) continue
            out.add(
                Title(
                    id = o.optInt("id"),
                    isMovie = isMovie,
                    name = name,
                    year = date.take(4).takeIf { it.length == 4 },
                    overview = o.optString("overview"),
                    posterUrl = POSTERS + poster,
                    rating = o.optDouble("vote_average", 0.0),
                )
            )
        }
        return out
    }

    fun trending(key: String, movies: Boolean): List<Title> =
        titles(call(key, "trending/${if (movies) "movie" else "tv"}/week"), movies)

    /** Streaming services TMDB knows about in the UK. */
    fun providers(key: String): List<Provider> {
        val results = call(key, "watch/providers/movie", mapOf("watch_region" to REGION)).optJSONArray("results")
            ?: return emptyList()
        val out = ArrayList<Provider>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            out.add(Provider(o.optInt("provider_id"), o.optString("provider_name")))
        }
        return out
    }

    /** Popular films and series included with a subscription to this service. */
    fun popularOn(key: String, providerId: Int): List<Title> {
        val params = mapOf(
            "watch_region" to REGION,
            "with_watch_providers" to providerId.toString(),
            "with_watch_monetization_types" to "flatrate",
            "sort_by" to "popularity.desc",
        )
        val films = titles(call(key, "discover/movie", params), true)
        val series = titles(call(key, "discover/tv", params), false)
        val mixed = ArrayList<Title>()
        for (i in 0 until maxOf(films.size, series.size)) {
            series.getOrNull(i)?.let { mixed.add(it) }
            films.getOrNull(i)?.let { mixed.add(it) }
        }
        return mixed.take(20)
    }

    /** Which UK subscription services include this title, e.g. ["Netflix"]. */
    fun whereToWatch(key: String, title: Title): List<String> {
        val json = call(key, "${if (title.isMovie) "movie" else "tv"}/${title.id}/watch/providers")
        val flatrate = json.optJSONObject("results")?.optJSONObject(REGION)?.optJSONArray("flatrate")
            ?: return emptyList()
        val names = LinkedHashSet<String>()
        for (i in 0 until flatrate.length()) {
            flatrate.optJSONObject(i)?.optString("provider_name")?.takeIf { it.isNotBlank() }?.let { names.add(it) }
        }
        return names.toList()
    }

    /** Matches a TMDB service to an app on the TV, e.g. "Amazon Prime Video" to "Prime Video". */
    fun matches(provider: String, app: TvApp): Boolean {
        val p = normalise(provider)
        val a = normalise(app.label)
        if (a.length < 3 || p.length < 3) return false
        return p == a || p.contains(a) || a.contains(p)
    }

    private fun normalise(name: String): String =
        name.lowercase().replace("+", "plus").filter { it.isLetterOrDigit() }
            .removeSuffix("plus").let { if (it.endsWith("tv") && it.length > 4) it.removeSuffix("tv") else it }
}
