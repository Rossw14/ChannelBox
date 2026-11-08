package uk.co.channelbox

import android.content.Context

/** Settings, favourites and history, stored on the TV. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("channelbox", Context.MODE_PRIVATE)

    /** Load the free iptv-org channels. */
    var iptvOrgEnabled: Boolean
        get() = sp.getBoolean(KEY_IPTV_ORG, true)
        set(value) = sp.edit().putBoolean(KEY_IPTV_ORG, value).apply()

    /** Also load the Free-TV list (broadcasters' own free streams). */
    var freeTvEnabled: Boolean
        get() = sp.getBoolean(KEY_FREE_TV, true)
        set(value) = sp.edit().putBoolean(KEY_FREE_TV, value).apply()

    /** Show every country rather than just the ones picked. */
    var allCountries: Boolean
        get() = sp.getBoolean(KEY_ALL_COUNTRIES, false)
        set(value) = sp.edit().putBoolean(KEY_ALL_COUNTRIES, value).apply()

    var countries: Set<String>
        get() = sp.getStringSet(KEY_COUNTRIES, null)?.toSet() ?: DEFAULT_COUNTRIES
        set(value) = sp.edit().putStringSet(KEY_COUNTRIES, HashSet(value)).apply()

    /** Use the playlist link below (it's kept even when switched off). */
    var customEnabled: Boolean
        get() = sp.getBoolean(KEY_CUSTOM_ENABLED, true)
        set(value) = sp.edit().putBoolean(KEY_CUSTOM_ENABLED, value).apply()

    var customUrl: String
        get() = sp.getString(KEY_CUSTOM_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_CUSTOM_URL, value.trim()).apply()

    /** Channel ids (older versions stored stream links; both are understood). */
    var favourites: Set<String>
        get() = sp.getStringSet(KEY_FAVOURITES, null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_FAVOURITES, HashSet(value)).apply()

    /** Most recently watched channel ids, newest first. */
    var recent: List<String>
        get() = sp.getString(KEY_RECENT, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) = sp.edit().putString(KEY_RECENT, value.joinToString("\n")).apply()

    /** Channel id, or a stream link saved by version 1.0. */
    var lastChannel: String?
        get() = sp.getString(KEY_LAST_CHANNEL, null)
        set(value) = sp.edit().putString(KEY_LAST_CHANNEL, value).apply()

    /** Highest picture quality to use (e.g. 720), or 0 for automatic. */
    var maxHeight: Int
        get() = sp.getInt(KEY_MAX_HEIGHT, 0)
        set(value) = sp.edit().putInt(KEY_MAX_HEIGHT, value).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) = sp.edit().putLong(KEY_LAST_UPDATE_CHECK, value).apply()

    /** Show the Apps tab (Netflix, Prime Video…) in the menu row. */
    var appsTab: Boolean
        get() = sp.getBoolean(KEY_APPS_TAB, true)
        set(value) = sp.edit().putBoolean(KEY_APPS_TAB, value).apply()

    /** Apps chosen in Settings, or null to pick the usual streaming apps automatically. */
    var chosenApps: Set<String>?
        get() = sp.getStringSet(KEY_CHOSEN_APPS, null)?.toSet()
        set(value) {
            if (value == null) sp.edit().remove(KEY_CHOSEN_APPS).apply()
            else sp.edit().putStringSet(KEY_CHOSEN_APPS, HashSet(value)).apply()
        }

    /** Free TMDB key for the home page's film rows (blank = use the one built in, if any). */
    var tmdbKey: String
        get() = sp.getString(KEY_TMDB, "") ?: ""
        set(value) = sp.edit().putString(KEY_TMDB, value).apply()

    var hideOffline: Boolean
        get() = sp.getBoolean(KEY_HIDE_OFFLINE, true)
        set(value) = sp.edit().putBoolean(KEY_HIDE_OFFLINE, value).apply()

    /** Bumped whenever settings are saved, so the main screen knows to reload. */
    var settingsVersion: Int
        get() = sp.getInt(KEY_VERSION, 0)
        set(value) = sp.edit().putInt(KEY_VERSION, value).apply()

    companion object {
        private const val KEY_IPTV_ORG = "iptv_org"
        private const val KEY_ALL_COUNTRIES = "all_countries"
        private const val KEY_FREE_TV = "free_tv"
        private const val KEY_COUNTRIES = "countries"
        private const val KEY_CUSTOM_URL = "custom_url"
        private const val KEY_CUSTOM_ENABLED = "custom_enabled"
        private const val KEY_FAVOURITES = "favourites"
        private const val KEY_RECENT = "recent"
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_HIDE_OFFLINE = "hide_offline"
        private const val KEY_APPS_TAB = "apps_tab"
        private const val KEY_TMDB = "tmdb_key"
        private const val KEY_CHOSEN_APPS = "chosen_apps"
        private const val KEY_MAX_HEIGHT = "max_height"
        private const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        private const val KEY_VERSION = "settings_version"

        const val HOME_COUNTRY = "United Kingdom"
        val DEFAULT_COUNTRIES = setOf(
            "United Kingdom", "Ireland", "United States", "Canada", "Australia", "New Zealand",
        )
    }
}
