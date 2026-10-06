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

- `-Test` checks the engine on the desktop JVM: core rules (SAS, QR URI, file names, framing, speed/time-left meter) and two Java peers talking to each other (pairing, transfers, Ask before receiving and trust, folders, 1,500 files, resuming after a dropped connection, slow uploads). No Android SDK needed.
- `-Interop` runs the phone's engine against the Windows command-line peer on ports 17410/17411, so a running HopDrop (port 7410) isn't disturbed.
- `-Debug` builds "HopDrop Dev" (`com.hop.drop.dev`): a separate app with its own pairings, so testing never replaces the real one.
- `-Release` builds the shrunk release app (about 2 MB) and copies it to `HopDrop.apk` at the repository root. It's signed with the key in `Private signing key/` (kept off git); without that folder the build signs with the debug key. `-Install` also installs it on the phone connected over adb; updates install over the old version and keep pairings.
- `./gradlew recordRoborazziDebug` draws every screen with sample data (light, dark, each colour theme, dialogs) into `screenshots/` (not in git), to review the design without a phone.

## Code map

- `src/com/hop/drop/core/` — pure Java, no `android.*`: protocol, pairing, SAS, QR URI, file names, `TransferMeter`, `Format`.
- `src/com/hop/drop/net/` — `Discovery` (UDP 7410) and `LocalSockets` (local-only sockets, this phone's labelled addresses).
- `src/com/hop/drop/store/` — identity (Android Keystore), paired devices, receive folder.
- `src/com/hop/drop/` — the app's Android parts: `HopApp` (engine, notification channels, Activity history), `ReceiveService`, `TransferService`, `Notices` (every notification), `Live` (what is moving right now), `FolderScan`, `QrScanActivity` (the camera scanner), and `MainActivity.kt`, which hosts the screens and connects them to the engine.
- `src/com/hop/drop/ui/` — the Compose screens: `Theme.kt` (six colour themes in light and dark, Plus Jakarta Sans), `App.kt` (top bar, bottom tabs, transitions), `SendScreen`, `ActivityScreen`, `DevicesScreen`, `SettingsScreen`, `Dialogs` (pairing number, incoming files, QR code, pair options), `Components` and `Thumbnails`. The screens only read `HopUiState` and call `HopActions`.
- `uitests/` — the screenshot tests (Robolectric + Roborazzi).

## Behaviour worth knowing

- Three tabs: **Send** (pick Files, Photos or a Folder, then Send next to a device; progress shows on that device), **Activity** (every transfer, grouped by day, with search, a Sent/Received filter and photo previews; tap to see the files one per line, tap a file to open it, long-press to share), **Devices** (this phone and its addresses, paired devices, nearby devices with a Pair button, and **Pair device** for QR or an IP address). Settings is the gear at the top.
- Receiving runs all the time while **Settings → Receive in the background** is on (the default). Realme/Oppo phones block the start-after-update until the app is opened once unless Auto-launch is allowed; Settings shows an "Allow auto-launch" row on those brands. On first start the app asks once to be exempted from battery optimisation.
- Battery: discovery announces every 5 s only while HopDrop is on screen; in the background it announces every 20 s and lets the Wi-Fi chip filter group traffic. Paired devices still reach the phone at its saved address.
- **Ask before receiving** shows a notification with Accept/Decline (and a dialog while the app is open); trusted devices (Devices → tap a device → Trust this device) skip it. **Who can see this phone → Paired only** hides the phone's name from unpaired devices, except while the Devices tab is open.
- A picked folder is one item (counted in the background); `TransferService` lists its files when it sends. Activity keeps where the first 50 files of each transfer are, so it can open and share them (received files, and sent files whose source the phone can still read).
- The QR scanner: preview without stretching, pinch / double-tap / chip zoom up to 4×, tap to focus, and "Pair with numbers instead". No standing hint text.
- Appearance: System, Light or Dark, and a colour theme (HopDrop, Ocean, Forest, Berry, Midnight, or Wallpaper on Android 12+). Both apply instantly.

On a real phone, check: the scanner on a laptop screen at arm's length; a folder of a few hundred files; phone-to-laptop and laptop-to-phone transfers with the app closed; Accept/Decline from the notification; opening and sharing from Activity.
