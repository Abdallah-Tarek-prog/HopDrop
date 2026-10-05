package com.hop.drop.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hop.drop.R
import com.hop.drop.core.Format
import kotlinx.coroutines.delay
import java.util.Locale

/** The logo mark, drawn from the same vector as the app icon. */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = modifier.size(size))
}

/** Small grey title above a group of rows, with an optional action on the right. */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp).height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A flat card: tinted surface, no shadow (the app's one card style). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HopCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && onClick != null) 0.985f else 1f, tween(120), label = "press")
    Surface(
        color = color,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth().scale(scale).clip(MaterialTheme.shapes.medium).then(
            if (onClick != null || onLongClick != null) Modifier.combinedClickable(
                interactionSource = interaction, indication = androidx.compose.material3.ripple(),
                onClick = { onClick?.invoke() }, onLongClick = onLongClick,
            ) else Modifier
        ),
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

/** Round badge with a laptop or phone, and a green dot when the device is online. */
@Composable
fun DeviceAvatar(platform: Platform, online: Boolean, size: Dp = 44.dp, container: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Box(Modifier.size(size)) {
        Box(
            Modifier.size(size).clip(CircleShape).background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (platform == Platform.Windows) Icons.Rounded.Laptop else Icons.Rounded.PhoneAndroid,
                contentDescription = if (platform == Platform.Windows) "PC" else "Phone",
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(size * 0.5f),
            )
        }
        AnimatedVisibility(online, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd)) {
            Box(
                Modifier.size(size * 0.3f).clip(CircleShape).background(MaterialTheme.colorScheme.surface).padding(2.dp)
                    .clip(CircleShape).background(OnlineGreen)
            )
        }
    }
}

/** "Online", "Seen 5 min ago", "Seen 2 days ago". */
fun presence(online: Boolean, lastSeen: Long, now: Long): String {
    if (online) return "Online"
    if (lastSeen <= 0) return "Offline"
    val minutes = maxOf(1L, (now - lastSeen) / 60000)
    if (minutes < 60) return "Seen $minutes min ago"
    val hours = minutes / 60
    if (hours < 24) return "Seen $hours h ago"
    val days = hours / 24
    return if (days == 1L) "Seen yesterday" else "Seen $days days ago"
}

/** The current time, refreshed every 30 seconds, for "seen … ago" labels. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    return now
}

enum class FileKind { Image, Video, Audio, Document, Archive, Folder, Other }

fun fileKind(name: String, mime: String? = null): FileKind {
    val lower = name.lowercase(Locale.ROOT)
    val ext = lower.substringAfterLast('.', "")
    return when {
        mime?.startsWith("image/") == true || ext in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp", "avif") -> FileKind.Image
        mime?.startsWith("video/") == true || ext in setOf("mp4", "mov", "mkv", "avi", "webm", "3gp", "m4v") -> FileKind.Video
        mime?.startsWith("audio/") == true || ext in setOf("mp3", "m4a", "wav", "flac", "ogg", "aac", "opus") -> FileKind.Audio
        ext in setOf("pdf", "doc", "docx", "txt", "rtf", "xls", "xlsx", "ppt", "pptx", "csv", "md", "odt", "ods", "odp", "epub") -> FileKind.Document
        ext in setOf("zip", "rar", "7z", "tar", "gz", "apk", "xz", "bz2") -> FileKind.Archive
        else -> FileKind.Other
    }
}

private fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.Image -> Icons.Rounded.Image
    FileKind.Video -> Icons.Rounded.Movie
    FileKind.Audio -> Icons.Rounded.MusicNote
    FileKind.Document -> Icons.Rounded.Description
    FileKind.Archive -> Icons.Rounded.FolderZip
    FileKind.Folder -> Icons.Rounded.Folder
    FileKind.Other -> Icons.AutoMirrored.Rounded.InsertDriveFile
}

/** A file's icon, or a thumbnail for photos and videos when the file can be read. */
@Composable
fun FileIcon(name: String, uri: Uri?, size: Dp = 40.dp, mime: String? = null, folder: Boolean = false) {
    val kind = if (folder) FileKind.Folder else fileKind(name, mime)
    val (bg, fg) = when (kind) {
        FileKind.Image, FileKind.Video -> MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        FileKind.Folder, FileKind.Archive -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
    }
    val shape = RoundedCornerShape(size * 0.28f)
    val placeholder: @Composable () -> Unit = {
        Box(Modifier.size(size).clip(shape).background(bg), contentAlignment = Alignment.Center) {
            Icon(kindIcon(kind), contentDescription = null, tint = fg, modifier = Modifier.size(size * 0.52f))
        }
    }
    if (uri != null && (kind == FileKind.Image || kind == FileKind.Video)) {
        Thumbnail(uri, Modifier.size(size).clip(shape), placeholder)
    } else placeholder()
}

