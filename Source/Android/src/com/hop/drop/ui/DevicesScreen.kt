package com.hop.drop.ui

import com.hop.drop.R

import androidx.compose.ui.res.stringResource

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.WifiTethering
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
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
            item(key = "paired-header") { SectionHeader(stringResource(R.string.ui_paired)) }
            if (state.devices.isEmpty()) {
                item(key = "paired-empty") {
                    HopCard {
                        Text(stringResource(R.string.ui_no_paired_devices_yet), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                items(state.devices, key = { "paired:" + it.id }) { device ->
                    PairedRow(device, now, Modifier.animateItem()) { state.deviceOpen = device.id }
                }
            }
            item(key = "nearby-header") {
                SectionHeader(stringResource(R.string.ui_nearby)) {
                    if (state.phone.receiving && state.phone.addresses.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 4.dp)) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.ui_searching), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            when {
                !state.phone.receiving -> item(key = "nearby-off") {
                    HopCard { EmptyState(Icons.Rounded.ErrorOutline, stringResource(R.string.ui_not_searching), stringResource(R.string.ui_hopdrop_isn_t_receiving_right_now_reopen_the_app)) }
                }
                state.phone.addresses.isEmpty() -> item(key = "nearby-offline") {
                    HopCard { EmptyState(Icons.Rounded.WifiOff, stringResource(R.string.ui_not_on_wi_fi), stringResource(R.string.ui_no_problem_scan_the_qr_code_on_your_pc_and_hopdrop_conn)) }
                }
                state.nearby.isEmpty() -> item(key = "nearby-empty") {
                    HopCard { EmptyState(Icons.Rounded.Radar, stringResource(R.string.ui_looking_for_devices), stringResource(R.string.ui_devices_on_this_wi_fi_show_up_here_for_a_pc_scanning_it)) }
                }
                else -> items(state.nearby, key = { "nearby:" + it.id }) { device ->
                    NearbyRow(device, Modifier.animateItem()) { actions.pairNearby(device) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThisPhoneCard(phone: PhoneUi, actions: HopActions) {
    val ipLabel = stringResource(R.string.ui_ip_address)
    HopCard(color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DeviceAvatar(Platform.Android, phone.receiving, size = 48.dp, container = MaterialTheme.colorScheme.primaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(stringResource(R.string.ui_this_phone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(phone.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalIconButton(onClick = actions::showMyQr) { Icon(Icons.Rounded.QrCode2, contentDescription = stringResource(R.string.ui_show_my_qr_code)) }
        }
        if (phone.addresses.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (a in phone.addresses) {
                    AssistChip(
                        onClick = { actions.copy(ipLabel, a.ip) },
                        label = { Text(a.kind + "  " + ltr(a.ip)) },
                        trailingIcon = { Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.ui_copy), modifier = Modifier.size(AssistChipDefaults.IconSize)) },
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
                Text((if (device.platform.label == "Computer") stringResource(R.string.ui_computer) else device.platform.label) + "  ·  " +
                    if (device.direct) stringResource(R.string.ui_connected_directly) else presence(device.online, device.lastSeen, now),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (device.hasLink) Icon(Icons.Rounded.WifiTethering, contentDescription = stringResource(R.string.ui_can_connect_directly), tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 6.dp).size(20.dp))
            if (device.trusted) Icon(Icons.Rounded.VerifiedUser, contentDescription = stringResource(R.string.ui_trusted), tint = MaterialTheme.colorScheme.primary,
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
                Text(ltr(device.address), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FilledTonalButton(onClick = onPair) { Text(stringResource(R.string.ui_pair)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDetailsScreen(device: DeviceUi, actions: HopActions, onDismiss: () -> Unit) {
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val now = rememberNow()
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.ui_device_details)) }, navigationIcon = {
            IconButton(onClick = onDismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.ui_back)) }
        }) },
        bottomBar = {
            Button(
                onClick = { removing = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp, vertical = 8.dp),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.ui_remove_device))
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                DeviceAvatar(device.platform, device.online, size = 56.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(device.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text((if (device.platform.label == "Computer") stringResource(R.string.ui_computer) else device.platform.label) + "  ·  " + presence(device.online, device.lastSeen, now),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.ui_rename)) }
            }
            Spacer(Modifier.height(12.dp))
            ListItem(
                headlineContent = { Text(stringResource(R.string.ui_trust_this_device)) },
                supportingContent = { Text(stringResource(R.string.ui_sends_without_asking_first)) },
                leadingContent = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
                trailingContent = { Switch(checked = device.trusted, onCheckedChange = { actions.setTrusted(device, it) }) },
                colors = transparent,
            )
            if (device.hasLink) ListItem(
                headlineContent = { Text(if (device.direct) stringResource(R.string.ui_connected_directly) else stringResource(R.string.ui_connect_directly)) },
                supportingContent = { Text(if (device.direct) stringResource(R.string.ui_no_shared_wi_fi_needed_right_now) else stringResource(R.string.ui_when_you_re_not_on_the_same_wi_fi_so_it_can_send_to_thi)) },
                leadingContent = { Icon(Icons.Rounded.WifiTethering, contentDescription = null) },
                trailingContent = {
                    if (device.direct) TextButton(onClick = actions::disconnectDirect) { Text(stringResource(R.string.ui_done)) }
                    else FilledTonalButton(onClick = { actions.connectDirectly(device) }) { Text(stringResource(R.string.ui_connect)) }
                },
                colors = transparent,
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.ui_paired)) },
                supportingContent = { Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(device.pairedAt))) },
                colors = transparent,
            )
            if (device.address != null) {
                ListItem(headlineContent = { Text(stringResource(R.string.ui_last_address)) }, supportingContent = { Text(ltr(device.address)) }, colors = transparent)
            }
        }
    }
    if (renaming) {
        TextInputDialog(
            title = stringResource(R.string.ui_rename_device), initial = device.name, label = stringResource(R.string.ui_name),
            onDismiss = { renaming = false },
            onSave = { name -> actions.rename(device, name).also { if (it == null) renaming = false } },
        )
    }
    if (removing) {
        HopModal(
            onDismissRequest = { removing = false }, paneTitle = stringResource(R.string.remove_device_question, ltr(device.name)),
            title = { Text(stringResource(R.string.remove_device_question, ltr(device.name))) },
            text = { Text(stringResource(R.string.ui_you_ll_need_to_pair_again_to_send_files_between_these_d)) },
            confirmButton = {
                Button(onClick = { removing = false; onDismiss(); actions.remove(device) },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.ui_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
}
