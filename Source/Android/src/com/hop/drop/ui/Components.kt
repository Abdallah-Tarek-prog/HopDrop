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
import androidx.compose.material3.contentColorFor
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
import androidx.compose.ui.res.stringResource
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
                if (platform == Platform.Android) Icons.Rounded.PhoneAndroid else Icons.Rounded.Laptop,
                contentDescription = platform.label,
                tint = contentColorFor(container),
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
@Composable
fun presence(online: Boolean, lastSeen: Long, now: Long): String {
    if (online) return stringResource(R.string.ui_online)
    if (lastSeen <= 0) return stringResource(R.string.ui_offline)
    val minutes = maxOf(1L, (now - lastSeen) / 60000)
    if (minutes < 60) return androidx.compose.ui.res.pluralStringResource(R.plurals.seen_minutes, minutes.toInt(), minutes)
    val hours = minutes / 60
    if (hours < 24) return androidx.compose.ui.res.pluralStringResource(R.plurals.seen_hours, hours.toInt(), hours)
    val days = hours / 24
    return if (days == 1L) stringResource(R.string.ui_seen_yesterday) else androidx.compose.ui.res.pluralStringResource(R.plurals.seen_days, days.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), days)
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
data class TopItem(val name: String, val folder: Boolean)

fun topItems(names: List<String>): List<TopItem> {
    val shown = ArrayList<TopItem>()
    val folders = HashSet<String>()
    for (name in names) {
        val slash = name.indexOf('/')
        if (slash <= 0) shown.add(TopItem(name, false))
        else if (folders.add(name.substring(0, slash))) shown.add(TopItem(name.substring(0, slash), true))
    }
    return shown
}

/** "report.pdf", "report.pdf + 2 more" — never a comma-separated list. */
@Composable
fun shortSummary(names: List<String>): String {
    val items = topItems(names)
    val first = items.firstOrNull()?.let { if (it.folder) stringResource(R.string.folder_named, ltr(it.name)) else it.name }
    return when {
        first == null -> stringResource(R.string.ui_no_files)
        items.size == 1 -> first
        else -> stringResource(R.string.summary_more, first, stringResource(R.string.more_count, items.size - 1))
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
        LiveState.Connecting -> stringResource(R.string.live_connecting, ltr(live.peer))
        LiveState.ConnectingDirectly -> stringResource(R.string.live_connecting_direct, ltr(live.peer))
        LiveState.Queued -> stringResource(R.string.live_waiting, ltr(live.peer))
        LiveState.TextSent -> stringResource(R.string.live_text_sent, ltr(live.peer))
        is LiveState.Waiting -> stringResource(R.string.live_waiting_accept, ltr(live.peer))
        LiveState.Reconnecting -> stringResource(if (live.incoming) R.string.live_waiting_reconnect else R.string.live_reconnecting, ltr(live.peer))
        is LiveState.Moving -> stringResource(if (live.incoming) R.string.live_receiving else R.string.live_sending, ltr(live.peer))
        is LiveState.Done -> stringResource(if (live.incoming) R.string.received_from else R.string.sent_to, filesText(state.count), ltr(live.peer))
        is LiveState.Stopped -> if (state.cancelled) stringResource(R.string.ui_cancelled) else stringResource(if (live.incoming) R.string.live_receive_stopped else R.string.live_send_stopped, ltr(live.peer))
    }
    val detail = when (state) {
        LiveState.Connecting -> null
        LiveState.ConnectingDirectly -> stringResource(R.string.ui_not_on_the_same_wi_fi)
        LiveState.Queued -> stringResource(R.string.ui_sends_when_it_s_back)
        LiveState.TextSent -> null
        is LiveState.Waiting -> filesText(state.count)
        LiveState.Reconnecting -> stringResource(R.string.ui_continues_where_it_stopped)
        is LiveState.Moving -> if (state.count > 1) stringResource(R.string.file_position, ltr(state.file), state.index, state.count) else state.file
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
                val cancelDescription = stringResource(R.string.ui_cancel_transfer)
                IconButton(onClick = { onCancel(live.incoming) }, modifier = Modifier.semantics { contentDescription = cancelDescription }) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                }
            } else Spacer(Modifier.width(8.dp))
        }
        if (live.active) {
            Spacer(Modifier.height(10.dp))
            ProgressBar((state as? LiveState.Moving)?.fraction, Modifier.padding(end = 8.dp))
            if (state is LiveState.Moving) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp)) {
                    Text(if (state.total < 0) sizeText(state.done) else stringResource(R.string.amount_of, sizeText(state.done), sizeText(state.total)),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.speed_per_second, sizeText(state.speed.toLong())) + if (state.secondsLeft >= 0)
                        "  ·  " + stringResource(R.string.time_left, durationText(state.secondsLeft)) else "",
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                }
            }
        } else if (state is LiveState.Done) {
            Text(stringResource(R.string.history_size_in, sizeText(state.bytes), durationText(maxOf(1, state.millis / 1000))),
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
