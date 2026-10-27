package com.hop.drop.ui

import com.hop.drop.R

import androidx.compose.ui.res.stringResource

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.TextSnippet
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material.icons.rounded.MarkChatRead
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
    var writing by remember { mutableStateOf<String?>(null) }
    val incoming = state.live.filter { it.incoming }
    val picking = state.picking
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding() + 8.dp,
            bottom = padding.calculateBottomPadding() + 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(incoming, key = { "live:" + it.key }) { LiveCard(it, actions::cancel, Modifier.animateItem()) }
        state.update?.let { update -> item(key = "update") { UpdateCard(update, actions, Modifier.animateItem()) } }
        state.directTo?.let { id ->
            val device = state.devices.firstOrNull { it.id == id }
            if (device != null) item(key = "direct") { DirectCard(device, actions, Modifier.animateItem()) }
        }
        item(key = "selection") {
            if (state.nothingSelected) ChooseCard(actions) { writing = actions.clipboardText() ?: "" }
            else SelectedCard(state, actions, showAll, onText = { writing = actions.clipboardText() ?: "" }) { showAll = !showAll }
        }
        item(key = "send-to") {
            SectionHeader(stringResource(R.string.ui_send_to)) {
                // Several devices at once: tick them, then one button sends to all of them side by side.
                if (state.devices.size >= 2 && !state.nothingSelected) {
                    TextButton(onClick = { state.picking = if (picking == null) emptySet() else null }) {
                        Text(if (picking == null) stringResource(R.string.ui_select_several) else stringResource(R.string.ui_cancel))
                    }
                }
            }
        }
        if (state.devices.isEmpty()) {
            item(key = "no-devices") {
                HopCard {
                    EmptyState(Icons.Rounded.Devices, stringResource(R.string.ui_no_devices_yet), stringResource(R.string.ui_pair_a_phone_or_pc_once_then_send_with_one_tap)) {
                        Button(onClick = actions::scanQr) { Text(stringResource(R.string.ui_pair_a_device)) }
                    }
                }
            }
        } else {
            items(state.devices, key = { "device:" + it.id }) { device ->
                DeviceSendRow(
                    device, ready = !state.nothingSelected, now = now, onSend = { actions.send(device) },
                    onCancel = { actions.cancelSend(device) }, onConnect = { actions.connectDirectly(device) },
                    connecting = state.connectingTo == device.id, modifier = Modifier.animateItem(),
                    picked = picking?.contains(device.id),
                    onPick = { on -> state.picking = (picking ?: emptySet()).let { if (on) it + device.id else it - device.id } },
                )
            }
            if (picking != null) item(key = "send-many") {
                Button(
                    onClick = { actions.sendToMany(state.devices.filter { it.id in picking }) },
                    enabled = picking.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp).heightIn(min = 52.dp),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(if (picking.isEmpty()) stringResource(R.string.ui_tick_the_devices_to_send_to)
                        else stringResource(R.string.send_to_count, androidx.compose.ui.res.pluralStringResource(R.plurals.device_count, picking.size, picking.size)))
                }
            }
        }
        item(key = "bluetooth") {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp), contentAlignment = Alignment.Center) {
                TextButton(onClick = { bluetoothAsk = true }) {
                    Icon(Icons.Rounded.Bluetooth, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_other_ways_bluetooth))
                }
            }
        }
    }
    writing?.let { initial -> TextNoteDialog(initial, onDismiss = { writing = null }, onAdd = { writing = null; actions.addText(it) }) }
    if (bluetoothAsk) {
        HopModal(
            onDismissRequest = { bluetoothAsk = false }, paneTitle = stringResource(R.string.ui_send_with_bluetooth),
            icon = { Icon(Icons.Rounded.Bluetooth, contentDescription = null) },
            title = { Text(stringResource(R.string.ui_send_with_bluetooth)) },
            text = { Text(stringResource(R.string.bluetooth_help)) },
            confirmButton = { Button(onClick = { bluetoothAsk = false; actions.bluetooth() }) { Text(stringResource(R.string.ui_continue)) } },
            dismissButton = { TextButton(onClick = { bluetoothAsk = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
}

/** The phone is on a PC's direct link (its own Wi-Fi): files go both ways without a shared network. */
@Composable
private fun DirectCard(device: DeviceUi, actions: HopActions, modifier: Modifier) {
    HopCard(modifier, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.WifiTethering, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(stringResource(R.string.direct_to, ltr(device.name)), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(stringResource(R.string.ui_your_usual_wi_fi_comes_back_when_you_tap_done), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            TextButton(onClick = actions::disconnectDirect) { Text(stringResource(R.string.ui_done)) }
        }
    }
}

/** A newer HopDrop is out: download it and open Android's installer, or put it off. */
@Composable
private fun UpdateCard(update: UpdateUi, actions: HopActions, modifier: Modifier) {
    HopCard(modifier, color = MaterialTheme.colorScheme.primaryContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.update_available, update.version), style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    when {
                        update.downloading -> stringResource(R.string.ui_downloading) + (update.fraction?.let { "  ·  ${(it * 100).toInt()}%" } ?: "…")
                        update.error != null -> update.error
                        else -> stringResource(R.string.update_install_confirm, sizeText(update.size))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (update.error != null && !update.downloading) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        if (update.downloading) ProgressBar(update.fraction, Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = actions::dismissUpdate) { Text(if (update.downloading) stringResource(R.string.ui_cancel) else stringResource(R.string.ui_later)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = actions::startUpdate, enabled = !update.downloading) { Text(if (update.error != null) stringResource(R.string.ui_try_again) else stringResource(R.string.ui_update)) }
        }
    }
}

/** Nothing picked yet: four big ways to pick. */
@Composable
private fun ChooseCard(actions: HopActions, onText: () -> Unit) {
    HopCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) { LogoMark(34.dp) }
            Column(Modifier.padding(start = 14.dp)) {
                Text(stringResource(R.string.ui_choose_what_to_send), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.ui_or_share_to_hopdrop_from_any_app), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ChoiceTile(Icons.AutoMirrored.Rounded.InsertDriveFile, stringResource(R.string.ui_files), actions::pickFiles, Modifier.weight(1f))
            ChoiceTile(Icons.Rounded.PhotoLibrary, stringResource(R.string.ui_photos), actions::pickPhotos, Modifier.weight(1f))
            ChoiceTile(Icons.Rounded.Folder, stringResource(R.string.ui_folder), actions::pickFolder, Modifier.weight(1f))
            ChoiceTile(Icons.AutoMirrored.Rounded.TextSnippet, stringResource(R.string.ui_text), onText, Modifier.weight(1f))
        }
    }
}

@Composable
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
        state.folders.isEmpty() -> filesText(state.files.size)
        state.files.isEmpty() -> foldersText(state.folders.size)
        else -> stringResource(R.string.selection_and, foldersText(state.folders.size), filesText(state.files.size))
    }
    val size = when {
        counting -> "counting…"
        unknown -> stringResource(R.string.size_at_least, sizeText(total))
        else -> sizeText(total)
    }
    return "$what  ·  $size"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectedCard(state: HopUiState, actions: HopActions, showAll: Boolean, onText: () -> Unit, onToggle: () -> Unit) {
    HopCard(padding = PaddingValues(start = 16.dp, top = 10.dp, end = 6.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.ui_ready_to_send), style = MaterialTheme.typography.titleMedium)
                Text(selectionSummary(state), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = actions::clearSelection) { Text(stringResource(R.string.ui_clear)) }
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
                detail = if (folder.files < 0) stringResource(R.string.folder_counting) else stringResource(R.string.folder_detail,
                    filesText(folder.files), if (folder.sizeUnknown) stringResource(R.string.size_at_least, sizeText(folder.bytes)) else sizeText(folder.bytes)),
                onRemove = { actions.removeFolder(folder) },
            )
        }
        for (file in state.files) {
            if (shown++ >= limit) break
            SelectedRow(
                icon = { FileIcon(file.name, file.uri, mime = file.mime) },
                name = file.name,
                detail = sizeText(file.size),
                onRemove = { actions.removeFile(file) },
            )
        }
        if (total > 5) {
            TextButton(onClick = onToggle) { Text(if (showAll) stringResource(R.string.ui_show_less) else stringResource(R.string.show_all_count, total)) }
        }
        FlowRow(Modifier.padding(top = 6.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AddChip(stringResource(R.string.ui_files), actions::pickFiles)
            AddChip(stringResource(R.string.ui_photos), actions::pickPhotos)
            AddChip(stringResource(R.string.ui_folder), actions::pickFolder)
            AddChip(stringResource(R.string.ui_text), onText)
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
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.remove_named, name)) }
    }
}

