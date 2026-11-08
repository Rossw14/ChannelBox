package uk.co.channelbox

import android.content.Context

/** Settings and favourites, stored on the TV. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("channelbox", Context.MODE_PRIVATE)

    var enabledSources: Set<String>
        get() = sp.getStringSet(KEY_SOURCES, null)?.toSet() ?: DEFAULT_SOURCES
        set(value) = sp.edit().putStringSet(KEY_SOURCES, HashSet(value)).apply()

    var customUrl: String
        get() = sp.getString(KEY_CUSTOM_URL, "") ?: ""
        set(value) = sp.edit().putString(KEY_CUSTOM_URL, value.trim()).apply()

    /** Channel ids (older versions stored stream links; both are understood). */
    var favourites: Set<String>
        get() = sp.getStringSet(KEY_FAVOURITES, null)?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_FAVOURITES, HashSet(value)).apply()

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
        private const val KEY_SOURCES = "sources"
        private const val KEY_CUSTOM_URL = "custom_url"
        private const val KEY_FAVOURITES = "favourites"
        private const val KEY_LAST_CHANNEL = "last_channel"
        private const val KEY_HIDE_OFFLINE = "hide_offline"
        private const val KEY_VERSION = "settings_version"
        val DEFAULT_SOURCES = setOf("uk")
    }
}
