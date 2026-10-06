# HopDrop — Product and Protocol Spec

Status: approved direction from the owner, 2026-09-29. This file is the single source of truth for the app.
If code and this spec disagree, fix the code or raise it — do not silently change the spec.

**Name (owner decision, 2026-10-05):** **HopDrop** everywhere — what people see and every technical name: Android package
`com.hop.drop`, `hopdrop://pair` URIs, SAS labels (`HopDrop-SAS-…`), certificate subject `CN=HopDrop`, TLS target host, discovery key
`"hopdrop"`, data folders (`%APPDATA%\HopDrop`), default receive folders (`Downloads\HopDrop`, `Download/HopDrop`), `HopDrop.exe`, the
Velopack package id, the firewall rule, sign-in entry, pipes and mutexes, MCP server name `hopdrop`.

---

## 0. Rules for implementers

- **Scope per stage.** Only do the stage you were asked to do (section 9). Do not start other stages.
- **No git commits.** Do not run `git commit`, `git push`, or change git config. The organizer commits.
- **Never touch `Private signing key/`** except where a build script must *read* the keystore to sign.
  Never print, log, copy, or commit the keystore or its password. `.gitignore` blocks it.
- **v1 is gone.** The owner approved removing v1 on 2026-09-29; v2 is the only version. Do not reintroduce v1 files.
- **Android stays plain Java + platform APIs.** No Gradle, no AndroidX, no Kotlin. minSdk 26, targetSdk 35,
  compile against `android-36`. Third-party code only where section 8 allows it.
- **Windows is .NET 9 + WPF.** Third-party NuGet packages only where section 8 allows it.
- **Local only.** No cloud, no accounts, no telemetry, never route over mobile data (section 5).
- **Report honestly.** In your final message, list what you built, what you ran, what passed, what you could not
  test and why, and any deviation from this spec. Never claim something works if you did not run it.
- If the sandbox blocks something, work around it inside the repo (gitignored folders), or stop and report it.

### Toolchain

| Tool | Notes |
|---|---|
| JDK 17+ | `JAVA_HOME`, or any installed Temurin / Microsoft / Oracle JDK (`Source/Android/build.ps1` finds it) |
| Android SDK | `ANDROID_HOME`, or Android Studio's default `%LOCALAPPDATA%\Android\Sdk`; Gradle installs the platform it needs (android-37) |
| .NET SDK | 10.x (`dotnet` on PATH) for the Windows app; the Windows SDK (makeappx, makepri) for the Store package |
| Test phone | Realme 6 Pro (RMX2061), Android 11 / API 30 |

---

## 1. Requirements (owner decisions) — every item must be met