/** [picked]: null when not choosing several devices, else whether this one is ticked. */
@Composable
private fun DeviceSendRow(
    device: DeviceUi, ready: Boolean, now: Long, onSend: () -> Unit, onCancel: () -> Unit,
    onConnect: () -> Unit, connecting: Boolean, modifier: Modifier,
    picked: Boolean? = null, onPick: (Boolean) -> Unit = {},
) {
    val out = device.outgoing
    HopCard(modifier, onClick = if (picked != null) ({ onPick(!picked) }) else null,
        padding = PaddingValues(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(device.platform, device.online)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    when {
                        connecting -> stringResource(R.string.ui_connecting_directly)
                        device.direct -> stringResource(R.string.ui_connected_directly)
                        device.online -> presence(true, device.lastSeen, now)
                        device.hasLink -> stringResource(R.string.connects_direct, presence(false, device.lastSeen, now))
                        else -> presence(false, device.lastSeen, now)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (device.online) OnlineGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                picked != null -> Checkbox(checked = picked, onCheckedChange = onPick)
                out != null && out.active -> IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.ui_cancel_sending)) }
                ready -> Button(onClick = onSend, contentPadding = PaddingValues(horizontal = 18.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.ui_send))
                }
                connecting -> FilledTonalButton(onClick = {}, enabled = false) { Text(stringResource(R.string.ui_connecting)) }
                !device.online && !device.direct && device.hasLink -> FilledTonalButton(
                    onClick = onConnect, contentPadding = PaddingValues(horizontal = 18.dp),
                ) { Text(stringResource(R.string.ui_connect)) }
                device.waiting > 0 -> TextButton(onClick = onCancel) { Text(stringResource(R.string.ui_cancel)) }
                // Nothing chosen yet: a quiet button. Tapping it says what's missing instead of doing nothing.
                else -> FilledTonalButton(
                    onClick = onSend, contentPadding = PaddingValues(horizontal = 18.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                ) { Text(stringResource(R.string.ui_send)) }
            }
        }
        AnimatedVisibility(out != null || device.waiting > 0, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            when {
                out != null && (out.active || device.waiting == 0) -> OutgoingStatus(out)
                device.waiting > 0 -> Column(Modifier.padding(top = 12.dp)) {
                    StatusLine(Icons.Rounded.Schedule, MaterialTheme.colorScheme.onSurfaceVariant,
                        androidx.compose.ui.res.pluralStringResource(R.plurals.waiting_sends, device.waiting, device.waiting, device.name))
                    if (ready || device.hasLink && !device.online) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel_waiting_sends)) }
                }
            }
        }
    }
}

