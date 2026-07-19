package dev.sfg.orchard.connect

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ImageLoader {
    private val executor = Executors.newSingleThreadExecutor()
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean {
            return size > 24
        }
    }
    private val pending = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()

    fun load(url: String, target: ImageView, onLoaded: ((Bitmap?) -> Unit)? = null) {
        if (url.isBlank()) {
            target.setImageDrawable(null)
            onLoaded?.invoke(null)
            return
        }

        synchronized(this) { cache[url] }?.let {
            target.setImageBitmap(it)
            onLoaded?.invoke(it)
            return
        }

        val expected = url
        target.tag = expected

        val callback: (Bitmap?) -> Unit = { bitmap ->
            target.post {
                if (target.tag == expected) {
                    if (bitmap != null) target.setImageBitmap(bitmap)
                    onLoaded?.invoke(bitmap)
                }
            }
        }

        val shouldStart = synchronized(this) {
            val callbacks = pending[url]
            if (callbacks == null) {
                pending[url] = mutableListOf(callback)
                true
            } else {
                callbacks.add(callback)
                false
            }
        }
        if (!shouldStart) return

        executor.execute {
            val bitmap = try {
                val connection = URL(expected).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                connection.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
            val callbacks = synchronized(this) {
                if (bitmap != null) cache[expected] = bitmap
                pending.remove(expected).orEmpty()
            }
            callbacks.forEach { it(bitmap) }
        }
    }
}
