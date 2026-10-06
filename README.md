# HopDrop

Send files and whole folders between your phones and PCs over your own Wi-Fi. No cloud, no accounts, no mobile data.

- **Android** (8 or newer) and **Windows** (10 or 11, x64 and ARM).
- Every device can send and receive: phone ↔ PC, PC ↔ PC, phone ↔ phone.
- Free and open source (GPL-3.0).

## Download

From the [Releases page](https://github.com/Abdallah-Tarek-prog/HopDrop/releases):
- `HopDrop.apk` — the Android app.
- `HopDrop-win-Setup.exe` — Windows installer (no admin needed; updates itself). `HopDrop-win-arm64-Setup.exe` for ARM laptops.
- `HopDrop.exe` — the same Windows app without installing (it doesn't update itself).
- A Microsoft Store version is on its way. It puts HopDrop in Windows' **Share** window (right-click files → **Share**), next to apps like Phone Link.

## First use

1. Install the app on both devices. If phones can't find the PC, use **Settings → Windows Firewall → Allow HopDrop** on the PC.
2. Put both devices on the same network: the same Wi-Fi router, or one device's hotspot with the other joined to it (works both ways).
3. Pair once, with either method:
   - **QR code:** the PC shows a code under **Devices**. On the phone, tap **Pair device → Scan a QR code**. Pinch or double-tap to zoom if the phone struggles to focus.
   - **Matching numbers:** tap **Pair** next to a device under **Nearby** (or pair by IP address). Both screens show the same 6-digit number; confirm on both.
4. Done. Devices stay paired until you remove them on either side.

## Sending and receiving

- **Phone:** tap **Files**, **Photos** or **Folder**, then **Send** next to a device. Sharing to HopDrop from any app works too.
- **PC:** drop files or folders on the **Send** page, then click **Send** next to a device. Or right-click files → **Send to → HopDrop** (turn it on in Settings).
- **Folders arrive as folders**, with their subfolders. Thousands of files go in one transfer.
- **If Wi-Fi drops mid-transfer**, the sender reconnects and continues where it stopped. Nothing is sent twice.
- **Live progress on both ends:** file, amount, speed, time left and **Cancel**, in the app and in notifications.
- **Activity:** every transfer, grouped by day, with search, a Sent / Received filter and photo previews. Tap one to see its files, one per line; tap a file to open it, long-press to share it.
- **The receiver decides where files go:** Settings → Receive folder (default `Downloads\HopDrop` on Windows, `Download/HopDrop` on Android).
- **Receiving in the background:** the phone receives with the app closed (Settings → Receive in the background, on by default). The PC receives while HopDrop runs in the tray; turn on Settings → Receive in the background to keep receiving after quitting.
- **Ask before receiving** (off by default): files wait for your **Accept**. Devices you trust skip the question.
- **Appearance:** System, Light or Dark on both apps. The phone also has six colour themes: HopDrop, Ocean, Forest, Berry, Midnight, and your wallpaper's colours (Android 12 and newer).

## AI agents (Windows)

Turn on **Settings → AI agents**, then click **Copy for Claude Code**, **Copy for Codex** or **Copy MCP settings (JSON)** and paste it into your assistant. Then ask things like "send report.pdf to my phone" or "wait for the photo I send from my phone, then crop it".

- Agents can list your paired devices, send files and folders, check a transfer, list recent transfers and wait for files from a device.
- They can't pair devices, change settings or accept files for you. Off until you turn it on; only your Windows account can connect.
- Scripts without MCP: `HopDrop.exe agent help`.

## Bluetooth

For when there's no shared network. Much slower than Wi-Fi, and the phone and PC must already be paired in Windows' Bluetooth settings.
- **Phone → PC:** on the PC click **Receive via Bluetooth**; HopDrop drives Windows' Bluetooth window and moves the files into its receive folder. On the phone: **Send with Bluetooth**.
- **PC → phone:** **Send via Bluetooth** opens Windows' Bluetooth window on its send page.

## How HopDrop compares

Good apps already move files between devices. Here is where HopDrop is different, and where others are the better pick.

**What HopDrop does differently**
- **Only your devices can send to you.** You pair each device once (QR code or matching numbers). After that, nobody else on the network can send you anything, or even ask to. In [LocalSend](https://github.com/localsend/localsend), by default any device on the network can send you a request (it has an optional PIN).
- **No account and no internet, ever.** Nothing goes through a server. [Quick Share for Windows](https://support.google.com/android/answer/13801258) needs a Google account for its "Your devices" mode, [Phone Link](https://support.microsoft.com/en-us/windows/apps/phonelink/phone-link-requirements-and-setup) needs the same Microsoft account on both devices, and [PairDrop](https://github.com/schlagmichdoch/PairDrop/blob/master/docs/faq.md) connects devices through a server (a public one on the internet, unless you host your own).
- **Picks up where it stopped.** If Wi-Fi drops mid-transfer, HopDrop reconnects and continues from the last byte. In LocalSend, resuming is still an [open request](https://github.com/localsend/localsend/issues/1191); an interrupted transfer starts over.
- **Big folders in one go.** A folder is one item in the list, arrives with its structure, and thousands of files travel in one transfer.
- **Works for AI assistants.** Claude, Codex, Cursor and other MCP clients on your PC can send and wait for files for you (off by default).
- **Careful with files from other devices.** On Windows, files from devices you haven't marked as trusted get Windows' "downloaded" mark, so Windows and Office warn before opening risky ones.
- **Small and focused.** The Android app is about 2 MB, does one job, and keeps receiving in the background.

**Where others are better**
- **More platforms:** LocalSend and [KDE Connect](https://kdeconnect.org/) also run on iPhone, Mac and Linux. HopDrop is Android and Windows only for now.
- **Nothing to install on the phone:** Quick Share is built into many Android phones.
- **More than files:** KDE Connect also shares notifications and the clipboard, and works as a remote control.

## Security

- Every connection is encrypted (TLS 1.2/1.3) and both devices prove their identity with a key kept in Windows' or Android's protected key storage.
- Pairing needs the one-time QR code (valid 5 minutes) or both people confirming the same 6-digit number.
- Files are checked with SHA-256; unfinished files are deleted. Received file names are cleaned so a sender can't write outside the receive folder.
- The apps only talk to addresses on your local networks and never use mobile data for transfers.
- **Privacy:** Settings → "Who can see this phone" → **Paired only** (on the PC: "Who can see this PC's name" → **Only my paired devices**) stops announcing the name on the network.
- The Windows firewall rule (**Allow HopDrop**) lets only the app itself in, only from your local network.
- **Installing on Android:** the APK isn't from Google Play, so Play Protect may say it doesn't know the app ("Install anyway").
- Found a problem? See [SECURITY.md](SECURITY.md). Privacy policy: [PRIVACY.md](PRIVACY.md).

## For developers

- `Source/SPEC.md` — the product and protocol spec (the source of truth).
- `Source/Windows/` — .NET 10 solution (engine, command-line test peer, tests, WPF app). See its README.
- `Source/Android/` — the Android app: Kotlin + Jetpack Compose screens, a plain-Java transfer engine, built with Gradle. See its README.
- Release signing keys are never committed (`.gitignore` blocks them).

## License

HopDrop is free software under the [GNU General Public License v3.0](LICENSE). Copyright © 2026 Abdallah Tarek.
