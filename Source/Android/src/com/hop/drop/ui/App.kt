package com.hop.drop.ui

import com.hop.drop.R

import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun HopDropApp(state: HopUiState, actions: HopActions) {
    val snackbar = remember { SnackbarHostState() }
    var lastDevice by remember { mutableStateOf<DeviceUi?>(null) }
    var lastFiles by remember { mutableStateOf<HistoryUi?>(null) }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message.text)
    }
    LaunchedEffect(state.deviceOpen, state.devices) {
        if (state.deviceOpen != null) {
            lastDevice = state.devices.firstOrNull { it.id == state.deviceOpen }
            if (lastDevice == null) state.deviceOpen = null
        }
    }
    LaunchedEffect(state.allFilesOf) { if (state.allFilesOf != null) lastFiles = state.allFilesOf }
    BackHandler(enabled = state.deviceOpen != null) { state.deviceOpen = null }
    BackHandler(enabled = state.allFilesOf != null) { state.allFilesOf = null }
    BackHandler(enabled = state.backupsOpen && state.backupOpen == null) { state.backupsOpen = false }
    BackHandler(enabled = state.backupOpen != null) { state.backupOpen = null }
    BackHandler(enabled = state.receivedBackupsOpen) { state.receivedBackupsOpen = false }
    BackHandler(enabled = state.settingsOpen && !state.backupsOpen && !state.receivedBackupsOpen && state.deviceOpen == null && state.allFilesOf == null) { state.settingsOpen = false }
    BackHandler(enabled = !state.settingsOpen && state.deviceOpen == null && state.allFilesOf == null && state.tab != Tab.Send) { state.tab = Tab.Send }
    if (state.onboarding) {
        Onboarding(state, actions)
        return
    }
    Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
        AnimatedContent(
            targetState = when {
                state.deviceOpen != null -> "device"
                state.allFilesOf != null -> "files"
                state.backupsOpen -> "backups"
                state.receivedBackupsOpen -> "received-backups"
                state.settingsOpen -> "settings"
                else -> "main"
            },
            transitionSpec = {
                if (targetState != "main") (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200))) togetherWith fadeOut(tween(120))
                else fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(150)))
            },
            label = "settings",
        ) { page ->
            when (page) {
                "device" -> (state.devices.firstOrNull { it.id == state.deviceOpen } ?: lastDevice)?.let {
                    DeviceDetailsScreen(it, actions) { state.deviceOpen = null }
                }
                "files" -> (state.allFilesOf ?: lastFiles)?.let { AllFilesScreen(it, actions) { state.allFilesOf = null } }
                "backups" -> AutoBackupsScreen(state, actions) { state.backupsOpen = false }
                "received-backups" -> ReceivedBackupsScreen(state, actions) { state.receivedBackupsOpen = false }
                "settings" -> SettingsScreen(state, actions, snackbar) { state.settingsOpen = false }
                else -> MainScreen(state, actions, snackbar)
            }
        }
    }
    GlobalDialogs(state, actions)
    if (state.whatsNewVersion != null) WhatsNew(state, actions)
}

