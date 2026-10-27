# Windows → Linux feature parity

State checked on 2026-10-27. Linux packages are test builds. WSL Ubuntu 24.04 is the tested Linux environment; physical Linux Mint hardware is not yet tested.

| Feature | Linux behavior | Evidence / boundary |
| --- | --- | --- |
| Encrypted local files and folders | Same protocol and core; nested structure preserved | WSL transfers and file hashes; distinct `A.txt`/`a.txt` names verified |
| Text and links | Send/receive, copy, open HTTP(S) links | Arabic text transferred; native UI rendered |
| Multiple recipients and progress | Per-device sends, live progress, cancel, details | Shared core tests; controls built and rendered |
| Offline queue | Persists sends; retries after reconnect; cancel | Native integration harness verifies restart and delivery; interrupted sends require review |
| Discovery and pairing | QR, link, IP and number match | WSL QR/SAS smoke test |
| Trust, consent, naming and visibility | Shared core controls; expired offer closes its prompt | Core tests and native UI |
| Receive folder and Android backups | Configurable folders, backup list/open/remove record | Same tested core backup receiver; no deletion on removal |
| Activity and connection method | Actual network/direct/Bluetooth metadata, text/file actions, clear | Core tests; real agent send and receive Activity both `local_network` |
| English/Arabic | English default, persisted Arabic, RTL, Arabic plurals | Resource coverage, localization tests and light/dark native renders |
| Logo | Existing Windows ICO and PNG | Same assets in window, tray, sidebar and package |
| Light/dark/system theme | Native controls and app palette follow selection; dark palette matches Windows | Native render checks; same orange, charcoal, text/border and on-accent colors; Fluent controls use HopDrop's accent |
| Background and sign-in | Per-user XDG autostart, close-to-tray, background receiving, relaunch restore | Isolated integration-file checks; Mint session behavior remains to test |
| File-manager send action | Nemo, Nautilus script and Dolphin service menu | Isolated file generation/removal checks; physical Mint/Nemo interaction remains to test |
| Clipboard | Explicit paste; received clipboard toggle; optional nearby Android auto-share on X11 | Existing shared policy; known private markers/loop suppression; Wayland automatic sharing unavailable |
| Local AI agents / CLI / MCP | Same five tools and machine result values as Windows; off by default | Actual Codex `gpt-6-sol` xhigh used the native Linux app to send 4 synthetic files; all names/hashes independently verified |
| Agent permissions and failures | Same-user pipe, live opt-in checks, paired targets, honest failure states | Shared harness on Windows and WSL; cross-user connection denied; revoked/ambiguous/missing/offline cases tested |
| PC-hosted direct connection | Explicit temporary NetworkManager hotspot using existing Android link fields | Fake adapters pass. Physical AP, polkit, DHCP and phone joining unverified. May disconnect current Wi-Fi; no automatic startup |
| Bluetooth sending | OS-paired BlueZ OBEX; record Activity only on confirmed completion | Fake BlueZ complete/error/disappearing-object cases pass. Physical device transfer unverified |
| Bluetooth receiving | Native Blueman consent and destination | Partial parity: HopDrop cannot verify completion, move files or record incoming Activity through this native handoff |
| Updates | Open release page; install new `.deb`/portable package | OS-specific difference: no Velopack/Store auto-update on Linux |
| Firewall and downloaded-file marking | Use Mint firewall and OS protections | No automatic firewall edits; Windows NTFS Mark of the Web does not exist on Linux |
| Windows Share target / Store integration | File-manager actions and per-user autostart supply desktop equivalents | Windows-specific OS features are not imitated |

## Verification performed

- Windows desktop Release build: 0 warnings/errors. Existing Core/localization suite: **85 passed**.
- Preview release gate: the shutdown cancellation race found by the folder-safety test was fixed. That test passed five consecutive runs, followed by all 85 tests and the native Linux agent/queue/transfer checks.
- Shared agent integration harness on Windows and WSL: real file/folder bytes, status, transport history, incoming wait, CLI, MCP initialize/list/all tools, disabled/revoked access, unknown/ambiguous device, missing path and offline failure. WSL cross-user pipe probe: denied.
- Linux desktop integration harness: temporary desktop files and own-file-only removal, confirmed file/Arabic-text delivery, offline persistence across restart, reconnect delivery, cancellation, interrupted-send review, Bluetooth Activity and Linux case-sensitive filenames.
- Linux radio backend fixtures: missing hardware/services, unsupported AP, existing-Wi-Fi refusal, temporary profile creation/cleanup, private passphrase file and no secret in arguments, private IPv4 verification, activation failure, paired Bluetooth selection, confirmed OBEX completion and unconfirmed disappearing transfer.
- Native app renders: English and Arabic in light and dark themes. The app renders itself; screenshots are not browser mockups.
- Native app agent regression script (`tests/agents.py`): CLI and MCP each launch a closed isolated app, return clean protocol output, preserve the enabled Arabic profile, send a synthetic nested folder with exact case-sensitive names and hashes, and deny access after restarting with the opt-in disabled. This caught and fixed a GUI launch that inherited the caller's output streams.
- **Real Codex caller:** process-local MCP configuration started `HopDrop.Linux --mcp` via WSL. With an isolated, explicitly enabled Arabic UI profile, Codex called `list_devices`, `send_files`, `transfer_status`, and `recent_transfers`. The receiver got the requested text file and nested folder (4 files, 101 bytes). Independent SHA-256 comparisons verified every file and both case-sensitive names. Returned status was `sent`; receiver Activity was `ok` / `local_network`. No real phone or personal files were involved, and no global MCP configuration was changed.

See the physical acceptance checks in [README](README.md). Passing WSL and fixture tests does not certify radio, tray, compositor or file-manager behavior on the friend's Mint machine.
