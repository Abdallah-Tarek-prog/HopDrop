# Microsoft Store submission 1.2.0.0 — every choice and what it means

Partner Center → HopDrop (Store ID 9N42KCXP85PD) → Submission 1 (draft started 2026-10-06, finished 2026-10-21).
"Was" = what the draft already had before 2026-10-21 (set by you or Microsoft's default). "Now" = what it is after my pass.

## Pricing and availability

| Setting | Now | Was | What it means |
|---|---|---|---|
| Markets | All worldwide markets (240) | same | Anyone in any country can get HopDrop. |
| Future markets | On | same | Countries Microsoft adds later get HopDrop automatically. |
| Audience | Public | same | Everyone can see it. "Private" would show it only to testers you list by email. |
| Discoverability | Available and discoverable | same | It shows up in Store search and browsing, not only by direct link. |
| Release | As soon as possible | same | It goes live as soon as Microsoft passes it (no fixed date). |
| Stop acquisition | Never | same | People can keep downloading it; no end date. |
| Price | 0 (Free) | same, saved now | Free. The currency shows EGP because your account is in Egypt; 0 is the same everywhere. |
| Free trial | None | same | Not needed for a free app. |
| Sale pricing | None | same | No discounts (nothing to discount). |
| Organisations, online | On | same | Companies and schools can hand it out through their Store management. |
| Organisations, offline | Off | same | No offline install package for IT departments; can be turned on later. |

## Properties

| Setting | Now | Was | What it means |
|---|---|---|---|
| Category | Productivity | same | Where HopDrop is listed in the Store. |
| Secondary category | Utilities + tools | same | A second place it can show up. |
| Uses personal information? | Yes | same | Files and text you send can contain personal data, and nearby devices see your device name. "Yes" is the honest, safe answer; it only means a privacy policy is required. |
| Privacy policy | Policy text, pasted in (= `PRIVACY.md`, updated 21 Oct) | the 6 Oct text | Shown on the Store page. **Changed:** PRIVACY.md was out of date. It now also covers text and clipboard sharing, the PC's direct link and Bluetooth. Same text in the repo and in the Store. |
| Website | github.com/Abdallah-Tarek-prog/HopDrop | empty | **Added.** A "Website" link on the Store page. |
| Support contact | …/HopDrop/issues | empty | **Added.** Where people report problems (GitHub Issues, already on). |
| Mixed-reality boxes | none ticked | same | Not a VR/HoloLens app. The "Seated + standing" dot underneath is ignored when no headset box is ticked. |
| "Purchases outside the Store" | Off | same | HopDrop sells nothing. |
| "Tested to meet accessibility guidelines" | **Off** | On | **Changed.** Ticking it tells Microsoft and customers we checked it with accessibility tools. We never did, so leaving it on would be a false claim. Can go back on after a real check. |
| Install to other drives | On | same | People can install HopDrop on a second drive or a USB disk. |
| OneDrive backups of app data | On | same | Windows may back up HopDrop's settings to your own OneDrive. The device key inside is encrypted for your Windows account (Windows' protected storage), never stored in plain form. |
| Record and broadcast clips | On | same | Windows' Game Bar recording is allowed. Microsoft says it only matters for games, so it has no effect here. |
| Pen and ink / generative AI | Off | same | HopDrop has neither. (The AI-agents bridge lets your own AI assistants use HopDrop; HopDrop itself generates nothing.) |
| Minimum memory | 300 MB | same | Store shows it; PCs with less get a warning. Every Windows 10/11 PC has more. |
| Minimum DirectX | Version 10 | same | Every Windows 10/11 PC meets it. |
| Minimum video memory | **Not specified** | 1 GB | **Changed.** Many laptops with built-in graphics report less than 1 GB, so the Store would have warned them and blocked their reviews, though HopDrop runs fine there. |
| Bluetooth LE (minimum and recommended) | **Off** | On | **Changed.** HopDrop doesn't use Bluetooth LE. Bluetooth sends go through Windows' own classic Bluetooth transfer, and they're optional. PCs without Bluetooth LE would have been warned for nothing. |
| Mouse (minimum and recommended) | On | same | Left as you had it. Touch-only tablets get a warning, though HopDrop also works by touch. Say if you want it moved to "recommended" only. |

## Age ratings

