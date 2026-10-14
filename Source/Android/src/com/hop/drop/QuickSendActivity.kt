package com.hop.drop

import android.Manifest
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.hop.drop.core.Format
import com.hop.drop.core.Peer
import com.hop.drop.ui.HopDropTheme
import com.hop.drop.ui.Palette
import com.hop.drop.ui.Platform
import com.hop.drop.ui.ThemeMode
import com.hop.drop.ui.QuickSendCard
import com.hop.drop.ui.QuickSendDevice

/**
 * The Quick Settings tile's floating card: pick the device (the last one used is already picked) and tap what to send — the
 * clipboard, the latest photo or screenshot, or files. Two taps from anywhere, and it closes itself.
 */
class QuickSendActivity : ComponentActivity() {
    private var clipText by mutableStateOf<String?>(null)
    private var clipUri by mutableStateOf<Uri?>(null)
    private var latest by mutableStateOf<Uri?>(null)
    private var photosAllowed by mutableStateOf(false)
    private var target by mutableStateOf<String?>(null)
    private var sendClipboardOnFocus = false
    private var clipboardHandled = false
    private lateinit var app: HopApp

    private val pickFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) send { job -> job.files.addAll(uris) } else finish()
    }
    private val askPhotos = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        photosAllowed = granted
        if (granted) { latest = latestPhoto(); latest?.let { uri -> send { it.files.add(uri) } } }
    }

    private val prefs get() = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as HopApp
        enableEdgeToEdge()
        val devices = app.book().all()
        if (devices.isEmpty()) {
            Toast.makeText(this, "Pair a device in HopDrop first.", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val last = prefs.getString("last_device", null)
        val lastDevice = devices.firstOrNull { it.id == last }
        target = lastDevice?.id ?: devices.maxByOrNull { it.lastSeen }?.id
        sendClipboardOnFocus = intent.getBooleanExtra("clipboard", false) && lastDevice != null
        if (sendClipboardOnFocus) {
            setContent { Box(Modifier.fillMaxSize()) }
            return
        }
        photosAllowed = checkSelfPermission(photoPermission) == PackageManager.PERMISSION_GRANTED
        if (photosAllowed) latest = latestPhoto()
        setContent {
            val theme = ThemeMode.entries.getOrElse(prefs.getInt("theme", 0)) { ThemeMode.System }
            HopDropTheme(theme, Palette.of(prefs.getString("palette", null))) { QuickCard(devices) }
        }
    }

    /** Android lets an app read the clipboard only while its window has focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val clip = try { getSystemService(ClipboardManager::class.java).primaryClip } catch (e: RuntimeException) { null }
        val item = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        clipUri = item?.uri
        clipText = item?.text?.toString()?.takeIf { it.isNotBlank() } ?: item?.coerceToText(this)?.toString()?.takeIf { it.isNotBlank() && item.uri == null }
        if (sendClipboardOnFocus && !clipboardHandled) {
            clipboardHandled = true
            when {
                clipText != null -> send(announce = true) { it.texts.add(clipText!!); it.clip = true }
                clipUri != null -> send(announce = true) { it.files.add(clipUri!!) }
                else -> {
                    Toast.makeText(this, "Nothing copied", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private val photoPermission get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun latestPhoto(): Uri? = try {
        val args = Bundle().apply {
            putStringArray(android.content.ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(android.content.ContentResolver.QUERY_ARG_SORT_DIRECTION, android.content.ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID), args, null)?.use { c ->
            if (c.moveToFirst()) ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null
        }
    } catch (e: Exception) { null }

    /** Starts the send to the picked device and closes the card. */
    private fun send(announce: Boolean = false, fill: (Sends.Job) -> Unit) {
        val device = target?.let { app.book().get(it) } ?: return finish()
        val ready = Sends.Job(device.id, "")
        fill(ready)
        ready.summary = if (ready.files.isEmpty()) "your clipboard" else Format.files(ready.files.size)
        prefs.edit().putString("last_device", device.id).apply()
        try {
            startForegroundService(ready.intent(this))
            Shortcuts.used(this, device.id)
            if (announce || !ready.textOnly()) Toast.makeText(this, "Sending to " + device.label(), Toast.LENGTH_SHORT).show()
        } catch (e: RuntimeException) {
            Toast.makeText(this, "HopDrop couldn't start sending. Open HopDrop and try again.", Toast.LENGTH_LONG).show()
        }
        finish()
    }

    @Composable
    private fun QuickCard(devices: List<Peer.Device>) {
        var shown by remember { mutableStateOf(false) }
        var closing by remember { mutableStateOf(false) }
        val motion = remember { Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f }
        fun close() { closing = true; shown = false }
        LaunchedEffect(Unit) { shown = true }
        LaunchedEffect(closing) { if (closing) { if (motion) delay(120); finish() } }
        BackHandler { close() }
        val scrim by animateFloatAsState(if (shown) 0.45f else 0f,
            tween(if (motion) if (shown) 200 else 120 else 0), label = "quick send scrim")
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { close() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            AnimatedVisibility(shown, enter = fadeIn(tween(if (motion) 200 else 0)) + scaleIn(tween(if (motion) 200 else 0), initialScale = 0.92f),
                exit = fadeOut(tween(if (motion) 120 else 0)) + scaleOut(tween(if (motion) 120 else 0), targetScale = 0.92f)) {
                val clip = clipText
                QuickSendCard(
                    devices = devices.map { QuickSendDevice(it.id, it.label(), if (it.platform == "windows") Platform.Windows else Platform.Android) },
                    selected = target,
                    clipboardDetail = clip?.lineSequence()?.firstOrNull()?.take(60) ?: if (clipUri != null) "A copied file" else "Nothing copied",
                    clipboardAvailable = clip != null || clipUri != null,
                    photoDetail = if (photosAllowed) (if (latest != null) "Tap to send" else "No photos") else "Allow photos",
                    photoAvailable = !photosAllowed || latest != null,
                    photoPreview = latest,
                    onSelect = { target = it },
                    onClipboard = {
                        val uri = clipUri
                        if (clip != null) send { it.texts.add(clip); it.clip = true } else if (uri != null) send { it.files.add(uri) }
                    },
                    onPhoto = {
                        val uri = latest
                        if (!photosAllowed) askPhotos.launch(photoPermission) else if (uri != null) send { it.files.add(uri) }
                    },
                    onFiles = { pickFiles.launch(arrayOf("*/*")) },
                    modifier = Modifier.padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp)
                        .widthIn(max = 400.dp).fillMaxWidth()
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
                )
            }
        }
    }
}
