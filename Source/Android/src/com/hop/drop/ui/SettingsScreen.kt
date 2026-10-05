package com.hop.drop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: HopUiState, actions: HopActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val s = state.settings
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var renaming by remember { mutableStateOf(false) }
    var licences by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                scrollBehavior = scroll,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 32.dp),
        ) {
            item(key = "appearance") {
                Group("Appearance") {
                    Text("Theme", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = s.theme == mode,
                                onClick = { actions.setTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                label = { Text(mode.label) },
                            )
                        }
                    }
                    Text("Colour", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    ) {
                        for (p in Palette.available) Swatch(p, selected = s.palette == p) { actions.setPalette(p) }
                    }
                }
            }
            item(key = "phone") {
                Group("This phone") {
                    SettingRow(clickable = { renaming = true }, icon = Icons.Rounded.PhoneAndroid, title = "Name", detail = state.phone.name)
                }
            }
            item(key = "receiving") {
                Group("Receiving") {
                    SettingRow(
                        clickable = actions::chooseReceiveFolder, icon = Icons.Rounded.Folder, title = "Receive folder", detail = s.folderName,
                        trailing = if (s.customFolder) ({ TextButton(onClick = actions::resetReceiveFolder) { Text("Reset") } }) else null,
                    )
                    SwitchRow("Receive in the background", "Even when HopDrop is closed", s.background, actions::setBackground)
                    if (s.background && s.batteryRestricted) {
                        SettingRow(clickable = actions::allowBackground, icon = Icons.Rounded.BatteryAlert, title = "Allow background activity",
                            detail = "So files arrive with the screen off", accent = true)
                    }
                    if (s.background && s.autoStartHint) {
                        SettingRow(clickable = actions::openAppSettings, icon = Icons.Rounded.RocketLaunch, title = "Allow auto-launch",
                            detail = "This phone's brand needs it to receive after a restart")
                    }
                    SwitchRow("Ask before receiving", "Trusted devices skip this", s.ask, actions::setAsk)
                }
            }
            item(key = "privacy") {
                Group("Privacy") {
                    Text("Who can see this phone", style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        SegmentedButton(selected = !s.pairedOnly, onClick = { actions.setPairedOnly(false) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text("Everyone") })
                        SegmentedButton(selected = s.pairedOnly, onClick = { actions.setPairedOnly(true) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text("Paired only") })
                    }
                    Text(
                        if (s.pairedOnly) "Hidden from other devices, except while the Devices tab is open." else "Devices on the same Wi-Fi can see its name.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    )
                }
            }
            item(key = "about") {
                Group("About") {
                    ListItem(
                        headlineContent = { Text("HopDrop " + state.phone.version) },
                        supportingContent = { Text("No cloud. No accounts.") },
                        leadingContent = { LogoMark(36.dp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    SettingRow(clickable = { actions.openLink("https://github.com/Abdallah-Tarek-prog/HopDrop") }, icon = Icons.Rounded.Code,
                        title = "Source code", detail = "github.com/Abdallah-Tarek-prog/HopDrop")
                    SettingRow(clickable = { actions.openLink("https://github.com/Abdallah-Tarek-prog/HopDrop/blob/main/PRIVACY.md") },
                        icon = Icons.Rounded.PrivacyTip, title = "Privacy policy", detail = "HopDrop collects nothing")
                    SettingRow(clickable = { licences = true }, icon = Icons.Rounded.Gavel, title = "Licences", detail = "GPL-3.0 and open-source parts")
                    if (state.phone.deviceId.isNotEmpty()) {
                        SettingRow(clickable = { actions.copy("Device ID", state.phone.deviceId) }, icon = null, title = "Device ID",
                            detail = state.phone.deviceId.chunked(4).take(2).joinToString(" ").uppercase())
                    }
                }
            }
        }
    }
    if (renaming) {
        TextInputDialog(
            title = "This phone's name", initial = state.phone.name, label = "Name",
            help = "Other devices see this name.",
            onDismiss = { renaming = false },
            onSave = { name -> actions.setPhoneName(name).also { if (it == null) renaming = false } },
        )
    }
    if (licences) {
        AlertDialog(
            onDismissRequest = { licences = false },
            title = { Text("Licences") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("HopDrop is free software under the GNU General Public License, version 3.\n\n" +
                        "It includes:\n" +
                        "•  ZXing, for reading QR codes (Apache License 2.0)\n" +
                        "•  Jetpack Compose and AndroidX (Apache License 2.0)\n" +
                        "•  Plus Jakarta Sans font (SIL Open Font License 1.1)")
                }
            },
            confirmButton = { TextButton(onClick = { licences = false }) { Text("Close") } },
        )
    }
}

/** A titled group of settings in one card. */
@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    SectionHeader(title)
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceContainerLow), content = content)
}

@Composable
private fun SettingRow(
    clickable: () -> Unit, icon: androidx.compose.ui.graphics.vector.ImageVector?, title: String, detail: String? = null,
    trailing: (@Composable () -> Unit)? = null, accent: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = detail?.let { { Text(it) } },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = if (accent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = clickable),
    )
}

@Composable
private fun SwitchRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(detail) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(role = Role.Switch) { onChange(!checked) },
    )
}

@Composable
private fun Swatch(palette: Palette, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
        Box(
            Modifier.size(52.dp).clip(CircleShape).border(2.dp, ring, CircleShape).padding(5.dp).clip(CircleShape)
                .background(
                    if (palette == Palette.Wallpaper) Brush.sweepGradient(listOf(Color(0xFFEF6C57), Color(0xFFF2C14E), Color(0xFF5DB075), Color(0xFF4F8CD6), Color(0xFFB06AB3), Color(0xFFEF6C57)))
                    else Brush.linearGradient(listOf(palette.swatch, palette.swatch))
                )
                .clickable(role = Role.RadioButton, onClick = onClick)
                .semantics { this.selected = selected; contentDescription = palette.label + " colour" },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(palette.label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}
