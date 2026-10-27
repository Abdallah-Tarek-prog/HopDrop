# HopDrop Linux preview 1

A 64-bit Linux desktop test build for trying HopDrop on Linux Mint. Download the `.deb` for Mint or the portable `.tar.gz`. Windows and Android 1.2.0 remain the stable releases.

## Install on Mint

Run these steps **on the Linux Mint computer**, inside its normal graphical desktop session. If the installer already completed with `Setting up hopdrop`, go straight to **Launch the installed app** below.

1. Download `hopdrop_1.3.0-linux-preview.1_amd64.deb` from this release.
2. Open Mint's **Files** application and go to the folder containing that downloaded file. It may be **Downloads** or another folder you chose.
3. Right-click an empty area in that folder and choose **Open in Terminal**. This opens the terminal in the correct folder.
4. Run:

```bash
sudo apt install ./hopdrop_1.3.0-linux-preview.1_amd64.deb
```

The `./` means “the file in this terminal's current folder.” Use `sudo` for this installation command; enter your Mint login password if requested. Password characters are hidden while typing.

If you open Terminal using **Ctrl+Alt+T** instead, first change to the folder containing the installer. For example, **only if the file is in your Downloads folder**:

```bash
cd "$HOME/Downloads"
sudo apt install ./hopdrop_1.3.0-linux-preview.1_amd64.deb
```

## Launch the installed app

The easiest way is **Mint Menu → search for HopDrop → click HopDrop**.

Alternatively, press **Ctrl+Alt+T** on the Mint computer and run:

```bash
/opt/hopdrop/HopDrop.Linux
```

**Run this launch command without `sudo`. It works from any folder.** You can be in your home folder, Downloads, or the folder used for installation; no `cd` command is needed because `/opt/hopdrop/HopDrop.Linux` is the full installed path. HopDrop should open a desktop window. If it prints an error, keep the terminal output for a bug report.

Inside HopDrop, choose **Settings → Appearance → Dark** for the Windows-matched dark theme. English is the default; **Settings → Language** also offers Arabic.

## Portable archive alternative

If using `hopdrop-linux-x64-1.3.0-linux-preview.1.tar.gz` instead of the `.deb`, extract all its files into a dedicated folder, for example `~/Applications/HopDrop`. Open that extracted folder in Files, right-click an empty area, choose **Open in Terminal**, then run:

```bash
./run-hopdrop
```

Here `./run-hopdrop` must be run **from the extracted folder containing both `run-hopdrop` and `HopDrop.Linux`**. Keep the extracted files together. The portable build also runs as your normal user, without `sudo`.

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

## What is the `.deb`?

A `.deb` is a Debian-format installation package, used by Linux Mint, Ubuntu and Debian. It contains the app files plus metadata such as its name, version, CPU architecture and required system packages. `apt` installs HopDrop into `/opt/hopdrop`, adds its menu launcher/icon, and resolves the declared dependencies. `amd64` means 64-bit Intel/AMD PCs. The `.deb` is the installer; the installed executable is `/opt/hopdrop/HopDrop.Linux`. See the [Debian packaging handbook](https://www.debian.org/doc/manuals/debian-handbook/packaging-system.en.html).

## How this Linux build was made

- **C# and .NET 10**, sharing HopDrop's existing transfer engine and local agent service with Windows.
- **Avalonia 12.1.4** for the Linux desktop interface, with HopDrop's Windows logo and matching dark colors.
- Cross-published on Windows for **`linux-x64`**, using `dotnet publish --self-contained true`. The package includes the .NET runtime, so users do not need to install .NET separately. Native system libraries are still required; see [Microsoft's publishing documentation](https://learn.microsoft.com/en-us/dotnet/core/deploying/).
- **Ubuntu 24.04 under WSL** for native Linux tests and `dpkg-deb` packaging. The `.deb` and portable archive come from the same published app files.

## Does working on Mint guarantee other Linux systems?

**No.** A successful Mint run validates that machine's configuration. This build targets 64-bit x86 Linux with glibc and the required native libraries. Other distribution versions, ARM processors, X11/Wayland sessions, permissions, and Wi-Fi/Bluetooth drivers can behave differently. Framework support also varies by platform/version; see [Avalonia's supported platforms](https://docs.avaloniaui.net/docs/supported-platforms).

Other recent Mint/Ubuntu configurations are reasonable test targets. Fedora or Arch users would need the portable archive and suitable system dependencies; their package managers do not install this `.deb` directly. ARM64 or musl-based systems such as Alpine require a separately built and tested package. The portable archive includes .NET but still depends on compatible Linux system libraries.

The reported Mint screenshot confirms that the package installed successfully. App startup, real transfers and wireless features on that physical machine remain separate checks.

The full feature matrix and repeatable checks are in [`Source/Linux/FEATURE_PARITY.md`](https://github.com/Abdallah-Tarek-prog/HopDrop/blob/v1.3.0-linux-preview.1/Source/Linux/FEATURE_PARITY.md). Source for these binaries is the release tag. `SHA256SUMS` accompanies the installer and archive.

To remove the installed preview, use `sudo apt remove hopdrop`. This does not delete received files or the user's HopDrop data/settings. The existing stable Windows/Android release is unchanged.
