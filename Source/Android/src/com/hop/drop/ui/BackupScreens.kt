package com.hop.drop.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.hop.drop.core.FileNames
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private const val ALL = 15
private val kinds = listOf(1 to "Photos", 2 to "Videos", 4 to "Documents", 8 to "Audio")
private fun time(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
private fun last(value: Long) = if (value == 0L) "Never" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(value))

/** "All files", "Photos", "Photos and videos", or "Photos · Videos · Audio". */
private fun what(mask: Int): String {
    if (mask == ALL) return "All files"
    val names = kinds.filter { (mask and it.first) != 0 }.map { it.second }
    return when (names.size) {
        1 -> names[0]
        2 -> names[0] + " and " + names[1].lowercase(Locale.getDefault())
        else -> names.joinToString(" · ")
    }
}

private fun whenText(b: BackupUi): String {
    val schedule = if (b.timed) "Between " + time(b.from) + " and " + time(b.to) else "Whenever possible"
    return if (b.charging) "$schedule · while charging" else schedule
}

/** Settings → Auto-backup: the phone's backups (several folders, each to one device), and one backup's own screen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoBackupsScreen(state: HopUiState, actions: HopActions, onBack: () -> Unit) {
    val selected = state.settings.backups.firstOrNull { it.id == state.backupOpen }
    var choosingDevice by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf<Int?>(null) }
    BackHandler(enabled = choosingDevice) { choosingDevice = false }
    val title = if (choosingDevice) "Send to" else selected?.folder ?: "Auto-backup"
    fun back() {
        if (choosingDevice) choosingDevice = false
        else if (selected != null) state.backupOpen = null
        else onBack()
    }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(title) }, navigationIcon = {
                IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            when {
                choosingDevice && selected != null -> DevicePicker(state.devices, selected.deviceId) { id ->
                    actions.setBackupDevice(selected.id, id); choosingDevice = false
                }
                selected == null -> BackupList(state, actions)
                else -> BackupEditor(selected, actions, onDevice = { choosingDevice = true }, onTime = { pickTime = it }, onRemove = { removing = true })
            }
        }
    }
    if (removing && selected != null) HopModal(onDismissRequest = { removing = false }, paneTitle = "Remove backup?",
        title = { Text("Remove this backup?") }, text = { Text("The files already backed up stay where they are.") },
        confirmButton = { Button(onClick = { actions.removeBackup(selected.id); removing = false },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Remove") } },
        dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } })
    if (pickTime != null && selected != null) {
        val which = pickTime!!
        val initial = if (which == 0) selected.from else selected.to
        val picker = rememberTimePickerState(initialHour = initial / 60, initialMinute = initial % 60, is24Hour = true)
        HopModal(onDismissRequest = { pickTime = null }, paneTitle = if (which == 0) "Start time" else "End time",
            title = { Text(if (which == 0) "Start time" else "End time") }, text = { TimePicker(state = picker) },
            confirmButton = { Button(onClick = {
                val value = picker.hour * 60 + picker.minute
                actions.setBackupWindow(selected.id, true, if (which == 0) value else selected.from, if (which == 1) value else selected.to)
                pickTime = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { pickTime = null }) { Text("Cancel") } })
    }
}

@Composable
private fun BackupList(state: HopUiState, actions: HopActions) {
    val backups = state.settings.backups
    Text("New files in a folder go to one of your devices by themselves, whenever both are on the same Wi-Fi.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 12.dp))
    if (backups.isEmpty()) HopCard { EmptyState(Icons.Rounded.Backup, "No backups yet", "Pick a folder, then the device it goes to.") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in backups) HopCard(onClick = { state.backupOpen = b.id }, padding = PaddingValues(start = 16.dp, top = 14.dp, end = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(b.folder ?: "Folder", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(what(b.kinds) + " to " + (b.device ?: "a device"), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (b.on) whenText(b) + (b.status?.let { " · $it" } ?: "") else "Off", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(b.on, onCheckedChange = { actions.setBackupOn(b.id, it) })
            }
        }
    }
    Button(onClick = { actions.chooseBackupFolder() }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp).height(52.dp)) { Text("Add a backup") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupEditor(b: BackupUi, actions: HopActions, onDevice: () -> Unit, onTime: (Int) -> Unit, onRemove: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    HopCard(padding = PaddingValues(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Back up this folder", style = MaterialTheme.typography.titleMedium)
                val detail = when {
                    !b.on -> "Off"
                    b.lastRun == 0L -> (b.status ?: "On") + " · not run yet"
                    else -> (b.status ?: "On") + " · last run " + last(b.lastRun)
                }
                Text(detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Switch(b.on, onCheckedChange = { actions.setBackupOn(b.id, it) })
        }
    }
    SectionHeader("Folder and device")
    HopCard(padding = PaddingValues(vertical = 4.dp)) {
        PickRow(Icons.Rounded.Folder, "Folder", b.folder ?: "Pick a folder") { actions.chooseBackupFolder(b.id) }
        HorizontalDivider(Modifier.padding(start = 56.dp), color = scheme.outlineVariant)
        PickRow(Icons.Rounded.Devices, "Send to", b.device ?: "Pick a device", onDevice)
    }
    SectionHeader("What")
    HopCard {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = b.kinds == ALL, onClick = { actions.setBackupKinds(b.id, ALL) }, label = { Text("All files") })
            for ((bit, name) in kinds) FilterChip(selected = b.kinds != ALL && (b.kinds and bit) != 0, onClick = {
                val next = if (b.kinds == ALL) bit else b.kinds xor bit
                actions.setBackupKinds(b.id, if (next == 0) ALL else next)
            }, label = { Text(name) })
        }
    }
    SectionHeader("When")
    HopCard(padding = PaddingValues(vertical = 4.dp)) {
        ChoiceRow("Whenever possible", selected = !b.timed) { actions.setBackupWindow(b.id, false, b.from, b.to) }
        ChoiceRow("Only between", selected = b.timed) {
            actions.setBackupWindow(b.id, true, b.from, if (b.from == b.to) (b.from + 300) % 1440 else b.to)
        }
        if (b.timed) Row(Modifier.padding(start = 56.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { onTime(0) }) { Text(time(b.from)) }
            Text("and", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 10.dp))
            OutlinedButton(onClick = { onTime(1) }) { Text(time(b.to)) }
        }
        HorizontalDivider(Modifier.padding(start = 16.dp), color = scheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Switch, onClick = { actions.setBackupCharging(b.id, !b.charging) })
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Only while charging", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Switch(b.charging, onCheckedChange = null)
        }
    }
    Button(onClick = { actions.backupNow(b.id) }, modifier = Modifier.fillMaxWidth().padding(top = 20.dp).height(52.dp)) {
        Icon(Icons.Rounded.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Back up now")
    }
    OutlinedButton(
        onClick = onRemove, colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.error),
        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().padding(top = 32.dp).height(52.dp),
    ) {
        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Remove backup")
    }
}

/** One row that opens a choice: icon, title, current value and a chevron. */
@Composable
private fun PickRow(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, role = Role.RadioButton, onClick = onSelect).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DevicePicker(devices: List<DeviceUi>, current: String?, onPick: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
        for (d in devices) HopCard(onClick = { onPick(d.id) }, padding = PaddingValues(start = 14.dp, top = 12.dp, end = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(d.platform, d.online)
                Text(d.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                if (d.id == current) Icon(Icons.Rounded.Check, contentDescription = "Chosen", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Settings → Backups received: where other devices' backups are saved on this phone, and which arrived. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceivedBackupsScreen(state: HopUiState, actions: HopActions, onBack: () -> Unit) {
    var forgetting by remember { mutableStateOf<ReceivedBackupUi?>(null) }
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Backups received") }, navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surface))
        },
        containerColor = scheme.surface,
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            SectionHeader("Save backups in")
            HopCard {
                Text(state.settings.backupPlace, style = MaterialTheme.typography.bodyLarge)
                Text("Each device gets its own folder in there.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = actions::chooseBackupPlace) { Text("Change") }
                    if (state.settings.backupPlace != "Download/HopDrop/Backups") TextButton(onClick = actions::resetBackupPlace) { Text("Use default") }
                }
            }
            SectionHeader("From your devices")
            if (state.settings.receivedBackups.isEmpty()) HopCard {
                EmptyState(Icons.Rounded.Backup, "No backups received yet", "When another device backs up to this phone, it shows here.")
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (b in state.settings.receivedBackups) HopCard(padding = PaddingValues(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 6.dp)) {
                    Text(b.device + " · " + b.folder, style = MaterialTheme.typography.titleMedium)
                    Text(String.format(Locale.getDefault(), "%,d", b.count) + (if (b.count == 1) " file" else " files") + " · last " + last(b.lastRun),
                        style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    Text(b.place + "/" + FileNames.sanitize(b.device) + "/" + FileNames.sanitize(b.folder), style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant)
                    TextButton(onClick = { forgetting = b }, modifier = Modifier.align(Alignment.End)) { Text("Forget") }
                }
            }
        }
    }
    forgetting?.let { entry ->
        HopModal(onDismissRequest = { forgetting = null }, paneTitle = "Forget this backup?",
            title = { Text("Forget this backup?") }, text = { Text("It leaves this list. Its files stay where they are.") },
            confirmButton = { Button(onClick = { actions.forgetReceivedBackup(entry.id, entry.folder, entry.location); forgetting = null }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } })
    }
}