@Composable
private fun OutgoingStatus(out: LiveUi) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.padding(top = 12.dp)) {
        when (val s = out.state) {
            is LiveState.Done -> StatusLine(Icons.Rounded.CheckCircle, scheme.primary,
                stringResource(R.string.send_done, filesText(s.count), sizeText(s.bytes)))
            is LiveState.Stopped -> StatusLine(Icons.Rounded.ErrorOutline, scheme.error, s.reason)
            LiveState.TextSent -> StatusLine(Icons.Rounded.MarkChatRead, scheme.primary, stringResource(R.string.ui_text_sent))
            LiveState.Queued -> StatusLine(Icons.Rounded.Schedule, scheme.onSurfaceVariant, stringResource(R.string.ui_can_t_reach_it_now_sends_when_it_s_back))
            is LiveState.Moving -> {
                ProgressBar(s.fraction)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(if (s.total < 0) sizeText(s.done) else stringResource(R.string.amount_of, sizeText(s.done), sizeText(s.total)),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                    if (s.secondsLeft >= 0) Text(stringResource(R.string.time_left, durationText(s.secondsLeft)), style = MaterialTheme.typography.labelMedium,
                        color = scheme.onSurfaceVariant)
                }
            }
            else -> {
                ProgressBar(null)
                Text(
                    when (s) {
                        is LiveState.Waiting -> stringResource(R.string.ui_waiting_for_them_to_accept)
                        LiveState.Reconnecting -> stringResource(R.string.ui_connection_dropped_reconnecting)
                        LiveState.ConnectingDirectly -> stringResource(R.string.ui_not_on_the_same_wi_fi_connecting_directly)
                        else -> stringResource(R.string.ui_connecting)
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
