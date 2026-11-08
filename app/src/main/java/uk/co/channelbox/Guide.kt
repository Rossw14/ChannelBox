package uk.co.channelbox

import android.content.Context
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.Calendar
import java.util.TimeZone
import java.util.zip.GZIPInputStream

data class Programme(
    val start: Long,
    val stop: Long,
    val title: String,
    val subtitle: String?,
    val description: String?,
)

/** TV listings for the channels we have, keyed by base tvg-id. */
class GuideData(private val byChannel: Map<String, List<Programme>>) {

    val isEmpty: Boolean get() = byChannel.isEmpty()
    val channelCount: Int get() = byChannel.size

    private fun listFor(channel: Channel): List<Programme> =
        channel.guideId?.let { byChannel[it] } ?: emptyList()

    fun current(channel: Channel, now: Long): Programme? =
        listFor(channel).firstOrNull { it.start <= now && it.stop > now }

    fun next(channel: Channel, now: Long): Programme? =
        listFor(channel).firstOrNull { it.start > now }

    fun upcoming(channel: Channel, now: Long): List<Programme> =
        listFor(channel).filter { it.stop > now }

    companion object {
        val EMPTY = GuideData(emptyMap())
    }
}

/**
 * Downloads XMLTV guide files (plain or .gz), keeps a copy on the TV and
 * pulls out the next day and a half of listings for our channels.
 */
object GuideRepository {

    private const val CACHE_MAX_AGE_MS = 6L * 60 * 60 * 1000 // 6 hours
    private const val KEEP_BEFORE_MS = 2L * 60 * 60 * 1000
    private const val KEEP_AFTER_MS = 36L * 60 * 60 * 1000

    data class LoadResult(val data: GuideData, val configured: Boolean, val errors: List<String>)

    fun load(context: Context, urls: List<String>, wanted: Set<String>, forceRefresh: Boolean): LoadResult {
        if (urls.isEmpty()) return LoadResult(GuideData.EMPTY, configured = false, errors = emptyList())

        val byChannel = HashMap<String, MutableList<Programme>>()
        val errors = ArrayList<String>()

        for (url in urls) {
            val cache = File(context.filesDir, "guide_${url.hashCode()}.bin")
            val isFresh = cache.exists() &&
                System.currentTimeMillis() - cache.lastModified() < CACHE_MAX_AGE_MS
            if (forceRefresh || !isFresh) {
                try {
                    cache.writeBytes(Net.get(url, maxBytes = 80 * 1024 * 1024))
                } catch (e: Exception) {
                    errors.add(e.message ?: "couldn't download")
                }
            }
            if (cache.exists()) {
                try {
                    parse(cache, wanted, byChannel)
                } catch (e: Exception) {
                    errors.add("couldn't read the guide (${e.message})")
                }
            }
        }

        val tidy = HashMap<String, List<Programme>>()
        for ((id, list) in byChannel) {
            list.sortBy { it.start }
            val clean = ArrayList<Programme>()
            for (p in list) {
                val last = clean.lastOrNull()
                if (last != null && p.start < last.stop) continue // overlap / duplicate
                clean.add(p)
            }
            tidy[id] = clean
        }
        return LoadResult(GuideData(tidy), configured = true, errors = errors)
    }

    private fun parse(file: File, wanted: Set<String>, into: HashMap<String, MutableList<Programme>>) {
        val now = System.currentTimeMillis()
        val from = now - KEEP_BEFORE_MS
        val to = now + KEEP_AFTER_MS

        BufferedInputStream(FileInputStream(file), 64 * 1024).use { raw ->
            raw.mark(4)
            val b1 = raw.read()
            val b2 = raw.read()
            raw.reset()
            val input: InputStream = if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(raw, 64 * 1024) else raw

            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            var inProgramme = false
            var channel = ""
            var start = 0L
            var stop = 0L
            var title: String? = null
            var subtitle: String? = null
            var description: String? = null

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    when (parser.name) {
                        "programme" -> {
                            val id = parser.getAttributeValue(null, "channel")
                                ?.substringBefore('@')?.trim()?.lowercase()
                            val s = parseTime(parser.getAttributeValue(null, "start"))
                            val e = parseTime(parser.getAttributeValue(null, "stop"))
                            if (id != null && id in wanted && s != null && e != null && e > from && s < to) {
                                inProgramme = true
                                channel = id
                                start = s
                                stop = e
                                title = null
                                subtitle = null
                                description = null
                            } else {
                                inProgramme = false
                                skip(parser)
                            }
                        }
                        "title" -> if (inProgramme && title == null) title = parser.nextText().trim()
                        "sub-title" -> if (inProgramme && subtitle == null) subtitle = parser.nextText().trim()
                        "desc" -> if (inProgramme && description == null) description = parser.nextText().trim()
                    }
                } else if (event == XmlPullParser.END_TAG && parser.name == "programme" && inProgramme) {
                    val list = into.getOrPut(channel) { ArrayList() }
                    list.add(
                        Programme(
                            start = start,
                            stop = stop,
                            title = title?.ifEmpty { null } ?: "Programme",
                            subtitle = subtitle?.ifEmpty { null },
                            description = description?.ifEmpty { null },
                        )
                    )
                    inProgramme = false
                }
                event = parser.next()
            }
        }
    }

    private fun skip(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** XMLTV times look like "20261108140000 +0000". */
    private fun parseTime(value: String?): Long? {
        val t = value?.trim() ?: return null
        if (t.length < 12) return null
        val year = t.substring(0, 4).toIntOrNull() ?: return null
        val month = t.substring(4, 6).toIntOrNull() ?: return null
        val day = t.substring(6, 8).toIntOrNull() ?: return null
        val hour = t.substring(8, 10).toIntOrNull() ?: return null
        val minute = t.substring(10, 12).toIntOrNull() ?: return null
        val second = if (t.length >= 14) t.substring(12, 14).toIntOrNull() ?: 0 else 0

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(year, month - 1, day, hour, minute, second)
        var millis = cal.timeInMillis

        val offset = if (t.length > 14) t.substring(14).trim() else ""
        if (offset.length >= 5 && (offset[0] == '+' || offset[0] == '-')) {
            val hh = offset.substring(1, 3).toIntOrNull() ?: 0
            val mm = offset.substring(3, 5).toIntOrNull() ?: 0
            val sign = if (offset[0] == '-') -1 else 1
            millis -= sign * (hh * 60L + mm) * 60_000L
        }
        return millis
    }
}
