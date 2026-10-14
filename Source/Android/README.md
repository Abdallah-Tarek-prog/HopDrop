# HopDrop Android

Kotlin + Jetpack Compose (Material 3) screens on top of a plain-Java transfer engine, built with Gradle.
Needs JDK 17+ and the Android SDK (`ANDROID_HOME`, or Android Studio's default `%LOCALAPPDATA%\Android\Sdk`);
Gradle downloads everything else on the first build.

From the repository root:

```powershell
powershell -File Source/Android/build.ps1 -Test
powershell -File Source/Android/build.ps1 -Interop
powershell -File Source/Android/build.ps1 -Debug
powershell -File Source/Android/build.ps1 -Release
powershell -File Source/Android/build.ps1 -Release -Install
```

- `-Test` checks the engine on the desktop JVM: core rules (including backup kinds, time windows, charger rule, migration shape and sent-list names) and two Java peers talking to each other (pairing, transfers, Ask before receiving and trust, folders, backup routing and registry, text messages, 1,500 files, resuming after a dropped connection, expired resume cleanup, slow uploads). No Android SDK needed. Runs reuse `out/jvm-classes` and `out/jvm/run` (cleared first), so they don't pile up on disk.
- `./gradlew testDebugUnitTest` also runs `unittests/` Robolectric checks for backup lists, waiting sends, notifications and direct-link cleanup.
- `-Interop` runs the phone's engine against the Windows command-line peer on ports 17410/17411, so a running HopDrop (port 7410) isn't disturbed: pairing, transfers both ways, text both ways, backup offers landing in each engine's backup folder, resume. It reuses `out/interop/run`.
- `-Debug` builds "HopDrop Dev" (`com.hop.drop.dev`): a separate app with its own pairings, so testing never replaces the real one.
- `-Release` builds the shrunk release app (about 2 MB) and copies it to `HopDrop.apk` at the repository root. It's signed with the key in `Private signing key/` (kept off git); without that folder the build signs with the debug key. `-Install` also installs it on the phone connected over adb; updates install over the old version and keep pairings.
- `./gradlew recordRoborazziDebug` draws every screen with sample data (light, dark, each colour theme, dialogs, every page of the tour) into `screenshots/` (not in git), to review the design without a phone.

## Code map

- `src/com/hop/drop/core/` — pure Java, no `android.*`: protocol, pairing, SAS, QR URI, file names, `TransferMeter`, `Format`.
- `src/com/hop/drop/net/` — `Discovery` (UDP 7410), `LocalSockets` (local-only sockets, this phone's labelled addresses) and `DirectLink` (joins a PC's own Wi-Fi when there's no shared network).
- `src/com/hop/drop/store/` — identity (Android Keystore), paired devices, receive folder.
- `src/com/hop/drop/` — the app's Android parts: `HopApp` (engine, notification channels, Activity history, full-speed locks while files move), `ReceiveService`, `TransferService`, `Sends` (what a send is, how a device is reached — same network, then its direct link — and the queue of sends waiting for their device), `Backup` (auto-backup), `Shortcuts` + `ShareActivity` (paired devices in the share sheet), `QuickTile` + `QuickSendActivity` (the Quick Settings tile), `Notices` (every notification) and `NoticeAction` (Copy / Cancel on them), `Live` (what is moving right now), `FolderScan`, `QrScanActivity` (the camera scanner), `Updater` (updates from GitHub Releases), and `MainActivity.kt`, which hosts the screens and connects them to the engine.
- `src/com/hop/drop/ui/` — the Compose screens: `Theme.kt` (six colour themes in light and dark, Plus Jakarta Sans), `App.kt` (top bar, bottom tabs, transitions), `SendScreen`, `ActivityScreen`, `DevicesScreen`, `SettingsScreen`, `Dialogs` (pairing number, incoming files, QR code, pair options, text, backup setup), `Onboarding` (the tour, which draws the real screens inside a phone outline), `Preview` (sample data and do-nothing actions for the tour and the screenshot tests), `Components` and `Thumbnails`. The screens only read `HopUiState` and call `HopActions`.
- `uitests/` — the screenshot tests (Robolectric + Roborazzi).
- `unittests/` — Android behavior tests (Robolectric + JUnit 4).

## Behaviour worth knowing

- New installs (and Settings → Show the tour again): a seven-page tour with pictures and bullets, skippable, ending in **Pair my first device**. Updates show a centred **What's new** modal once per version, with a link to the full tour.
- Three tabs: **Send** (pick Files, Photos, a Folder or **Text** — prefilled with the clipboard, arriving as text with Copy / Open link — then Send next to a device, or **Select several**; with nothing selected, tap **Connect** on an offline PC with a direct link so it can send to the phone; progress shows on that device; a device that can't be reached gets the send queued until it's back), **Activity** (every transfer, grouped by day, with search, a Sent/Received filter and photo previews; tap to see the files one per line, tap a file to open it, long-press to share), **Devices** (this phone and its addresses, paired devices, nearby devices with a Pair button, and **Pair device** for QR or an IP address). Settings is the gear at the top.
- Not on the same Wi-Fi as a PC? Pairing by QR and sending join the PC's direct link by themselves (Android asks "Connect?" the first time). The link is let go two minutes after the last transfer; Devices → the PC → **Connect directly** joins it on purpose (so the PC can send to the phone).
- Share sheet: where device targets appear, tapping one sends at once. On phones that show only apps, tap HopDrop, then Send. Quick Settings tile: a card with full-width Clipboard, Latest photo and Files options; pick one to send to the selected device. With a paired device, the launcher icon menu puts **Send clipboard** first. The ongoing receiver notification also offers **Send clipboard**; both shortcuts read the clipboard after the activity gains focus.
- Outgoing progress has a notification per device with its own **Cancel**; a foreground summary lasts while any send runs. QR pairing errors explain different networks, direct-link failures, and no answer on the shared network without reading Wi-Fi names.
- Settings → Auto-backup: keep several backups, each with its own phone folder, paired device, All files or selected Photos / Videos / Documents / Audio, on/off, charger rule, and optional daily time window. **Back up now** ignores the window. Each backup remembers its own sent files; the old single backup becomes the first entry and reuses its sent list without copying it. The receiver service checks eligibility every 30 seconds while running, with folder scans at most every ten minutes. Backups use the shared network and never join a direct link.
- Settings → Backups: choose where incoming backups go (default `Download/HopDrop/Backups`), see the sending device, source folder, file count and last run, and forget entries without deleting files. A receiver with the `backup` feature stores them under `<device>/<folder>/`; an older receiver keeps the merged folder in its normal receive place.
- Receiving runs all the time while **Settings → Receive in the background** is on (the default). Realme/Oppo phones block the start-after-update until the app is opened once unless Auto-launch is allowed; Settings shows an "Allow auto-launch" row on those brands. On first start the app asks once to be exempted from battery optimisation.
- Battery: discovery announces every 5 s only while HopDrop is on screen; in the background it announces every 20 s and lets the Wi-Fi chip filter group traffic. Paired devices still reach the phone at its saved address.
- **Ask before receiving** shows a notification with Accept/Decline (and a dialog while the app is open); trusted devices (Devices → tap a device → Trust this device) skip it. **Who can see this phone → Paired only** hides the phone's name from unpaired devices, except while the Devices tab is open.
- A picked folder is one item (counted in the background); `TransferService` lists its files when it sends. Activity keeps where the first 50 files of each transfer are, so it can open and share them (received files, and sent files whose source the phone can still read).
- The QR scanner: preview without stretching, pinch / double-tap / chip zoom up to 4×, tap to focus, and "Pair with numbers instead". No standing hint text.
- Updates: once a day (when the app opens) HopDrop reads the latest GitHub release. If it is newer and has `HopDrop.apk`, a card on the Send tab offers **Update** / **Later** (Later hides that version for three days). Update downloads the APK into the app's cache, checks it is HopDrop, signed with the same key and newer, then opens Android's installer. The first time, Android asks to allow HopDrop to install apps. Settings → About has **Check for updates** (on/off) and **Check now**. Debug builds never check. Every release needs a higher `versionCode` and `versionName`, a tag like `v1.0.1`, and an asset named exactly `HopDrop.apk`.
- Appearance: System, Light or Dark, and a colour theme (HopDrop, Ocean, Forest, Berry, Midnight, or Wallpaper on Android 12+). Both apply instantly.

On a real phone, check: pairing by QR with the phone on a different Wi-Fi (or none); the tour; Direct Share and the tile; the scanner on a laptop screen at arm's length; a folder of a few hundred files; phone-to-laptop and laptop-to-phone transfers with the app closed; Accept/Decline from the notification; opening and sharing from Activity.
