package uk.co.channelbox

import android.content.Context
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Checks, from the TV itself, which streams actually answer. Doing it here
 * (rather than on a server) means channels blocked in the UK are correctly
 * spotted as not working, and UK-only ones as working.
 */
class HealthChecker(context: Context) {

    private data class Result(val ok: Boolean, val time: Long)

    private val file = File(context.filesDir, "stream_health.txt")
    private val results = ConcurrentHashMap<String, Result>()

    init {
        load()
    }

    /** true = works, false = didn't answer, null = not checked recently. */
    fun status(url: String): Boolean? {
        val r = results[url] ?: return null
        val maxAge = if (r.ok) OK_MAX_AGE_MS else FAILED_MAX_AGE_MS
        if (System.currentTimeMillis() - r.time > maxAge) return null
        return r.ok
    }

    /** true if any stream works, false if every stream failed, null if unknown. */
    fun channelStatus(channel: Channel): Boolean? {
        var unknown = false
        for (stream in channel.streams) {
            when (status(stream.url)) {
                true -> return true
                null -> unknown = true
                false -> Unit
            }
        }
        return if (unknown) null else false
    }

    /** Streams in the order to try them: known-good first, known-bad last. */
    fun ordered(channel: Channel): List<Stream> = channel.streams.sortedBy {
        when (status(it.url)) {
            true -> 0
            null -> 1
            false -> 2
        }
    }

    /** Returns true if this changed what we knew about the stream. */
    fun record(url: String, ok: Boolean): Boolean {
        val before = status(url)
        results[url] = Result(ok, System.currentTimeMillis())
        return before != ok
    }

    /** Used by Refresh, so channels that were down get another chance. */
    fun forgetFailures() {
        results.entries.removeAll { !it.value.ok }
    }

    /**
     * Checks every channel that hasn't been checked recently. For each one,
     * streams are tried in order until one works.
     */
    fun checkAll(
        channels: List<Channel>,
        onProgress: (done: Int, total: Int) -> Unit,
        isCancelled: () -> Boolean,
    ) {
        val todo = channels.filter { channelStatus(it) == null }
        if (todo.isEmpty()) return
        val done = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        for (channel in todo) {
            pool.execute {
                if (!isCancelled()) {
                    for (stream in channel.streams) {
                        if (isCancelled()) break
                        if (status(stream.url) == false) continue
                        val ok = probe(stream)
                        results[stream.url] = Result(ok, System.currentTimeMillis())
                        if (ok) break
                    }
                }
                onProgress(done.incrementAndGet(), todo.size)
            }
        }
        pool.shutdown()
        try {
            pool.awaitTermination(15, TimeUnit.MINUTES)
        } catch (e: InterruptedException) {
            pool.shutdownNow()
        }
        save()
    }

    @Synchronized
    fun save() {
        try {
            val now = System.currentTimeMillis()
            val text = StringBuilder()
            for ((url, r) in results) {
                if (now - r.time > FAILED_MAX_AGE_MS.coerceAtLeast(OK_MAX_AGE_MS)) continue
                text.append(if (r.ok) '1' else '0').append('\t')
                    .append(r.time).append('\t')
                    .append(url).append('\n')
            }
            file.writeText(text.toString())
        } catch (e: Exception) {
            // Not important: we'll just check again next time.
        }
    }

    private fun load() {
        try {
            if (!file.exists()) return
            for (line in file.readLines()) {
                val parts = line.split('\t', limit = 3)
                if (parts.size != 3) continue
                val time = parts[1].toLongOrNull() ?: continue
                results[parts[2]] = Result(parts[0] == "1", time)
            }
        } catch (e: Exception) {
            results.clear()
        }
    }

    /** Asks the server for the start of the stream and checks it looks like video. */
    private fun probe(stream: Stream): Boolean = try {
        var url = stream.url
        var ok = false
        for (attempt in 0 until 5) {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 7_000
            conn.readTimeout = 7_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", stream.userAgent ?: Net.BROWSER_USER_AGENT)
            stream.referrer?.let { conn.setRequestProperty("Referer", it) }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: break
                    url = URL(URL(url), location).toString()
                    continue
                }
                if (code !in 200..299) break
                val head = conn.inputStream.use { readUpTo(it, 8 * 1024) }
                val text = String(head, Charsets.ISO_8859_1)
                val lowerUrl = url.lowercase()
                ok = when {
                    text.contains("#EXTM3U") -> true
                    text.contains("<MPD") -> true
                    // Said it was a playlist, but sent something else (often an error page).
                    lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") -> false
                    else -> head.isNotEmpty()
                }
                break
            } finally {
                conn.disconnect()
            }
        }
        ok
    } catch (e: Exception) {
        false
    }

    private fun readUpTo(input: InputStream, max: Int): ByteArray {
        val buffer = ByteArray(max)
        var total = 0
        while (total < max) {
            val n = input.read(buffer, total, max - total)
            if (n < 0) break
            total += n
        }
        return buffer.copyOf(total)
    }

    companion object {
        private const val OK_MAX_AGE_MS = 3L * 60 * 60 * 1000      // 3 hours
        private const val FAILED_MAX_AGE_MS = 3L * 60 * 60 * 1000  // 3 hours
    }
}
