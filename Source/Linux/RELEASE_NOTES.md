# HopDrop Linux preview 1

A 64-bit Linux desktop test build for trying HopDrop on Linux Mint. Download the `.deb` for Mint or the portable `.tar.gz`. Windows and Android 1.2.0 remain the stable releases.

## Install on Mint

Download `hopdrop_1.3.0-linux-preview.1_amd64.deb`, open a terminal in the download folder, and run:

```bash
sudo apt install ./hopdrop_1.3.0-linux-preview.1_amd64.deb
```

Open **HopDrop** from the application menu. For the portable build, extract the archive and run `./run-hopdrop`. Do not run HopDrop itself with `sudo`.

## Included

- File, nested-folder and text transfers with the existing Android and Windows apps; QR/IP pairing, trust and incoming consent.
- Multi-device sending, persistent offline sends, progress/cancel, receive/backup locations and Activity connection labels.
- **Dark, Light and Follow system themes.** Dark mode uses Windows HopDrop's exact orange, charcoal, text, border and button-foreground colors, including the accent on native controls. Choose **Settings → Appearance → Dark** to keep dark mode regardless of the system theme.
- The same HopDrop logo as Windows. English is the default; Arabic and RTL are available in Settings.
- Tray/background receiving, optional sign-in startup, file-manager send actions and clipboard options.
- Local AI agent support through MCP and the CLI, off until enabled in **Settings → AI agents**. Agents can list paired devices, send requested files/folders, check status and find received files. Copy the MCP configuration from Settings into your client.
- User-started NetworkManager hotspots and BlueZ Bluetooth sending, subject to system hardware/services.

## What to test first

1. Use the same Wi-Fi or Ethernet network as the phone/Windows PC. Pair once using a QR code or matching number.
2. Send a file, a nested folder and Arabic text both ways. Check received contents, Activity and connection labels.
3. Select Dark in Settings, restart HopDrop and check the saved theme. Try Arabic and check filenames, addresses and dialogs.
4. Test notifications, the tray, background receiving and Nemo's optional send action on your actual Mint desktop.
5. Try the hotspot and Bluetooth only after the basic network transfer works. Report your Mint version, desktop environment and wireless adapter with any failure.

If the firewall blocks local transfers, allow TCP 7410 and UDP 7410 on the trusted local network. HopDrop does not change firewall rules automatically.

## Tested and known limits

Core/localization tests passed (85 tests), as did native Linux transfer, queue, agent/CLI/MCP, disabled-access and radio-fixture checks under Ubuntu 24.04 WSL. A real Codex caller sent synthetic files through the native app; every received filename and hash matched. The release check also found and fixed a shutdown cancellation race. English/Arabic light/dark screens were rendered from the actual app.

**Physical Mint hardware is the purpose of this preview.** Hotspot/BlueZ fixtures pass, but actual adapter, polkit, DHCP, phone joining, tray and file-manager behavior still need the friend's machine. A hotspot may disconnect the adapter's existing Wi-Fi; HopDrop warns before activation. Incoming Bluetooth uses Blueman's native receiver, so HopDrop cannot confirm it or add it to Activity. Automatic clipboard sharing is unavailable on Wayland. Linux updates are installed manually from a new package.

The full feature matrix and repeatable checks are in [`Source/Linux/FEATURE_PARITY.md`](https://github.com/Abdallah-Tarek-prog/HopDrop/blob/v1.3.0-linux-preview.1/Source/Linux/FEATURE_PARITY.md). Source for these binaries is the release tag. `SHA256SUMS` accompanies the installer and archive.

To remove the installed preview, use `sudo apt remove hopdrop`. This does not delete received files or the user's HopDrop data/settings. The existing stable Windows/Android release is unchanged.
