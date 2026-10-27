package com.hop.drop.ui

import com.hop.drop.R

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hop.drop.core.Format

/** Dialogs that can pop up over any screen. */
@Composable
fun GlobalDialogs(state: HopUiState, actions: HopActions) {
    val prompt = state.prompt
    val offer = state.offers.firstOrNull()
    val pairing = state.pairing
    when {
        prompt != null -> PairCodeDialog(prompt, actions)
        offer != null -> OfferDialog(offer, actions)
        pairing != null -> PairingDialog(pairing, actions)
    }
    state.qr?.let { QrModal(it, actions) }
    state.backupSetup?.let { setup ->
        BackupSetupDialog(setup, state.settings.backups.firstOrNull { it.id == state.backupOpen }?.deviceId, actions) {
            state.backupSetup = null
            state.backupOpen?.let(actions::removeBackup)
        }
    }
    if (state.askAddress) TextInputDialog(
        title = stringResource(R.string.ui_pair_by_ip_address), initial = "", label = stringResource(R.string.ui_address), placeholder = "192.168.1.23",
        help = stringResource(R.string.ui_shown_on_the_other_device_under_devices), keyboard = KeyboardType.Uri, confirm = stringResource(R.string.ui_pair),
        onDismiss = { state.askAddress = false },
        onSave = { address -> actions.pairByAddress(address).also { if (it == null) state.askAddress = false } },
    )
    if (state.askBackground) {
        HopModal(
            onDismissRequest = { state.askBackground = false }, paneTitle = stringResource(R.string.ui_receive_files_any_time),
            icon = { Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null) },
            title = { Text(stringResource(R.string.ui_receive_files_any_time)) },
            text = { Text(stringResource(R.string.ui_let_hopdrop_run_in_the_background_so_files_arrive_even_)) },
            confirmButton = { Button(onClick = { state.askBackground = false; actions.allowBackground() }) { Text(stringResource(R.string.ui_allow)) } },
            dismissButton = { TextButton(onClick = { state.askBackground = false }) { Text(stringResource(R.string.ui_not_now)) } },
        )
    }
}

