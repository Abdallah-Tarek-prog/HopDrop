package com.hop.drop.ui

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap

// What the screens show. MainActivity turns the engine's objects into these and writes them into [HopUiState];
// the screens only read the state and call [HopActions].

enum class Tab(val label: String) { Send("Send"), Activity("Activity"), Devices("Devices") }

enum class Platform { Windows, Android }

data class PickedFile(val uri: Uri, val name: String, val size: Long, val mime: String?)

/** A whole folder to send. [files] is -1 while it's being counted. */
data class PickedFolder(val uri: Uri, val name: String, val files: Int, val bytes: Long, val sizeUnknown: Boolean)

sealed interface LiveState {
    data object Connecting : LiveState
    /** Not on the same Wi-Fi: joining the device's direct link. */
    data object ConnectingDirectly : LiveState
    /** The device couldn't be reached; the send waits for it. */
    data object Queued : LiveState
    /** Text went out. */
    data object TextSent : LiveState
    data class Waiting(val count: Int) : LiveState
    data object Reconnecting : LiveState
    data class Moving(
        val file: String, val index: Int, val count: Int, val done: Long, val total: Long,
        val speed: Double, val secondsLeft: Long,
    ) : LiveState {
        val fraction: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }
    data class Done(val files: List<String>, val count: Int, val bytes: Long, val millis: Long, val folder: String?) : LiveState
    data class Stopped(val reason: String, val cancelled: Boolean, val delivered: Int, val offered: Int) : LiveState
}

/** A transfer that is moving right now (or just ended: it stays a few seconds so people see how it went). */
data class LiveUi(val key: String, val incoming: Boolean, val peer: String, val state: LiveState) {
    val active: Boolean get() = state !is LiveState.Done && state !is LiveState.Stopped && state !is LiveState.Queued && state !is LiveState.TextSent
}

/**
 * [waiting]: sends queued for it until it's back. [hasLink]: it offers a direct link (its own Wi-Fi), so the phone can
 * reach it without a shared network. [direct]: the phone is on that link right now.
 */
data class DeviceUi(
    val id: String, val name: String, val platform: Platform, val online: Boolean, val lastSeen: Long,
    val pairedAt: Long, val trusted: Boolean, val address: String?, val outgoing: LiveUi?,
    val waiting: Int = 0, val hasLink: Boolean = false, val direct: Boolean = false,
)

data class NearbyUi(val id: String, val name: String, val platform: Platform, val address: String, val port: Int)

data class AddressUi(val kind: String, val ip: String)

data class PhoneUi(
    val name: String = "", val receiving: Boolean = false, val addresses: List<AddressUi> = emptyList(),
    val deviceId: String = "", val version: String = "",
)

data class HistoryFile(val name: String, val uri: Uri?)

/** A transfer in Activity, or a text message ([text] not null, no files). */
data class HistoryUi(
    val at: Long, val incoming: Boolean, val peer: String, val files: List<HistoryFile>, val count: Int,
    val bytes: Long, val millis: Long, val folder: String?, val problem: String?, val cancelled: Boolean, val offered: Int,
    val text: String? = null,
) {
    val ok: Boolean get() = problem == null
    val key: String get() = "$at|$incoming|$peer"
}

data class PromptUi(val name: String, val code: String)

data class OfferUi(val id: String, val peer: String, val files: List<String>, val total: Long)

/** Pairing after a scanned code or an address: in progress, or what went wrong. */
data class PairingUi(val name: String, val error: String? = null, val direct: Boolean = false)

data class QrUi(val image: ImageBitmap, val addresses: List<AddressUi>)

data class SettingsUi(
    val theme: ThemeMode = ThemeMode.System,
    val palette: Palette = Palette.Ember,
    val background: Boolean = true,
    val ask: Boolean = false,
    val pairedOnly: Boolean = false,
    val folderName: String = "Download/HopDrop",
    val customFolder: Boolean = false,
    val batteryRestricted: Boolean = false,
    val autoStartHint: Boolean = false,
    val updateChecks: Boolean = true,
    val updatesSupported: Boolean = true,
    /** Text a paired device copied goes straight onto this phone's clipboard. */
    val clipAuto: Boolean = true,
    val backup: BackupUi = BackupUi(),
    /** Android 13+ can add the Quick Settings tile with one tap. */
    val canAddTile: Boolean = false,
)

/** Settings → Auto-backup. */
data class BackupUi(
    val on: Boolean = false, val folder: String? = null, val device: String? = null, val charging: Boolean = false,
    val status: String? = null, val lastRun: Long = 0,
)

/** Turning auto-backup on: the folder is picked; [existing] files are already in it (ask whether to send them too). */
data class BackupSetupUi(val folderName: String, val existing: Int, val step: Int)

