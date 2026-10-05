package com.hop.drop.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.material3.ModalBottomSheet
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
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(message.text)
    }
    BackHandler(enabled = state.settingsOpen) { state.settingsOpen = false }
    BackHandler(enabled = !state.settingsOpen && state.tab != Tab.Send) { state.tab = Tab.Send }
    Box(Modifier.background(MaterialTheme.colorScheme.surface)) {
        AnimatedContent(
            targetState = state.settingsOpen,
            transitionSpec = {
                if (targetState) (slideInHorizontally(tween(260)) { it / 3 } + fadeIn(tween(200))) togetherWith fadeOut(tween(120))
                else fadeIn(tween(200)) togetherWith (slideOutHorizontally(tween(200)) { it / 3 } + fadeOut(tween(150)))
            },
            label = "settings",
        ) { settings ->
            if (settings) SettingsScreen(state, actions, snackbar) { state.settingsOpen = false }
            else MainScreen(state, actions, snackbar)
        }
    }
    GlobalDialogs(state, actions)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(state: HopUiState, actions: HopActions, snackbar: SnackbarHostState) {
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
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Clear activity") }, onClick = { menuOpen = false; confirmClear = true })
                            }
                        }
                    }
                    IconButton(onClick = { state.settingsOpen = true }) { Icon(Icons.Rounded.Settings, contentDescription = "Settings") }
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
                        label = { Text(tab.label) },
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
                    onClick = { state.pairSheet = true },
                    icon = { Icon(Icons.Rounded.QrCodeScanner, contentDescription = null) },
                    text = { Text("Pair device") },
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
    if (statusOpen) ReceiverSheet(state.phone, state.settings.background, actions) { statusOpen = false }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear activity?") },
            text = { Text("This clears the list only. Received files stay where they are.") },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; actions.clearHistory() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
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
            Text(if (receiving) "Ready" else "Off", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiverSheet(phone: PhoneUi, background: Boolean, actions: HopActions, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(if (phone.receiving) "Ready to receive" else "Not receiving", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp))
            Text(
                when {
                    !phone.receiving -> "Close HopDrop and open it again. If that doesn't help, turn Wi-Fi off and on."
                    background -> "Paired devices can send to this phone at any time."
                    else -> "Paired devices can send to this phone while HopDrop is open."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
            )
            ListItem(headlineContent = { Text("Name") }, supportingContent = { Text(phone.name) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            if (phone.addresses.isEmpty()) {
                ListItem(headlineContent = { Text("No local network") }, supportingContent = { Text("Join Wi-Fi or turn on your hotspot.") },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent))
            }
            for (a in phone.addresses) {
                ListItem(
                    headlineContent = { Text(a.ip) },
                    supportingContent = { Text(a.kind) },
                    trailingContent = { IconButton(onClick = { actions.copy("IP address", a.ip) }) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy ${a.ip}") } },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}