| Setting | Now | Was | What it means |
|---|---|---|---|
| Questionnaire | Kept (answered 6 Oct) | same | 1.2.0 changes nothing the questions ask about (no chat with strangers, no purchases, no ads, no violence), so there was no need to retake it. |
| Result | 3+ / Everyone everywhere (IARC, PEGI 3, ESRB Everyone, USK 0, Microsoft 3+) | same | Anyone of any age can install it, and family settings won't block it. |

## Packages

| Setting | Now | Was | What it means |
|---|---|---|---|
| Old packages | Removed | HopDrop 1.0.0.0 bundle + a duplicate 1.0.0.0 arm64 file (Microsoft flagged the duplicate as an error) | The draft from 6 Oct still had version 1.0.0.0. People would have got an old version. |
| New package | `HopDrop_1.2.0.0.msixbundle` (157.4 MB, PC + ARM laptops in one file): **Validated, saved** | none | You uploaded it (this browser can't pick files from your disk). Built from commit 41c1a0c. A second, broken copy of the upload ("null Bytes, Paused", left when the browser panel closed) was deleted; it was blocking Save. |
| Device families | Windows 10/11 Desktop (version 1809 and later); "let Microsoft decide future device families" on | default | Offered to PCs and laptops only, not Xbox, HoloLens or Surface Hub. |
| "Full trust" warning | Expected | same | Every normal desktop app needs "runFullTrust". It is explained to Microsoft under Submission options. |

## Store listing (English, United States)

| Setting | Now | Was | What it means |
|---|---|---|---|
| Languages | US English only | 4 English listings (Australia, South Africa, UK, US), all empty | **Changed.** The app is US English. Microsoft shows the US page to everyone without their own, so one listing covers all. |
| Description | Files, folders and text between PC and Android; pair once by QR; direct link on supported Wi-Fi; 6 bullets; free and open source, collects nothing | empty | The main text on the Store page. Every claim was checked against the app. The "coming Linux app" line was left out: a Store page shouldn't promise future products. |
| What's new | Blank | blank | Microsoft says to leave it blank on a product's first submission. |
| Product features | 10 short bullets (send files/text, pair once, no cloud, direct link, Share menu, several devices, clipboard, sends wait, encrypted, open source) | empty | The bullet list on the Store page. |
| Screenshots | 4 desktop pictures, 1375×975, in this order with captions: 1 Send ("Pick files, folders or text, then choose a device"), 2 Pair ("Pair a phone once by scanning a QR code"), 3 Send to several, dark ("Send to several devices at once"), 4 Activity, dark ("Everything you sent and received, by day") | none | You uploaded them; I put them in order and added the captions. The first one is the picture people see first. Made from sample data: no real paths, names or files. |
| Short description | "Send files, folders and text between your PC and Android phone, straight from one to the other. Private, fast, no cloud." | empty | The line at the top of the page. |
| Keywords | file transfer, send files to phone, Android, share files, Wi-Fi Direct, clipboard, QR pairing | empty | Store search terms (7 allowed). |
| Copyright | © 2026 Abdallah Tarek | empty | Shown on the page. |
| Developed by | Abdallah Tarek | empty | Shown on the page. |
| Additional license terms | GPL v3 note + link to the source | empty | HopDrop is GPL v3. This tells buyers their rights under it, next to Microsoft's standard terms. |
| Logos, trailers, Xbox art | Package logos | same | The Store uses the logos inside the package (black tile). Nothing extra is needed. |

## Submission options and tester notes

| Setting | Now | Was | What it means |
|---|---|---|---|
| Publishing | As soon as it passes certification | same | Goes live right after Microsoft approves. The other choice is to hold it until you press "Publish now". |
| Notes for certification (private) | How to try HopDrop, why it needs full trust, network use | empty | Only Microsoft's testers see this. It explains that a full test needs the Android app, why HopDrop is a desktop app (tray, background receiving, port 7410, Wi-Fi Direct), and that it only uses the local network. |
| runFullTrust justification | "HopDrop is a normal desktop app (WPF, .NET)… needs full trust to run a background receiver and tray icon, show notifications, listen on TCP/UDP port 7410 for paired devices…, save received files in the folder the user picks, and open a Wi-Fi Direct link…" (441 of 500 characters) | empty | Microsoft must approve this "restricted capability" before HopDrop can be listed. Every ordinary desktop app needs it; the text says why. |

## Result

- **2026-10-21: submitted for certification** on your go-ahead. Partner Center status: "In certification" (Microsoft: usually a few hours, up to 3 business days). It publishes as soon as it passes; you get an email either way.
- While it's in certification you can still cancel it (Partner Center → HopDrop → Cancel certification), but not edit it.
