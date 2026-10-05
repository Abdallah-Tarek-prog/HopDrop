# HopDrop Android

Plain Java and Android platform APIs, built with the installed Android SDK and JDK 21. No Gradle or AndroidX.

From the repository root:

```powershell
powershell -File Source/Android/build.ps1 -Test
powershell -File Source/Android/build.ps1 -Debug
powershell -File Source/Android/build.ps1 -Release
powershell -File Source/Android/build.ps1 -Release -Install
powershell -File Source/Android/build.ps1 -Interop
```

- `-Release` signs `HopDrop.apk` at the repository root with the existing release key (the build script reads the password file; never print or copy it). `-Install` also installs it on the phone attached over adb, updating the app in place and keeping its pairings.
- `-Debug` builds `com.hop.drop.dev` ("HopDrop Dev"), a separate app with its own identity and pairings.
- `-Test` runs the pure-Java core tests (SAS vectors, QR URI, file names, framing, speed/time-left meter) and two Java peers talking to each other (pairing, transfers, Ask before receiving and trust, folders, 1,500 files in one transfer, resuming after a dropped connection).
- `-Interop` runs the phone's Java engine against the Windows CLI on ports 17410/17411, so a running HopDrop (port 7410) is not disturbed. It covers pairing, transfers, Ask before receiving, folders, big selections and resuming, in both directions. It needs Windows .NET TLS credentials and fails inside a restricted sandbox with `SEC_E_NO_CREDENTIALS`; run it outside that sandbox.

## Code map

- `core/` — pure Java, no `android.*`: protocol, pairing, SAS, QR URI, file names, `TransferMeter` (smoothed speed and time left), `Format` (sizes, durations).
- `net/` — `Discovery` (UDP 7410; every send is independent and runs off the main thread) and `LocalSockets` (local-only sockets, and this phone's labelled addresses).
- `store/` — identity (Android Keystore), paired devices, receive folder.
- App layer — `MainActivity` (header, live-transfer cards, bottom navigation, theme override), the four tabs, `Live` (what is moving right now), `Notices` (every notification), `ReceiveService`, `TransferService`, `FolderScan` (a picked folder: name, and its files when counted or sent), `QrScanActivity` (the pairing-code scanner).

## Behaviour worth knowing

- Receiving runs all the time while **Settings → Receive in the background** is on (the default). Realme/Oppo phones block the start-after-update until the app is opened once unless Auto-launch is allowed. On first start the app asks once to be exempted from battery optimisation, and again when the switch is turned on; some phones also need Auto-launch. With the switch off it receives only while the app is open. Arrivals use the high-importance "Files received" channel, so they pop up on screen.
- Battery: discovery holds Wi-Fi's multicast lock and announces every 5 s only while HopDrop is on screen; in the background it announces every 20 s and lets the Wi-Fi chip filter group traffic. Paired devices still reach the phone at its saved address.
- **Ask before receiving** (Settings) shows a notification with Accept/Decline (and a dialog while the app is open); devices with **Trust this device** (Devices → tap a device) skip it. **Privacy → Paired only** hides the phone's name from devices it isn't paired with, except while the Devices tab is open.
- **Folder** on the Send tab adds the folder as one item (counted in the background); `TransferService` lists its files (except `.hidden` ones) when it sends, so a huge folder never passes between the app's parts as thousands of entries. Received folders keep their structure in the receive folder. Bluetooth has no folders, so it gets the files inside them.
- **Scan QR** opens a full-screen scanner: preview without stretching, pinch / double-tap / chip zoom up to 4×, tap to focus, reading anywhere in the frame, messages for a different QR code or another version, a camera-permission screen, and "Can't scan? Pair by comparing numbers". After a scan, a "Pairing with …" dialog shows until it works or explains why not.
- Settings → Appearance: System, Light or Dark (applied on top of the system setting).
- Bluetooth hands files to Android's own Bluetooth app. The Windows app's **Receive via Bluetooth** puts the PC in receive mode and moves the files into its receive folder.

On a real phone, check an upgrade over the installed release app; the scanner (zoom, tap to focus, a laptop screen at arm's length, permission denied and allowed again); a folder of a few hundred files as one item; phone-to-laptop and laptop-to-phone pairing and transfers; live progress on both ends; number match cancellation and timeout; the receive folder and persistence; background receiving after leaving the app and after a reboot; file picker multi-select, photos, share sheet, and Bluetooth; notifications; and all four tabs in light and dark mode at normal and larger font sizes.
