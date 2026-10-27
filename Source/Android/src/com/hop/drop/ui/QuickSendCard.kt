package com.hop.drop.ui

import com.hop.drop.R

import androidx.compose.ui.res.stringResource

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal data class QuickSendDevice(val id: String, val label: String, val platform: Platform)

/** The floating card's content. Full-width options leave room for titles at large text sizes. */
@Composable
internal fun QuickSendCard(
    devices: List<QuickSendDevice>, selected: String?,
    clipboardDetail: String, clipboardAvailable: Boolean,
    photoDetail: String, photoAvailable: Boolean, photoPreview: Uri?,
    onSelect: (String) -> Unit, onClipboard: () -> Unit, onPhoto: () -> Unit, onFiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = scheme.surfaceContainerLow, modifier = modifier) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LogoMark(26.dp)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.ui_quick_send), style = MaterialTheme.typography.titleLarge)
            }
            Text(stringResource(R.string.ui_send_to), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices, key = { it.id }) { device ->
                    FilterChip(
                        selected = selected == device.id, onClick = { onSelect(device.id) },
                        label = { Text(device.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { DeviceAvatar(device.platform, false, size = 22.dp) },
                    )
                }
            }
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickOption(Icons.Rounded.ContentPaste, stringResource(R.string.ui_clipboard), clipboardDetail, clipboardAvailable, null, onClipboard)
                QuickOption(Icons.Rounded.Image, stringResource(R.string.ui_latest_photo), photoDetail, photoAvailable, photoPreview, onPhoto)
                QuickOption(Icons.AutoMirrored.Rounded.InsertDriveFile, stringResource(R.string.ui_files), stringResource(R.string.ui_choose), true, null, onFiles)
            }
        }
    }
}

@Composable
private fun QuickOption(icon: ImageVector, title: String, detail: String, enabled: Boolean, preview: Uri?, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(scheme.primaryContainer),
                contentAlignment = Alignment.Center) {
                val placeholder = @Composable {
                    Icon(icon, contentDescription = null, tint = if (enabled) scheme.onPrimaryContainer else scheme.outline,
                        modifier = Modifier.size(28.dp))
                }
                if (preview != null) Thumbnail(preview, Modifier.fillMaxSize(), placeholder) else placeholder()
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) scheme.onSurface else scheme.outline)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
