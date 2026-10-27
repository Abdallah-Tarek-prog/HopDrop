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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.hop.drop.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: HopUiState, actions: HopActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val s = state.settings
    val deviceIdLabel = stringResource(R.string.ui_device_id)
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var renaming by remember { mutableStateOf(false) }
    var licences by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.ui_settings)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.ui_back)) } },
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
            item(key = "phone") {
                Group(stringResource(R.string.ui_this_phone)) {
                    SettingRow(clickable = { renaming = true }, icon = Icons.Rounded.PhoneAndroid, title = stringResource(R.string.ui_name), detail = state.phone.name)
                }
            }
            item(key = "receiving") {
                Group(stringResource(R.string.ui_receiving)) {
                    SettingRow(
                        clickable = actions::chooseReceiveFolder, icon = Icons.Rounded.Folder, title = stringResource(R.string.ui_receive_folder), detail = s.folderName,
                        trailing = if (s.customFolder) ({ TextButton(onClick = actions::resetReceiveFolder) { Text(stringResource(R.string.ui_reset)) } }) else null,
                    )
                    SwitchRow(stringResource(R.string.ui_receive_in_the_background), stringResource(R.string.ui_even_when_hopdrop_is_closed), s.background, actions::setBackground)
                    if (s.background && s.batteryRestricted) {
                        SettingRow(clickable = actions::allowBackground, icon = Icons.Rounded.BatteryAlert, title = stringResource(R.string.ui_allow_background_activity),
                            detail = stringResource(R.string.ui_so_files_arrive_with_the_screen_off), accent = true)
                    }
                    if (s.background && s.autoStartHint) {
                        SettingRow(clickable = actions::openAppSettings, icon = Icons.Rounded.RocketLaunch, title = stringResource(R.string.ui_allow_auto_launch),
                            detail = stringResource(R.string.ui_this_phone_s_brand_needs_it_to_receive_after_a_restart))
                    }
                    SwitchRow(stringResource(R.string.ui_ask_before_receiving), stringResource(R.string.ui_trusted_devices_skip_this), s.ask, actions::setAsk)
                }
            }
            item(key = "clipboard") {
                Group(stringResource(R.string.ui_clipboard_and_quick_send)) {
                    SwitchRow(stringResource(R.string.ui_copy_text_from_my_devices), stringResource(R.string.ui_when_a_pc_shares_what_it_copied_it_lands_on_this_phone_), s.clipAuto, actions::setClipAuto)
                    SettingRow(clickable = actions::addTile, icon = Icons.Rounded.Dashboard, title = stringResource(R.string.ui_add_the_quick_send_tile),
                        detail = stringResource(R.string.ui_send_your_clipboard_or_latest_photo_from_the_quick_sett))
                }
            }
            item(key = "backup") {
                Group(stringResource(R.string.ui_backups)) {
                    val on = s.backups.count { it.on }
                    SettingRow(clickable = { state.backupsOpen = true }, icon = Icons.Rounded.Backup, title = stringResource(R.string.ui_auto_backup),
                        detail = when {
                            s.backups.isEmpty() -> stringResource(R.string.ui_back_up_a_folder_to_one_of_your_devices)
                            on == 0 -> stringResource(R.string.ui_off)
                            else -> pluralStringResource(R.plurals.enabled_backups, on, on)
                        })
                    SettingRow(clickable = { state.receivedBackupsOpen = true }, icon = Icons.Rounded.Folder, title = stringResource(R.string.ui_backups_received),
                        detail = stringResource(R.string.backup_received_from, ltr(s.backupPlace)))
                }
            }
            item(key = "privacy") {
                Group(stringResource(R.string.ui_privacy)) {
                    Text(stringResource(R.string.ui_who_can_see_this_phone), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        SegmentedButton(selected = !s.pairedOnly, onClick = { actions.setPairedOnly(false) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2), label = { Text(stringResource(R.string.ui_everyone)) })
                        SegmentedButton(selected = s.pairedOnly, onClick = { actions.setPairedOnly(true) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2), label = { Text(stringResource(R.string.ui_paired_only)) })
                    }
                    Text(
                        if (s.pairedOnly) stringResource(R.string.ui_hidden_from_other_devices_except_while_the_devices_tab_) else stringResource(R.string.ui_devices_on_the_same_wi_fi_can_see_its_name),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    )
                }
            }
            item(key = "appearance") {
                Group(stringResource(R.string.ui_appearance)) {
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        for ((i, language) in listOf("en", "ar").withIndex()) {
                            SegmentedButton(selected = s.language == language, onClick = { actions.setLanguage(language) },
                                shape = SegmentedButtonDefaults.itemShape(i, 2),
                                label = { Text(if (language == "en") "English" else "العربية") })
                        }
                    }
                    Text(stringResource(R.string.ui_theme), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 10.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        ThemeMode.entries.forEachIndexed { i, mode ->
                            SegmentedButton(
                                selected = s.theme == mode,
                                onClick = { actions.setTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                                label = { Text(stringResource(mode.label)) },
                            )
                        }
                    }
                    Text(stringResource(R.string.ui_colour), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 18.dp, bottom = 10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    ) {
                        for (p in Palette.available) Swatch(p, selected = s.palette == p) { actions.setPalette(p) }
                    }
                }
            }
            item(key = "about") {
                Group(stringResource(R.string.ui_about)) {
                    ListItem(
                        headlineContent = { Text("HopDrop " + state.phone.version) },
                        supportingContent = { Text(stringResource(R.string.ui_no_cloud_no_accounts)) },
                        leadingContent = { LogoMark(36.dp) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    if (s.updatesSupported) {
                        SwitchRow(stringResource(R.string.ui_check_for_updates), stringResource(R.string.ui_looks_at_github_once_a_day), s.updateChecks, actions::setUpdateChecks)
                        SettingRow(
                            clickable = actions::checkForUpdates, icon = Icons.Rounded.SystemUpdate,
                            title = state.update?.let { stringResource(R.string.update_to, it.version) } ?: stringResource(R.string.ui_check_now),
                            detail = when {
                                state.checkingUpdates -> stringResource(R.string.ui_checking)
                                state.update != null -> stringResource(R.string.ui_tap_to_download_and_install)
                                else -> stringResource(R.string.ui_look_for_a_newer_version_now)
                            },
                        )
                    }
                    SettingRow(clickable = actions::showTour, icon = Icons.Rounded.School, title = stringResource(R.string.ui_show_the_tour_again), detail = stringResource(R.string.ui_what_each_part_of_hopdrop_does))
                    SettingRow(clickable = { actions.openLink("https://github.com/Abdallah-Tarek-prog/HopDrop") }, icon = Icons.Rounded.Code,
                        title = stringResource(R.string.ui_source_code), detail = "github.com/Abdallah-Tarek-prog/HopDrop")
                    SettingRow(clickable = { actions.openLink("https://github.com/Abdallah-Tarek-prog/HopDrop/blob/main/PRIVACY.md") },
                        icon = Icons.Rounded.PrivacyTip, title = stringResource(R.string.ui_privacy_policy), detail = stringResource(R.string.ui_hopdrop_collects_nothing))
                    SettingRow(clickable = { licences = true }, icon = Icons.Rounded.Gavel, title = stringResource(R.string.ui_licences), detail = "GPL-3.0 and open-source parts")
                    if (state.phone.deviceId.isNotEmpty()) {
                        SettingRow(clickable = { actions.copy(deviceIdLabel, state.phone.deviceId) }, icon = null, title = deviceIdLabel,
                            detail = state.phone.deviceId.chunked(4).take(2).joinToString(" ").uppercase())
                    }
                }
            }
        }
    }
    if (renaming) {
        TextInputDialog(
            title = stringResource(R.string.ui_this_phone_s_name), initial = state.phone.name, label = stringResource(R.string.ui_name),
            help = stringResource(R.string.ui_other_devices_see_this_name),
            onDismiss = { renaming = false },
            onSave = { name -> actions.setPhoneName(name).also { if (it == null) renaming = false } },
        )
    }
    if (licences) {
        HopModal(
            onDismissRequest = { licences = false }, paneTitle = stringResource(R.string.ui_licences),
            title = { Text(stringResource(R.string.ui_licences)) },
            text = {
                Column {
                    Text(stringResource(R.string.licences_body))
                }
            },
            confirmButton = { Button(onClick = { licences = false }) { Text(stringResource(R.string.ui_done)) } },
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
    val label = stringResource(palette.label)
    val description = stringResource(R.string.palette_colour, label)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
        val ring = if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent
        Box(
            Modifier.size(52.dp).clip(CircleShape).border(2.dp, ring, CircleShape).padding(5.dp).clip(CircleShape)
                .background(
                    if (palette == Palette.Wallpaper) Brush.sweepGradient(listOf(Color(0xFFEF6C57), Color(0xFFF2C14E), Color(0xFF5DB075), Color(0xFF4F8CD6), Color(0xFFB06AB3), Color(0xFFEF6C57)))
                    else Brush.linearGradient(listOf(palette.swatch, palette.swatch))
                )
                .clickable(role = Role.RadioButton, onClick = onClick)
                .semantics { this.selected = selected; contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}
