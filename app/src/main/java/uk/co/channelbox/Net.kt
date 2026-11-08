package uk.co.channelbox

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Small HTTP helper for playlists and logos (follows http <-> https redirects). */
object Net {
    const val USER_AGENT = "ChannelBox/1.1 (Android TV)"

    /** Sent to stream servers, many of which only answer normal browsers. */
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    fun get(
        url: String,
        maxBytes: Int = 40 * 1024 * 1024,
        headers: Map<String, String> = emptyMap(),
    ): ByteArray {
        var current = url
        for (attempt in 0 until 6) {
            val conn = URL(current).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            for ((name, value) in headers) conn.setRequestProperty(name, value)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                        ?: throw IOException("Redirect with no destination")
                    current = URL(URL(current), location).toString()
                    continue
                }
                if (code !in 200..299) throw IOException("Server replied HTTP $code")
                conn.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > maxBytes) throw IOException("File too large")
                        out.write(buffer, 0, n)
                    }
                    return out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }
}