/** A newer HopDrop on GitHub: offered, downloading ([fraction] null while the size is unknown), or failed. */
data class UpdateUi(val version: String, val size: Long, val downloading: Boolean = false, val fraction: Float? = null, val error: String? = null)

data class UiMessage(val text: String, val id: Long = System.nanoTime())

/** Everything the screens draw. Written on the main thread by MainActivity. */
class HopUiState {
    var tab by mutableStateOf(Tab.Send)
    var settingsOpen by mutableStateOf(false)
    val files = mutableStateListOf<PickedFile>()
    val folders = mutableStateListOf<PickedFolder>()
    var live by mutableStateOf<List<LiveUi>>(emptyList())
    var devices by mutableStateOf<List<DeviceUi>>(emptyList())
    var nearby by mutableStateOf<List<NearbyUi>>(emptyList())
    var phone by mutableStateOf(PhoneUi())
    var history by mutableStateOf<List<HistoryUi>>(emptyList())
    var settings by mutableStateOf(SettingsUi())
    var prompt by mutableStateOf<PromptUi?>(null)
    val offers = mutableStateListOf<OfferUi>()
    var pairing by mutableStateOf<PairingUi?>(null)
    var qr by mutableStateOf<QrUi?>(null)
    var pairSheet by mutableStateOf(false)
    var askBackground by mutableStateOf(false)
    var message by mutableStateOf<UiMessage?>(null)
    var update by mutableStateOf<UpdateUi?>(null)
    var checkingUpdates by mutableStateOf(false)
    /** First start (and Settings → Show the tour): the tutorial covers the app until it's finished or skipped. */
    var onboarding by mutableStateOf(false)
    /** Send to several devices: ticked devices while choosing. Null when not choosing. */
    var picking by mutableStateOf<Set<String>?>(null)
    /** The device the phone is joined to through its direct link, or null. */
    var directTo by mutableStateOf<String?>(null)
    var backupSetup by mutableStateOf<BackupSetupUi?>(null)

    val nothingSelected: Boolean get() = files.isEmpty() && folders.isEmpty()

    fun say(text: String) {
        message = UiMessage(text)
    }
}

interface HopActions {
    fun pickFiles()
    fun pickPhotos()
    fun pickFolder()
    /** Typed or pasted text, sent as a .txt file. */
    fun addText(text: String)
    fun removeFile(file: PickedFile)
    fun removeFolder(folder: PickedFolder)
    fun clearSelection()
    fun send(device: DeviceUi)
    /** One transfer per device, side by side. */
    fun sendToMany(devices: List<DeviceUi>)
    fun cancel(incoming: Boolean)
    /** Stops the send to one device, and forgets sends waiting for it. */
    fun cancelSend(device: DeviceUi)
    /** Joins the device's direct link (so it can reach this phone too). */
    fun connectDirectly(device: DeviceUi)
    fun disconnectDirect()
    /** Text on the clipboard right now (to prefill the Text dialog), or null. */
    fun clipboardText(): String?
    fun bluetooth()

    fun scanQr()
    fun showMyQr()
    fun closeMyQr()
    /** Null when it started, or what's wrong with the address. */
    fun pairByAddress(text: String): String?
    fun pairNearby(device: NearbyUi)
    fun refreshNearby()
    fun rename(device: DeviceUi, name: String): String?
    fun remove(device: DeviceUi)
    fun setTrusted(device: DeviceUi, trusted: Boolean)
    fun answerPrompt(match: Boolean)
    fun answerOffer(offer: OfferUi, accept: Boolean)
    fun dismissPairing()

    fun open(file: HistoryFile)
    fun shareText(text: String)
    fun share(files: List<HistoryFile>)
    fun removeFromHistory(entry: HistoryUi)
    fun clearHistory()

    fun setTheme(mode: ThemeMode)
    fun setPalette(palette: Palette)
    fun setBackground(on: Boolean)
    fun setAsk(on: Boolean)
    fun setPairedOnly(on: Boolean)
    fun chooseReceiveFolder()
    fun resetReceiveFolder()
    fun setPhoneName(name: String): String?
    fun allowBackground()
    fun openAppSettings()
    fun copy(label: String, text: String)
    fun openLink(url: String)

    fun setClipAuto(on: Boolean)
    fun setBackup(on: Boolean)
    fun chooseBackupFolder()
    fun setBackupDevice(id: String)
    fun setBackupCharging(on: Boolean)
    fun backupNow()
    /** Finishes turning auto-backup on: [existing] = also send the files already in the folder. */
    fun startBackup(deviceId: String, existing: Boolean)
    fun addTile()
    /** The tutorial ended; [pair] = go straight to scanning a pairing code. */
    fun finishOnboarding(pair: Boolean)
    fun showTour()

    fun setUpdateChecks(on: Boolean)
    fun checkForUpdates()
    fun startUpdate()
    fun dismissUpdate()
}
