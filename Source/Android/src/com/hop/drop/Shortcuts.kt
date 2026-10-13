package com.hop.drop

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.hop.drop.core.Peer

/**
 * Paired devices as shortcuts: in Android's share sheet (Direct Share: share from any app → tap "LOQ Laptop" → it's
 * sent, no HopDrop screen in between) and in the menu of HopDrop's launcher icon. Most recently seen devices first.
 */
object Shortcuts {
    private const val SHARE_TARGET = "com.hop.drop.category.SHARE_TARGET"
    private const val PREFIX = "device:"

    fun deviceId(shortcutId: String?): String? = shortcutId?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)

    /** Publishes the current paired devices (call after pairing, removing or renaming). */
    @JvmStatic
    fun update(context: Context, devices: List<Peer.Device>) {
        try {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(1, 5)
            val sorted = devices.sortedByDescending { it.lastSeen }.take(max)
            val shortcuts = sorted.mapIndexed { rank, d ->
                ShortcutInfoCompat.Builder(context, PREFIX + d.id)
                    .setShortLabel(d.label())
                    .setLongLabel("Send to " + d.label())
                    .setIcon(icon(context, d.platform == "windows"))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("device", d.id))
                    .setCategories(setOf(SHARE_TARGET))
                    .setLongLived(true)
                    .setRank(rank)
                    .build()
            }
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
            if (shortcuts.isNotEmpty()) ShortcutManagerCompat.addDynamicShortcuts(context, shortcuts)
        } catch (e: RuntimeException) {
            // Launchers may refuse (rate limits, work profiles); sharing through HopDrop's own screen still works.
            android.util.Log.w("HopDrop", "Couldn't publish share shortcuts", e)
        }
    }

    /** Helps Android rank the device in the share sheet. */
    @JvmStatic
    fun used(context: Context, deviceId: String) {
        try { ShortcutManagerCompat.reportShortcutUsed(context, PREFIX + deviceId) } catch (ignored: RuntimeException) { }
    }

    /** The device's glyph in white on the brand orange, as an adaptive icon (the launcher crops it to its shape). */
    private fun icon(context: Context, laptop: Boolean): IconCompat {
        val density = context.resources.displayMetrics.density
        val side = (108 * density).toInt()
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.brand))
        val glyph = ContextCompat.getDrawable(context, if (laptop) R.drawable.ic_glyph_laptop else R.drawable.ic_glyph_phone)!!.mutate()
        glyph.setTint(Color.WHITE)
        val size = (side * 0.40f).toInt()
        val left = (side - size) / 2
        glyph.setBounds(left, left, left + size, left + size)
        glyph.draw(canvas)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}
