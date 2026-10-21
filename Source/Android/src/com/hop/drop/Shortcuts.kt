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
 * Paired devices as share and launcher shortcuts. The launcher also offers Send clipboard after pairing.
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
            val sorted = devices.sortedByDescending { it.lastSeen }.take((max - 1).coerceAtLeast(0))
            val shortcuts = mutableListOf<ShortcutInfoCompat>()
            if (devices.isNotEmpty()) shortcuts += ShortcutInfoCompat.Builder(context, "clipboard")
                .setShortLabel("Send clipboard")
                .setIcon(icon(context, R.drawable.ic_glyph_clipboard))
                .setIntent(Intent(context, QuickSendActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("clipboard", true))
                .setRank(0)
                .build()
            shortcuts += sorted.mapIndexed { rank, d ->
                ShortcutInfoCompat.Builder(context, PREFIX + d.id)
                    .setShortLabel(d.label())
                    .setLongLabel("Send to " + d.label())
                    .setIcon(icon(context, if (d.platform == "android") R.drawable.ic_glyph_phone else R.drawable.ic_glyph_laptop))
                    .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra("device", d.id))
                    .setCategories(setOf(SHARE_TARGET))
                    .setLongLived(true)
                    .setRank(rank + 1)
                    .build()
            }
            // One call replaces the whole list: a launcher that refuses (rate limit) leaves the old shortcuts instead of none.
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
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
    private fun icon(context: Context, glyphId: Int): IconCompat {
        val density = context.resources.displayMetrics.density
        val side = (108 * density).toInt()
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ContextCompat.getColor(context, R.color.brand))
        val glyph = ContextCompat.getDrawable(context, glyphId)!!.mutate()
        glyph.setTint(Color.WHITE)
        val size = (side * 0.40f).toInt()
        val left = (side - size) / 2
        glyph.setBounds(left, left, left + size, left + size)
        glyph.draw(canvas)
        return IconCompat.createWithAdaptiveBitmap(bitmap)
    }
}
