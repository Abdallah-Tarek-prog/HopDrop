package com.hop.drop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hop.drop.core.Format

/** Every dialog that can pop up over any screen: pairing, files waiting for an answer, QR code, pair options. */
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
    state.qr?.let { QrSheet(it, actions) }
    if (state.pairSheet) PairOptionsSheet(actions) { state.pairSheet = false }
    if (state.askBackground) {
        AlertDialog(
            onDismissRequest = { state.askBackground = false },
            icon = { Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null) },
            title = { Text("Receive files any time") },
            text = { Text("Let HopDrop run in the background so files arrive even when the screen is off.") },
            confirmButton = { TextButton(onClick = { state.askBackground = false; actions.allowBackground() }) { Text("Allow") } },
            dismissButton = { TextButton(onClick = { state.askBackground = false }) { Text("Not now") } },
        )
    }
}

/** Number comparison: both screens show the same six digits when nobody is in the middle. */
@Composable
private fun PairCodeDialog(prompt: PromptUi, actions: HopActions) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Rounded.Link, contentDescription = null) },
        title = { Text("Pair with ${prompt.name}?", textAlign = TextAlign.Center) },
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
        confirmButton = { TextButton(onClick = { actions.answerPrompt(true) }) { Text("They match") } },
        dismissButton = { TextButton(onClick = { actions.answerPrompt(false) }) { Text("Cancel") } },
    )
}

/** "Ask before receiving": who wants to send what, one file per line. */
@Composable
private fun OfferDialog(offer: OfferUi, actions: HopActions) {
    val items = topItems(offer.files)
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Rounded.Download, contentDescription = null) },
        title = { Text("${offer.peer} wants to send you " + Format.files(offer.files.size), textAlign = TextAlign.Center) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
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
        confirmButton = { TextButton(onClick = { actions.answerOffer(offer, true) }) { Text("Accept") } },
        dismissButton = { TextButton(onClick = { actions.answerOffer(offer, false) }) { Text("Decline") } },
    )
}

/** Pairing after a scan or an address: a spinner while it connects, then what went wrong if it didn't work. */
@Composable
private fun PairingDialog(pairing: PairingUi, actions: HopActions) {
    val failed = pairing.error != null
    AlertDialog(
        onDismissRequest = { if (failed) actions.dismissPairing() },
        icon = { Icon(if (failed) Icons.Rounded.ErrorOutline else Icons.Rounded.Link, contentDescription = null) },
        title = { Text(if (failed) "Couldn't pair" else "Pairing with ${pairing.name}…", textAlign = TextAlign.Center) },
        text = {
            if (failed) Text(pairing.error ?: "")
            else ProgressBar(null, Modifier.padding(top = 8.dp))
        },
        confirmButton = {
            if (failed) TextButton(onClick = { actions.dismissPairing(); actions.scanQr() }) { Text("Scan again") }
        },
        dismissButton = { TextButton(onClick = actions::dismissPairing) { Text(if (failed) "Close" else "Cancel") } },
    )
}

/** This phone's pairing code, for another device to scan. Keeps the screen on while it shows. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun QrSheet(qr: QrUi, actions: HopActions) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    ModalBottomSheet(onDismissRequest = actions::closeMyQr) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Scan to pair", style = MaterialTheme.typography.titleLarge)
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
    }
}

/** The ways to pair, from the Devices tab's "Pair device" button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PairOptionsSheet(actions: HopActions, onDismiss: () -> Unit) {
    var byAddress by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
            Text("Pair a device", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
            Text("Pair once, then send with one tap.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp, top = 2.dp, bottom = 16.dp))
            PairOption(Icons.Rounded.QrCodeScanner, "Scan a QR code", "Use the code shown on the other device", primary = true) {
                onDismiss(); actions.scanQr()
            }
            PairOption(Icons.Rounded.QrCode2, "Show my QR code", "For the other device to scan") { onDismiss(); actions.showMyQr() }
            PairOption(Icons.Rounded.Lan, "Enter an IP address", "If the device doesn't show up nearby") { byAddress = true }
        }
    }
    if (byAddress) {
        TextInputDialog(
            title = "Pair by IP address", initial = "", label = "Address", placeholder = "192.168.1.23",
            help = "Shown on the other device under Devices.", keyboard = KeyboardType.Uri, confirm = "Pair",
            onDismiss = { byAddress = false },
            onSave = { text -> actions.pairByAddress(text).also { if (it == null) { byAddress = false; onDismiss() } } },
        )
    }
}

@Composable
private fun PairOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, primary: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(if (primary) scheme.primaryContainer else scheme.surfaceContainerLow)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(if (primary) scheme.primary else scheme.secondaryContainer), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = if (primary) scheme.onPrimary else scheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (primary) scheme.onPrimaryContainer else scheme.onSurface)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = if (primary) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null,
            tint = if (primary) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
    }
    Spacer(Modifier.height(8.dp))
}

/** One text field with Save / Cancel. [onSave] returns null when it worked, or the problem to show under the field. */
@Composable
fun TextInputDialog(
    title: String, initial: String, label: String, onDismiss: () -> Unit, onSave: (String) -> String?,
    placeholder: String? = null, help: String? = null, keyboard: KeyboardType = KeyboardType.Text, confirm: String = "Save",
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
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
        confirmButton = { TextButton(onClick = { error = onSave(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
