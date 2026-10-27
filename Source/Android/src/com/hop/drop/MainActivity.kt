package com.hop.drop

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.asImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.hop.drop.core.Format
import com.hop.drop.core.Json
import com.hop.drop.core.PairUri
import com.hop.drop.core.Peer
import com.hop.drop.core.Transport
import com.hop.drop.net.LocalSockets
import com.hop.drop.ui.AddressUi
import com.hop.drop.ui.BackupSetupUi
import com.hop.drop.ui.BackupUi
import com.hop.drop.ui.ReceivedBackupUi
import com.hop.drop.store.ReceiveStorage
import com.hop.drop.ui.DeviceUi
import com.hop.drop.ui.HistoryFile
import com.hop.drop.ui.HistoryUi
import com.hop.drop.ui.HopActions
import com.hop.drop.ui.HopDropApp
import com.hop.drop.ui.HopDropTheme
import com.hop.drop.ui.HopUiState
import com.hop.drop.ui.LiveState
import com.hop.drop.ui.LiveUi
import com.hop.drop.ui.NearbyUi
import com.hop.drop.ui.OfferUi
import com.hop.drop.ui.PairingUi
import com.hop.drop.ui.Palette
import com.hop.drop.ui.PhoneUi
import com.hop.drop.ui.PickedFile
import com.hop.drop.ui.PickedFolder
import com.hop.drop.ui.Platform
import com.hop.drop.ui.PromptUi
import com.hop.drop.ui.QrUi
import com.hop.drop.ui.SettingsUi
import com.hop.drop.ui.Tab
import com.hop.drop.ui.ThemeMode
import com.hop.drop.ui.UpdateUi
import com.hop.drop.ui.isDark
import com.hop.drop.ui.hasWhatsNew
import java.io.EOFException
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one screen of the app. It hosts the Compose UI (com.hop.drop.ui) and connects it to the transfer engine:
 * HopApp calls the public methods below (showPrompt, showOffer, onLiveChanged, …) and the screens call [HopActions].
 */
