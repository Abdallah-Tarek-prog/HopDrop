# HopDrop Windows

`HopDrop.Core` contains TLS identity, framing, pairing, discovery, transfers, and JSON stores. `HopDrop.Cli` is a headless peer, `HopDrop.Tests` contains xUnit tests, and `HopDrop.Desktop` is the Windows WPF app.

Desktop code map: `Program.cs` (entry point; Velopack install/update hooks run first), `App.xaml.cs` (startup, single instance), `Shell.cs` (what works without the window: tray icon, peer events, notifications, Bluetooth, Ask-before-receiving prompts, freeing the closed window), `Updates.cs` (automatic updates), `MainWindow.cs` (shell, theme, live transfers bar), `Pages.cs` (Send, Devices, Activity, Settings), `Controls.cs` (palette, settings rows, badges, formatting), `Bluetooth.cs` (drives Windows' Bluetooth File Transfer wizard), `Platform.cs` (preferences, firewall, Send to, toasts), `Dialogs.cs` (number match, input, the big pairing QR), `Agents.cs` (AI agents: the pipe the running app serves, the MCP server behind `--mcp`, and `agent …` commands; spec 7.1).

The desktop uses the brand blue accent in light and dark (Settings → Appearance: Windows setting, Light or Dark) on a solid themed background. Pages are scrolled by the NavigationView's own scroll viewer — don't wrap a page in another `ScrollViewer`, it swallows mouse-wheel and touchpad scrolling. Re-render the visual review set with:

```powershell
Source/Windows/HopDrop.Desktop/bin/Release/net10.0-windows10.0.19041.0/HopDrop.exe --data Source/Windows/out/snapdata --render-pages Source/Windows/out/screens
```

(`--data` keeps it away from your real settings; put `{"CloseToTray":true,"Theme":"system","Agents":true}` in that folder's `desktop.json` to see the AI agents setup.)

From the repository root:

```powershell
dotnet build Source/Windows/HopDrop.sln -c Release -m:1
dotnet test Source/Windows/HopDrop.sln -c Release -m:1
powershell -File Source/Windows/build.ps1 -Test
powershell -File Source/Windows/build.ps1 -E2E
powershell -File Source/Windows/build.ps1 -DesktopE2E
powershell -File Source/Windows/build.ps1 -Publish
```

`-Store` makes the Microsoft Store packages (`Windows App/Store`: `HopDrop_<version>_x64.msix`, `_arm64.msix` and the `.msixbundle` to upload); `-Store -Install` also installs the x64 one on this PC for testing (Developer Mode). Identity values come from `Store/identity.json`; the whole Store procedure is in `Store/README.md`.

`-Publish` makes, for x64 and arm64: the portable `Windows App/HopDrop.exe` / `HopDrop-arm64.exe`, and Velopack installers in `Windows App/Installer` (`HopDrop-win-Setup.exe`, `HopDrop-win-arm64-Setup.exe`, plus `*.nupkg`, `releases.*.json`, `assets.*.json`, `RELEASES*`). It needs the `vpk` tool (`dotnet tool install -g vpk --version 1.2.161`, matching the `Velopack` package). To ship an update, raise `<Version>` in `HopDrop.Desktop.csproj`, publish, and upload everything in `Windows App/Installer` to a GitHub release (for example `vpk upload github --repoUrl <repo> --publish --releaseName <version> --tag v<version> --token <token> -o "Windows App/Installer"`). Installed copies find it within a day; the repository's releases must be public for that to work.

Use `-Isolated` with the build script when the shell cannot write to user NuGet or temp folders. It keeps build caches in the gitignored `.scratch` directory. The E2E scripts use separate gitignored data and receive folders under `Source/Windows/.e2e`.

Desktop arguments: `HopDrop.exe [--minimized | --background] [--send <files...>] [--pair] [--receive-bluetooth] [--page send|devices|activity|settings] [--data <dir>]`; for AI agents `HopDrop.exe --mcp [--data <dir>]` (MCP over standard input/output) and `HopDrop.exe agent devices|send|status|recent|wait …` (JSON; `agent help` lists the options). Both answer through the running app and start it in the tray if needed; they need Settings → AI agents on. `--background` starts the hidden receiver (no window, no tray icon); the window is created only when HopDrop is opened again, and quitting with Settings → Receive in the background on closes the window but keeps that receiver running. A second launch with the same data directory forwards arguments over a named pipe. For laptop testing, `--port <n>` and `--receive-dir <dir>` are available. `--render-pages <dir>` writes light and dark PNGs for Send (with a sample folder and file), Devices, Activity, Settings, the AI agents part of Settings (with a sample live transfer) and number match, then exits. The Bluetooth helper logs its steps (no file contents) to `%LOCALAPPDATA%\HopDrop\logs\bluetooth.log`. `-DesktopE2E` drives a hidden Desktop peer and the CLI through QR pairing, number match, transfers both ways, and AI agents (an MCP client and the `agent` command: devices, a folder send, an unknown device, waiting for a file, recent transfers); it prints PASS/FAIL for each scenario. TLS handshakes require running outside the restricted sandbox on this PC.

Run CLI commands with `dotnet Source/Windows/HopDrop.Cli/bin/Release/net10.0/HopDrop.Cli.dll`. Common options are `--data <dir>`, `--receive-dir <dir>`, `--port <n>`, `--name <text>`, `--auto-confirm-sas`, and `--ask accept|decline` (turns on Ask before receiving and answers every request). Testing aid: `--drop-after <bytes>` drops the first send connection after that many bytes, to exercise resuming. Commands: `serve`, `qr`, `pair-qr <uri>`, `pair-sas <ip[:port]>`, `send <deviceId|ip[:port]> <files or folders...>`, `devices`, `nearby`, and `unpair <deviceId>`. `qr` listens until Ctrl+C; `serve --show-qr` also shows a pairing URI. Use `--qr-address 127.0.0.1` for loopback tests.

The CLI prints SAS numbers and QR URIs only for interactive pairing. Treat a displayed QR URI as a temporary pairing credential.

NuGet packages resolved for tests: `Microsoft.NET.Test.Sdk 17.14.1`, `Microsoft.CodeCoverage 17.14.1`, `Microsoft.TestPlatform.ObjectModel 17.14.1`, `Microsoft.TestPlatform.TestHost 17.14.1`, `Newtonsoft.Json 13.0.3`, `System.Collections.Immutable 8.0.0`, `System.Reflection.Metadata 8.0.0`, `xunit 2.9.3`, `xunit.core 2.9.3`, `xunit.assert 2.9.3`, `xunit.analyzers 1.18.0`, `xunit.abstractions 2.0.3`, `xunit.extensibility.core 2.9.3`, `xunit.extensibility.execution 2.9.3`, and `xunit.runner.visualstudio 3.1.4`. Only `Microsoft.NET.Test.Sdk`, `xunit`, and `xunit.runner.visualstudio` are direct references.

Desktop direct NuGet packages: `WPF-UI 4.3.0`, `QRCoder 1.8.0`, `Microsoft.Toolkit.Uwp.Notifications 7.1.3`, and `Velopack 1.2.161`. Transitive desktop packages: `WPF-UI.Abstractions 4.3.0`, `System.Drawing.Common 6.0.0`, `Microsoft.Win32.SystemEvents 6.0.0`, `Microsoft.Win32.Registry 4.7.0`, `System.Reflection.Emit 4.7.0`, `System.Security.AccessControl 4.7.0`, `System.Security.Principal.Windows 4.7.0`, `System.ValueTuple 4.5.0`, and `Microsoft.NETCore.Platforms 3.1.0`. Self-contained publish uses the .NET 10 Windows runtime packs (x64 and arm64).
