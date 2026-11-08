package uk.co.channelbox

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable

/** A streaming (or any other) app installed on the TV. */
data class TvApp(val packageName: String, val label: String)

/**
 * Finds the apps installed on the TV so the Apps tab can open them.
 * Netflix, Prime Video and the like don't let other apps play their
 * programmes, but any app can open them.
 */
object AppsRepository {

    /** Not a real app: the first tile, which opens Google TV's search. */
    const val SEARCH_TILE = "channelbox.search"

    /**
     * Hands a search to Google TV, which looks across Netflix, Prime Video,
     * Disney+, iPlayer and the rest, and plays results in the right app.
     */
    fun searchFilmsAndShows(context: Context, query: String): Boolean {
        val attempts = listOf(
            Intent("android.search.action.GLOBAL_SEARCH"),
            Intent(Intent.ACTION_WEB_SEARCH),
        )
        for (intent in attempts) {
            if (query.isNotBlank()) intent.putExtra(SearchManager.QUERY, query)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return true
            } catch (e: ActivityNotFoundException) {
                // try the next way
            } catch (e: SecurityException) {
                // try the next way
            }
        }
        return false
    }

    /** Names picked automatically until you choose your own in Settings. */
    private val USUAL_STREAMING = listOf(
        "netflix", "prime video", "disney", "youtube", "iplayer", "itvx", "channel 4", "my5", "5",
        "now", "apple tv", "paramount", "sky", "plex", "pluto", "spotify", "u", "britbox", "discovery",
        "rakuten", "hayu", "mubi", "dazn", "tnt sports", "twitch",
    )

    fun installedApps(context: Context): List<TvApp> {
        val pm = context.packageManager
        val found = LinkedHashMap<String, TvApp>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (info in pm.queryIntentActivities(query, 0)) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || found.containsKey(pkg)) continue
                found[pkg] = TvApp(pkg, info.loadLabel(pm).toString())
            }
        }
        return found.values.sortedBy { it.label.lowercase() }
    }

    /** The apps to show: your choice from Settings, or the usual streaming apps. */
    fun chosenApps(context: Context, prefs: Prefs): List<TvApp> {
        val installed = installedApps(context)
        val chosen = prefs.chosenApps
        return if (chosen != null) {
            installed.filter { it.packageName in chosen }
        } else {
            installed.filter { looksLikeStreaming(it) }
        }
    }

    fun looksLikeStreaming(app: TvApp): Boolean {
        val label = app.label.lowercase()
        // Whole words only, so "now" doesn't match "Snow" and "u" only matches U.
        return USUAL_STREAMING.any { word ->
            Regex("(^|[^a-z0-9])" + Regex.escape(word) + "($|[^a-z0-9])").containsMatchIn(label)
        }
    }

    /** The app's wide TV banner if it has one, otherwise its icon. */
    fun artwork(context: Context, app: TvApp): Drawable? {
        val pm = context.packageManager
        return try {
            pm.getApplicationBanner(app.packageName) ?: pm.getApplicationIcon(app.packageName)
        } catch (e: Exception) {
            null
        }
    }

    fun launch(context: Context, app: TvApp): Boolean {
        val pm = context.packageManager
        val intent = pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: pm.getLaunchIntentForPackage(app.packageName)
            ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
