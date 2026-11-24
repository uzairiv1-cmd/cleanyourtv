package app.iptvplayer.tv.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import app.iptvplayer.tv.data.Http
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Channel logos and posters, without an image library: four threads, a memory cache, and a check
 * before each download that the view still wants that picture (fast scrolling skips the rest).
 */
object ImageLoader {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val failed = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val wanted = Collections.synchronizedMap(WeakHashMap<ImageView, String>())
    private val pool = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun load(view: ImageView, url: String?, placeholder: Int, targetPx: Int) {
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://")) || url in failed) {
            wanted.remove(view)
            view.setImageResource(placeholder)
            return
        }
        cache.get(url)?.let { wanted.remove(view); view.setImageBitmap(it); return }
        wanted[view] = url
        view.setImageResource(placeholder)
        pool.execute {
            if (wanted[view] != url) return@execute
            val bmp = try { decode(Http.bytes(url, 4 * 1024 * 1024), targetPx) } catch (e: Exception) { null }
            if (bmp == null) { failed.add(url); return@execute }
            cache.put(url, bmp)
            main.post { if (wanted[view] == url) { wanted.remove(view); view.setImageBitmap(bmp) } }
        }
    }

    private fun decode(data: ByteArray, targetPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.RGB_565
        })
    }
}
