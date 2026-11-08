package uk.co.channelbox

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.Collections
import java.util.concurrent.Executors

/** Loads channel logos in the background with a small in-memory cache. */
object LogoLoader {
    private const val TARGET_SIZE = 128

    private val cache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }
    private val failed: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(url: String?, view: ImageView, targetSize: Int = TARGET_SIZE) {
        view.setTag(R.id.logo_url_tag, url)
        view.setImageDrawable(null)
        if (url.isNullOrBlank() || failed.contains(url)) return

        cache.get(url)?.let {
            view.setImageBitmap(it)
            return
        }

        pool.execute {
            val bitmap = try {
                decode(Net.get(url, maxBytes = 3 * 1024 * 1024), targetSize)
            } catch (e: Exception) {
                null
            }
            if (bitmap == null) failed.add(url) else cache.put(url, bitmap)
            main.post {
                if (bitmap != null && view.getTag(R.id.logo_url_tag) == url) {
                    view.setImageBitmap(bitmap)
                }
            }
        }
    }

    private val pending: MutableSet<String> = Collections.synchronizedSet(HashSet())

    /** A logo that's already loaded, or null. Used by the TV guide grid. */
    fun cached(url: String?): Bitmap? = if (url.isNullOrBlank()) null else cache.get(url)

    /** Starts loading a logo in the background and calls [onReady] when it arrives. */
    fun request(url: String?, onReady: () -> Unit) {
        if (url.isNullOrBlank() || failed.contains(url) || cache.get(url) != null) return
        if (!pending.add(url)) return
        pool.execute {
            val bitmap = try {
                decode(Net.get(url, maxBytes = 3 * 1024 * 1024))
            } catch (e: Exception) {
                null
            }
            pending.remove(url)
            if (bitmap == null) failed.add(url) else cache.put(url, bitmap)
            if (bitmap != null) main.post { onReady() }
        }
    }

    private fun decode(bytes: ByteArray, targetSize: Int = TARGET_SIZE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetSize &&
            bounds.outHeight / (sample * 2) >= targetSize
        ) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
