package com.hop.drop

import android.app.Activity
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.pm.ShortcutManagerCompat
import com.hop.drop.core.Format
import com.hop.drop.core.Peer

/**
 * Where Android's share sheet lands. Picked through a device's own target (Direct Share), it sends right away and
 * closes: one tap from any app. Picked as "HopDrop", it opens the Send screen with the files or text, as before.
 */
class ShareActivity : Activity() {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Language.wrap(newBase))
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as HopApp
        val deviceId = Shortcuts.deviceId(intent.getStringExtra(ShortcutManagerCompat.EXTRA_SHORTCUT_ID))
        val device = deviceId?.let { app.book().get(it) }
        if (device == null) {
            startActivity(Intent(intent).setClass(this, MainActivity::class.java).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } else {
            val job = jobFor(app, device, intent)
            if (job == null) Toast.makeText(this, R.string.quick_nothing_to_send, Toast.LENGTH_SHORT).show()
            else {
                try {
                    startForegroundService(job.intent(this))
                    Shortcuts.used(this, device.id)
                    Toast.makeText(this, getString(R.string.quick_sending_to, Language.ltr(this, device.label())), Toast.LENGTH_SHORT).show()
                } catch (e: RuntimeException) {
                    Toast.makeText(this, R.string.quick_send_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }
        finish()
    }

    companion object {
        /** What a share intent holds, as a send to [device]: its files, or its text (sent as text). Null when empty. */
        @Suppress("DEPRECATION")
        private fun jobFor(app: HopApp, device: Peer.Device, intent: Intent): Sends.Job? {
            val uris = ArrayList<Uri>()
            intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
            if (intent.action == Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
            else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
            val files = uris.distinct()
            val text = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (files.isEmpty() && text.isNullOrEmpty()) return null
            val job = Sends.Job(device.id, if (files.isEmpty()) app.getString(R.string.your_text) else Language.files(app, files.size))
            if (files.isEmpty()) {
                if (text!!.length <= Peer.MAX_TEXT) job.texts.add(text)
                else job.files.add(SharedTextProvider.create(app, text))
            } else job.files.addAll(files)
            return job
        }
    }
}
