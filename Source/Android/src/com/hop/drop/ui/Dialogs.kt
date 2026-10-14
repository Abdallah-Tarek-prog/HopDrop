package com.hop.drop.ui

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
        title = "Pair by IP address", initial = "", label = "Address", placeholder = "192.168.1.23",
        help = "Shown on the other device under Devices.", keyboard = KeyboardType.Uri, confirm = "Pair",
        onDismiss = { state.askAddress = false },
        onSave = { address -> actions.pairByAddress(address).also { if (it == null) state.askAddress = false } },
    )
    if (state.askBackground) {
        HopModal(
            onDismissRequest = { state.askBackground = false }, paneTitle = "Receive files any time",
            icon = { Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null) },
            title = { Text("Receive files any time") },
            text = { Text("Let HopDrop run in the background so files arrive even when the screen is off.") },
            confirmButton = { Button(onClick = { state.askBackground = false; actions.allowBackground() }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { state.askBackground = false }) { Text("Not now") } },
        )
    }
}

/** Number comparison: both screens show the same six digits when nobody is in the middle. */
@Composable
private fun PairCodeDialog(prompt: PromptUi, actions: HopActions) {
    HopModal(
        onDismissRequest = { actions.answerPrompt(false) }, required = true, paneTitle = "Pair with ${prompt.name}?",
        icon = { Icon(Icons.Rounded.Link, contentDescription = null) },
        title = { Text("Pair with ${prompt.name}?") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    prompt.code.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum", letterSpacing = 2.sp),
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text("Check that ${prompt.name} shows the same number.", textAlign = TextAlign.Center)
            }
        },
        confirmButton = { Button(onClick = { actions.answerPrompt(true) }) { Text("They match") } },
        dismissButton = { TextButton(onClick = { actions.answerPrompt(false) }) { Text("Cancel") } },
    )
}

/** "Ask before receiving": who wants to send what, one file per line. */
@Composable
private fun OfferDialog(offer: OfferUi, actions: HopActions) {
    val items = topItems(offer.files)
    HopModal(
        onDismissRequest = { actions.answerOffer(offer, false) }, required = true,
        paneTitle = "${offer.peer} wants to send you files",
        icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
        title = { Text("${offer.peer} wants to send you " + Format.files(offer.files.size)) },
        text = {
            Column {
                for (name in items.take(6)) {
                    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        FileIcon(name.removeSuffix(" folder"), null, size = 32.dp, folder = name.endsWith(" folder"))
                        Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 12.dp))
                    }
                }
                if (items.size > 6) Text("+ ${items.size - 6} more", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (offer.total >= 0) Text(Format.size(offer.total), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { Button(onClick = { actions.answerOffer(offer, true) }) { Text("Accept") } },
        dismissButton = { TextButton(onClick = { actions.answerOffer(offer, false) }) { Text("Decline") } },
    )
}

/** Pairing after a scan or an address: a spinner while it connects, then what went wrong if it didn't work. */
@Composable
private fun PairingDialog(pairing: PairingUi, actions: HopActions) {
    val failed = pairing.error != null
    HopModal(
        onDismissRequest = actions::dismissPairing, required = !failed,
        paneTitle = if (failed) "Couldn't pair" else "Pairing with ${pairing.name}",
        icon = { Icon(if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Link, contentDescription = null) },
        title = { Text(if (failed) "Couldn't pair" else "Pairing with ${pairing.name}…") },
        text = {
            if (failed) Text(pairing.error ?: "")
            else Column {
                if (pairing.direct) Text("Not on the same Wi-Fi, so HopDrop is connecting to it directly. If Android asks, tap Connect.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 12.dp))
                ProgressBar(null, Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            if (failed) Button(onClick = { actions.dismissPairing(); actions.scanQr() }) { Text("Scan again") }
        },
        dismissButton = { TextButton(onClick = actions::dismissPairing) { Text(if (failed) "Close" else "Cancel") } },
    )
}

/** This phone's pairing code, for another device to scan. Keeps the screen on while it shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QrModal(qr: QrUi, actions: HopActions) {
    HopModal(
        onDismissRequest = actions::closeMyQr, paneTitle = "Scan to pair", keepScreenOn = true,
        title = { Text("Scan to pair") },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Open HopDrop on the other device and scan this code.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                Surface(color = Color.White, shape = RoundedCornerShape(20.dp), modifier = Modifier.padding(top = 20.dp).widthIn(max = 300.dp).fillMaxWidth().aspectRatio(1f)) {
                    Image(qr.image, contentDescription = "Pairing QR code", filterQuality = FilterQuality.None, modifier = Modifier.padding(14.dp))
                }
                Text("Works once, for 5 minutes", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp))
                if (qr.addresses.isNotEmpty()) {
                    FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        for (a in qr.addresses) Text(a.kind + "  " + a.ip, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = actions::closeMyQr) { Text("Done") } },
    )
}

/**
 * Turning auto-backup on, after the folder is picked: which device to back up to (skipped when only one is paired),
 * then whether to send the files already in the folder or only new ones.
 */
@Composable
private fun BackupSetupDialog(setup: BackupSetupUi, device: String?, actions: HopActions, onDismiss: () -> Unit) {
    HopModal(
        onDismissRequest = onDismiss, paneTitle = "Back up ${setup.folderName}",
        icon = { Icon(Icons.Rounded.Backup, contentDescription = null) },
        title = { Text("Send the ${setup.existing} files already there, or only new ones?") },
        text = { Text("Choose which files to send first. You can change this backup's settings later.") },
        confirmButton = {
            Button(onClick = { device?.let { actions.startBackup(it, true) } }, enabled = device != null) { Text("Send existing files") }
        },
        dismissButton = {
            TextButton(onClick = { device?.let { actions.startBackup(it, false) } }, enabled = device != null) { Text("Only new files") }
        },
    )
}

/** One text field with Save / Cancel. [onSave] returns null when it worked, or the problem to show under the field. */
@Composable
fun TextInputDialog(
    title: String, initial: String, label: String, onDismiss: () -> Unit, onSave: (String) -> String?,
    placeholder: String? = null, help: String? = null, keyboard: KeyboardType = KeyboardType.Text, confirm: String = "Save",
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Type or paste text to send; [initial] is what's on the clipboard. It arrives as text with Copy and Open buttons. */
@Composable
fun TextNoteDialog(initial: String, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    HopModal(
        onDismissRequest = onDismiss, paneTitle = "Send text",
        title = { Text("Send text") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type or paste text") },
                supportingText = { Text(if (initial.isNotEmpty() && text == initial) "From your clipboard" else "Arrives as text, ready to copy") },
                minLines = 5, maxLines = 10,
                keyboardOptions = KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { Button(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
