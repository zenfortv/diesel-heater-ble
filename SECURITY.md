# Security policy

## Reporting a problem
Please **don't open a public issue** for a security problem. Use GitHub's private report instead:
**Security** tab → **Report a vulnerability** (or [this link](../../security/advisories/new)).
Only the maintainer sees it. Say what you found and how to reproduce it.

Safety problems that could start, stop or overheat a heater unexpectedly count as security problems too.

## Supported versions
Only the latest release gets fixes. Please update before reporting.

## What the app does and doesn't do
- It talks to **one heater over Bluetooth** and to nothing else, except the optional weather lookup
  (Open-Meteo), and only after you type a place yourself.
- No accounts, no analytics, no ads, no cloud, no data collection.
- In the Android app, the page and the background service talk over a socket that only accepts connections
  from the phone itself (127.0.0.1). It also requires a secret that changes at every start, so other apps and web
  pages on the phone can't send commands.
- The PC server (`server.py`) asks for an access token, created on first start.

## Checking the download
Every release lists the APK's SHA-256 checksum and the signing certificate fingerprint.
On a PC: `sha256sum DieselHeater-x.y.z.apk` (Linux/macOS) or `certutil -hashfile DieselHeater-x.y.z.apk SHA256` (Windows).
Android only installs an update if it is signed with the same certificate as the version you have.
