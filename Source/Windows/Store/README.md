# Putting HopDrop on the Microsoft Store

The Store version is the same Windows app, packed as an **MSIX package**: a file that tells Windows what to set up when the app is installed. Microsoft signs it, hosts it and updates it, so people get no "unknown publisher" warning and HopDrop can appear in Windows' **Share** menu.

## 1. What changes in the Store version

| | Installer / portable | Store |
|---|---|---|
| File Explorer | right-click → Send to → HopDrop (turned on in Settings) | right-click → **Share → HopDrop** (always there) |
| Start at sign-in | Run entry in the registry | a **startup task** Windows manages (Task Manager → Startup apps) |
| Firewall | "Allow HopDrop" button, needs admin | opened at install, closed at uninstall, **no admin prompt** |
| Updates | Velopack, from GitHub Releases | the **Store** |
| AI agents' command | full path to `HopDrop.exe` | `hopdrop.exe` (packaged programs can't be started by their path) |
| Signing | unsigned | Microsoft signs it |

## 2. What was built (done)

- `Store/AppxManifest.xml` — the package description: name and publisher (filled in from identity.json), the Share target, the startup task, the `hopdrop.exe` command, the notification activator, the two firewall rules (TCP and UDP 7410), and the capabilities (network, and *runFullTrust* — "this is a normal desktop app").
- `Store/make-assets.ps1` — draws every logo size Windows asks for (Start, taskbar, Store, 100–400 % scaling): the vector logo on a white tile, so no images live in git.
- `Store/identity.json` — the app's Store identity, with the real values from Partner Center (AbdallahTarek.HopDrop).
- App code (`HopDrop.Desktop/Store.cs` and small changes): detects that it's the Store version; turns a Share into "add these files to the Send list"; uses the startup task for "Start with Windows" / "Receive in the background"; shows the Share menu instead of "Send to" in Settings; leaves updates to the Store; gives agents the `hopdrop.exe` command.
- `build.ps1 -Store` — publishes the app for PCs (x64) and ARM laptops (arm64), adds the manifest and logos, builds the logo index (`resources.pri`), packs `HopDrop_<version>_x64.msix` and `_arm64.msix`, and bundles both into **`Windows App\Store\HopDrop_<version>.msixbundle`** — the file you upload.
- `build.ps1 -Store -Install` — also installs the x64 build on this PC for testing (needs Windows' Developer Mode; no signing needed).

## 3. What you do in Partner Center (once)

1. Open **storedeveloper.microsoft.com** → sign in with a Microsoft account → **Individual**. Registration is free; you verify yourself with an ID photo and a selfie.
2. In **Partner Center → Apps and games → New product → MSIX or PWA app**, reserve the name **HopDrop**.
3. Open the app → **Product management → Product identity**. Copy three values into `Source/Windows/Store/identity.json`:
   - `Package/Identity/Name` → `"Name"`
   - `Package/Identity/Publisher` (starts with `CN=`) → `"Publisher"`
   - `Package/Properties/PublisherDisplayName` → `"PublisherDisplayName"`
4. Build: `powershell -File Source/Windows/build.ps1 -Store`.
5. **Start your submission** and fill in:
   - **Pricing and availability:** Free; all markets (or pick).
   - **Properties:** category *Productivity* (or *Utilities & tools*); privacy policy URL = the `PRIVACY.md` link on GitHub (works once the repository is public).
   - **Age ratings:** answer the questionnaire honestly (no ads, no purchases, no chat with strangers; files go only to the user's own paired devices).
   - **Packages:** upload `Windows App\Store\HopDrop_<version>.msixbundle`.
   - **Store listing:** description (below), at least one screenshot (1366×768 or larger: maximise the window, Win+Shift+S), and the search terms.
   - **Submission options → restricted capabilities:** explain *runFullTrust*: "HopDrop is a desktop app. It receives files in the background, shows a tray icon and notifications, and listens on the local network."
6. **Submit.** Certification usually takes one to three working days; you get an email either way.

Suggested listing text:
- **Short description:** Send files and whole folders between your phone and PC over your own Wi-Fi. Private, fast, no cloud.
- **Description:** HopDrop moves files between your Windows PCs and Android phones over your own network — no accounts, no cloud, no mobile data. Pair once with a QR code, then send files or whole folders both ways with live progress. Right-click any file → Share → HopDrop. Everything is encrypted, and only devices you paired can send to you.

## 4. Every release

1. Raise `<Version>` in `HopDrop.Desktop.csproj` (the Store needs a higher number each time; the package version gets a `.0` added).
2. `build.ps1 -Store`.
3. Partner Center → the app → **Update** → Packages → upload the new `.msixbundle` → Submit. Installed copies update by themselves after certification.

## 5. Good to know

- Store and installer versions shouldn't run side by side: both use the same data folder name and the same port, so the second one hands its work to the first.
- A Store app's own writes to `%APPDATA%` go to a private copy inside the package; received files still go to the real `Downloads\HopDrop` (or your chosen folder).
- To remove the test install: Settings → Apps → HopDrop → Uninstall (or `Get-AppxPackage HopDrop.LocalTest | Remove-AppxPackage`).
