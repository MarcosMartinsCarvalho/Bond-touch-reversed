# E87 Bike Bridge

Android field-test app for using the E87/Q87 badge as a live e-bike speed display.

Current strategy:

- Connect to the E87/Q87 badge over the Qix BLE service.
- Send speed as tiny live text packets instead of slow GIF/media uploads.
- Connect to an e-bike BLE device and subscribe to a notifying characteristic.
- Parse either standard Cycling Speed and Cadence (`0x1816` / `0x2A5B`) or simple speed-like byte payloads.
- Keep BLE scanning lightweight by throttling scan-result redraws and showing the strongest 25 candidates.
- Use `Connect Known Badge` to auto-connect to the badge by known address, E87/Q87 name, advertised Qix service, or an already-connected Android GATT device.
- If the badge is not advertising after a previous session, the app falls back to the remembered badge address.
- Mirror app events to Android logcat under tag `E87Bridge`.
- Use `Start Badge Demo` for badge-only testing when the e-bike is not nearby. It sends fake changing speeds once per second through the same live-text path used by bike speed.
- Use `Wake Badge Live Screen` if the badge only appears to accept/show data from its connect/live app screen.

Known badge addresses seen so far:

- ZRun app display: `[device address removed]`
- Windows BLE scan: `[device address removed]`
- Windows BLE scan, later session: `[device address removed]`

Badge output packets currently sent for every speed update:

- `0x8D` interpretation original + translated text, e.g. `BIKE SPEED` and `SPD 25 KMH`
- `0x2B` weather payload with city label like `SPD25`

The app now mirrors ZRun's interpretation screen setup before sending speed:

- `0x8D` kind `1`, value `1`: enter the interpretation/live-text screen.
- `0x8D` kind `6`, value `1`: enable interpretation streaming.

Those setup packets are sent once after badge connection. `Wake Badge Live Screen` forces them again, which is useful if the badge was manually switched back to its GIF/home face. The app avoids resending them for every normal speed update.

The strongest badge-side candidates from PC tests were:

- `0x8D`, ACKed by badge as `FF 8D 00`
- `0x2B`, ACKed by badge as `FF 2B 00`

## Build

```powershell
cd "[local path removed]"
.\gradlew.bat assembleDebug
```

Debug APK:

```text
E87BikeBridge\app\build\outputs\apk\debug\app-debug.apk
```

## Test Flow

1. Open `E87 Bike Bridge` on the phone.
2. Tap `Connect Known Badge`; if the badge is advertising, the app scans and connects automatically.
3. If the badge is already connected at Android's GATT layer, the app connects without scanning.
4. If auto-connect misses it, tap `Scan` and choose `Badge` for the `Q87`, `E87`, known address, or Qix-looking entry.
5. When badge status says `ready=true`, tap `Send Speed`.
6. Check whether the badge shows any speed text or switches to a live text/card screen.
7. Tap `Scan` again, find the e-bike, tap `Bike`.
8. In `Bike Characteristics`, try notify characteristics until log lines decode a plausible km/h value.

## Badge-Only Debug

When the bike is not available:

1. Tap `Connect Known Badge`.
2. Wait for `ready=true`.
3. Tap `Wake Badge Live Screen`.
4. Tap `Start Badge Demo`.
5. Watch the badge and/or `adb logcat -d -s E87Bridge:D`.
6. Tap `Stop Demo`.

Verified demo ACK pattern:

- `9E8F01FF02008D00` for interpretation text command `0x8D`
- `9E2D01FF02002B00` for weather command `0x2B`

Verified wake ACK pattern:

- `Wake Badge Live Screen` produces two immediate `0x8D` ACKs for the forced live-screen setup, then sends one `wake-test` speed update.

ADB log check:

```powershell
adb logcat -d -s E87Bridge:D
```

This first version is an on-screen field tester. Keep it open while testing; a foreground background service can be added once we know which badge display path and e-bike characteristic actually work.