/** Top bar, the three tabs and their content. Also drawn, untouchable, in the tour's phone pictures. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(state: HopUiState, actions: HopActions, snackbar: SnackbarHostState) {
    var statusOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LogoMark(30.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("HopDrop", style = MaterialTheme.typography.titleLarge)
                    }
                },
                actions = {
                    ReceiverPill(state.phone.receiving) { statusOpen = true }
                    if (state.tab == Tab.Activity && state.history.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.ui_more)) }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.ui_clear_activity)) }, onClick = { menuOpen = false; confirmClear = true })
                            }
                        }
                    }
                    IconButton(onClick = { state.settingsOpen = true }) { Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.ui_settings)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                for (tab in Tab.entries) {
                    val busy = tab == Tab.Activity && state.live.any { it.active } && state.tab != Tab.Activity
                    NavigationBarItem(
                        selected = state.tab == tab,
                        onClick = { state.tab = tab },
                        icon = {
                            BadgedBox(badge = { if (busy) Badge() }) {
                                Icon(when (tab) { Tab.Send -> Icons.AutoMirrored.Rounded.Send; Tab.Activity -> Icons.Rounded.History; Tab.Devices -> Icons.Rounded.Devices },
                                    contentDescription = null)
                            }
                        },
                        label = { Text(stringResource(tab.label)) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        },
        floatingActionButton = {
            AnimatedVisibility(state.tab == Tab.Devices, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                ExtendedFloatingActionButton(
                    onClick = actions::scanQr,
                    icon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) },
                    text = { Text(stringResource(R.string.ui_pair_device)) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        AnimatedContent(
            targetState = state.tab,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(240)) { w -> if (forward) w / 8 else -w / 8 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(180)) { w -> if (forward) -w / 8 else w / 8 } + fadeOut(tween(140)))
            },
            label = "tabs",
        ) { tab ->
            when (tab) {
                Tab.Send -> SendScreen(state, actions, padding)
                Tab.Activity -> ActivityScreen(state, actions, padding)
                Tab.Devices -> DevicesScreen(state, actions, padding)
            }
        }
    }
    if (statusOpen) ReceiverModal(state.phone, state.settings.background, actions) { statusOpen = false }
    if (confirmClear) {
        HopModal(
            onDismissRequest = { confirmClear = false }, paneTitle = stringResource(R.string.ui_clear_activity_15),
            title = { Text(stringResource(R.string.ui_clear_activity_15)) },
            text = { Text(stringResource(R.string.ui_this_clears_the_list_only_received_files_stay_where_the)) },
            confirmButton = {
                Button(onClick = { confirmClear = false; actions.clearHistory() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text(stringResource(R.string.ui_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.ui_cancel)) } },
        )
    }
}

/** "● Ready" when other devices can send to this phone, "● Off" when they can't. */
@Composable
private fun ReceiverPill(receiving: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(if (receiving) OnlineGreen.copy(alpha = 0.14f) else scheme.surfaceContainerHigh, label = "pill")
    Surface(onClick = onClick, shape = CircleShape, color = bg, modifier = Modifier.padding(end = 4.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (receiving) OnlineGreen else scheme.outline))
            Spacer(Modifier.width(7.dp))
            Text(if (receiving) stringResource(R.string.ui_ready) else stringResource(R.string.ui_off), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun ReceiverModal(phone: PhoneUi, background: Boolean, actions: HopActions, onDismiss: () -> Unit) {
    val ipLabel = stringResource(R.string.ui_ip_address)
    val title = if (phone.receiving) stringResource(R.string.ui_ready_to_receive) else stringResource(R.string.ui_not_receiving)
    HopModal(onDismissRequest = onDismiss, paneTitle = title,
        title = { Text(title) },
        text = { Column {
            Text(
                when {
                    !phone.receiving -> stringResource(R.string.ui_close_hopdrop_and_open_it_again_if_that_doesn_t_help_tu)
                    background -> stringResource(R.string.ui_paired_devices_can_send_to_this_phone_at_any_time)
                    else -> stringResource(R.string.ui_paired_devices_can_send_to_this_phone_while_hopdrop_is_)
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            ListItem(headlineContent = { Text(stringResource(R.string.ui_name)) }, supportingContent = { Text(phone.name) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            if (phone.addresses.isEmpty()) {
                ListItem(headlineContent = { Text(stringResource(R.string.ui_no_local_network)) }, supportingContent = { Text(stringResource(R.string.ui_join_wi_fi_or_turn_on_your_hotspot)) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
            for (a in phone.addresses) {
                ListItem(
                    headlineContent = { Text(ltr(a.ip)) },
                    supportingContent = { Text(a.kind) },
                    trailingContent = { IconButton(onClick = { actions.copy(ipLabel, a.ip) }) { Icon(Icons.Rounded.ContentCopy,
                        contentDescription = stringResource(R.string.copy_address, ltr(a.ip))) } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        } },
        confirmButton = { Button(onClick = onDismiss) { Text(stringResource(R.string.ui_done)) } },
    )
}
