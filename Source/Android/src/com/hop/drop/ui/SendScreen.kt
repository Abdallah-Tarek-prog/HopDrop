package com.hop.drop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hop.drop.core.Format

@Composable
fun SendScreen(state: HopUiState, actions: HopActions, padding: PaddingValues) {
    val now = rememberNow()
    var showAll by rememberSaveable { mutableStateOf(false) }
    var bluetoothAsk by remember { mutableStateOf(false) }
    val incoming = state.live.filter { it.incoming }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(incoming, key = { "live:" + it.key }) { LiveCard(it, actions::cancel, Modifier.animateItem()) }
        item(key = "selection") {
            if (state.nothingSelected) ChooseCard(actions) else SelectedCard(state, actions, showAll) { showAll = !showAll }
        }
        item(key = "send-to") { SectionHeader("Send to") }
        if (state.devices.isEmpty()) {
            item(key = "no-devices") {
                HopCard {
                    EmptyState(Icons.Rounded.Devices, "No devices yet", "Pair a phone or PC once, then send with one tap.") {
                        Button(onClick = { state.tab = Tab.Devices; state.pairSheet = true }) { Text("Pair a device") }
                    }
                }
            }
        } else {
            items(state.devices, key = { "device:" + it.id }) { device ->
                DeviceSendRow(device, ready = !state.nothingSelected, now = now, onSend = { actions.send(device) },
                    onCancel = { actions.cancel(false) }, modifier = Modifier.animateItem())
            }
        }
        item(key = "bluetooth") {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { bluetoothAsk = true }) {
                    Icon(Icons.Rounded.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("No Wi-Fi? Use Bluetooth")
                }
            }
        }
    }
    if (bluetoothAsk) {
        AlertDialog(
            onDismissRequest = { bluetoothAsk = false },
            icon = { Icon(Icons.Rounded.Bluetooth, contentDescription = null) },
            title = { Text("Send with Bluetooth") },
            text = { Text("Much slower than Wi-Fi. On the PC, click Receive via Bluetooth first, then pick the PC in the list.") },
            confirmButton = { TextButton(onClick = { bluetoothAsk = false; actions.bluetooth() }) { Text("Continue") } },
            dismissButton = { TextButton(onClick = { bluetoothAsk = false }) { Text("Cancel") } },
        )
    }
}

/** Nothing picked yet: three big ways to pick. */
@Composable
private fun ChooseCard(actions: HopActions) {
    HopCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { LogoMark(34.dp) }
            Column(Modifier.padding(start = 14.dp)) {
                Text("Choose what to send", style = MaterialTheme.typography.titleMedium)
                Text("Or share to HopDrop from any app", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceTile(Icons.AutoMirrored.Rounded.InsertDriveFile, "Files", actions::pickFiles, Modifier.weight(1f))
            ChoiceTile(Icons.Rounded.PhotoLibrary, "Photos", actions::pickPhotos, Modifier.weight(1f))
            ChoiceTile(Icons.Rounded.Folder, "Folder", actions::pickFolder, Modifier.weight(1f))
        }
    }
}

private fun selectionSummary(state: HopUiState): String {
    var total = 0L
    var unknown = false
    var counting = false
    for (f in state.files) if (f.size < 0) unknown = true else total += f.size
    for (f in state.folders) {
        if (f.files < 0) counting = true
        if (f.sizeUnknown) unknown = true
        total += f.bytes
    }
    val what = when {
        state.folders.isEmpty() -> Format.files(state.files.size)
        state.files.isEmpty() -> Format.folders(state.folders.size)
        else -> Format.folders(state.folders.size) + " and " + Format.files(state.files.size)
    }
    val size = when {
        counting -> "counting…"
        unknown -> "at least " + Format.size(total)
        else -> Format.size(total)
    }
    return "$what  ·  $size"
}

@Composable
private fun SelectedCard(state: HopUiState, actions: HopActions, showAll: Boolean, onToggle: () -> Unit) {
    HopCard(padding = PaddingValues(start = 16.dp, top = 10.dp, end = 6.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ready to send", style = MaterialTheme.typography.titleMedium)
                Text(selectionSummary(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = actions::clearSelection) { Text("Clear") }
        }
        Spacer(Modifier.height(6.dp))
        val total = state.folders.size + state.files.size
        val limit = if (showAll || total <= 5) Int.MAX_VALUE else 4
        var shown = 0
        for (folder in state.folders) {
            if (shown++ >= limit) break
            SelectedRow(
                icon = { FileIcon(folder.name, null, folder = true) },
                name = folder.name,
                detail = if (folder.files < 0) "Folder  ·  counting…" else "Folder  ·  " + Format.files(folder.files) + "  ·  " +
                    (if (folder.sizeUnknown) "at least " else "") + Format.size(folder.bytes),
                onRemove = { actions.removeFolder(folder) },
            )
        }
        for (file in state.files) {
            if (shown++ >= limit) break
            SelectedRow(
                icon = { FileIcon(file.name, file.uri, mime = file.mime) },
                name = file.name,
                detail = Format.size(file.size),
                onRemove = { actions.removeFile(file) },
            )
        }
        if (total > 5) {
            TextButton(onClick = onToggle) { Text(if (showAll) "Show less" else "Show all $total") }
        }
        Row(Modifier.padding(top = 6.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddChip("Files", actions::pickFiles)
            AddChip("Photos", actions::pickPhotos)
            AddChip("Folder", actions::pickFolder)
        }
    }
}

@Composable
private fun AddChip(label: String, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
    )
}

@Composable
private fun SelectedRow(icon: @Composable () -> Unit, name: String, detail: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        icon()
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove $name") }
    }
}

@Composable
private fun DeviceSendRow(device: DeviceUi, ready: Boolean, now: Long, onSend: () -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    val out = device.outgoing
    HopCard(modifier, padding = PaddingValues(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(device.platform, device.online)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(presence(device.online, device.lastSeen, now), style = MaterialTheme.typography.bodySmall,
                    color = if (device.online) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                out != null && out.active -> IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = "Cancel sending") }
                ready -> Button(onClick = onSend, contentPadding = PaddingValues(horizontal = 18.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send")
                }
                // Nothing chosen yet: a quiet button. Tapping it says what's missing instead of doing nothing.
                else -> FilledTonalButton(
                    onClick = onSend, contentPadding = PaddingValues(horizontal = 18.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) { Text("Send") }
            }
        }
        AnimatedVisibility(out != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            if (out != null) OutgoingStatus(out)
        }
    }
}

@Composable
private fun OutgoingStatus(out: LiveUi) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 12.dp)) {
        when (val s = out.state) {
            is LiveState.Done -> StatusLine(Icons.Rounded.CheckCircle, scheme.primary,
                "Sent " + Format.files(s.count) + "  ·  " + Format.size(s.bytes))
            is LiveState.Stopped -> StatusLine(Icons.Rounded.ErrorOutline, scheme.error, s.reason)
            is LiveState.Moving -> {
                ProgressBar(s.fraction)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(Format.amount(s.done, s.total), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (s.secondsLeft >= 0) Text(Format.duration(s.secondsLeft) + " left", style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant)
                }
            }
            else -> {
                ProgressBar(null)
                Text(
                    when (s) {
                        is LiveState.Waiting -> "Waiting for them to accept"
                        LiveState.Reconnecting -> "Connection dropped, reconnecting…"
                        else -> "Connecting…"
                    },
                    style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusLine(icon: androidx.compose.ui.graphics.vector.ImageVector, color: androidx.compose.ui.graphics.Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
