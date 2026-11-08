package uk.co.channelbox

import android.content.Context

/** Settings, favourites and history, stored on the TV. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("channelbox", Context.MODE_PRIVATE)

    /** Load the free iptv-org channels. */
    var iptvOrgEnabled: Boolean
        get() = sp.getBoolean(KEY_IPTV_ORG, true)
        set(value) = sp.edit().putBoolean(KEY_IPTV_ORG, value).apply()

    /** Show every country rather than just the ones picked. */
    var allCountries: Boolean
        get() = sp.getBoolean(KEY_ALL_COUNTRIES, false)
        set(value) = sp.edit().putBoolean(KEY_ALL_COUNTRIES, value).apply()

    var countries: Set<String>
        get() = sp.getStringSet(KEY_COUNTRIES, null)?.toSet() ?: DEFAULT_COUNTRIES
        set(value) = sp.edit().putStringSet(KEY_COUNTRIES, HashSet(value)).apply()

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
        private const val KEY_COUNTRIES = "countries"
        private const val KEY_CUSTOM_URL = "custom_url"
        private const val KEY_FAVOURITES = "favourites"
        private const val KEY_RECENT = "recent"
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_HIDE_OFFLINE = "hide_offline"
        private const val KEY_VERSION = "settings_version"

        const val HOME_COUNTRY = "United Kingdom"
        val DEFAULT_COUNTRIES = setOf(
            "United Kingdom", "Ireland", "United States", "Canada", "Australia", "New Zealand",
        )
    }
}
