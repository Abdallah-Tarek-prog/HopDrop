# HopDrop for Linux Mint

HopDrop's separate Linux desktop app uses Avalonia 12, .NET 10, the shared transfer engine, and the same local agent service as Windows. The current **x86-64 build is a test build**. Ubuntu 24.04 under WSL has been tested; the friend's Mint desktop and physical wireless adapters have not. See [feature parity and test evidence](FEATURE_PARITY.md).

## Install or run

```bash
sudo apt install ./hopdrop_1.3.0-linux-preview.1_amd64.deb
```

Launch **HopDrop** from the application menu. The package installs in `/opt/hopdrop`. For a portable run, extract `out/hopdrop-linux-x64-1.3.0-linux-preview.1.tar.gz` and run `./run-hopdrop`. The window, tray, launcher and sidebar use the Windows app's logo. Settings → Appearance offers Dark, Light and Follow system. Dark mode uses the Windows app's orange accent, charcoal surfaces, text and border colors.

The package declares native .NET and desktop libraries. Portable users need the same libraries listed in `build.sh`, including OpenSSL, ICU, X11/XWayland, ICE/SM and Fontconfig. `xdg-open` opens files and links; `notify-send` sends desktop notifications. A tray needs StatusNotifierItem/AppIndicator support. Test those desktop integrations on Mint.

For local transfers, allow **TCP 7410** and **UDP 7410** on the trusted local network if the firewall blocks them. HopDrop does not change firewall rules. Pair by QR, a pairing link, or IP and number match. Both devices must be reachable on a private network, hotspot or other supported local connection.

## Desktop features

- Send files, nested folders and text to one or several paired devices. Drag and drop or use the file pickers. Offline sends persist and retry when the device returns. A send interrupted by app shutdown requires review before retrying, because some files may already have arrived.
- Manage device names, trust, discovery visibility and incoming consent. Activity shows the actual transport, supports opening received files, copying text, opening web links and clearing the list.
- Choose receive and backup locations. Android folder backups keep their structure; removing a backup from the list leaves its files on disk.
- English is the explicit default. Settings switches to Arabic with RTL layout and Arabic plurals. Paths, addresses, codes and configuration keep their character order. System, light and dark themes are available.
- Start at sign-in, receive after the window closes, or close to tray. These are per-user settings. **Quit HopDrop** stops the app. Background receiving needs a logged-in desktop session. Relaunch to bring back a hidden window; Settings also includes Quit.
- Optionally add “Send with HopDrop” to Mint's Nemo, Nautilus's Scripts menu, and Dolphin service menus. Selected files open in HopDrop for recipient selection. Integration and autostart files are changed only when you turn the options on/off. Custom `--data` profiles write integration files under the test profile instead of the real desktop directories.
- Paste clipboard text explicitly on the Send page. Received clipboard text can be copied automatically. Optional automatic copied-text sharing targets nearby paired Android devices under X11, skips known private clipboard formats and suppresses received-text loops. Apps may omit private markers. Automatic sharing is unavailable in Wayland sessions; explicit paste remains available.

## Hotspot and Bluetooth

**Hotspot:** Settings → Direct link → Start hotspot uses NetworkManager (`nmcli`), a managed AP-capable adapter, polkit permission and shared IPv4 support (usually `dnsmasq-base`). It creates a temporary app-owned WPA2 hotspot and includes its details in the existing Android pairing QR/hello protocol. Credentials persist privately so a paired phone can reconnect; they are never command-line arguments. A sole connected Wi-Fi adapter may disconnect its current network; HopDrop warns before activation. It never starts a hotspot automatically or deletes unrelated network profiles. This is an infrastructure hotspot, not concurrent Windows Wi-Fi Direct.

**Bluetooth send:** select files, choose Send files with Bluetooth, and choose an OS-paired device. BlueZ, `bluetoothctl`, GLib `gdbus`, a powered controller and the user OBEX service are required. Only `Transfer1.Status=complete` records delivery in Activity. An unconfirmed transfer is shown as unconfirmed; check the receiver before retrying. Bluetooth sends individual files; use the network for folders.

**Bluetooth receive:** the system receiver opens Blueman with its transfer plugin and native consent. HopDrop cannot verify those incoming completions, relocate the files, or add them to Activity. Opening Blueman never counts as receiving a file. These radio paths pass simulated backend tests but still require physical Mint/phone testing.

Linux updates are installed from a new package. Settings links to the public release page; it does not run the Windows updater or claim a newer Linux package exists.

## AI agents and scripts

Turn on **Settings → AI agents → Allow local AI agents**. It is off by default. Copy the MCP configuration shown there into your client. For an installed app, Codex can be configured with:

```bash
codex mcp add hopdrop -- /opt/hopdrop/HopDrop.Linux --mcp
```

