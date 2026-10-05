package com.hop.drop.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small previews of photos and videos, read once and kept in memory while the app runs. */
object Thumbnails {
    private const val PX = 192
    private val cache = object : LruCache<Uri, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: Uri, value: Bitmap) = value.byteCount
    }
    /** Files that couldn't be read (moved, deleted, no access): don't try again on every redraw. */
    private val missing = HashSet<Uri>()

    fun cached(uri: Uri): Bitmap? = cache.get(uri)

    suspend fun load(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        cache.get(uri)?.let { return@withContext it }
        synchronized(missing) { if (uri in missing) return@withContext null }
        val bitmap = try {
            if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, Size(PX, PX), null)
            else decodeSampled(context, uri)
        } catch (e: Exception) {
            try { decodeSampled(context, uri) } catch (e2: Exception) { null }
        }
        if (bitmap == null) synchronized(missing) { missing.add(uri) } else cache.put(uri, bitmap)
        bitmap
    }

    private fun decodeSampled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= PX && bounds.outHeight / (sample * 2) >= PX) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }
}

/** Shows a file's thumbnail, or [placeholder] while it loads or when there is none. */
@Composable
fun Thumbnail(uri: Uri, modifier: Modifier, placeholder: @Composable () -> Unit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(Thumbnails.cached(uri)?.asImageBitmap(), uri) {
        if (value == null) value = Thumbnails.load(context, uri)?.asImageBitmap()
    }
    val shown = image
    if (shown == null) placeholder()
    else Image(shown, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
}
