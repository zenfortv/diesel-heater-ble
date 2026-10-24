# DieselHeater

Control an AirHeaterBLE diesel heater (Vevor / BYD / generic Chinese) from the phone and laptop, with the
on/off timers the official app lacks and automatic correction of the heater's drifting clock.

## The app (`web/index.html`)
One self-contained page, installable as an app on Android ("Add to Home screen"). It picks its mode by itself:

| Mode | When | Timers | Clock fix |
|---|---|---|---|
| **Box** | page opened from the ESP32 box / PC server (`http://<box>:8765`) | weekly, one-time, countdown, 24/7 | automatic, hourly |
| **Direct Bluetooth** | page opened from GitHub Pages in Chrome (Android) -> "Connect via Bluetooth" | only while the page is open + the heater's built-in timer | when the page is open |
| **Demo** | "Try the demo" | simulated | simulated |

Tabs: **Home** (power, thermostat / power level, room temp, faults in plain words, "switch off in"),
**Timers** (week chart, weekly slots incl. past midnight, one-time timers, coming up),
**Heater** (built-in timer, heater clock + drift, auto start/stop, safety auto-off, connection, history).
On a wide screen the three tabs show side by side.

Web Bluetooth needs `https://` (GitHub Pages) or `localhost`. The page's protocol code is checked against
the Python one by `tests/test_web_protocol.py`.

```
phone / laptop ──Wi-Fi──► ESP32 box or PC (server.py :8765) ──BLE──► heater      (Box mode)
phone (Chrome) ─────────────────────────────────────────────BLE──► heater      (Direct mode)
```

## Run the PC server
```
.venv\Scripts\python.exe scan.py          # find the heater address (close the phone app first!)
.venv\Scripts\python.exe probe.py <addr>  # read-only test: connects and asks for status only
# edit config.json: "address": "XX:XX:...", "simulate": false
.venv\Scripts\python.exe server.py        # prints http://<pc-ip>:8765
```
With `"simulate": true` (default) a fake heater is used, so you can try the app without hardware.

**Known issue (2026-10-24):** this PC (Windows, Realtek Bluetooth) opens the radio link to the heater
`BYD-988917049E28` but the heater never answers Windows' GATT service discovery (bleak and raw WinRT both time
out). Phone (Android) and ESP32 stacks work with these modules, so use Direct mode or the ESP32 box.

## Features
- power, thermostat (8-36 °C) or level (1-10), live status (room/heater temp, battery, errors)
- weekly slots (days + on/off time + temp/level), past midnight allowed
- one-time timers, "switch off in" countdown, safety auto-off after N hours
- the heater's own built-in daily timer (start + run minutes), runs with the PC off
- automatic clock correction: the heater's clock drifts (fast or slow), so the server re-sets it (cmd 10)
  on connect and every `clock_sync_minutes` (config, default 60). Heaters that report their clock
  (encrypted variants) are only corrected when off by >= 1 min, and the drift in min/day is shown.
- auto start/stop toggle (only on newer, encrypted-protocol heaters)
- "Release Bluetooth" switch: the heater allows ONE connection at a time; release it to use AirHeaterBLE

## Protocol (sources)
- warehog/esphome-diesel-heater-ble - decompiled AirHeaterBLE JS; 4 variants AA55/AA66 (+ XOR "password" encrypted)
- iotmaestro/vevor-heater-ble - command table; spin877/Bruciatore_BLE - sniffed from the app
- Spettacolo83/diesel-heater-ble (pip lib, used for parsing)

Command: `AA 55 <pin/100> <pin%100> <cmd> <data lo> <data hi> <sum of bytes 2-6>` on FFE0/FFE1
(PIN 1234 -> `0C 22`). Verified against the AirHeaterBLE APK itself
(`assets/apps/__UNI__9A3735B/www/app-service.js`, `common/bluetooth/useBluetooth.js` packet builder).

Command numbers are DECIMAL (warehog's table prints them with a wrong `0x` prefix):

| cmd | meaning | data |
|----|----|----|
| 1 | status poll | 0 |
| 2 | mode | 1 level, 2 thermostat |
| 3 | power | 0/1 |
| 4 | level or target temp | 1-10 / 8-36 |
| 10 | set heater clock | minutes since midnight |
| 11 | built-in timer start | minutes since midnight |
| 12 | built-in timer run time | minutes |
| 13 | built-in timer enable | 0/1 |
| 14 | language | |
| 15 | temperature unit | |
| 16 | tank volume (also "bluetooth on/off" 20/21 on some pages) | |
| 17 | oil pump type / 433 MHz remote on/off (20/21) | |
| 18 | auto start/stop ("i_stop") | 0/1 |
| 19 | altitude unit | |
| 20 | temperature offset | signed |
| 21 | backlight | |

Readback of clock/timer (bytes 19-25 of the decrypted frame) only exists on the 48-byte encrypted variants.
The newest app also has a multi-slot weekly timer, but only over the separate "V2.1" protocol (header `FE AA`).

## Remote access later
- Easiest: install Tailscale on the room PC and the phone -> open `http://<pc-tailscale-name>:8765` from anywhere.
  `tailscale serve 8765` gives HTTPS, which also allows a full app install on Android.
- If you expose it any other way, set `"token"` in config.json first.
- Cheaper always-on alternative to a PC: ESP32 (~5 EUR) with warehog's ESPHome component + Home Assistant.

## Publishing
`.github/workflows/pages.yml` publishes `web/` to GitHub Pages on every push to `main`
(one-time: repo Settings -> Pages -> Source "GitHub Actions"). `config.json` and `schedules.json` stay local.

## Notes
- Windows firewall must allow Python on port 8765 for the phone to reach it.
- Commands 10-13 (clock + built-in timer) come from the AirHeaterBLE app code but are not yet confirmed on the real heater.