class MainActivity : ComponentActivity(), HopActions {
    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(Language.wrap(newBase))
    private lateinit var app: HopApp
    private val state = HopUiState()
    private val work: ExecutorService = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val offers = HashMap<String, Peer.Offer>()
    private var shownPrompt: HopApp.Prompt? = null
    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)
    private var updateTask: java.util.concurrent.Future<*>? = null
    /** Waiting for the user to allow "Install unknown apps" in Android's settings; the update continues when they return. */
    private var installAllowedPending = false

    private val openFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addFiles(it, persist = true) }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { addFiles(it, persist = false) }
    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(::addFolder) }
    private val chooseReceive = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(::setReceiveFolder) }
    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onScanned)
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { offerBackgroundActivity() }
    private val storage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { offerBackgroundActivity() }
    private val chooseBackup = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(::backupFolderChosen) }
    private val chooseBackupPlacePicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(::setBackupPlace) }

    private val qrRefresh = object : Runnable {
        override fun run() {
            if (state.qr == null) return
            makeQr()
            main.postDelayed(this, 290_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as HopApp
        enableEdgeToEdge()
        if (savedInstanceState != null) restore(savedInstanceState) else handle(intent)
        state.settings = loadSettings()
        refreshAll()
        if (savedInstanceState != null) {
            state.onboarding = savedInstanceState.getBoolean("onboarding")
            state.whatsNewVersion = savedInstanceState.getInt("whats_new_version", 0).takeIf { it > 0 }
        } else {
            val info = packageManager.getPackageInfo(packageName, 0)
            val version = appVersionCode()
            val seen = prefs.getInt("seen_version", 0)
            val usedBefore = prefs.getBoolean("onboarded", false) || state.devices.isNotEmpty() || state.history.isNotEmpty() ||
                info.firstInstallTime != info.lastUpdateTime
            when {
                !usedBefore -> state.onboarding = true
                seen < version && hasWhatsNew(version) -> state.whatsNewVersion = version
                seen < version -> prefs.edit().putInt("seen_version", version).putBoolean("onboarded", true).apply()
            }
        }
        if (!state.onboarding && state.whatsNewVersion == null) askPermissions(savedInstanceState == null)
        setContent {
            val settings = state.settings
            val dark = isDark(settings.theme)
            LaunchedEffect(dark) {
                val bars = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            LaunchedEffect(state.tab) {
                // A phone hidden from unpaired devices shows its name while the Devices tab is open, so it can be paired.
                app.setPairingVisible(state.tab == Tab.Devices)
                if (state.tab == Tab.Devices) ReceiveService.activeDiscovery?.query()
            }
            HopDropTheme(settings.theme, settings.palette) { HopDropApp(state, this@MainActivity) }
        }
    }

    /** Notifications (Android 13+), then the one-time offer to receive with the screen off. */
    private fun askPermissions(fresh: Boolean) {
        if (state.onboarding || state.whatsNewVersion != null) return
        val askNotifications = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (askNotifications) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else if (fresh) main.post(::offerBackgroundActivity)
    }

    override fun onStart() {
        super.onStart()
        app.visible(this)
        try {
            startForegroundService(Intent(this, ReceiveService::class.java))
        } catch (e: RuntimeException) {
            state.say(getString(R.string.main_receiving_could_not_start_open_hopdrop_again_to_retry))
        }
        state.settings = loadSettings()
        refreshAll()
        offerKnownUpdate()
        if (Updater.due(this)) checkUpdates(quiet = true)
        // On screen: sends waiting for a device are tried again (joining a PC's direct link is fine now), and so is the backup.
        work.execute {
            Sends.retry(app, null, true)
            Backup.check(app, false)
            Shortcuts.update(this, app.book().all())
        }
        if (installAllowedPending && packageManager.canRequestPackageInstalls()) { installAllowedPending = false; startUpdate() }
    }

    override fun onStop() {
        app.visible(null)
        if (!ReceiveService.background(this)) {
            startService(Intent(this, ReceiveService::class.java).setAction(ReceiveService.STOP_WHEN_IDLE))
        }
        super.onStop()
    }

    override fun onDestroy() {
        main.removeCallbacks(qrRefresh)
        work.shutdownNow()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt("tab", state.tab.ordinal)
        out.putBoolean("onboarding", state.onboarding)
        out.putInt("whats_new_version", state.whatsNewVersion ?: 0)
        out.putParcelableArrayList("files", ArrayList(state.files.map { it.uri }))
        out.putStringArrayList("file_names", ArrayList(state.files.map { it.name }))
        out.putLongArray("file_sizes", state.files.map { it.size }.toLongArray())
        out.putStringArrayList("file_mimes", ArrayList(state.files.map { it.mime ?: "" }))
        out.putParcelableArrayList("folders", ArrayList(state.folders.map { it.uri }))
        out.putStringArrayList("folder_names", ArrayList(state.folders.map { it.name }))
        out.putIntArray("folder_files", state.folders.map { it.files }.toIntArray())
        out.putLongArray("folder_bytes", state.folders.map { if (it.sizeUnknown) -1 else it.bytes }.toLongArray())
    }

    @Suppress("DEPRECATION")
    private fun restore(saved: Bundle) {
        state.tab = Tab.entries.getOrElse(saved.getInt("tab", 0)) { Tab.Send }
        val uris = saved.getParcelableArrayList<Uri>("files") ?: arrayListOf()
        val names = saved.getStringArrayList("file_names") ?: arrayListOf()
        val sizes = saved.getLongArray("file_sizes") ?: LongArray(0)
        val mimes = saved.getStringArrayList("file_mimes") ?: arrayListOf()
        if (names.size == uris.size && sizes.size == uris.size && mimes.size == uris.size) {
            uris.forEachIndexed { i, uri -> state.files.add(PickedFile(uri, names[i], sizes[i], mimes[i].ifEmpty { null })) }
        }
        val trees = saved.getParcelableArrayList<Uri>("folders") ?: arrayListOf()
        val folderNames = saved.getStringArrayList("folder_names") ?: arrayListOf()
        val counts = saved.getIntArray("folder_files") ?: IntArray(0)
        val bytes = saved.getLongArray("folder_bytes") ?: LongArray(0)
        if (folderNames.size == trees.size && counts.size == trees.size && bytes.size == trees.size) {
            trees.forEachIndexed { i, uri -> state.folders.add(PickedFolder(uri, folderNames[i], counts[i], maxOf(0, bytes[i]), bytes[i] < 0)) }
        }
    }

    // ---- Called by HopApp ----

    fun showPrompt(prompt: HopApp.Prompt) {
        if (shownPrompt === prompt) return
        shownPrompt = prompt
        state.prompt = PromptUi(prompt.name, prompt.code)
    }

    fun closePrompt(prompt: HopApp.Prompt, reason: String) {
        if (shownPrompt !== prompt) return
        shownPrompt = null
        state.prompt = null
        state.say(reason)
    }

    fun showOffer(offer: Peer.Offer) {
        if (offers.containsKey(offer.transferId)) return
        offers[offer.transferId] = offer
        state.offers.add(OfferUi(offer.transferId, offer.peer, ArrayList(offer.files), offer.total))
    }

    fun closeOffer(offer: Peer.Offer) {
        offers.remove(offer.transferId)
        state.offers.removeAll { it.id == offer.transferId }
    }

    fun refresh() = refreshAll()

    fun onPaired() {
        closeMyQr()
        refreshAll()
        work.execute { Shortcuts.update(this, app.book().all()) }
    }

    /** Throttled by HopApp to about four times a second while something moves. */
    fun onLiveChanged() {
        state.live = app.live().items().map(::toLive)
        refreshPhone()
        refreshDevices()
        if (state.settingsOpen || state.backupsOpen || state.receivedBackupsOpen) state.settings = loadSettings()
    }

    // ---- Turning engine objects into what the screens show ----

    private fun refreshAll() {
        state.live = app.live().items().map(::toLive)
        refreshPhone()
        refreshDevices()
        refreshHistory()
    }

    private fun refreshPhone() {
        val peer = app.peer()
        state.phone = PhoneUi(
            name = peer?.name() ?: getString(R.string.unavailable),
            receiving = ReceiveService.running,
            addresses = LocalSockets.describe(this).map { AddressUi(networkName(it.kind), it.ip) },
            deviceId = peer?.id() ?: "",
            version = versionName(),
        )
    }

    private fun networkName(kind: String): String = getString(when (kind) {
        "Wi-Fi" -> R.string.network_wifi
        "Hotspot" -> R.string.network_hotspot
        "USB tethering" -> R.string.network_usb
        "Ethernet" -> R.string.network_ethernet
        else -> R.string.network_local
    })

    private fun refreshDevices() {
        val nearby = ReceiveService.activeDiscovery?.list() ?: emptyList()
        val online = nearby.map { it.id.lowercase(Locale.ROOT) }.toSet()
        val live = state.live
        val book = app.book()
        val joined = app.directLink()?.connectedTo()
        val waiting = Sends.queued(this)
        val all = book.all()
        state.devices = all.map { d ->
            val direct = joined != null && joined == d.linkSsid
            DeviceUi(
                id = d.id, name = d.label(), platform = Platform.from(d.platform), online = d.id.lowercase(Locale.ROOT) in online || direct,
                lastSeen = d.lastSeen, pairedAt = d.pairedAt, trusted = d.trusted, address = d.addresses.firstOrNull(),
                outgoing = live.lastOrNull { !it.incoming && it.peer == d.label() },
                waiting = waiting.count { it.deviceId.equals(d.id, ignoreCase = true) }, hasLink = d.hasLink(), direct = direct,
            )
        }
        state.directTo = all.firstOrNull { joined != null && joined == it.linkSsid }?.id
        // Devices that hide their name only show once paired; paired ones are already in the list above.
        state.nearby = nearby.filter { it.name.isNotEmpty() && book.get(it.id) == null }
            .map { NearbyUi(it.id, it.name, Platform.from(it.platform), it.address, it.port) }
    }

    private fun toLive(item: Live.Item): LiveUi {
        val p = item.progress
        val r = item.result
        val s: LiveState = when {
            item.note == "direct" -> LiveState.ConnectingDirectly
            item.note == "queued" -> LiveState.Queued
            item.note == "text" -> LiveState.TextSent
            item.connecting() -> LiveState.Connecting
            item.failure != null -> LiveState.Stopped(item.failure, false, 0, 0)
            r != null -> if (r.error == null) LiveState.Done(ArrayList(r.files), r.files.size, r.bytes, r.millis, r.folder) else {
                val cancelled = r.error.startsWith("cancelled")
                LiveState.Stopped(if (cancelled) Notices.cancelReason(app, r) else ErrorText.forDevice(app, r.error, r.peer), cancelled, r.files.size, r.offered)
            }
            p.reconnecting -> LiveState.Reconnecting
            p.waiting -> LiveState.Waiting(p.count)
            else -> LiveState.Moving(p.file, p.index, p.count, p.done, p.total, p.speed, p.secondsLeft)
        }
        return LiveUi(item.key, item.incoming, item.peer, s)
    }

    private fun refreshHistory() {
        state.history = try {
            val saved = Json.parseObject(getSharedPreferences("history_v2", MODE_PRIVATE).getString("data", "{\"items\":[]}"))
            (saved["items"] as? List<*>).orEmpty().mapNotNull { (it as? Map<*, *>)?.let(::toHistory) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun toHistory(e: Map<*, *>): HistoryUi? {
        val at = (e["at"] as? Number)?.toLong() ?: return null
        val incoming = e["incoming"] == true
        val peer = e["peer"]?.toString() ?: getString(R.string.main_unknown_device)
        val names = (e["files"] as? List<*>).orEmpty().map { it.toString() }
        val count = (e["count"] as? Number)?.toInt() ?: names.size
        val error = e["error"]?.toString()
        val cancelled = error?.startsWith("cancelled") == true
        val files = if (incoming) {
            val uris = e["uris"] as? List<*>
            names.mapIndexed { i, n -> HistoryFile(n, (uris?.getOrNull(i) as? String)?.let(Uri::parse)) }
        } else {
            val sources = (e["sources"] as? List<*>).orEmpty().mapNotNull { s ->
                (s as? List<*>)?.takeIf { it.isNotEmpty() }?.let { HistoryFile(it[0].toString(), (it.getOrNull(1) as? String)?.let(Uri::parse)) }
            }
            sources.ifEmpty { names.map { HistoryFile(it, null) } }
        }
        val problem = when {
            error == null -> null
            cancelled -> if (error.contains("Peer cancelled") || error.contains("Sender cancelled")) getString(R.string.peer_cancelled, Language.ltr(this, peer)) else getString(R.string.main_you_cancelled_it)
            else -> ErrorText.forDevice(app, error, peer)
        }
        return HistoryUi(
            at = at, incoming = incoming, peer = peer, files = files, count = count,
            bytes = (e["bytes"] as? Number)?.toLong() ?: -1, millis = (e["millis"] as? Number)?.toLong() ?: 0,
            folder = e["folder"]?.toString(), problem = problem, cancelled = cancelled,
            offered = (e["offered"] as? Number)?.toInt() ?: -1,
            text = e["text"] as? String,
            transport = Transport.stored(e["transport"] as? String),
        )
    }

    private fun loadSettings(): SettingsUi {
        val p = prefs
        return SettingsUi(
            language = Language.selected(this),
            theme = ThemeMode.entries.getOrElse(p.getInt("theme", 0)) { ThemeMode.System },
            palette = Palette.of(p.getString("palette", null)),
            background = ReceiveService.background(this),
            ask = p.getBoolean("ask", false),
            pairedOnly = p.getString("visibility", "everyone") == "paired",
            folderName = folderName(),
            customFolder = p.getString("folder", null) != null,
            batteryRestricted = batteryRestricted(),
            autoStartHint = Build.MANUFACTURER.lowercase(Locale.ROOT) in AUTO_START_BRANDS,
            updateChecks = Updater.enabled(this),
            updatesSupported = Updater.supported(this),
            clipAuto = p.getBoolean("clip_auto", true),
            backups = Backup.entries(this).map { b -> BackupUi(
                id = b.id, on = b.on, folder = b.folder, device = b.device?.let { app.book().get(it)?.label() },
                deviceId = b.device, kinds = b.kinds, charging = b.charging, timed = b.timed,
                from = b.from, to = b.to, status = Backup.displayStatus(this, b.status), lastRun = b.lastRun,
            ) },
            backupPlace = folderName("backup_folder", "Download/HopDrop/Backups"),
            receivedBackups = ReceiveStorage.backups(this).map { ReceivedBackupUi(it.id, it.device, it.folder, it.count, it.lastRun, it.location, it.place) },
            canAddTile = Build.VERSION.SDK_INT >= 33,
        )
    }

    private fun folderName(): String = folderName("folder", "Download/HopDrop")

    private fun folderName(key: String, default: String): String {
        val saved = prefs.getString(key, null) ?: return default
        try {
            val tree = Uri.parse(saved)
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) return c.getString(0)
            }
            val id = DocumentsContract.getTreeDocumentId(tree)
            val path = id.substringAfterLast(':')
            if (path.isNotEmpty()) return path.substringAfterLast('/')
        } catch (ignored: Exception) {
        }
        return getString(R.string.main_folder_you_chose)
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    // ---- Picking what to send ----

    override fun pickFiles() = openFiles.launch(arrayOf("*/*"))

    override fun pickPhotos() = pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))

    override fun pickFolder() = openFolder.launch(null)

    private fun addFiles(uris: List<Uri>, persist: Boolean) {
        val fresh = uris.distinct().filter { u -> state.files.none { it.uri == u } }
        if (fresh.isEmpty()) return
        state.tab = Tab.Send
        work.execute {
            val picked = fresh.map { uri ->
                if (persist) try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (ignored: Exception) {
                }
                describe(uri)
            }
            runOnUiThread { for (f in picked) if (state.files.none { it.uri == f.uri }) state.files.add(f) }
        }
    }

    private fun describe(uri: Uri): PickedFile {
        var name = uri.lastPathSegment ?: getString(R.string.file_fallback)
        var size = -1L
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        } catch (ignored: Exception) {
        }
        val mime = try { contentResolver.getType(uri) } catch (e: Exception) { null }
        return PickedFile(uri, name, size, mime)
    }

    private fun addFolder(tree: Uri) {
        try {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (ignored: Exception) {
        }
        if (state.folders.any { it.uri == tree }) {
            state.say(getString(R.string.main_that_folder_is_already_in_the_list))
            return
        }
        state.tab = Tab.Send
        state.folders.add(PickedFolder(tree, getString(R.string.ui_folder), -1, 0, false))
        work.execute {
            try {
                val name = FolderScan.name(this, tree)
                val files = FolderScan.files(this, tree)
                val unknown = files.any { it.size < 0 }
                val bytes = files.filter { it.size >= 0 }.sumOf { it.size }
                runOnUiThread {
                    val i = state.folders.indexOfFirst { it.uri == tree }
                    if (i < 0) return@runOnUiThread
                    if (files.isEmpty()) {
                        state.folders.removeAt(i)
                        state.say(getString(R.string.main_that_folder_has_no_files_to_send))
                    } else state.folders[i] = PickedFolder(tree, name, files.size, bytes, unknown)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    state.folders.removeAll { it.uri == tree }
                    state.say(getString(R.string.main_hopdrop_couldn_t_read_that_folder))
                }
            }
        }
    }

    override fun removeFile(file: PickedFile) {
        state.files.remove(file)
    }

    override fun removeFolder(folder: PickedFolder) {
        state.folders.remove(folder)
    }

    override fun clearSelection() {
        state.files.clear()
        state.folders.clear()
    }

    /** Files shared to HopDrop from another app, and taps on getString(R.string.main_show_files) in a notification. */
    @Suppress("DEPRECATION")
    private fun handle(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("show_files", false)) state.tab = Tab.Activity
        // A device from the launcher icon's menu: straight to Send.
        if (intent.action == Intent.ACTION_VIEW && intent.hasExtra("device")) state.tab = Tab.Send
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = ArrayList<Uri>()
        intent.clipData?.let { clip -> for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add) }
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::addAll)
        if (action != Intent.ACTION_SEND_MULTIPLE) intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let(uris::add)
        intent.data?.let(uris::add)
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (text != null && uris.isEmpty()) {
            try {
                uris.add(SharedTextProvider.create(this, text))
            } catch (e: Exception) {
                state.say(getString(R.string.main_couldn_t_prepare_the_shared_text))
            }
        }
        addFiles(uris, persist = false)
        state.tab = Tab.Send
    }

    override fun addText(text: String) {
        try {
            addFiles(listOf(SharedTextProvider.create(this, text)), persist = false)
        } catch (e: Exception) {
            state.say(getString(R.string.main_couldn_t_prepare_the_text))
        }
    }

    // ---- Sending ----

    private fun selectionSummary(): String = when {
        state.folders.isEmpty() -> Language.files(this, state.files.size)
        state.files.isEmpty() -> Language.folders(this, state.folders.size)
        else -> getString(R.string.selection_and, Language.folders(this, state.folders.size), Language.files(this, state.files.size))
    }

    override fun send(device: DeviceUi) = sendToMany(listOf(device))

    /**
     * One job per device, sent side by side. Typed or shared text in the list travels as text (the other side shows it
     * with Copy / Open); everything else as files and folders.
     */
    override fun sendToMany(devices: List<DeviceUi>) {
        if (state.nothingSelected) {
            state.say(getString(R.string.main_choose_files_to_send_first))
            return
        }
        state.picking = null
        for (ui in devices) {
            val d = app.book().get(ui.id) ?: continue
            val current = app.live().outgoing(d.label())
            if (current != null && !current.finished()) {
                state.say(getString(R.string.already_sending, Language.ltr(this, d.label())))
                continue
            }
            val job = Sends.Job(d.id, selectionSummary())
            for (f in state.files) {
                val text = Sends.textOf(this, f.uri)
                if (text != null) job.texts.add(text) else job.files.add(f.uri)
            }
            job.trees.addAll(state.folders.map { it.uri })
            if (job.textOnly()) job.summary = getString(R.string.main_your_text)
            app.live().connecting(d.label())
            prefs.edit().putString("last_device", d.id).apply()
            try {
                startForegroundService(job.intent(this))
                Shortcuts.used(this, d.id)
            } catch (e: RuntimeException) {
                app.live().failedToStart(d.label(), ErrorText.forDevice(app, e, d.label()))
            }
        }
    }

    override fun cancel(incoming: Boolean) {
        startService(Intent(this, if (incoming) ReceiveService::class.java else TransferService::class.java).setAction("cancel"))
    }

    override fun cancelSend(device: DeviceUi) {
        Sends.cancelQueued(app, device.id)
        startService(Intent(this, TransferService::class.java).setAction("cancel").putExtra("device", device.id))
        refreshDevices()
    }

    override fun connectDirectly(device: DeviceUi) {
        val d = app.book().get(device.id) ?: return
        if (!d.hasLink() || state.connectingTo != null) return
        state.connectingTo = d.id
        work.execute {
            try {
                app.directLink().use(d.linkSsid, d.linkKey, d.linkIp, 60000)
                try {
                    d.addresses.remove(d.linkIp)
                    d.addresses.add(0, d.linkIp)
                    app.peer().ping(d)
                    runOnUiThread { state.connectingTo = null; refreshDevices() }
                    Sends.retry(app, d.id, true)
                } finally {
                    app.directLink().done()
                }
            } catch (e: Exception) {
                runOnUiThread { state.connectingTo = null; state.say(ErrorText.forDevice(app, e, d.label())) }
            }
        }
    }

    override fun disconnectDirect() {
        work.execute { app.directLink().leave(); runOnUiThread { refreshDevices() } }
    }

    override fun clipboardText(): String? = try {
        getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
            ?.let { it.text?.toString() ?: if (it.uri == null) it.coerceToText(this)?.toString() else null }?.takeIf { it.isNotBlank() }
    } catch (e: RuntimeException) {
        null
    }

    override fun bluetooth() {
        if (state.nothingSelected) {
            state.say(getString(R.string.main_choose_files_to_send_first))
            return
        }
        val loose = ArrayList(state.files.map { it.uri })
        val trees = state.folders.map { it.uri }
        if (trees.isEmpty()) {
            launchBluetooth(loose)
            return
        }
        state.say(getString(R.string.main_bluetooth_sends_the_files_inside_folders_without_their_))
        work.execute {
            try {
                for (tree in trees) for (item in FolderScan.files(this, tree)) loose.add(item.uri)
                runOnUiThread { launchBluetooth(loose) }
            } catch (e: Exception) {
                runOnUiThread { state.say(getString(R.string.main_hopdrop_couldn_t_read_that_folder)) }
            }
        }
    }

    private fun launchBluetooth(files: ArrayList<Uri>) {
        if (files.isEmpty()) {
            state.say(getString(R.string.main_there_are_no_files_to_send))
            return
        }
        val share = Intent(if (files.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val clip = ClipData.newRawUri("HopDrop files", files[0])
        for (i in 1 until files.size) clip.addItem(ClipData.Item(files[i]))
        share.clipData = clip
        if (files.size == 1) share.putExtra(Intent.EXTRA_STREAM, files[0]) else share.putParcelableArrayListExtra(Intent.EXTRA_STREAM, files)
        val target: ResolveInfo? = packageManager.queryIntentActivities(share, 0)
            .firstOrNull { it.activityInfo.packageName.lowercase(Locale.ROOT).contains("bluetooth") }
        try {
            if (target != null) {
                share.setClassName(target.activityInfo.packageName, target.activityInfo.name)
                startActivity(share)
            } else startActivity(Intent.createChooser(share, getString(R.string.main_send_with_bluetooth)))
        } catch (e: Exception) {
            state.say(getString(R.string.main_bluetooth_sharing_isn_t_available_on_this_phone))
        }
    }

    // ---- Pairing ----

    override fun scanQr() = scan.launch(Intent(this, QrScanActivity::class.java))

    private fun onScanned(result: ActivityResult) {
        val data = result.data ?: return
        if (result.resultCode != RESULT_OK) return
        if (data.getBooleanExtra("show_qr", false)) { showMyQr(); return }
        if (data.getBooleanExtra("address", false)) { state.askAddress = true; return }
        if (data.getBooleanExtra("fallback", false)) {
            state.tab = Tab.Devices
            state.say(getString(R.string.main_tap_the_device_under_nearby_then_check_both_screens_sho))
            return
        }
        val uri = data.getStringExtra("uri") ?: return
        val code = try {
            PairUri.parse(uri)
        } catch (e: IllegalArgumentException) {
            state.say(getString(R.string.main_that_qr_code_can_t_be_used_show_a_new_one_and_scan_it_a))
            return
        }
        val name = code.name
        state.pairing = PairingUi(name)
        work.execute {
            try {
                val device = pairSmart(code)
                runOnUiThread {
                    state.pairing = null
                    state.say(getString(R.string.paired_with, Language.ltr(this, device.name)))
                    refreshAll()
                }
                Shortcuts.update(this, app.book().all())
            } catch (e: Exception) {
                runOnUiThread { if (state.pairing != null) state.pairing = PairingUi(name,
                    if (e is PairNetworkException) e.message else ErrorText.forDevice(app, e, name)) }
            }
        }
    }

    /**
     * Pairs with a scanned code: over the network both devices are on, else (or when that fails) through the PC's direct
     * link from the code — so the phone doesn't need to be on the same Wi-Fi. The link is kept briefly for a first send.
     */
    private class PairNetworkException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private fun pairingNoAnswer(error: Exception) = Sends.unreachable(error) || error is EOFException

    private fun pairSmart(code: PairUri): Peer.Device {
        val lan = LocalSockets.sameNetwork(this, code.addresses)
        // Without a direct link in the code, the PC's addresses are tried even from another network: a router may connect them.
        if (lan || code.linkSsid == null) {
            try {
                return app.peer().pairQr(code, null)
            } catch (e: Exception) {
                if (!pairingNoAnswer(e)) throw e
                if (code.linkSsid == null) throw PairNetworkException(
                    if (lan) getString(R.string.main_your_pc_didn_t_answer_on_this_network_open_hopdrop_on_i)
                    else getString(R.string.main_this_phone_and_pc_are_on_different_networks_join_the_sa), e)
            }
        }
        runOnUiThread { if (state.pairing != null) state.pairing = PairingUi(code.name, null, direct = true) }
        try {
            app.directLink().use(code.linkSsid, code.linkKey, code.linkIp, 60000)
            try {
                return app.peer().pairQr(code, listOf(code.linkIp))
            } finally {
                app.directLink().done()
            }
        } catch (e: Exception) {
            if (!pairingNoAnswer(e)) throw e
            val fix = when {
                e.message?.startsWith("wifi_off") == true -> getString(R.string.main_turn_on_wi_fi_on_this_phone)
                e.message?.contains("Android didn't allow") == true -> getString(R.string.main_android_didn_t_allow_it_on_this_phone)
                e.message?.contains("another PC") == true -> getString(R.string.main_finish_the_other_direct_connection_first)
                else -> getString(R.string.main_check_that_hopdrop_is_open_on_the_pc_and_you_re_near_it)
            }
            throw PairNetworkException(getString(if (lan) R.string.pair_direct_failed_lan else R.string.pair_direct_failed_remote, fix), e)
        }
    }

    override fun showMyQr() {
        if (LocalSockets.addresses(this).isEmpty()) {
            state.say(getString(R.string.main_join_a_wi_fi_network_or_turn_on_your_hotspot_first))
            return
        }
        makeQr()
        main.removeCallbacks(qrRefresh)
        main.postDelayed(qrRefresh, 290_000)
    }

    private fun makeQr() {
        try {
            val uri = app.peer().issueQr(LocalSockets.addresses(this), 7410)
            val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            val matrix = MultiFormatWriter().encode(uri, BarcodeFormat.QR_CODE, 0, 0, hints)
            val side = matrix.width
            val pixels = IntArray(side * side) { i -> if (matrix.get(i % side, i / side)) Color.BLACK else Color.WHITE }
            val bitmap = Bitmap.createBitmap(pixels, side, side, Bitmap.Config.ARGB_8888)
            state.qr = QrUi(bitmap.asImageBitmap(), LocalSockets.describe(this).map { AddressUi(networkName(it.kind), it.ip) })
        } catch (e: Exception) {
            state.qr = null
            state.say(getString(R.string.main_couldn_t_show_a_qr_code_try_again))
        }
    }

    override fun closeMyQr() {
        main.removeCallbacks(qrRefresh)
        if (state.qr != null) {
            state.qr = null
            app.peer()?.invalidateQr()
        }
    }

    override fun pairByAddress(text: String): String? {
        val parts = text.trim().split(":", limit = 2)
        if (!PairUri.validIpv4(parts[0])) return getString(R.string.main_enter_an_address_like_192_168_1_23)
        val port = if (parts.size == 2) parts[1].toIntOrNull() ?: -1 else 7410
        if (port !in 1..65535) return getString(R.string.main_enter_an_address_like_192_168_1_23)
        pairSas(parts[0], port, parts[0])
        return null
    }

    override fun pairNearby(device: NearbyUi) = pairSas(device.address, device.port, device.name)

    private fun pairSas(address: String, port: Int, label: String) {
        state.pairing = PairingUi(label)
        work.execute {
            try {
                val device = app.peer().pairSas(address, port)
                runOnUiThread {
                    state.pairing = null
                    state.say(getString(R.string.paired_with, Language.ltr(this, device.name)))
                    refreshAll()
                }
            } catch (e: Exception) {
                runOnUiThread { if (state.pairing != null) state.pairing = PairingUi(label, ErrorText.forDevice(app, e, label)) }
            }
        }
    }

    override fun dismissPairing() {
        state.pairing = null
    }

    override fun refreshNearby() {
        ReceiveService.activeDiscovery?.query()
    }

    override fun rename(device: DeviceUi, name: String): String? {
        if (name.length > 40) return getString(R.string.main_use_at_most_40_characters)
        val d = app.book().get(device.id) ?: return null
        d.alias = name.ifEmpty { null }
        app.book().put(d)
        refreshDevices()
        work.execute { Shortcuts.update(this, app.book().all()) }
        return null
    }

    override fun remove(device: DeviceUi) {
        val d = app.book().get(device.id) ?: return
        work.execute {
            app.peer().unpair(d)
            Sends.cancelQueued(app, d.id)
            Shortcuts.update(this, app.book().all())
            runOnUiThread {
                state.say(getString(R.string.removed_device, Language.ltr(this, d.label())))
                refreshAll()
            }
        }
    }

    override fun setTrusted(device: DeviceUi, trusted: Boolean) {
        app.peer().setTrusted(device.id, trusted)
        refreshDevices()
    }

    override fun answerPrompt(match: Boolean) {
        (shownPrompt ?: app.prompt())?.answer(match)
        shownPrompt = null
        state.prompt = null
    }

    override fun answerOffer(offer: OfferUi, accept: Boolean) {
        offers.remove(offer.id)?.answer(accept)
        state.offers.removeAll { it.id == offer.id }
    }

    // ---- Activity ----

    override fun open(file: HistoryFile) {
        work.execute {
            val uri = file.uri ?: app.receivedUri(file.name)
            val mime = uri?.let { try { contentResolver.getType(it) } catch (e: Exception) { null } }
            runOnUiThread {
                if (uri == null) {
                    state.say(getString(R.string.main_this_file_isn_t_available_anymore))
                    return@runOnUiThread
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: Exception) {
                    state.say(getString(R.string.main_no_app_on_this_phone_can_open_this_file))
                }
            }
        }
    }

    override fun share(files: List<HistoryFile>) {
        work.execute {
            val uris = ArrayList(files.mapNotNull { it.uri ?: app.receivedUri(it.name) })
            runOnUiThread {
                if (uris.isEmpty()) {
                    state.say(getString(R.string.main_these_files_aren_t_available_anymore))
                    return@runOnUiThread
                }
                val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                intent.type = if (uris.size == 1) (try { contentResolver.getType(uris[0]) } catch (e: Exception) { null } ?: "*/*") else "*/*"
                val clip = ClipData.newRawUri("HopDrop files", uris[0])
                for (i in 1 until uris.size) clip.addItem(ClipData.Item(uris[i]))
                intent.clipData = clip
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    startActivity(Intent.createChooser(intent, null))
                } catch (e: Exception) {
                    state.say(getString(R.string.main_sharing_isn_t_available_on_this_phone))
                }
            }
        }
    }

    override fun shareText(text: String) {
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        } catch (e: Exception) {
            state.say(getString(R.string.main_sharing_isn_t_available_on_this_phone))
        }
    }

    override fun removeFromHistory(entry: HistoryUi) {
        app.removeHistory(entry.at, entry.incoming, entry.peer)
        refreshHistory()
    }

    override fun clearHistory() {
        app.clearHistory()
        refreshHistory()
    }

    // ---- Settings ----

    override fun setLanguage(language: String) {
        if (language != "en" && language != "ar") return
        if (Language.selected(this) == language) return
        prefs.edit().putString("language", language).apply()
        app.refreshLanguage()
        recreate()
    }

    override fun setTheme(mode: ThemeMode) {
        prefs.edit().putInt("theme", mode.ordinal).apply()
        state.settings = state.settings.copy(theme = mode)
    }

    override fun setPalette(palette: Palette) {
        prefs.edit().putString("palette", palette.key).apply()
        state.settings = state.settings.copy(palette = palette)
    }

    override fun setBackground(on: Boolean) {
        prefs.edit().putBoolean("background", on).apply()
        if (on) {
            try {
                startForegroundService(Intent(this, ReceiveService::class.java))
                if (batteryRestricted()) requestBatteryExemption()
            } catch (e: RuntimeException) {
                state.say(getString(R.string.main_receiving_could_not_start_open_hopdrop_again_to_retry))
            }
        }
        state.settings = loadSettings()
    }

    override fun setAsk(on: Boolean) {
        prefs.edit().putBoolean("ask", on).apply()
        app.peer()?.askBeforeReceiving = on
        state.settings = state.settings.copy(ask = on)
    }

    override fun setPairedOnly(on: Boolean) {
        prefs.edit().putString("visibility", if (on) "paired" else "everyone").apply()
        app.setPairingVisible(state.tab == Tab.Devices)
        state.settings = state.settings.copy(pairedOnly = on)
    }

    override fun chooseReceiveFolder() = chooseReceive.launch(null)

    private fun setReceiveFolder(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            state.say(getString(R.string.main_hopdrop_can_t_save_files_in_that_folder_choose_another_))
            return
        }
        prefs.edit().putString("folder", uri.toString()).apply()
        state.settings = loadSettings()
    }

    override fun resetReceiveFolder() {
        prefs.edit().remove("folder").apply()
        state.settings = loadSettings()
    }

    override fun setPhoneName(name: String): String? {
        if (name.isEmpty() || name.length > 40) return getString(R.string.main_use_1_to_40_characters)
        val peer = app.peer() ?: return getString(R.string.main_this_phone_s_identity_isn_t_available)
        peer.setName(name)
        prefs.edit().putString("name", name).apply()
        app.notices().showReady()
        ReceiveService.activeDiscovery?.announce()
        refreshPhone()
        return null
    }

    override fun allowBackground() = requestBatteryExemption()

    override fun openAppSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            state.say(getString(R.string.main_open_settings_apps_hopdrop_and_allow_background_activit))
        }
    }

    override fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13 and newer confirm copies on their own.
        if (Build.VERSION.SDK_INT < 33) state.say(getString(R.string.copied_text, text))
    }

    override fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            state.say(getString(R.string.main_no_browser_on_this_phone_can_open_the_link))
        }
    }

    override fun setClipAuto(on: Boolean) {
        prefs.edit().putBoolean("clip_auto", on).apply()
        state.settings = loadSettings()
    }

    // ---- Auto-backup (Backup.java) ----

    override fun chooseBackupFolder(id: String?) {
        if (id == null && app.book().all().isEmpty()) { state.say(getString(R.string.main_pair_a_device_first)); return }
        backupEditingId = id
        chooseBackup.launch(null)
    }

    private var backupEditingId: String? = null

    /** Step 2: the folder is picked; count what's in it, then ask for the device and about the files already there. */
    private fun backupFolderChosen(tree: Uri) {
        try {
            contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            state.say(getString(R.string.main_hopdrop_can_t_read_that_folder_choose_another_one))
            return
        }
        val editing = backupEditingId
        work.execute {
            val name = FolderScan.name(this, tree)
            val count = try { FolderScan.files(this, tree).size } catch (e: Exception) { 0 }
            runOnUiThread {
                val id = if (editing == null) Backup.create(app, tree, name, app.book().all().first().id)
                    else editing.also { Backup.setFolder(app, it, tree, name, true) }
                state.settings = loadSettings()
                state.backupsOpen = true
                state.backupOpen = id
                if (editing == null) state.backupSetup = BackupSetupUi(name, count, 0)
            }
        }
    }

    override fun startBackup(deviceId: String, existing: Boolean) {
        val setup = state.backupSetup ?: return
        val id = state.backupOpen ?: return
        state.backupSetup = null
        Backup.setDevice(app, id, deviceId)
        Backup.activate(app, id, existing)
        state.settings = loadSettings()
        state.say(getString(if (existing) R.string.backing_up else R.string.new_files_backup, Language.ltr(this, setup.folderName)))
    }

    override fun setBackupDevice(id: String, deviceId: String) {
        Backup.setDevice(app, id, deviceId)
        state.settings = loadSettings()
    }

    override fun setBackupOn(id: String, on: Boolean) { Backup.setOn(app, id, on); state.settings = loadSettings() }
    override fun setBackupKinds(id: String, kinds: Int) { Backup.setKinds(app, id, kinds); state.settings = loadSettings() }
    override fun setBackupCharging(id: String, on: Boolean) {
        Backup.setCharging(app, id, on)
        state.settings = loadSettings()
    }
    override fun setBackupWindow(id: String, timed: Boolean, from: Int, to: Int) {
        Backup.setWindow(app, id, timed, from, to); state.settings = loadSettings()
    }
    override fun backupNow(id: String) {
        Backup.check(app, id, true)
        state.say(getString(R.string.main_looking_for_new_files))
    }
    override fun removeBackup(id: String) { Backup.remove(app, id); state.backupOpen = null; state.settings = loadSettings() }
    override fun chooseBackupPlace() = chooseBackupPlacePicker.launch(null)
    private fun setBackupPlace(uri: Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        catch (e: Exception) { state.say(getString(R.string.main_hopdrop_can_t_save_backups_there)); return }
        prefs.edit().putString("backup_folder", uri.toString()).apply(); state.settings = loadSettings()
    }
    override fun resetBackupPlace() { prefs.edit().remove("backup_folder").apply(); state.settings = loadSettings() }
    override fun forgetReceivedBackup(id: String, folder: String, location: String) {
        ReceiveStorage.forgetBackup(this, id, folder, location); state.settings = loadSettings()
    }

    override fun addTile() {
        if (Build.VERSION.SDK_INT < 33) {
            state.say(getString(R.string.main_pull_down_the_quick_settings_tap_the_pencil_and_drag_ho))
            return
        }
        try {
            getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                android.content.ComponentName(this, QuickTile::class.java), "HopDrop",
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification), mainExecutor,
            ) { }
        } catch (e: RuntimeException) {
            state.say(getString(R.string.main_pull_down_the_quick_settings_tap_the_pencil_and_drag_ho))
        }
    }

    // ---- Tour ----

    override fun finishOnboarding(pair: Boolean) {
        prefs.edit().putBoolean("onboarded", true).putInt("seen_version", appVersionCode()).apply()
        state.onboarding = false
        main.postDelayed({ askPermissions(true) }, 200)
        if (pair) {
            state.tab = Tab.Devices
            scanQr()
        }
    }

    override fun showTour() {
        state.settingsOpen = false
        state.onboarding = true
    }

    override fun dismissWhatsNew(showTour: Boolean) {
        prefs.edit().putInt("seen_version", appVersionCode()).putBoolean("onboarded", true).apply()
        state.whatsNewVersion = null
        if (showTour) state.onboarding = true else main.postDelayed({ askPermissions(true) }, 200)
    }

    @Suppress("DEPRECATION")
    private fun appVersionCode(): Int {
        val info = packageManager.getPackageInfo(packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else info.versionCode
    }

    // ---- Updates (Updater.kt) ----

    /** Shows the newer version the last check found, unless the user chose "Later" for it in the last three days. */
    private fun offerKnownUpdate() {
        if (!Updater.supported(this) || !Updater.enabled(this) || state.update != null) return
        val release = Updater.known(this) ?: return
        val later = prefs.getString("update_later", null) == release.version &&
            System.currentTimeMillis() - prefs.getLong("update_later_at", 0) < 3L * 24 * 60 * 60 * 1000
        if (!later) state.update = UpdateUi(release.version, release.size)
    }

    private fun checkUpdates(quiet: Boolean) {
        if (state.checkingUpdates) return
        state.checkingUpdates = true
        work.execute {
            val result = runCatching { Updater.check(this) }
            main.post {
                state.checkingUpdates = false
                result.onSuccess { release ->
                    when {
                        release == null -> { if (state.update?.downloading != true) state.update = null; if (!quiet) state.say(getString(R.string.main_hopdrop_is_up_to_date)) }
                        quiet -> offerKnownUpdate()
                        state.update == null -> state.update = UpdateUi(release.version, release.size)
                    }
                }.onFailure { if (!quiet) state.say(getString(R.string.main_couldn_t_reach_github_check_the_internet_connection)) }
            }
        }
    }

    override fun setUpdateChecks(on: Boolean) {
        Updater.setEnabled(this, on)
        state.settings = loadSettings()
        if (!on && state.update?.downloading != true) state.update = null
        if (on) checkUpdates(quiet = true)
    }

    override fun checkForUpdates() {
        if (state.update != null) { state.settingsOpen = false; startUpdate() } else checkUpdates(quiet = false)
    }

    override fun startUpdate() {
        val update = state.update ?: return
        if (update.downloading) return
        val release = Updater.known(this) ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            // Android asks once per app: allow HopDrop to install apps. Coming back here continues the update.
            installAllowedPending = true
            state.say(getString(R.string.main_allow_hopdrop_to_install_updates_then_come_back))
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                openAppSettings()
            }
            return
        }
        state.update = update.copy(downloading = true, fraction = null, error = null)
        updateTask = work.submit {
            try {
                val apk = Updater.download(this, release) { fraction ->
                    main.post { state.update?.takeIf { it.downloading }?.let { state.update = it.copy(fraction = fraction.takeIf { f -> f >= 0 }) } }
                }
                main.post {
                    if (state.update?.downloading != true) return@post
                    state.update = state.update?.copy(downloading = false, fraction = null)
                    if (!Updater.install(this, apk)) state.update = state.update?.copy(error = getString(R.string.main_allow_hopdrop_to_install_updates_first))
                }
            } catch (e: InterruptedException) {
                // Cancelled.
            } catch (e: Exception) {
                main.post {
                    if (state.update?.downloading == true) state.update = state.update?.copy(downloading = false, fraction = null,
                        error = getString((e as? Updater.UpdateError)?.messageId ?: R.string.main_the_download_failed))
                }
            }
        }
    }

    override fun dismissUpdate() {
        val update = state.update ?: return
        if (update.downloading) {
            updateTask?.cancel(true)
            state.update = update.copy(downloading = false, fraction = null)
            return
        }
        prefs.edit().putString("update_later", update.version).putLong("update_later_at", System.currentTimeMillis()).apply()
        state.update = null
    }

    private fun batteryRestricted(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        return power != null && !power.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (e: Exception) {
            openAppSettings()
        }
    }

    /** Once: receiving in the background is on, but Android may still pause HopDrop to save battery. */
    private fun offerBackgroundActivity() {
        if (state.onboarding || state.whatsNewVersion != null || prefs.getBoolean("background_offered", false) ||
            !ReceiveService.background(this) || !batteryRestricted() || isFinishing) return
        prefs.edit().putBoolean("background_offered", true).apply()
        state.askBackground = true
    }

    companion object {
        const val PREFS = "settings_v2"

        /** Phone brands that stop apps from starting after a restart unless "Auto-launch" is allowed. */
        private val AUTO_START_BRANDS = setOf("realme", "oppo", "oneplus", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "meizu")
    }
}
