package uk.co.channelbox

/** One #EXTINF entry exactly as it appears in a playlist (before merging). */
data class PlaylistEntry(
    val name: String,
    val url: String,
    val logo: String?,
    val groups: List<String>,
    val tvgId: String?,
    val userAgent: String?,
    val referrer: String?,
)

data class ParsedPlaylist(
    val entries: List<PlaylistEntry>,
    /** TV guide links from the "#EXTM3U url-tvg=..." header, if any. */
    val guideUrls: List<String>,
)

/**
 * Reads standard IPTV M3U / M3U8 playlists:
 *
 *   #EXTM3U url-tvg="https://.../guide.xml.gz"     (guide link optional)
 *   #EXTINF:-1 tvg-id="x" tvg-logo="https://..." group-title="News",Channel Name (720p)
 *   #EXTVLCOPT:http-user-agent=...      (optional)
 *   #EXTVLCOPT:http-referrer=...        (optional)
 *   https://example.com/stream.m3u8
 */
object M3uParser {

    const val OTHER = "Other"

    private val ATTRIBUTE = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")

    fun looksLikePlaylist(text: String): Boolean =
        text.contains("#EXTINF", ignoreCase = true)

    fun parse(text: String): ParsedPlaylist {
        val entries = ArrayList<PlaylistEntry>()
        val guideUrls = ArrayList<String>()
        var info: String? = null
        var userAgent: String? = null
        var referrer: String? = null
        var extGroup: String? = null

        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    for (m in ATTRIBUTE.findAll(line)) {
                        val key = m.groupValues[1].lowercase()
                        if (key == "url-tvg" || key == "x-tvg-url") {
                            m.groupValues[2].split(',')
                                .map { it.trim() }
                                .filter { it.startsWith("http", ignoreCase = true) }
                                .forEach { guideUrls.add(it) }
                        }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    info = line
                    userAgent = null
                    referrer = null
                    extGroup = null
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val key = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) {
                        when (key) {
                            "http-user-agent" -> userAgent = value
                            "http-referrer", "http-referer" -> referrer = value
                        }
                    }
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    extGroup = line.substringAfter(':').trim()
                }
                line.startsWith("#") -> Unit // other directives we don't need
                else -> {
                    entries.add(buildEntry(info, line, userAgent, referrer, extGroup))
                    info = null
                    userAgent = null
                    referrer = null
                    extGroup = null
                }
            }
        }
        return ParsedPlaylist(entries, guideUrls)
    }

    private fun buildEntry(
        info: String?,
        url: String,
        userAgent: String?,
        referrer: String?,
        extGroup: String?,
    ): PlaylistEntry {
        if (info == null) {
            return PlaylistEntry(url, url, null, listOf(OTHER), null, userAgent, referrer)
        }
        val comma = titleComma(info)
        val head = if (comma >= 0) info.substring(0, comma) else info
        val title = if (comma >= 0) info.substring(comma + 1).trim() else ""

        val attrs = HashMap<String, String>()
        for (m in ATTRIBUTE.findAll(head)) {
            attrs[m.groupValues[1].lowercase()] = m.groupValues[2].trim()
        }

        val groupText = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: extGroup
        val groups = groupText
            ?.split(';')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && !it.equals("Undefined", ignoreCase = true) }
            ?.takeIf { it.isNotEmpty() }
            ?: listOf(OTHER)

        val name = title.ifEmpty { attrs["tvg-name"].orEmpty() }.ifEmpty { url }
        return PlaylistEntry(
            name = name,
            url = url,
            logo = attrs["tvg-logo"]?.takeIf { it.startsWith("http", ignoreCase = true) },
            groups = groups,
            tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
            userAgent = userAgent ?: attrs["user-agent"]?.takeIf { it.isNotBlank() },
            referrer = referrer ?: attrs["referrer"]?.takeIf { it.isNotBlank() },
        )
    }

    /** Position of the comma that separates attributes from the channel name. */
    private fun titleComma(info: String): Int {
        var inQuotes = false
        val start = info.indexOf(':').coerceAtLeast(0)
        for (i in start until info.length) {
            when (info[i]) {
                '"' -> inQuotes = !inQuotes
                ',' -> if (!inQuotes) return i
            }
        }
        return -1
    }
}