| ID | Requirement |
|---|---|
| R1 | **Three ways to connect:** (a) same Wi-Fi router, (b) hotspot in both directions (phone hosts, laptop joins; laptop hosts, phone joins), (c) Bluetooth via the operating system's own Bluetooth file transfer ("option a"). No Wi-Fi Direct. |
| R2 | **The receiver decides where files are saved**, on the phone and on the laptop. Editable in the UI, saved permanently, survives restarts/reboots. (Bluetooth exception: the OS saves those; the laptop's Windows wizard asks where.) |
| R3 | **Any device to any device.** Every device can send and receive. Phone↔laptop, laptop↔laptop and phone↔phone all use the same protocol, over local Wi-Fi, hotspot, and (after Stage 6) Bluetooth through HopDrop itself. |
| R4 | **Multi-select on the phone:** an in-app list of chosen files; "Add files" appends (never replaces); remove one file (✕); "Clear all"; a hint that the system picker multi-selects by long-press; a "Photos & videos" button with tap-to-select multi-pick; the Android Share sheet still works. |
| R5 | **Pair once, remembered on both sides.** Two pairing methods: (a) **QR** — a device shows a QR code, a phone scans it; (b) **number match** — both screens show the same 6-digit code, the user confirms on both. No PIN or trust code per send. Unpaired devices cannot send files. Paired devices are accepted automatically (no accept pop-up). |
| R6 | **Find devices by name** on the local network (needed by R3 and R5b). Manual "Add by IP address" fallback. |
| R7 | **Real Windows app:** window + tray icon, modern Windows 11 look, device list, pairing with QR, activity history, settings, drag & drop, Explorer "Send to", Windows notifications. ~100 MB is acceptable. |
| R8 | **Notifications look good on both ends.** Sender: what was sent, to which device, where it landed. Receiver: what arrived, from whom, with Open / Show in folder. Progress shows file, count, speed, time left, and Cancel. |
| R9 | **Hotspot both directions works;** transfers never go over mobile data; the laptop listens on all its local networks. |
| R10 | **Bluetooth handoff (option a) works and is honest:** the phone finds the system Bluetooth share target by itself (never a hard-coded package), shows real error reasons, and tells the user the PC must be waiting. The laptop app's **Receive via Bluetooth** opens Windows' Bluetooth File Transfer wizard (`fsquirt.exe`) and presses "Receive files" itself (fsquirt has no working receive/send switch — `-receive` only opens the first page; verified 2026-09-29), then presses Finish when files arrive and moves them into HopDrop's receive folder, with a toast and an Activity entry; it keeps waiting for more until the user closes the wizard (15 min idle limit). **Send via Bluetooth** opens the wizard on its send page. If a step can't be automated, the wizard is left to the user. **Later (Stage 6, option b):** HopDrop's own Bluetooth link on both platforms — same pairing, receive folder, progress and notifications as Wi-Fi, any device to any device. |
| R11 | **Phone receiving** (changed 2026-10-05 on the owner's request "receive notification even when in background"): the phone receives, and notifies, even when the app is closed. Settings has a **"Receive in the background"** switch, now **on by default** (it was off, so updating to 2.3.0 switches it on once; turning it off afterwards sticks), that keeps the receiver running with its ongoing notification. On first start HopDrop asks once to be exempted from battery optimisation; turning the switch on asks too. With the switch off, the phone listens only while the app is open, and a transfer that has started continues if the user leaves the app. **Laptop receiving:** whenever HopDrop runs, including hidden in the tray; "Start with Windows" starts it hidden at sign-in. Settings has a **"Receive in the background"** switch (default off): quitting then closes the window and tray icon but keeps a hidden receiver in the user's session, which also starts at sign-in; it shows toasts for arrivals and opens the window for a pairing request. |
| R12 | Both apps look polished and consistent: same structure (Send · Devices · Activity · Settings), same brand colors and semantic color tokens, light and dark themes with a **System / Light / Dark** choice in Settings. |
| R13 | **Live progress on both ends, in the app and in notifications:** direction, device, current file and k of N, bytes done of total, percent, smoothed speed, time left, and Cancel — for sending and receiving. |
| R14 | **Each device shows its own local IP addresses** (labelled Wi-Fi / Hotspot / Ethernet …) so the other side can pair by address. Nearby lists show every HopDrop device found, marking the ones already paired. |
| R15 | **AI agents** (owner request 2026-10-05): AI assistants on the PC (Claude Code, Claude Desktop, Codex, Cursor… through MCP; anything else through a command line) can list paired devices, send files and whole folders to them, check a transfer, list recent transfers, and wait for files from a device. They cannot pair, change settings or accept files. Off by default (Windows Settings → AI agents); only the signed-in Windows user can connect. Section 7.1. |

---

## 2. Architecture

```
Phone (Java)                         Laptop (.NET 9)
 ├─ UI (Activities, notifications)    ├─ HopDrop.Desktop (WPF UI, tray, toasts)
 ├─ ReceiveService (listener+discovery)├─ HopDrop.Cli (headless peer for tests)
 ├─ TransferService (outgoing)        └─ HopDrop.Core (protocol, identity, pairing,
 └─ core/ (protocol, pairing, SAS,        discovery, transfers, storage)
    sanitizer — pure Java)
                 TCP 7410 + TLS (mutual certs)  ·  UDP 7410 discovery
```

- Every device is both a **server** (listens on TCP 7410, answers discovery) and a **client** (connects out to send or pair).
- Default ports: TCP 7410 for connections, UDP 7410 for discovery. Tests may override the TCP port.

---

## 3. Protocol v2 (normative)

### 3.1 Identity
- On first run each installation creates an **EC P-256 key pair and a self-signed X.509 v3 certificate** (CN=HopDrop, ~30-year validity, random serial).
- **Device ID** = lowercase hex SHA-256 of the certificate's DER encoding (64 chars). Short form for display: first 8 hex chars, uppercase, as `AB12 CD34`.
- **Device name**: user-editable, 1–40 chars. Default: phone → `Settings.Global.DEVICE_NAME`, else `Build.MANUFACTURER + " " + Build.MODEL`; Windows → the computer name.
- Android: prefer an AndroidKeyStore key (alias `hopdrop_identity_v2`, PURPOSE_SIGN|VERIFY, digests NONE/SHA-256/SHA-384/SHA-512); the auto-generated self-signed cert is the identity cert. If this cannot do TLS client+server on the test phone, fall back to a software key + self-signed cert in app-private storage and document why.
- Windows: `CertificateRequest.CreateSelfSigned`, saved as `%APPDATA%\HopDrop\identity.pfx`; its random password is protected with DPAPI (CurrentUser) in `identity.key`. Note: SChannel cannot use `EphemeralKeySet` keys for a TLS server.

### 3.2 Transport and framing
- TCP, then **TLS 1.3 (1.2 allowed)**. **Both sides present their certificate** (server requires a client cert). At the TLS layer both sides accept any certificate; identity is checked right after the handshake (3.3).
- Frames: `u8 kind | u32 big-endian length | payload`.
  - kind `1` = JSON control message, UTF-8 JSON object, length 1..65536.
  - kind `2` = DATA, length 1..1048576.
  - Any other kind or length → `protocol_error`, close.
- Every JSON message has a `"type"` string. Unknown fields are ignored. Unknown `type` → `protocol_error`.
- **All hex strings on the wire are lowercase** (device ids, `sha256`, SAS commitments and nonces). Senders MUST emit lowercase; receivers SHOULD compare case-insensitively.
- Example: `{"type":"ping"}` → `01 00 00 00 0F 7B 22 74 79 70 65 22 3A 22 70 69 6E 67 22 7D`.

### 3.3 Hello (both directions, first message after TLS)
```json
{"type":"hello","proto":2,"id":"<64 hex>","name":"LOQ Laptop","platform":"windows","app":"1.0.0","paired":true,"features":["consent","folders","pages","resume"]}
```
- `features` (optional) lists the protocol extensions this build understands (3.9). A connection uses an extension only when
  **both** hellos list it; a missing list means none (the baseline below), so older peers keep working unchanged.
- The **client** sends hello first. The **server** replies with its own hello. `paired` = whether the sender of this hello has the peer in its paired list.
- Each side MUST check `hello.id == SHA-256(peer certificate DER)`. Mismatch → `identity_mismatch`, close.
- `proto` other than 2 → `unsupported_version`, close.
- **Pinning:** when a client connects to a known device (paired, or the id from a QR code), it MUST check the server certificate fingerprint equals the expected id *before* sending anything after hello; mismatch → close and report "This isn't the device you paired with".

### 3.4 Requests (client → server, after hello)

| Request | Allowed from | Reply |
|---|---|---|
| `{"type":"ping"}` | paired | `{"type":"pong"}` |
| `{"type":"pair_qr","token":"…"}` | anyone | `{"type":"pair_ok"}` or error |
| `{"type":"pair_sas"}` | anyone | SAS flow (3.5.2) |
| `{"type":"offer",…}` | paired | transfer flow (3.6) |
| `{"type":"unpair"}` | paired | `{"type":"ok"}`; server removes the client from its list |

Unpaired client sending `ping`/`offer`/`unpair` → `{"type":"error","code":"not_paired"}`, close.

Error message: `{"type":"error","code":"<code>","message":"<human text>"}`. Codes: `not_paired`, `identity_mismatch`,
`bad_token`, `token_expired`, `busy`, `rejected`, `cancelled`, `no_space`, `checksum_mismatch`, `io_error`,
`protocol_error`, `unsupported_version`, `timeout`, `user_declined`, `declined` (the receiver's user said no, or didn't answer, 3.9.1).
Receivers also record `disconnected` (the sender dropped and didn't come back, 3.9.4) in their own history; it is never sent.

### 3.5 Pairing

Both methods end with each side storing a **paired-device record**:
`{id, name, platform, pairedAt, lastAddresses[], lastSeen, alias?}`. Removing a device on one side sends a best-effort `unpair` to the other.

#### 3.5.1 QR pairing
- The **shower** opens "Pair with QR". It creates a one-time **token** (16 random bytes, base64url without padding, 22 chars), valid **5 minutes**, single use. Only one active token at a time. Closing the QR screen invalidates it. The QR is refreshed automatically on expiry.
- QR payload (a URI):
  ```
  hopdrop://pair?v=2&id=<64 lowercase hex>&n=<percent-encoded UTF-8 name>&pl=<android|windows>&p=<tcp port>&a=<ipv4>,<ipv4>...&t=<token>
  ```
  `a` = all of the shower's local private IPv4 addresses, best first (Wi-Fi/Ethernet with a gateway, then hotspot, then others).
- **Showing the code (both apps):** error correction level **M** (fewer, bigger squares than Q, still tolerant of glare) and a white quiet
  zone of at least 2 modules. Windows draws it as sharp vector squares at 232 DIP with a 4-module quiet zone; clicking it opens a 460 DIP
  copy. The phone draws one pixel per module and scales it up without smoothing.
- **Scanning (phone):** full-screen camera preview without stretching (centre crop), up to 1920×1080 analysis frames, codes read
  anywhere in the frame (two frames in three only the middle, the third the whole frame), pinch / double-tap / chip zoom up to 4× (so the
  phone can stay far enough from a laptop screen to focus), tap to focus, a scanning frame with corner brackets, a short vibration and
  an outline on success, plain-language messages for a different QR code or one from another version, a camera-permission screen
  with **Allow camera** (or Settings when Android won't ask again), and "Can't scan? Pair by comparing numbers". The camera is released
  while the scanner isn't on screen. After a scan the app shows "Pairing with <name>…" until it succeeds, or the reason with **Scan again**.
- The **scanner** parses it, connects to all addresses in parallel (first one whose certificate matches `id` wins), exchanges hello, sends `pair_qr` with the token.
- The shower checks the token (constant-time compare, unexpired, unused) → stores the scanner as paired → replies `pair_ok` → the scanner stores the shower as paired. Both screens show "Paired with <name>". The shower's QR screen closes.
- Wrong token → `bad_token`; expired → `token_expired`.

Example URI (parsers must accept it):
`hopdrop://pair?v=2&id=59eb3cb130074f7b1ddb99c8fe14f27e1dbca735bb3da82e1ff0305340a32419&n=LOQ%20Laptop&pl=windows&p=7410&a=192.168.1.5,192.168.137.1&t=AAECAwQFBgcICQoLDA0ODw`
→ id as given, name `LOQ Laptop`, platform `windows`, port 7410, addresses `[192.168.1.5, 192.168.137.1]`, token = bytes 0x00..0x0f.

#### 3.5.2 Number-match pairing (SAS)
Initiator **I** = client, responder **R** = server. `fpX` = raw 32-byte SHA-256 of X's certificate DER.
1. I → `{"type":"pair_sas"}`.
2. R: if another SAS pairing is in progress, or more than 5 SAS attempts in the last 10 minutes → `busy`. Else R picks random 32-byte `nR` and sends
   `{"type":"sas_commit","c":hex(SHA256("HopDrop-SAS-commit" ‖ fpR ‖ fpI ‖ nR))}`.
3. I picks random 32-byte `nI` and sends `{"type":"sas_nonce","n":hex(nI)}`.
4. R sends `{"type":"sas_reveal","n":hex(nR)}`.
5. I recomputes the commitment; mismatch → `protocol_error`, abort.
6. Both compute `code = BE_uint32(SHA256("HopDrop-SAS-code" ‖ fpI ‖ fpR ‖ nI ‖ nR)[0..3]) mod 1000000`, shown as 6 digits with a space: `219 471`.
7. Both UIs show: peer name, the code, "Does <peer> show the same number?" — **They match** / **Cancel**.
   Each side sends `{"type":"sas_confirm","ok":true|false}` when its user decides, and reads the peer's confirm concurrently.
   Pairing succeeds only when both sides said yes. Any no, or 120 s without both answers → both show "Pairing cancelled".
8. R must surface an incoming request: Windows → bring the window to front + toast; Android → dialog if the app is visible, otherwise a high-priority notification that opens it.

Strings are ASCII bytes; `‖` = concatenation. **Test vectors (both implementations must pass):**

| | v1 | v2 |
|---|---|---|
| fpI | `11` ×32 | `59eb3cb130074f7b1ddb99c8fe14f27e1dbca735bb3da82e1ff0305340a32419` |
| fpR | `22` ×32 | `6dcde155f1f900e157d25bb5c24b1a54d71ea35b78661ed9b1a8be3532c3d4f5` |
| nI | `33` ×32 | `90c25e389b9cdf641350c7572c1d030f051747d44809a6ec1243eb9227fc0c96` |
| nR | `44` ×32 | `433d46ffcc5cd2238a19974a19da7eb21770b0d908ed20ebb2b91ceb230b6d40` |
| commit | `6935596fda9b27748a1b439b2172499fbf95b23fc48787576c9def67405cf3b6` | `5e8269ab296d63bb36e4f46c832c56140eb138618809dc51c4de68652dc46bd5` |
| code | `219471` | `447251` |

### 3.6 File transfer (client = sender, server = receiver)
1. Sender → `{"type":"offer","transferId":"<uuid>","count":N,"totalBytes":T,"files":[{"i":0,"name":"IMG_2031.jpg","size":123456}, …]}`.
   `size` may be `-1` if unknown (then `totalBytes` is `-1` too).
2. Receiver (sender must be paired): if the free space in the receive folder < `totalBytes` + 16 MiB → `no_space`. Otherwise it accepts
   (after asking its user first when "Ask before receiving" is on and the sender isn't trusted, 3.9.1):
   `{"type":"accept","folder":"<display name of the receive folder, e.g. Downloads\\HopDrop>"}`.
   A receiver takes at most 10,000 files per offer; senders split bigger selections into several transfers.
3. For each file in order: `{"type":"file","i":k}`, then DATA frames, then `{"type":"file_end","i":k,"sha256":"<hex>"}`.
   Receiver writes to a temporary file in the receive folder, hashes while writing, and on `file_end` compares:
   match → move to the final unique name → `{"type":"file_ok","i":k,"savedAs":"<final file name>"}`;
   mismatch → `checksum_mismatch`, delete the temp file, close. If `size ≥ 0` and the byte count differs → `io_error`.
   The sender waits for `file_ok` before the next file. Zero-byte files send no DATA frames.
4. Sender → `{"type":"done"}`; receiver → `{"type":"done_ok","saved":N}`; close cleanly.
- **Cancel:** either side may send `{"type":"cancel"}` and close at any time. The receiver deletes the unfinished file (completed files stay).
  The sender MUST read control frames concurrently while sending DATA, so a cancel or error from the receiver is noticed at once.
- **Timeouts:** TCP connect 5 s per address; 30 s idle read timeout, reset by any frame; 130 s while waiting for a user (SAS, and
  "Ask before receiving" with senders that list `consent`).
- **Receiving files on Windows:** files from devices the user hasn't trusted get Windows' "downloaded from another computer"
  mark (Mark of the Web, `Zone.Identifier` with `ZoneId=3`), written to the temporary file before it gets its final name, so
  Windows, SmartScreen and Office warn before opening risky content. Trusted devices' files and Bluetooth arrivals: see 4.
- Multiple transfers may run at the same time (one connection each).
- Both sides report progress: current file, file k of N, bytes done/total, speed (smoothed), time left.
  Speed is an exponential moving average of half-second samples (70 % old, 30 % new); time left = remaining bytes ÷ smoothed speed.
  Progress is reported at most every 250 ms, plus once at the start of each file and at the end (`TransferMeter` on both platforms).

### 3.7 File names (receiver side, identical on both platforms)
- Keep only the last path segment (split on both `/` and `\`).
- Replace control chars (< 0x20) and `< > : " / \ | ? *` with `_`.
- Trim trailing dots and spaces.
- If the part before the first dot equals (case-insensitive) CON, PRN, AUX, NUL, COM1–COM9 or LPT1–LPT9 → prefix `_`.
- Empty, `.` or `..` → `file`.
- Max 180 UTF-16 code units, truncating the stem (never splitting a surrogate pair) and keeping the extension.
- Extension = from the last `.` only if that dot is not the first char (`.hidden` has no extension).
- Duplicates: `name (1).ext`, `name (2).ext`, … (`README` → `README (1)`, `.hidden` → `.hidden (1)`).

| Input | Saved as |
|---|---|
| `report.pdf` | `report.pdf` |
| `../evil.txt` | `evil.txt` |
| `C:\Windows\x.dll` | `x.dll` |
| `con.txt` | `_con.txt` |
| `COM1` | `_COM1` |
| `what?.txt` | `what_.txt` |
| `name. ` | `name` |
| `` (empty) / `..` | `file` |
| `تقرير 📁.pdf` | unchanged |
| `\u0001a.txt` | `_a.txt` |
| 250 × `a` + `.txt` | 176 × `a` + `.txt` |

**Folders** (`folders` extension, 3.9.2): an offered `path` is cleaned the same way part by part (split on `/` and `\`; empty
parts and `.` dropped; `..` becomes `_`; each part cleaned as a name; at most 32 levels), so a file can never land outside the
receive folder. The first part (the sent folder) gets a new name when it already exists there: `Photos`, `Photos (1)`, …

### 3.8 Discovery (UDP 7410)
- Message (UTF-8 JSON, ≤ 1200 bytes):
  `{"hopdrop":2,"type":"announce"|"query","id":"<64 hex>","name":"…","pl":"android|windows","p":7410,"rx":true}`
  (`rx` = currently able to receive).
- Send `announce` at start, on every network change, and periodically: every **5 s while HopDrop is on screen**, otherwise every
  **15 s (Windows)** or **20 s (Android)**. Send to multicast `239.255.74.10:7410` (TTL 1) on each local interface, plus
  `255.255.255.255:7410` and each interface's directed broadcast. The list of network interfaces is read once per network change
  (and at most every 2 minutes), not on every announcement.
- **Hidden name:** a device set to "Only my paired devices" sends `"name":""` (except while its pairing screen is open). Paired
  devices recognise it by id; others don't list it. Older versions ignore such announcements.
- On `announce` or `query` from another id: update `nearby[id] = {name, pl, p, address = UDP source IPv4, lastSeen}`. Reply with a unicast `announce` to a `query`, and to the first `announce` seen from a new id.
- Send `query` when a device list opens. Entries expire **45 s** after `lastSeen`. Ignore your own id.
- Discovery is only a hint. Every connection still verifies certificates; paired devices are pinned by id.
- Connecting to a paired device: try its discovered address and its stored `lastAddresses` in parallel; first certificate match wins; update `lastAddresses`.
- Android holds a `WifiManager.MulticastLock` only while HopDrop is on screen (the lock makes the Wi-Fi chip wake the phone for
  all group traffic on the network, which costs battery); in the background the chip filters it. All discovery sends run off the main thread, and each
  send (per destination, per interface) is independent: one refused interface must never stop the others or stop discovery starting.
- Windows: virtual adapters (Hyper-V, WSL, Docker, VMs) are announced on but never shown to the user, and are left out of
  QR codes unless the PC has no other address.

### 3.9 Extensions (used only when both hellos list them)

#### 3.9.1 `consent` — ask before receiving
- When the receiver asks its user first, it sends `{"type":"pending"}` (only to senders that list `consent`) before deciding, then
  `accept`, or `error` `declined`. The sender shows "Waiting for … to accept" and waits up to 130 s.
- Senders without `consent` give up after 30 s, so for them the receiver must answer within **25 s** (it declines after that).
- While waiting, the sender sends nothing; anything it sends (or the connection closing) means it cancelled, and the question closes.

#### 3.9.2 `folders`
- Offer entries may carry `"path":"Photos/2024"` (`/`-separated, relative): the file's folder inside a sent folder. The receiver
  recreates it under the receive folder (cleaning rules in 3.7). `savedAs` is then the relative path, e.g. `Photos/2024/beach.jpg`.
- Without `folders`, senders leave `path` out and the receiver gets the files flat, as before. Hidden/system files and links
  (which could loop or point outside the folder) are not sent.

#### 3.9.3 `pages` — big selections in one transfer
- When the `files` list doesn't fit in one control message (64 KiB), the offer carries the first part and `"more":true`, followed by
  `{"type":"offer_more","files":[…],"more":true|false}` until `more` is false. Indexes `i` continue across pages; `count` and
  `totalBytes` in the offer cover all pages.
- Without `pages`, a sender splits the selection into several transfers whose offers each fit in one message.

#### 3.9.4 `resume` — continue after a dropped connection
- Each offered file may carry `"k"`: its position in the whole transfer (left out when equal to `i`).
- When the connection drops during a transfer (connection reset or closed, or a timeout — not a cancel, a checksum or disk
  error, or an answer from the receiver), the sender reconnects up to **3 times** (after 2, 4 and 8 s) and offers again with the
  **same `transferId`**, listing only the files it hasn't had a `file_ok` for.
- The receiver keeps an interrupted transfer for **2 minutes**: the files already saved, the open half-received file with its
  running checksum, and its folder names. When the same sender comes back with the same `transferId`, it accepts with
  `"resume":{"done":[{"i":0,"savedAs":"…"}],"i":2,"offset":6291456}`: `done` = offered files it already saved (by `k`, name,
  size and folder — this covers a lost `file_ok`), `i`/`offset` = the half-received file and how many bytes it has.
- The sender skips `done` files and sends `{"type":"file","i":2,"offset":6291456}`, then the rest of that file from that byte (it
  still reads the earlier bytes to compute the whole-file SHA-256). An `offset` the receiver didn't offer → `protocol_error`.
- If the sender doesn't come back in time, the receiver deletes the half-received file and records `disconnected`.

---

## 4. Security notes
- Only paired devices can send or ping; unpaired devices can only attempt pairing, which needs the QR token or both users confirming matching numbers.
- The SAS commit-before-reveal order gives a man-in-the-middle about a 1-in-1,000,000 chance per attempt; the attempt limit keeps it there.
- Never log file contents, tokens, nonces or private keys. Logs may contain device names, ids and IPs.
- **Trusted devices:** pairing proves who a device is, not that its files are safe. By default Windows marks received files as
  downloaded (3.6) and the arrival notification offers no one-click **Open** for programs (.exe, .msi, .bat, .ps1, .lnk, …).
  Per device, **Trust files from this device** removes the mark and skips "Ask before receiving". Bluetooth arrivals have no
  device identity and are always marked. A receive folder on a drive that can't store the mark (FAT32/exFAT) is pointed out in Settings.
- **Ask before receiving** (off by default, both apps): files from devices that aren't trusted wait for the user's Accept.
- At most **32** connections are handled at once; more are dropped immediately, so a flood from the network can't exhaust the receiver.
- The Windows firewall rule allows only `HopDrop.exe`, only from the local subnet, on TCP and UDP 7410 (it replaces the older
  port-only rules that opened the port to every program).

---

## 5. Local-only networking

**Local interface** = up, not loopback, has a private IPv4 (10/8, 172.16/12, 192.168/16, 169.254/16), and is not the interface of a cellular or VPN network. This covers Wi-Fi client, hotspot/tethering, USB tethering and Ethernet.

- **Android client:** the destination must be inside a local interface's subnet. On the Wi-Fi client network's subnet → create the socket through that `Network`. On a hotspot/tethering subnet → a plain socket (kernel local routes). Anything else → refuse: "This device isn't on your local network". Debug builds may also allow 127.0.0.1 (for `adb reverse` tests) behind a developer flag.
- **Android server:** accept only if the accepted socket's local address belongs to a local interface.
- **Windows:** listen on `0.0.0.0:7410` (all interfaces). Accept only private or loopback source addresses. Discovery on every local interface.
- This is what makes both hotspot directions work (R9).

---

## 6. Android app

**Code layout:** `com.hop.drop` (activities, services, notifications), `com.hop.drop.core` (framing, messages, SAS, QR URI, file names, transfer state — pure Java, no `android.*`, so it can be tested on the desktop JVM), `com.hop.drop.net` (discovery, local interfaces), `com.hop.drop.store` (paired devices, settings, history).

**Screens** — Kotlin + Jetpack Compose with Material 3 (`src/com/hop/drop/ui`), hosted by `MainActivity.kt`; the engine stays plain
Java. Three tabs in a bottom navigation bar (**Send**, **Activity**, **Devices**); the top bar has the logo, a "Ready" / "Off" pill (tap →
this phone's name and addresses) and Settings (gear). Back from another tab returns to Send. The paragraphs below describe what each
screen holds; where they say "tab" for Settings, it is the full-screen Settings page.
- **Header (all tabs):** logo, "HopDrop", this phone's name, and a status chip ("Ready" / "Not receiving"; tap → why, and this phone's addresses).
  Below it, a **live transfer card** per transfer in either direction (R13): direction icon, "Sending 3 files to …" /
  "Receiving 3 files from …", current file and k of N, progress bar, "412.0 MB of 1.60 GB · 25%", "11.8 MB/s · 1 min 44 s left", **Cancel**.
  Finished transfers stay for a few seconds with their outcome.
- **Send:** Files card: list (type icon, name, size, ✕), **Add files** (system picker, multi-select, appends), **Folder**
  (`ACTION_OPEN_DOCUMENT_TREE`; the folder is **one item** — folder icon, name, "N files · size" counted in the background — and arrives
  as a folder; its files, except `.hidden` ones, are listed by `TransferService` when it sends, so thousands of files never travel
  between HopDrop's parts as separate entries), **Photos**
  (`MediaStore.ACTION_PICK_IMAGES` when available, else `ACTION_GET_CONTENT` image/video with multiple), **Clear all**,
  tip "In the file picker, long-press a file to select several". Send-to card: paired devices as rows (platform icon, name,
  live online dot, **Send**); tap → sends the selected files; the row shows live progress and the last result. Bluetooth card as a secondary action.
- **Devices:** **This phone** (name, visible/not receiving, each local address with its kind — Wi-Fi, Hotspot, … — and a copy
  button, **Show my QR code**); **Paired devices** (online now / last seen, paired date; tap → **Trust this device**, rename, remove; note that pairing is
  permanent until removed); **Pair a new device** (*Scan QR*, *Pair by IP*); **Nearby devices** (every device found, paired ones
  marked "Paired", others tap → number match; clear empty states for "not receiving", "no local network", "searching").
- **Activity:** live transfers under "Now", then the history grouped by day (Today, Yesterday, date) with a search field (file or device
  names) and an All / Received / Sent filter. Each row: a photo thumbnail when the transfer has one (else a direction badge), "From/To
  <device>", "first file + N more" (never a comma list), "N files · size" or the problem in plain words, and the time. Tap → the files,
  one per line (tap opens, long-press shares), size and time taken, where they were saved, **Share** and **Remove from list**;
  long-press a row → Share / Remove from list. Overflow menu → **Clear activity** (list only). Sent transfers keep where their first 50
  source files are, so they can be opened later while the phone can still read them.
- **Settings:** **Appearance** (System / Light / Dark); this phone's name; **Receive folder** (current place; **Change** via
  `ACTION_OPEN_DOCUMENT_TREE` with persisted read/write permission; **Use default**); **Receive in the background** (switch,
  default on, R11; turning it on requests the battery-optimisation exemption; a warning with **Allow background activity** while
  still restricted; a hint and button for Auto-launch on Realme/Oppo/Xiaomi-style phones); **Ask before receiving** (switch, default off);
  **Privacy** (who can see this phone's name: Everyone / Paired only); about (logo, version, short device id).

**Services**
- `ReceiveService`: foreground service type `connectedDevice` (needs `CHANGE_WIFI_MULTICAST_STATE`). Started when the app comes to the foreground; runs the TCP listener and discovery. When the app goes to the background it stops after any running transfer finishes — unless **Receive in the background** is on (R11), in which case it keeps running (and restarts after the app process is recreated). Low-importance notification "Ready to receive as <name>".
- `TransferService`: foreground service type `dataSync` for outgoing transfers (queue, one at a time; its notification stays until the
  last queued send ends). Cancel closes the connection at once. Android 15's six-hour daily limit for `dataSync` (`onTimeout`) stops it cleanly.

**Receive storage**
- Default: `Download/HopDrop` — MediaStore Downloads with `RELATIVE_PATH` and `IS_PENDING` on API 29+; on API 26–28 the public Downloads folder via `WRITE_EXTERNAL_STORAGE` (maxSdkVersion 28), requested when first needed.
- Custom: the SAF tree picked in Settings; write to `<name>.part` then rename; delete partial files on failure. If the permission was lost, fall back to the default and tell the user.

**Notifications** (channels: "Transfers in progress" = low, "Finished transfers" = default, "Files received" = high, so arrivals
pop up on screen, "Pairing requests" = high, "Requests to send you files" = high). Progress notifications update at most once a second.
Files from a sent folder are named once as "<folder> folder" in summaries ("Photos folder + 2 more"). Lists are never comma-separated:
one line summaries say "a.jpg and b.pdf" / "a.jpg + 3 more"; expanded lists put one name per line.
- Ask before receiving: "<device> wants to send you 3 files" · names and size · **Accept** / **Decline** (expires with the request);
  a dialog too while HopDrop is open.
- A dropped connection: "Reconnecting to …" / "Waiting for … to reconnect" until the transfer continues.
Copy rule: the title says what is happening and with whom, the collapsed line how far along, the expanded text adds the file and speed.
- Sending: "Sending 3 files to LOQ Laptop" · "412.0 MB of 1.60 GB · 1 min 44 s left" · header "25%" · expanded "IMG_2031.jpg (2 of 3)" +
  speed · progress bar · **Cancel** · small icon = up arrow.
- Receiving: same shape, "Receiving 3 files from …", small icon = down arrow. It replaces the receiver's "Ready to receive files ·
  Visible as <name> · <address>" notification while it runs.
- Sent: "Sent 3 files to LOQ Laptop" · "IMG_2031.jpg + 2 more · 1.7 MB" · expanded: the names one per line, "Saved in Downloads\HopDrop on LOQ Laptop", size and time taken.
- Received: "Received 3 files from LOQ Laptop" · names and size · expanded "Saved in Download/HopDrop" · **Open** (one file) / **Show files**.
- Failed / cancelled: "Couldn't finish sending to …" / "Receiving from … stopped" / "… cancelled" + the real reason in plain words
  (who cancelled, unreachable, no space, …) + how many files made it.
- A problem starting the receiver gets its own notification with the reason.

**Other**
- Share sheet (`SEND`, `SEND_MULTIPLE`) opens the Send tab with the files added. Shared text, and text typed in the Send tab's **Text** dialog, becomes a cached `.txt` served by a small in-app `ContentProvider` — no `file://` URIs leave the app. Its name is the text's first line, cut at a word to about 40 characters ("Link.txt" for a URL, "Text.txt" when nothing usable is left); `FileNames.forText` / `FileNames.ForText` apply the same rule on both platforms.
- Bluetooth (R10): pick the `ACTION_SEND` target whose package name contains `bluetooth`; if none, open the system chooser.
  Each use first explains: on the laptop click HopDrop → Receive via Bluetooth (it waits and saves into its receive folder), then pick the laptop in Android's list; much slower than Wi-Fi.
- **Look:** Material 3, flat cards on navy-tinted neutrals, Plus Jakarta Sans. Colour themes (Settings → Colour): **HopDrop** (orange
  `#C2410C` on light, `#FFB693` on dark, navy secondary), **Ocean**, **Forest**, **Berry**, **Midnight**, and **Wallpaper** (Material You,
  Android 12+), each in light and dark; with System / Light / Dark they apply instantly. Primary text and buttons meet 4.5:1 contrast.
  Subtitles stay short (one line, only where the title isn't enough). 48 dp touch targets, edge-to-edge, short (150–260 ms) motion.
- Permissions: INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, CHANGE_WIFI_MULTICAST_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_DATA_SYNC, FOREGROUND_SERVICE_CONNECTED_DEVICE, POST_NOTIFICATIONS, WRITE_EXTERNAL_STORAGE (maxSdkVersion 28), CAMERA (Stage 4 only), REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (background receiving).
- Build: Gradle (Android plugin 9.4, Kotlin 2.4, Compose BOM 2026.09), `compileSdk` 37, `targetSdk` 36, `minSdk` 26; release shrunk with R8 (about 2 MB).
  Version: versionCode 1, versionName 1.0.0. Output `HopDrop.apk` at the repo root, signed with the release key (CN=HopDrop) so updates install in place.
- Activity keeps the first 1,000 file names of a transfer (and the real count), and looks up where the first 50 were saved.

---

## 7. Windows app

**Solution** `Source/Windows/HopDrop.sln`:
- `HopDrop.Core` (net10.0): identity, protocol, pairing store, discovery, transfer engine, file names, settings. No UI.
- `HopDrop.Cli` (net10.0): headless peer for tests and automation — e.g. `serve`, `qr` (prints the pairing URI),
  `pair-qr <uri>`, `pair-sas <ip[:port]> [--auto-confirm]`, `send <deviceId|ip[:port]> <files or folders…>`, `devices`, `nearby`;
  options `--data <dir>`, `--port <n>`, `--name <text>`, `--receive-dir <dir>`, `--auto-confirm-sas`, `--ask accept|decline`,
  and the testing aid `--drop-after <bytes>` (the first send connection drops after that many bytes).
- `HopDrop.Desktop` (net10.0-windows, WPF): the app.
- `HopDrop.Tests`: automated tests (xUnit if NuGet is allowed, otherwise a console test runner that exits non-zero on failure).

**Desktop UI** (Windows 11 Fluent look, solid themed background, rounded cards; **System / Light / Dark** in Settings; brand blue accent,
including the navigation highlight; the window opens at a size that fits the screen's work area):
- **Live transfers bar (all pages, bottom of the window):** one row per transfer in either direction (R13): direction icon, "Receiving 3 files
  from …", file and k of N, progress bar, amount and percent, speed and time left, **Cancel**; outcome shown for a few seconds after.
  The tray tooltip shows the percentage. While the window is hidden or inactive, an incoming transfer also shows a Windows progress toast (Cancel button).
- **Send:** dashed drop zone ("Drop files or folders here", **Browse files**, **Add a folder**, **Text** — typed or pasted text, added as a `.txt` named after its first line), file list: a folder is **one row** (folder icon,
  name, "Folder · N files · size", where it is, remove), loose files one row each (type icon, name, size, where it is, remove);
  **Clear all**; paired device cards with live online status and **Send** (disabled until files are added); progress and result on the card.
  Bluetooth row: **Send via Bluetooth**.
- **Devices:** **This computer** (name, "Ready", each real local address with its kind and a copy button); **Pair a phone** (QR code, countdown);
  **Paired devices** (online now / last seen, platform, paired date, last address; **Trust files from this device**; rename, remove with confirmation; pairing is permanent until removed);
  **Nearby devices** (every device found; paired ones marked "Paired", others **Pair** by number match), **Refresh**, **Pair by IP address**.
  Lists update in place when discovery changes.
- **Activity:** grouped by day; direction/outcome icons; **Open** / **Show in folder**; Bluetooth arrivals listed as "from Bluetooth"; **Clear list**.
- **Settings** (Windows 11 settings rows: icon, title, one-line description, control on the right): General (this PC's name, appearance);
  Receiving (receive folder Change…/Open/Default, with a note when its drive can't mark files; "Ask before receiving"; "Close to the tray";
  "Receive in the background" (R11); Start with Windows); Privacy ("Who can see this PC's name": everyone / only paired devices);
  Windows integration (Explorer "Send to"; Windows Firewall status in plain words + **Allow HopDrop** with UAC, optional public networks);
  AI agents (R15: switch, off by default; when on, **Copy for Claude Code** / **Copy for Codex** / **Copy MCP settings (JSON)**);
  Bluetooth (**Receive**, **Send**, R10); About (version, device id, **Updates**: check now / restart to update, installed copies only).
- **Tray icon:** left-click opens the window; menu: Open HopDrop, Send files…, Pair a phone, Receive via Bluetooth, Send via Bluetooth, Quit HopDrop. With background receiving on, the last items are **Close HopDrop (keep receiving)** and **Quit and stop receiving**.
- **Toasts:** "Received 3 files from Realme 6 Pro" + names and size + "Saved in Downloads\HopDrop" + **Open** (one file; not for programs
  from devices that aren't trusted) / **Open folder**; clicking the toast shows the files in their folder. "Ask before receiving":
  "<device> wants to send you 3 files" with **Accept** / **Decline**, kept on screen until answered or expired;
  "Sent …" when the window isn't active; failures with the reason and how many files made it; Bluetooth arrivals; pairing request → opens the number-match dialog.
- **Number-match dialog:** peer name, the 6-digit code large, **They match** / **Cancel**.
- **Single instance:** a second launch forwards its arguments to the running instance (named pipe) and exits. Command line: `HopDrop.exe [--minimized | --background] [--send <files…>] [--pair] [--receive-bluetooth] [--page send|devices|activity|settings]`;
  for agents `HopDrop.exe --mcp` and `HopDrop.exe agent …` (7.1).
- Activity keeps the first 1,000 file names of a transfer (and the real count); files sent from a folder are recorded by their place in it.
- **Data:** `%APPDATA%\HopDrop\` (settings.json, devices.json, history.json, identity.pfx, identity.key, desktop.json for window/theme preferences); logs in `%LOCALAPPDATA%\HopDrop\logs`. Default receive folder `%USERPROFILE%\Downloads\HopDrop`.
- **App icon:** the HopDrop logo (from `Source/Android/res/drawable/ic_logo.xml`) as a multi-size `.ico`.
- **Memory:** the window is freed one minute after it's closed to the tray (it reopens in about half a second), so HopDrop waiting in the
  tray or in the background uses about 20–35 MB instead of about 200 MB.
- **Output** (`build.ps1 -Publish`): portable self-contained single-file `Windows App\HopDrop.exe` (x64) and `HopDrop-arm64.exe`; installers
  with automatic updates (Velopack) in `Windows App\Installer`: `HopDrop-win-Setup.exe`, `HopDrop-win-arm64-Setup.exe`, plus the release
  files to upload to GitHub Releases. The installed app checks GitHub Releases daily, downloads quietly, and installs when idle or on
  the next start; uninstalling removes its sign-in entry, "Send to" shortcut and notifications. The update source is the GitHub
  repository named in `Updates.cs`; installed copies can only see its releases if they are public (a private repository needs a token,
  which can't be shipped inside the app).

### 7.2 Microsoft Store version (owner decision 2026-10-05)

- Same app as an MSIX package built by `build.ps1 -Store` from `Source/Windows/Store/AppxManifest.xml` (identity from `Store/identity.json`,
  logos drawn from the vector logo by `Store/make-assets.ps1`). Unsigned for upload; the Store signs it.
- The manifest adds: the **Share target** (any file type, storage items) → the app starts with `--send <files>`; a **startup task**
  `HopDropStartup` → `--signin`, which becomes `--background` when only "Receive in the background" is on and `--minimized` otherwise; the
  **`hopdrop.exe`** execution alias (agents and their MCP setup use it, since packaged executables can't be started by path); a toast
  activator (COM, `-ToastActivated`); **firewall rules** for HopDrop.exe, TCP and UDP 7410, all profiles, added at install and removed at uninstall.
- In the package the app hides the "Send to" switch (it shows the Share menu instead), keeps "Start with Windows" in desktop.json and
  turns the startup task on or off, and leaves updates to the Store (Velopack is inactive).

### 7.1 AI agents (R15)

- **Bridge:** the running HopDrop (window, tray or hidden receiver) serves a named pipe `HopDrop-agent-<24 hex of SHA-256 of the upper-case
  data folder path>`, current user only. One request per connection: a line of JSON in (`{"op":"devices"|"send"|"status"|"recent"|"wait"|"hello",…}`),
  a line of JSON out (`{"ok":true,…}` or `{"ok":false,"error":"<plain words>"}`). Everything but `hello` is refused while Settings → AI agents is off.
- **Sending** uses the same engine call as the Send page (folders expanded with their structure), so the window, tray and notifications show
  agent transfers like the user's own. Devices are chosen by id or name (exact, then a unique partial match; the only paired device when no name is given).
  Paths must be full paths and exist. A send waits up to `wait` seconds (default 50, at most 600) and otherwise reports "sending" with a transfer id.
- **`HopDrop.exe --mcp [--data <dir>]`:** an MCP server over standard input/output (JSON-RPC 2.0, one message per line; protocol versions
  2025-11-25, 2025-06-18, 2025-03-26, 2024-11-05). Server name `hopdrop`. Tools: `list_devices`, `send_files` (device, paths, wait_seconds),
  `transfer_status` (transfer_id), `recent_transfers` (limit, direction), `wait_for_files` (timeout_seconds, device). Tool failures come back as
  tool results with `isError` and the reason. Requests run side by side, so a long wait doesn't block pings.
- **`HopDrop.exe agent devices | send [--to <device>] [--wait <s>] <paths…> | status <id> | recent [--limit n] [--direction …] | wait [--timeout s] [--from <device>]`:**
  the same abilities for scripts; prints JSON; exit code 0 when it worked.
- If HopDrop isn't running for that data folder, the agent side starts it in the tray (`--minimized`) and waits up to 25 s. Downloaded updates are
  never applied when HopDrop is started for an agent (the restart would cut the agent off).

---

## 8. Third-party code

Approved by the owner on 2026-09-29, from official registries only (nuget.org, Maven Central):
- NuGet: `WPF-UI` (Fluent controls), `H.NotifyIcon.Wpf` or WinForms `NotifyIcon` (tray), `CommunityToolkit.WinUI.Notifications` or equivalent (toasts), `QRCoder` (QR images), `xunit` (tests), the .NET runtime packs needed for a self-contained publish, and for Stage 6 a Bluetooth library if needed (e.g. `InTheHand.Net.Bluetooth`) or the Windows SDK projection.
- Maven: `com.google.zxing:core` (QR scan/encode on Android, Apache-2.0) — the jar goes in `Source/Android/libs/` with its license.
- NuGet `Velopack` (installer and automatic updates, MIT), approved by the owner on 2026-10-03.
Keep dependencies few; record every package and version in the stage report.

---

## 9. Stages

1. **Windows core:** `HopDrop.Core`, `HopDrop.Cli`, `HopDrop.Tests`, `Source/Windows/build.ps1`. Tests cover framing, file names (3.7 table), SAS vectors, QR URI example, token expiry/single use, not-paired rejection, identity mismatch, transfer (multi-file, 50 MB random, zero-byte, duplicates, Unicode), checksum mismatch, cancel cleans the partial file. End-to-end: two CLI peers on 127.0.0.1 (different ports and data dirs) pair by QR and by number match, then send both ways.
2. **Android:** protocol in Java, all screens except QR scanning, receiver, folder choice, notifications, hotspot routing, multi-select, Bluetooth handoff fix, `Source/Android/build.ps1` (aapt2, javac, d8, zipalign, apksigner). JVM tests for `core/` (SAS vectors, URI, file names, framing). Cross-test with the Stage 1 CLI, including on the real phone over adb.
3. **Windows desktop app:** the WPF app per section 7, published to `Windows App\HopDrop.exe`.
4. **QR scanning + polish:** camera QR scanning on Android, final UI polish on both, README update.
5. **Review fixes.**
6. **HopDrop's own Bluetooth link (option b):** a second transport under the same protocol (RFCOMM on both platforms, TLS over the Bluetooth stream, same pairing and receive folder), selectable when Wi-Fi/hotspot is not available. Only after stages 1–5 are done and reviewed.

## 10. Acceptance checklist (organizer verifies each)
- [ ] R1 Wi-Fi router, hotspot ×2, Bluetooth handoff each tried
- [ ] R2 receive folder editable and persistent on phone and laptop
- [ ] R3 phone→laptop, laptop→phone, laptop→laptop tested; phone→phone built (needs a 2nd phone to test)
- [ ] R4 multi-select add/remove/clear, photo picker, share sheet
- [ ] R5 QR pairing and number-match pairing, remembered after restart, no per-send codes
- [ ] R6 devices appear by name; add by IP works
- [ ] R7 Windows window, tray, drag & drop, Send to, toasts
- [ ] R8 notifications on both ends match section 6/7
- [ ] R9 no traffic over mobile data; laptop listens on all local networks
- [ ] R10 Bluetooth target found dynamically; real errors; Windows wizard buttons
- [ ] R11 receiving while app open; in-flight transfer survives leaving the app; background switch works
- [ ] R12 consistent, polished look, light and dark
