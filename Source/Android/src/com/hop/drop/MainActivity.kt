package com.hop.drop

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
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
import com.hop.drop.net.LocalSockets
import com.hop.drop.ui.AddressUi
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
import com.hop.drop.ui.isDark
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one screen of the app. It hosts the Compose UI (com.hop.drop.ui) and connects it to the transfer engine:
 * HopApp calls the public methods below (showPrompt, showOffer, onLiveChanged, …) and the screens call [HopActions].
 */
class MainActivity : ComponentActivity(), HopActions {
    private lateinit var app: HopApp
    private val state = HopUiState()
    private val work: ExecutorService = Executors.newCachedThreadPool()
    private val main = Handler(Looper.getMainLooper())
    private val offers = HashMap<String, Peer.Offer>()
    private var shownPrompt: HopApp.Prompt? = null
    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private val openFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { addFiles(it, persist = true) }
    private val pickMedia = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { addFiles(it, persist = false) }
    private val openFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(::addFolder) }
    private val chooseReceive = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(::setReceiveFolder) }
    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult(), ::onScanned)
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { offerBackgroundActivity() }

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
        val askNotifications = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (askNotifications) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        else if (savedInstanceState == null) main.post(::offerBackgroundActivity)
        if (Build.VERSION.SDK_INT <= 28 && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 62)
        }
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

    override fun onStart() {
        super.onStart()
        app.visible(this)
        try {
            startForegroundService(Intent(this, ReceiveService::class.java))
        } catch (e: RuntimeException) {
            state.say("Receiving could not start. Open HopDrop again to retry.")
        }
        state.settings = loadSettings()
        refreshAll()
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
        state.pairSheet = false
        refreshAll()
    }

    /** Throttled by HopApp to about four times a second while something moves. */
    fun onLiveChanged() {
        state.live = app.live().items().map(::toLive)
        refreshPhone()
        refreshDevices()
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
            name = peer?.name() ?: "Unavailable",
            receiving = ReceiveService.running,
            addresses = LocalSockets.describe(this).map { AddressUi(it.kind, it.ip) },
            deviceId = peer?.id() ?: "",
            version = versionName(),
        )
    }

    private fun refreshDevices() {
        val nearby = ReceiveService.activeDiscovery?.list() ?: emptyList()
        val online = nearby.map { it.id.lowercase(Locale.ROOT) }.toSet()
        val live = state.live
        val book = app.book()
        state.devices = book.all().map { d ->
            DeviceUi(
                id = d.id, name = d.label(), platform = platform(d.platform), online = d.id.lowercase(Locale.ROOT) in online,
                lastSeen = d.lastSeen, pairedAt = d.pairedAt, trusted = d.trusted, address = d.addresses.firstOrNull(),
                outgoing = live.lastOrNull { !it.incoming && it.peer == d.label() },
            )
        }
        // Devices that hide their name only show once paired; paired ones are already in the list above.
        state.nearby = nearby.filter { it.name.isNotEmpty() && book.get(it.id) == null }
            .map { NearbyUi(it.id, it.name, platform(it.platform), it.address, it.port) }
    }

    private fun platform(value: String?) = if (value == "windows") Platform.Windows else Platform.Android

    private fun toLive(item: Live.Item): LiveUi {
        val p = item.progress
        val r = item.result
        val s: LiveState = when {
            item.connecting() -> LiveState.Connecting
            item.failure != null -> LiveState.Stopped(item.failure, false, 0, 0)
            r != null -> if (r.error == null) LiveState.Done(ArrayList(r.files), r.files.size, r.bytes, r.millis, r.folder) else {
                val cancelled = r.error.startsWith("cancelled")
                LiveState.Stopped(if (cancelled) Notices.cancelReason(r) else ErrorText.forDevice(r.error, r.peer), cancelled, r.files.size, r.offered)
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
        val peer = e["peer"]?.toString() ?: "Unknown device"
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
            cancelled -> if (error.contains("Peer cancelled") || error.contains("Sender cancelled")) "$peer cancelled it." else "You cancelled it."
            else -> ErrorText.forDevice(error, peer)
        }
        return HistoryUi(
            at = at, incoming = incoming, peer = peer, files = files, count = count,
            bytes = (e["bytes"] as? Number)?.toLong() ?: -1, millis = (e["millis"] as? Number)?.toLong() ?: 0,
            folder = e["folder"]?.toString(), problem = problem, cancelled = cancelled,
            offered = (e["offered"] as? Number)?.toInt() ?: -1,
        )
    }

    private fun loadSettings(): SettingsUi {
        val p = prefs
        return SettingsUi(
            theme = ThemeMode.entries.getOrElse(p.getInt("theme", 0)) { ThemeMode.System },
            palette = Palette.of(p.getString("palette", null)),
            background = ReceiveService.background(this),
            ask = p.getBoolean("ask", false),
            pairedOnly = p.getString("visibility", "everyone") == "paired",
            folderName = folderName(),
            customFolder = p.getString("folder", null) != null,
            batteryRestricted = batteryRestricted(),
            autoStartHint = Build.MANUFACTURER.lowercase(Locale.ROOT) in AUTO_START_BRANDS,
        )
    }

    private fun folderName(): String {
        val saved = prefs.getString("folder", null) ?: return "Download/HopDrop"
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
        return "Folder you chose"
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
        var name = uri.lastPathSegment ?: "File"
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
            state.say("That folder is already in the list.")
            return
        }
        state.tab = Tab.Send
        state.folders.add(PickedFolder(tree, "Folder", -1, 0, false))
        work.execute {
            try {
                val name = FolderScan.name(contentResolver, tree)
                val files = FolderScan.files(contentResolver, tree)
                val unknown = files.any { it.size < 0 }
                val bytes = files.filter { it.size >= 0 }.sumOf { it.size }
                runOnUiThread {
                    val i = state.folders.indexOfFirst { it.uri == tree }
                    if (i < 0) return@runOnUiThread
                    if (files.isEmpty()) {
                        state.folders.removeAt(i)
                        state.say("That folder has no files to send.")
                    } else state.folders[i] = PickedFolder(tree, name, files.size, bytes, unknown)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    state.folders.removeAll { it.uri == tree }
                    state.say("HopDrop couldn't read that folder.")
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

    /** Files shared to HopDrop from another app, and taps on "Show files" in a notification. */
    @Suppress("DEPRECATION")
    private fun handle(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("show_files", false)) state.tab = Tab.Activity
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
                state.say("Couldn't prepare the shared text.")
            }
        }
        addFiles(uris, persist = false)
        state.tab = Tab.Send
    }

    // ---- Sending ----

    private fun selectionSummary(): String = when {
        state.folders.isEmpty() -> Format.files(state.files.size)
        state.files.isEmpty() -> Format.folders(state.folders.size)
        else -> Format.folders(state.folders.size) + " and " + Format.files(state.files.size)
    }

    override fun send(device: DeviceUi) {
        if (state.nothingSelected) {
            state.say("Choose files to send first.")
            return
        }
        val d = app.book().get(device.id) ?: return
        val current = app.live().outgoing(d.label())
        if (current != null && !current.finished()) {
            state.say("Already sending to ${d.label()}.")
            return
        }
        val files = ArrayList(state.files.map { it.uri })
        val trees = ArrayList(state.folders.map { it.uri })
        val intent = Intent(this, TransferService::class.java)
            .putExtra("device", d.id)
            .putParcelableArrayListExtra("files", files)
            .putParcelableArrayListExtra("trees", trees)
            .putExtra("summary", selectionSummary())
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val all = files + trees
        val clip = ClipData.newRawUri("HopDrop files", all[0])
        for (i in 1 until all.size) clip.addItem(ClipData.Item(all[i]))
        intent.clipData = clip
        app.live().connecting(d.label())
        try {
            startForegroundService(intent)
        } catch (e: RuntimeException) {
            app.live().failedToStart(d.label(), ErrorText.forDevice(e, d.label()))
        }
    }

    override fun cancel(incoming: Boolean) {
        startService(Intent(this, if (incoming) ReceiveService::class.java else TransferService::class.java).setAction("cancel"))
    }

    override fun bluetooth() {
        if (state.nothingSelected) {
            state.say("Choose files to send first.")
            return
        }
        val loose = ArrayList(state.files.map { it.uri })
        val trees = state.folders.map { it.uri }
        if (trees.isEmpty()) {
            launchBluetooth(loose)
            return
        }
        state.say("Bluetooth sends the files inside folders, without their subfolders.")
        work.execute {
            try {
                for (tree in trees) for (item in FolderScan.files(contentResolver, tree)) loose.add(item.uri)
                runOnUiThread { launchBluetooth(loose) }
            } catch (e: Exception) {
                runOnUiThread { state.say("HopDrop couldn't read that folder.") }
            }
        }
    }

    private fun launchBluetooth(files: ArrayList<Uri>) {
        if (files.isEmpty()) {
            state.say("There are no files to send.")
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
            } else startActivity(Intent.createChooser(share, "Send with Bluetooth"))
        } catch (e: Exception) {
            state.say("Bluetooth sharing isn't available on this phone.")
        }
    }

    // ---- Pairing ----

    override fun scanQr() = scan.launch(Intent(this, QrScanActivity::class.java))

    private fun onScanned(result: ActivityResult) {
        val data = result.data ?: return
        if (result.resultCode != RESULT_OK) return
        if (data.getBooleanExtra("fallback", false)) {
            state.tab = Tab.Devices
            state.say("Tap the device under Nearby, then check both screens show the same number.")
            return
        }
        val uri = data.getStringExtra("uri") ?: return
        val name = try {
            PairUri.parse(uri).name
        } catch (e: IllegalArgumentException) {
            state.say("That QR code can't be used. Show a new one and scan it again.")
            return
        }
        state.pairing = PairingUi(name)
        work.execute {
            try {
                val device = app.peer().pairQr(uri)
                runOnUiThread {
                    state.pairing = null
                    state.say("Paired with ${device.name}")
                    refreshAll()
                }
            } catch (e: Exception) {
                runOnUiThread { if (state.pairing != null) state.pairing = PairingUi(name, ErrorText.forDevice(e, name)) }
            }
        }
    }

    override fun showMyQr() {
        if (LocalSockets.addresses(this).isEmpty()) {
            state.say("Join a Wi-Fi network or turn on your hotspot first.")
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
            state.qr = QrUi(bitmap.asImageBitmap(), LocalSockets.describe(this).map { AddressUi(it.kind, it.ip) })
        } catch (e: Exception) {
            state.qr = null
            state.say("Couldn't show a QR code. Try again.")
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
        if (!PairUri.validIpv4(parts[0])) return "Enter an address like 192.168.1.23"
        val port = if (parts.size == 2) parts[1].toIntOrNull() ?: -1 else 7410
        if (port !in 1..65535) return "Enter an address like 192.168.1.23"
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
                    state.say("Paired with ${device.name}")
                    refreshAll()
                }
            } catch (e: Exception) {
                runOnUiThread { if (state.pairing != null) state.pairing = PairingUi(label, ErrorText.forDevice(e, label)) }
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
        if (name.length > 40) return "Use at most 40 characters"
        val d = app.book().get(device.id) ?: return null
        d.alias = name.ifEmpty { null }
        app.book().put(d)
        refreshDevices()
        return null
    }

    override fun remove(device: DeviceUi) {
        val d = app.book().get(device.id) ?: return
        work.execute {
            app.peer().unpair(d)
            runOnUiThread {
                state.say("Removed ${d.label()}")
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
                    state.say("This file isn't available anymore.")
                    return@runOnUiThread
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                } catch (e: Exception) {
                    state.say("No app on this phone can open this file.")
                }
            }
        }
    }

    override fun share(files: List<HistoryFile>) {
        work.execute {
            val uris = ArrayList(files.mapNotNull { it.uri ?: app.receivedUri(it.name) })
            runOnUiThread {
                if (uris.isEmpty()) {
                    state.say("These files aren't available anymore.")
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
                    state.say("Sharing isn't available on this phone.")
                }
            }
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
                state.say("Receiving could not start. Open HopDrop again to retry.")
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
            state.say("HopDrop can't save files in that folder. Choose another one.")
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
        if (name.isEmpty() || name.length > 40) return "Use 1 to 40 characters"
        val peer = app.peer() ?: return "This phone's identity isn't available."
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
            state.say("Open Settings → Apps → HopDrop and allow background activity.")
        }
    }

    override fun copy(label: String, text: String) {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        // Android 13 and newer confirm copies on their own.
        if (Build.VERSION.SDK_INT < 33) state.say("Copied $text")
    }

    override fun openLink(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            state.say("No browser on this phone can open the link.")
        }
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
        if (prefs.getBoolean("background_offered", false) || !ReceiveService.background(this) || !batteryRestricted() || isFinishing) return
        prefs.edit().putBoolean("background_offered", true).apply()
        state.askBackground = true
    }

    companion object {
        const val PREFS = "settings_v2"

        /** Phone brands that stop apps from starting after a restart unless "Auto-launch" is allowed. */
        private val AUTO_START_BRANDS = setOf("realme", "oppo", "oneplus", "xiaomi", "redmi", "poco", "vivo", "iqoo", "huawei", "honor", "meizu")
    }
}