/** Empty list placeholder: an icon, a short title, at most one line of help, and an optional button. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** A rounded progress bar that glides between values; no value means "working on it". */
@Composable
fun ProgressBar(fraction: Float?, modifier: Modifier = Modifier) {
    val m = modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
    if (fraction == null) {
        LinearProgressIndicator(modifier = m, strokeCap = StrokeCap.Round, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest)
    } else {
        val shown by animateFloatAsState(fraction, tween(400), label = "progress")
        LinearProgressIndicator(progress = { shown }, modifier = m, strokeCap = StrokeCap.Round,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {})
    }
}

/** Top-level items of a transfer: loose files, plus each sent folder once ("Photos folder"). */
fun topItems(names: List<String>): List<String> {
    val shown = ArrayList<String>()
    val folders = HashSet<String>()
    for (name in names) {
        val slash = name.indexOf('/')
        if (slash <= 0) shown.add(name)
        else if (folders.add(name.substring(0, slash))) shown.add(name.substring(0, slash) + " folder")
    }
    return shown
}

/** "report.pdf", "report.pdf + 2 more" — never a comma-separated list. */
fun shortSummary(names: List<String>): String {
    val items = topItems(names)
    return when {
        items.isEmpty() -> "No files"
        items.size == 1 -> items[0]
        else -> items[0] + " + " + (items.size - 1) + " more"
    }
}

/** One transfer that is moving now: who, which file, progress, speed and time left, and Cancel. */
@Composable
fun LiveCard(live: LiveUi, onCancel: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val state = live.state
    val ok = state is LiveState.Done
    val failed = state is LiveState.Stopped
    val badgeBg by animateColorAsState(
        when { ok -> scheme.primaryContainer; failed -> scheme.errorContainer; else -> scheme.secondaryContainer }, label = "badge")
    val badgeFg = when { ok -> scheme.onPrimaryContainer; failed -> scheme.onErrorContainer; else -> scheme.onSecondaryContainer }
    val title = when (state) {
        LiveState.Connecting -> "Connecting to ${live.peer}…"
        is LiveState.Waiting -> "Waiting for ${live.peer} to accept"
        LiveState.Reconnecting -> if (live.incoming) "Waiting for ${live.peer} to reconnect" else "Reconnecting to ${live.peer}"
        is LiveState.Moving -> if (live.incoming) "Receiving from ${live.peer}" else "Sending to ${live.peer}"
        is LiveState.Done -> (if (live.incoming) "Received " else "Sent ") + Format.files(state.count) +
            (if (live.incoming) " from " else " to ") + live.peer
        is LiveState.Stopped -> if (state.cancelled) "Cancelled" else if (live.incoming) "Receiving from ${live.peer} stopped" else "Couldn't send to ${live.peer}"
    }
    val detail = when (state) {
        LiveState.Connecting -> null
        is LiveState.Waiting -> Format.files(state.count)
        LiveState.Reconnecting -> "Continues where it stopped"
        is LiveState.Moving -> if (state.count > 1) "${state.file}  ·  ${state.index} of ${state.count}" else state.file
        is LiveState.Done -> shortSummary(state.files)
        is LiveState.Stopped -> state.reason
    }
    HopCard(modifier, padding = PaddingValues(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(badgeBg), contentAlignment = Alignment.Center) {
                Icon(
                    when { ok -> Icons.Rounded.CheckCircle; failed -> Icons.Rounded.ErrorOutline; live.incoming -> Icons.Rounded.Download; else -> Icons.Rounded.Upload },
                    contentDescription = null, tint = badgeFg, modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = if (failed) scheme.error else scheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (live.active) {
                IconButton(onClick = { onCancel(live.incoming) }, modifier = Modifier.semantics { contentDescription = "Cancel transfer" }) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                }
            } else Spacer(Modifier.width(8.dp))
        }
        if (live.active) {
            Spacer(Modifier.height(10.dp))
            ProgressBar((state as? LiveState.Moving)?.fraction, Modifier.padding(end = 8.dp))
            if (state is LiveState.Moving) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp)) {
                    Text(Format.amount(state.done, state.total) + (state.fraction?.let { "  ·  ${(it * 100).toInt()}%" } ?: ""),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(Format.speed(state.speed) + if (state.secondsLeft >= 0) "  ·  " + Format.duration(state.secondsLeft) + " left" else "",
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
        } else if (state is LiveState.Done) {
            Text(Format.size(state.bytes) + " in " + Format.duration(maxOf(1, state.millis / 1000)),
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 52.dp, top = 6.dp))
        }
    }
}

/** A big square-ish button with an icon over a label (Files, Photos, Folder). */
@Composable
fun ChoiceTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "tile")
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.height(92.dp).scale(scale),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Thin outline used to separate a soft container from the background in dark mode. */
fun Modifier.hairline(color: Color, shape: androidx.compose.ui.graphics.Shape): Modifier = border(1.dp, color, shape)
