# Product

<!-- impeccable:product-schema 1 -->

## Platform

adaptive

Native apps share one brand and one protocol, each following its own OS's conventions: Android (Kotlin, Jetpack
Compose, Material 3; Android 8+) in `Source/Android`, and Windows (WPF on .NET 10, Windows 10 and 11, x64 and ARM; installer
and Microsoft Store builds) in `Source/Windows`. The Linux frontend in `Source/Linux` uses Avalonia and the shared .NET engine;
Linux Mint is the intended physical test target, with WSL Ubuntu for development checks. There is no iOS app.

## Users

Everyday people moving photos, videos, documents, folders and text between their own phone and PC (owner, 2026-10-19). They
are not technical: they should never need to know about IP addresses, networks or certificates to succeed. The owner himself
(a student and developer) also sends large project folders and datasets, so heavy use must stay fast and fully featured.

## Product Purpose

HopDrop sends files, whole folders and text straight from one device to another: phone ↔ PC, PC ↔ PC, phone ↔ phone. Over the
shared Wi-Fi or hotspot, or, between a phone and a PC with no shared network, over the PC's own direct link. No cloud, no
accounts, no mobile data. Success: pair once, then any send takes the fewest taps and just arrives.

## Positioning

Checked 2026-10-19 against the vendors' own pages (sources below):

- **No shared network needed (phone ↔ PC).** The PC hosts its own Wi-Fi Direct network and the phone joins it by itself.
  Google's Quick Share for Windows, Microsoft's Phone Link and LocalSend all ask for both devices on the same network.
- **No account.** Quick Share asks for a Google sign-in for full functionality; Phone Link asks for the same Microsoft account on
  the PC and the phone. HopDrop has none (LocalSend has none either).
- **Pair once, trusted afterwards.** Only paired devices can send; each device's identity is pinned. LocalSend finds anyone on
  the network with no pairing (an optional PIN).
- **Open source (GPL-3.0), nothing leaves your devices.** Shared with LocalSend; not true of Quick Share or Phone Link.
- **Fewest taps.** Paired devices in Android's share sheet, a Quick Settings tile, clipboard sharing both ways, send-when-it's-back
  queue, auto-backup of phone folders to a PC, sending to several devices at once.
- Wider reach than Phone Link: Android 8+ and Windows 10 (Phone Link file sharing needs Windows 11 and Android 10+).
- **Common ground** (show it, don't hide it): every product here sends files and text between Android and Windows, finds nearby
  devices, and shows progress. LocalSend also covers iOS, macOS and Linux; Quick Share comes preinstalled on many Android phones.

Sources: [Quick Share for Windows (Google)](https://support.google.com/android/answer/13801258?hl=en),
[Phone Link requirements and setup (Microsoft)](https://support.microsoft.com/en-us/topic/phone-link-requirements-and-setup-cd2a1ee7-75a7-66a6-9d4e-bf22e735f9e3),
[Phone Link file sharing (Microsoft)](https://support.microsoft.com/en-US/Windows/apps/phonelink/seamlessly-transfer-content-between-your-devices),
LocalSend: secondary reviews only ([TechSpot](https://www.techspot.com/news/113367-localsend-like-airdrop-actually-works-everywhere.html),
[Tecmint](https://tecmint.com/localsend-share-files-linux-windows)); confirm against localsend.org before quoting it publicly.

## Operating Context

- A phone in one hand, often mid-task in another app (gallery, files, browser): sharing starts from Android's share sheet, the
  Quick Settings tile or the launcher shortcut as often as from HopDrop itself.
- A PC at a desk: HopDrop usually sits in the tray or runs hidden as a receiver; sending starts from drag and drop, Explorer's
  Send to / Share, the tray menu, or the window.
- Home Wi-Fi, a phone hotspot, a café or office network that blocks devices, or no network at all.
- Transfers range from one photo to thousands of small files (a 7,200-file dataset folder) or multi-GB videos.
- Notifications and toasts on both ends carry most of the feedback; the app window is often closed.

## Capabilities and Constraints

**Every current feature stays (owner, 2026-10-19: "I don't want to lose a feature; on the contrary").** The authoritative list
is `Source/SPEC.md` (requirements R1–R23, sections 6 and 7). In short:

- Phone: tabs Send · Activity · Devices, Settings; pick files, photos, a folder or text; select several devices; Connect to an
  offline PC's direct link; per-device progress and Cancel; Activity grouped by day with search, filter and photo previews;
  pairing by QR scan, number match or IP address; tour (7 pages) and What's new; share-sheet targets; Quick Settings tile;
  launcher shortcuts; Send clipboard; auto-backup (several backups, filters, time window, charger rule); receive in the
  background; choosable receive and backup folders; ask before receiving; trusted devices; System / Light / Dark and colour
  palettes (HopDrop, Ocean, Forest, Berry, Midnight, Wallpaper); in-app updates from GitHub.
- PC: window + tray; Send · Devices · Activity · Settings; pairing code shown until a phone is paired; drag and drop, Explorer
  Send to and Share; text dialog, tray "Send clipboard to", optional "Share what I copy"; send-when-back queue; several devices
  at once; direct link on/off; backups received; Bluetooth handoff to Windows' own wizard; AI agents (MCP / command line, off by
  default); start with Windows, receive in the background.
- Protocol and behaviour don't change in a redesign: pairing rules, trust, file names, notifications' content.
- Language: **English by default**, with persisted **Arabic** selection, translated text and right-to-left layout.
- Size and disk matter to the owner; no heavy new libraries without asking.
- Undecided: tablet / large-screen layout for the phone app.

## Brand Commitments

- Name **HopDrop**. Keep the current logo (owner: "I love it"): `Source/Android/res/drawable/ic_logo.xml`, launcher
  foreground `ic_launcher_foreground.xml` / monochrome `ic_launcher_monochrome.xml` on a black tile, Windows
  `Source/Windows/HopDrop.Desktop/Assets/HopDrop.ico` and Store assets from `Source/Windows/Store/make-assets.ps1`.
- The Windows icon's black background gets rounded corners like the Android launcher icon (owner, 2026-10-19).
- Voice: plain, short, friendly words; bullets over paragraphs; never technical jargon in the main path.

## Evidence on Hand

- Measured speeds (2026-10-19, `TODO.md`): 7,200 small files laptop → phone in 9 min 47 s; a 50 MB file at ~2.3 MB/s on a
  2.4 GHz router. No published benchmark comparing HopDrop with other products: don't claim "faster than X".
- No user testimonials, reviews, download counts or press exist: never invent them.
- Real screens: phone screenshot tests (`gradlew -p Source/Android recordRoborazziDebug` → `Source/Android/screenshots/`), PC
  page renders (`HopDrop.exe --data <dir> --render-pages <dir>`).

## Product Principles

1. **It just arrives.** Pair once; after that, sending is one or two taps from wherever the user already is.
2. **Simple first, everything reachable.** Everyday people see only what they need; every power feature stays one step away,
   never removed.
3. **Honest and private.** Say plainly what is happening and why something failed; nothing leaves the user's devices.
4. **Feels native on each device.** Material on Android, Windows 11 on the PC, one recognisable HopDrop across both.
5. **Fast at any size.** One photo or seven thousand files: progress stays clear and the app never feels stuck.

## Accessibility & Inclusion

- Large text (Android font scale up to at least 1.5×, Windows text scaling) must never cut labels; screen readers announce
  modals and progress; System Back always works; Remove animations is honoured.
- Right-to-left readiness for the Arabic release.