This is a standard local stdio server; see [OpenAI's MCP configuration documentation](https://learn.chatgpt.com/docs/extend/mcp?surface=cli). Other MCP clients can use the JSON from Settings. No global client configuration is changed by HopDrop itself.

Tools match Windows: `list_devices`, `send_files`, `transfer_status`, `recent_transfers`, `wait_for_files`. Agents use the running peer; they cannot pair devices, change settings or approve incoming offers. Only processes running as the same OS user can connect. The agent opt-in is read on every request, and disabling it revokes further tool access. Sends require paired, reachable devices and report success only after confirmed completion. The agent path reports offline failure rather than silently enqueuing a send.

```bash
/opt/hopdrop/HopDrop.Linux agent devices
/opt/hopdrop/HopDrop.Linux agent send --to "My phone" /home/me/report.pdf
/opt/hopdrop/HopDrop.Linux agent recent --direction sent
```

`--data <directory>` selects an isolated profile for both the app and its CLI/MCP bridge. Do not enable access on a shared/untrusted OS account. Machine response values stay in English even when the UI is Arabic.

## Build and verify

On Linux with the .NET 10 SDK and `dpkg-deb`:

```bash
bash Source/Linux/build.sh
```

On Windows, cross-publish then package through WSL:

```powershell
dotnet publish Source/Linux/HopDrop.Linux/HopDrop.Linux.csproj -c Release -r linux-x64 --self-contained true -p:UsedAvaloniaProducts= -o Source/Linux/out/publish
wsl.exe -d Ubuntu-24.04 -- bash /mnt/g/Study/Programming/'Personal Products'/ArcDrop/Source/Linux/build.sh --package-only
```

Run the core/localization regression tests and the integration harnesses:

```bash
dotnet test Source/Windows/HopDrop.Tests/HopDrop.Tests.csproj
dotnet run --project Source/Shared/HopDrop.Agents.Tests/HopDrop.Agents.Tests.csproj
dotnet run --project Source/Linux/HopDrop.Linux.Tests/HopDrop.Linux.Tests.csproj
dotnet run --project Source/Linux/HopDrop.Linux.Platform.Tests/HopDrop.Linux.Platform.Tests.csproj
```

The radio fixtures and case-sensitive filename assertions require Linux. Cross-publish the harnesses with `-r linux-x64 --self-contained true` and run their executables in WSL when developing on Windows. For repeatable network pairing/consent/file/folder/text checks:

```bash
dotnet publish Source/Windows/HopDrop.Cli/HopDrop.Cli.csproj -c Release -r linux-x64 --self-contained true -o Source/Linux/out/cli-publish
bash Source/Linux/tests/smoke.sh
python3 Source/Linux/tests/agents.py
```

The smoke script uses temporary profiles and ports 17410–17412, verifies bytes, and removes its own files. The native agent script needs a graphical session and free port 7410; it tests CLI/MCP cold launch, synthetic folder transfers, exact filenames/hashes and disabled access, then stops only its own profiles. To render the native UI without a network listener or normal profile changes:

```bash
./HopDrop.Linux --data /tmp/hopdrop-preview --render-preview ./screens
```

It produces Send, Devices, Activity and Settings PNGs, plus scrolled Settings sections. Set `Language` and `Theme` in that isolated profile's `linux.json` before rendering.

## Data and security

Data: `$XDG_DATA_HOME/hopdrop` or `~/.local/share/hopdrop`. Settings: `$XDG_CONFIG_HOME/hopdrop` or `~/.config/hopdrop`. Logs: `$XDG_STATE_HOME/hopdrop/logs` or `~/.local/state/hopdrop/logs`. Received files default to `~/Downloads/HopDrop`.

The TLS identity is an encrypted PFX inside a mode-0700 data directory. PFX, key and persisted hotspot credentials use mode 0600. With `secret-tool` and an unlocked Secret Service, the PFX password is stored there; otherwise a random password is in the protected `identity.key`. Keep both identity files together when backing up. Received files cannot use Windows' NTFS Mark of the Web on Linux.

## Physical Mint acceptance checks

1. Install the `.deb`; confirm dependencies, logo, tray, notifications, file opening, themes and Arabic on the actual desktop.
2. Pair with real Android and Windows builds using QR and number match. Send files, nested folders, text and an Android backup both ways where supported. Check consent, trust, cancel, resume, locations and Activity transport.
3. Disconnect/reconnect the receiver; verify queued sends persist, deliver once and can be cancelled. Close/restart during a send and review the interrupted entry.
4. Check autostart/background behavior and Nemo selections, including spaces and Arabic filenames. Check explicit clipboard paste; test optional sharing with synthetic text only.
5. Create/stop the hotspot; verify polkit, DHCP, Android QR join, transfer, Wi-Fi interruption warning, reconnection and cleanup of only the HopDrop profile.
6. Pair Bluetooth at OS level. Confirm send status/consent, unconfirmed failure handling, and native Blueman receiving. Incoming native transfers must not produce false Activity entries.
7. Enable agent access and connect the actual preferred MCP client. Request a synthetic file/folder send, verify received hashes, disable access, and confirm further calls are denied.
