package com.hop.drop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(state: HopUiState, actions: HopActions, padding: PaddingValues) {
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var openId by remember { mutableStateOf<String?>(null) }
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = {
            refreshing = true
            actions.refreshNearby()
            scope.launch { delay(1200); refreshing = false }
        },
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
    ) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = padding.calculateBottomPadding() + 96.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "phone") { ThisPhoneCard(state.phone, actions) }
            item(key = "paired-header") { SectionHeader("Paired") }
            if (state.devices.isEmpty()) {
                item(key = "paired-empty") {
                    HopCard {
                        Text("No paired devices yet", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(state.devices, key = { "paired:" + it.id }) { device ->
                    PairedRow(device, now, Modifier.animateItem()) { openId = device.id }
                }
            }
            item(key = "nearby-header") {
                SectionHeader("Nearby") {
                    if (state.phone.receiving && state.phone.addresses.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Searching", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            when {
                !state.phone.receiving -> item(key = "nearby-off") {
                    HopCard { EmptyState(Icons.Rounded.ErrorOutline, "Not searching", "HopDrop isn't receiving right now. Reopen the app.") }
                }
                state.phone.addresses.isEmpty() -> item(key = "nearby-offline") {
                    HopCard { EmptyState(Icons.Rounded.WifiOff, "Not on Wi-Fi", "Join the other device's Wi-Fi, or turn on your hotspot.") }
                }
                state.nearby.isEmpty() -> item(key = "nearby-empty") {
                    HopCard { EmptyState(Icons.Rounded.Radar, "Looking for devices…", "Open HopDrop on the other device.") }
                }
                else -> items(state.nearby, key = { "nearby:" + it.id }) { device ->
                    NearbyRow(device, Modifier.animateItem()) { actions.pairNearby(device) }
                }
            }
        }
    }
    val open = state.devices.firstOrNull { it.id == openId }
    if (open != null) DeviceSheet(open, now, actions) { openId = null }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThisPhoneCard(phone: PhoneUi, actions: HopActions) {
    HopCard(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(Platform.Android, phone.receiving, size = 48.dp, container = MaterialTheme.colorScheme.primaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text("This phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(phone.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalIconButton(onClick = actions::showMyQr) { Icon(Icons.Rounded.QrCode2, contentDescription = "Show my QR code") }
        }
        if (phone.addresses.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in phone.addresses) {
                    AssistChip(
                        onClick = { actions.copy("IP address", a.ip) },
                        label = { Text(a.kind + "  " + a.ip) },
                        trailingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(AssistChipDefaults.IconSize)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PairedRow(device: DeviceUi, now: Long, modifier: Modifier, onClick: () -> Unit) {
    HopCard(modifier, onClick = onClick, padding = PaddingValues(start = 14.dp, top = 12.dp, end = 8.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(device.platform, device.online)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((if (device.platform == Platform.Windows) "Windows" else "Android") + "  ·  " + presence(device.online, device.lastSeen, now),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (device.trusted) Icon(Icons.Rounded.VerifiedUser, contentDescription = "Trusted", tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp))
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun NearbyRow(device: NearbyUi, modifier: Modifier, onPair: () -> Unit) {
    HopCard(modifier, padding = PaddingValues(start = 14.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(device.platform, online = false, container = MaterialTheme.colorScheme.surfaceContainerHighest)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(device.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onPair) { Text("Pair") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(device: DeviceUi, now: Long, actions: HopActions, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(device.platform, device.online, size = 56.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text((if (device.platform == Platform.Windows) "Windows" else "Android") + "  ·  " + presence(device.online, device.lastSeen, now),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, contentDescription = "Rename") }
            }
            Spacer(Modifier.height(12.dp))
            ListItem(
                headlineContent = { Text("Trust this device") },
                supportingContent = { Text("Sends without asking first") },
                leadingContent = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
                trailingContent = { Switch(checked = device.trusted, onCheckedChange = { actions.setTrusted(device, it) }) },
                colors = transparent,
            )
            ListItem(
                headlineContent = { Text("Paired") },
                supportingContent = { Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(device.pairedAt))) },
                colors = transparent,
            )
            if (device.address != null) {
                ListItem(headlineContent = { Text("Last address") }, supportingContent = { Text(device.address) }, colors = transparent)
            }
            OutlinedButton(
                onClick = { removing = true },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Remove device")
            }
        }
    }
    if (renaming) {
        TextInputDialog(
            title = "Rename device", initial = device.name, label = "Name",
            onDismiss = { renaming = false },
            onSave = { name -> actions.rename(device, name).also { if (it == null) renaming = false } },
        )
    }
    if (removing) {
        AlertDialog(
            onDismissRequest = { removing = false },
            title = { Text("Remove ${device.name}?") },
            text = { Text("You'll need to pair again to send files between these devices.") },
            confirmButton = {
                TextButton(onClick = { removing = false; onDismiss(); actions.remove(device) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text("Cancel") } },
        )
    }
}
