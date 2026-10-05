# Security

## Reporting a problem

Please report security problems privately: on GitHub, open the repository's **Security** tab → **Report a vulnerability**. Don't open a public issue for them. You'll get an answer within a few days, and a fix in the next release.

Only the latest version is supported.

## How HopDrop protects transfers

- Every connection is TLS 1.2 or 1.3, and both sides prove their identity with a key created on the device and kept in Windows' or Android's protected key storage.
- Only paired devices can send files. Pairing needs a one-time QR code (valid 5 minutes) or both people confirming the same 6-digit number.
- Every file is checked with SHA-256; unfinished files are deleted. Received file names are cleaned so a sender can't write outside the receive folder.
- The apps accept connections only from local network addresses and never send files over the internet.
- On Windows, files from devices you haven't marked as trusted get Windows' "downloaded" mark, so Windows and Office warn before opening risky ones.

## What isn't in this repository

The release signing key for the Android app is kept offline and is never committed. No passwords, tokens or keys belong in this repository; GitHub secret scanning is turned on for it.
