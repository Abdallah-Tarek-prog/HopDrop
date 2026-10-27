package com.hop.drop.ui

import com.hop.drop.R
import androidx.compose.ui.res.stringResource

import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay

/** The common centred card for short tasks and confirmations. */
@Composable
fun HopModal(
    onDismissRequest: () -> Unit,
    paneTitle: String,
    title: @Composable () -> Unit,
    text: @Composable () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit = {},
    icon: (@Composable () -> Unit)? = null,
    required: Boolean = false,
    keepScreenOn: Boolean = false,
) {
    val context = LocalContext.current
    val motion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
    }
    var visible by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    val dismiss by rememberUpdatedState(onDismissRequest)
    fun close() {
        if (!closing) {
            closing = true
            visible = false
        }
    }
    LaunchedEffect(Unit) { visible = true }
    LaunchedEffect(closing) {
        if (closing) {
            if (motion) delay(120)
            dismiss()
            // Still shown: the caller kept it, or put the next question (a second offer) in its place. Show that one.
            closing = false
            visible = true
        }
    }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(if (motion) if (visible) 200 else 120 else 0), label = "modal alpha")
    val scale by animateFloatAsState(if (visible) 1f else 0.92f, tween(if (motion) if (visible) 200 else 120 else 0), label = "modal scale")
    // Edge to edge, so the dimmed background also covers the status and navigation bars.
    Dialog(onDismissRequest = { close() }, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false,
        decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0f)
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            // The keyboard's height arrives as insets (imePadding below), so the card stays above it; Android 10 and older
            // only send them to windows that ask to be resized.
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < 30) window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            if (keepScreenOn) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (Build.VERSION.SDK_INT >= 31 && window != null) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val params = window.attributes
                params.blurBehindRadius = 32
                window.attributes = params
            }
            onDispose {
                if (keepScreenOn) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (Build.VERSION.SDK_INT >= 31) window?.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f * alpha))
            .pointerInput(required) { detectTapGestures {
                if (!required) close()
            } }.systemBarsPadding().imePadding(), contentAlignment = Alignment.Center) {
            Surface(
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp).widthIn(max = 400.dp).fillMaxWidth()
                    .graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }
                    .pointerInput(Unit) { detectTapGestures {} }
                    .semantics { this.paneTitle = paneTitle },
            ) {
                val scheme = MaterialTheme.colorScheme
                Column(Modifier.padding(24.dp)) {
                    // Same look in every modal: the icon in the theme colour, a headline, then quieter body text.
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f).padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            icon?.let { CompositionLocalProvider(LocalContentColor provides scheme.primary, content = it) }
                            ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                        }
                        if (!required) IconButton(onClick = { close() }, modifier = Modifier.padding(start = 8.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.scan_close))
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 16.dp).weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium, text)
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically) {
                        dismissButton()
                        confirmButton()
                    }
                }
            }
        }
    }
}