/** Number comparison: both screens show the same six digits when nobody is in the middle. */
@Composable
private fun PairCodeDialog(prompt: PromptUi, actions: HopActions) {
    HopModal(
        onDismissRequest = { actions.answerPrompt(false) }, required = true, paneTitle = stringResource(R.string.pair_with, ltr(prompt.name)),
        icon = { Icon(Icons.Rounded.Link, contentDescription = null) },
        title = { Text(stringResource(R.string.pair_with, ltr(prompt.name))) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    prompt.code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum", letterSpacing = 2.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.compare_number, ltr(prompt.name)), textAlign = TextAlign.Center)
            }
        },
        confirmButton = { Button(onClick = { actions.answerPrompt(true) }) { Text(stringResource(R.string.ui_they_match)) } },
        dismissButton = { TextButton(onClick = { actions.answerPrompt(false) }) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

/** stringResource(R.string.ui_ask_before_receiving): who wants to send what, one file per line. */
@Composable
private fun OfferDialog(offer: OfferUi, actions: HopActions) {
    val items = topItems(offer.files)
    HopModal(
        onDismissRequest = { actions.answerOffer(offer, false) }, required = true,
        paneTitle = stringResource(R.string.offer_files, ltr(offer.peer), fileObjectsText(offer.files.size)),
        icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
        title = { Text(stringResource(R.string.offer_files, ltr(offer.peer), fileObjectsText(offer.files.size))) },
        text = {
            Column {
                for (item in items.take(6)) {
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileIcon(item.name, null, size = 32.dp, folder = item.folder)
                        Text(if (item.folder) stringResource(R.string.folder_named, ltr(item.name)) else item.name,
                            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp))
                    }
                }
                if (items.size > 6) Text("+ " + stringResource(R.string.more_count, items.size - 6), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (offer.total >= 0) Text(sizeText(offer.total), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { Button(onClick = { actions.answerOffer(offer, true) }) { Text(stringResource(R.string.ui_accept)) } },
        dismissButton = { TextButton(onClick = { actions.answerOffer(offer, false) }) { Text(stringResource(R.string.ui_decline)) } },
    )
}

/** Pairing after a scan or an address: a spinner while it connects, then what went wrong if it didn't work. */
@Composable
private fun PairingDialog(pairing: PairingUi, actions: HopActions) {
    val failed = pairing.error != null
    HopModal(
        onDismissRequest = actions::dismissPairing, required = !failed,
        paneTitle = if (failed) stringResource(R.string.ui_couldn_t_pair) else stringResource(R.string.pairing_with, ltr(pairing.name)),
        icon = { Icon(if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Link, contentDescription = null) },
        title = { Text(if (failed) stringResource(R.string.ui_couldn_t_pair) else stringResource(R.string.pairing_with, ltr(pairing.name))) },
        text = {
            if (failed) Text(pairing.error ?: "")
            else Column {
                if (pairing.direct) Text(stringResource(R.string.pairing_direct_help),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                ProgressBar(null, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            if (failed) Button(onClick = { actions.dismissPairing(); actions.scanQr() }) { Text(stringResource(R.string.ui_scan_again)) }
        },
        dismissButton = { TextButton(onClick = actions::dismissPairing) { Text(if (failed) stringResource(R.string.ui_close) else stringResource(R.string.ui_cancel)) } },
    )
}

/** This phone's pairing code, for another device to scan. Keeps the screen on while it shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QrModal(qr: QrUi, actions: HopActions) {
    HopModal(
        onDismissRequest = actions::closeMyQr, paneTitle = stringResource(R.string.ui_scan_to_pair), keepScreenOn = true,
        title = { Text(stringResource(R.string.ui_scan_to_pair)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.ui_open_hopdrop_on_the_other_device_and_scan_this_code), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(top = 20.dp).widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f)) {
                    Image(qr.image, contentDescription = stringResource(R.string.ui_pairing_qr_code), filterQuality = FilterQuality.None, modifier = Modifier.padding(14.dp))
                }
                Text(stringResource(R.string.ui_works_once_for_5_minutes), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp))
                if (qr.addresses.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        for (a in qr.addresses) Text(a.kind + "  " + ltr(a.ip), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = actions::closeMyQr) { Text(stringResource(R.string.ui_done)) } },
    )
}

/**
 * Turning auto-backup on, after the folder is picked: which device to back up to (skipped when only one is paired),
 * then whether to send the files already in the folder or only new ones.
 */
@Composable
private fun BackupSetupDialog(setup: BackupSetupUi, device: String?, actions: HopActions, onDismiss: () -> Unit) {
    HopModal(
        onDismissRequest = onDismiss, paneTitle = stringResource(R.string.backup_folder_title, ltr(setup.folderName)),
        icon = { Icon(Icons.Rounded.Backup, contentDescription = null) },
        title = { Text(stringResource(R.string.backup_existing_question, setup.existing)) },
        text = { Text(stringResource(R.string.backup_setup_help)) },
        confirmButton = {
            Button(onClick = { device?.let { actions.startBackup(it, true) } }, enabled = device != null) { Text(stringResource(R.string.ui_send_existing_files)) }
        },
        dismissButton = {
            TextButton(onClick = { device?.let { actions.startBackup(it, false) } }, enabled = device != null) { Text(stringResource(R.string.ui_only_new_files)) }
        },
    )
}

/** One text field with Save / Cancel. [onSave] returns null when it worked, or the problem to show under the field. */
@Composable
fun TextInputDialog(
    title: String, initial: String, label: String, onDismiss: () -> Unit, onSave: (String) -> String?,
    placeholder: String? = null, help: String? = null, keyboard: KeyboardType = KeyboardType.Text, confirm: String = stringResource(R.string.ui_save),
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    HopModal(
        onDismissRequest = onDismiss, paneTitle = title,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                label = { Text(label) },
                placeholder = placeholder?.let { { Text(it) } },
                supportingText = (error ?: help)?.let { { Text(it) } },
                isError = error != null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { error = onSave(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}

/** Type or paste text to send; [initial] is what's on the clipboard. It arrives as text with Copy and Open buttons. */
@Composable
fun TextNoteDialog(initial: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    HopModal(
        onDismissRequest = onDismiss, paneTitle = stringResource(R.string.ui_send_text),
        title = { Text(stringResource(R.string.ui_send_text)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.ui_type_or_paste_text)) },
                supportingText = { Text(if (initial.isNotEmpty() && text == initial) stringResource(R.string.ui_from_your_clipboard) else stringResource(R.string.ui_arrives_as_text_ready_to_copy)) },
                minLines = 5, maxLines = 10,
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ui_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_cancel)) } },
    )
}
