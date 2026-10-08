package com.shareguard.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

object ItemImageLoader {
    private val executor = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(view: ImageView, url: String, failed: () -> Unit) {
        loadResolved(view, url, { url }, failed)
    }

    fun loadResolved(view: ImageView, key: String, resolveUrl: () -> String, failed: () -> Unit) {
        val url = key
        view.tag = url
        cache.get(url)?.let { view.setImageBitmap(it); return }
        executor.execute {
            val bitmap = runCatching {
                val connection = URL(resolveUrl()).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 10_000
                    connection.readTimeout = 15_000
                    check(connection.responseCode == 200)
                    val bytes = connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            check(output.size() + count <= 10 * 1024 * 1024)
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    check(bytes.size <= 10 * 1024 * 1024)
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    require(options.outWidth > 0 && options.outHeight > 0)
                    options.inSampleSize = 1
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 1200) options.inSampleSize *= 2
                    options.inJustDecodeBounds = false
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                } finally { connection.disconnect() }
            }.getOrNull()
            if (bitmap != null) cache.put(url, bitmap)
            main.post {
                if (view.tag == url) {
                    if (bitmap != null) view.setImageBitmap(bitmap) else failed()
                }
            }
        }
    }
}
