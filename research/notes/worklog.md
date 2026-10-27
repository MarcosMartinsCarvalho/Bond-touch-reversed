# Worklog

## 2026-10-27

- Located migrated Bond Notify source under `[local path removed]`.
- Refreshed native UI with shared light/dark glass styling, readable green accents, app icons/switches, recycled searchable picker, responsive dialogs, and slider-based rule editors.
- Used static native glass fallback for low rendering cost; no continuous effects or background tasks added. Detailed handoff: `bond-notify-redesign-2026-10-27.md`.

## 2026-06-15

- Created Desktop workspace: `[local path removed]`.
- Installed Android SDK Platform-Tools with `winget`.
- Installed JADX GUI with `winget`.
- Downloaded JADX CLI 1.5.5 to `tools/jadx-1.5.5`.
- Verified Java and APKTool.
- Phone connected over ADB:
  - model: `CPH2791`
  - device id: `[ADB device ID removed]`
- Found package:
  - `com.impossible.bondtouch`
- Pulled APK split set:
  - `base.apk`
  - `split_config.arm64_v8a.apk`
  - `split_config.xxxhdpi.apk`
- Decoded APKs with APKTool.
- Full JADX decompilation was too slow on this app; stopped leftover Java processes and switched to targeted smali mapping.
- Created Python BLE bridge venv and installed `bleak`.
- Mapped the app-side initialization/auth state machines for v2 and v3 into `research/notes/initialization-sequence.md`.
- Confirmed the double-tap confirmation path exists in both v2 and v3 BLE manager code.
- Mapped BLE scan helper behavior, device generation selection, and local preference/AUID storage.
- Mapped v2 command ids, LED/motor packet layout, execute-queue commands, and message queue branches.
- Mapped v3 FFB2 message header/chunk payload shape and confirmed v3 `sendSingleVibration` writes the vibration-intensity/config command on FFB1.
- Confirmed BT4 and Mini extend the v2-base protocol, use FFA8 during bonding, and do not use the v3 auth manager.
- Added `research/notes/handoff-dossier.md` as the detailed continuation file for another AI/engineer.
- Extended `tools/ble-probe/bond_ble_probe.py` with `classify` and `payloads` commands.
- Added `research/notes/app-flow-map.md` as the second-pass map of app buttons, settings, dashboard/widget flows, touch meanings, connect sequence, and the Android MVP build blueprint.
- Created Android Studio project `apps/android/bridge` for Band 4 / Band 4 Pop local BLE testing.
- Implemented scan, connect, legacy/BT4 init, token/AUID writes, paired-correct handling, raw BLE logging, and FFA6 vibration buttons.
- Generated Gradle wrapper and verified `.\gradlew.bat assembleDebug` builds successfully.
- Live test confirmed the Android app connects to the Band 4 Pop and triggers vibration successfully.
- Mapped immediate LED color timing: color buzz needs nonzero `ledExecuteTimeMs`; added `research/notes\color-command-map.md`.
- Updated Android app with Color Lab controls: selected-color buzz, show/hide/breathe color, and stored touch/double-tap/warning/error color config writes.
- Added Bad Apple haptic test controls to the Android app: scheduled accented vibration pattern plus stop/cancel button.
- Added Phone Bass Haptics to the Android app: Android playback-capture bass detector for phone audio, live bass meter, tuning controls, and direct reuse of the local haptic pattern sender.

## 2026-06-16

- Created separate Android app `apps/android/notify` with package `com.marco.bondnotifyhaptics`.
- Purpose: phone-only Bond bracelet haptics for selected app notifications and alarms, without PC bridge, Minecraft bridge, microphone, or phone-audio bass detection.
- Implemented `NotificationListenerService` for event-driven notification/alarm handling with per-app rules, cooldowns, color, intensity, duration, pattern, alarm repeat, and quiet hours.
- Implemented foreground BLE service with `connectedDevice` service type, quiet persistent notification, saved bracelet reconnect, and Band 4 / v2-family FFA1/FFA2/FFA4/FFA5/FFA6 auth/command flow.
- Kept the app lightweight: no polling loop, no indefinite wake lock, scan auto-stops, and the foreground service only starts after permissions plus a saved/enabled bracelet.
- Added documentation:
  - `08_notification_haptics\README.md`
  - `research/notes\notification-haptics-app.md`
- Verified `apps/android/notify` builds with `.\gradlew.bat assembleDebug`.
- Installed `com.marco.bondnotifyhaptics` to phone `[ADB device ID removed]` with bundled ADB and launched it once.
- Fixed alarm handling in `BondNotifyHaptics`: alarm-category notifications are no longer enough by themselves, so upcoming alarm notifications such as 30-minute warnings should be ignored. The listener now checks Android `AlarmManager.getNextAlarmClock()` and only triggers near the actual alarm time, or when notification text/actions clearly look like a ringing alarm with snooze/dismiss.
- Tightened alarm timing again after testing showed a 3-minute alarm could still trigger early. The next-alarm guard now only allows about 5 seconds early clock skew, removed vague `wake up` ringing detection, and added more upcoming-alarm text filters.
- Added intentional pre-alarm haptics to `BondNotifyHaptics`: upcoming alarm notifications can now trigger a separate configurable `Pre-Alarm` rule. Default is enabled, pattern `long_tap` (long buzz then one tap), amber color, 70% strength, and 10-minute cooldown. Real alarm haptics remain separate.
- Improved `Add App` picker in `BondNotifyHaptics`: custom scrollable app list with search field, app icons, app labels, and package names. Selecting an app still creates an independent per-app notification rule.
- Fixed `Add App` picker only showing a tiny set of apps. Added `QUERY_ALL_PACKAGES` and switched from launcher-activity query to installed-application query with duplicate/system-junk filtering, so more notification-capable apps should appear.
- Started E87 e-badge reverse engineering in `experiments/e87-badge`.
- Confirmed the badge is controlled by ZRun package `com.zijun.zrun` version `2.3.1`; pulled APK splits and decompiled them with JADX.
- Badge appears in the app as Q87/E87, current version `11.1.0.7`, MAC shown by ZRun as `[device address removed]`.
- Strong evidence this is not ESP32: ZRun includes Qix/JieLi libraries (`com.qix.library.*`, `com.jieli.*`, native `libjl_*` OTA/auth/FATFS libs).
- Found firmware update UI:
  - `com.zijun.zrun.ui.upgrade.UpgradeActivity`
  - updater ViewModel `com.zijun.zrun.ui.upgrade.a`
- Found official OTA check endpoint: `https://www.zgntec.com/hy-cloud/app/prod/upgrade`.
- OTA check request uses:
  - `model` = app preference `device_serial_num` (integer from bind response)
  - `type` = `OTA`
  - `version` = firmware version without first component, so `11.1.0.7` becomes `1.0.7`
- Probed OTA endpoint with guessed models; all returned `{"code":"CD000003","msg":"处理失败","body":null}`. Results saved as `experiments/e87-badge\ota_probe_results.json`.
- Android backup of ZRun produced `zrun.ab` with 0 bytes, so app data backup is blocked or did not complete.
- Mapped bind response parsing:
  - request command `COMMAND_REQ_BIND = 0x60`
  - response command `COMMAND_RET_BIND = 0x61`
  - response includes pact version, firmware version, platform, and serial number
- Mapped Qix BLE service:
  - service `C2E6FD00-E966-1000-8000-BEF9C223DF6A`
  - notify `C2E6FD01-E966-1000-8000-BEF9C223DF6A`
  - write `C2E6FD02-E966-1000-8000-BEF9C223DF6A`
  - control point `C2E6FD03-E966-1000-8000-BEF9C223DF6A`
- Added `experiments/e87-badge\probe_e87_ble.ps1`, a Windows BLE probe that sends the same Qix bind request and parses the serial from a `0x61` response.
- First Windows probe could not open the badge because the phone/ZRun connection still owned it. Next step: disconnect/close ZRun or pair/connect badge in Windows Bluetooth settings, then rerun `probe_e87_ble.ps1`.
- Added `experiments/e87-badge\probe_e87_ble.py` using Python/Bleak; this successfully connected to the badge and decoded the bind response.
- Confirmed real serial/model number: `1558`.
- Confirmed live bind response:
  - pact version `2.7`
  - firmware version `11.1.0.7`
  - serial/model `1558`
- Sent exact OTA request `{"model":"1558","type":"OTA","version":"1.0.7"}` and server returned `CD000004` / already latest.
- Probed older versions with model `1558`; version `1.0.0` returns a downloadable OTA binary:
  - `https://share-1329296078.cos.ap-guangzhou.myqcloud.com/OTA/prod/1558/QX7613_E87_C32_OTA_APP_V1.0.3_20260104.bin`
- Downloaded OTA binary:
  - `experiments/e87-badge\QX7613_E87_C32_OTA_APP_V1.0.3_20260104.bin`
  - size `1,079,363` bytes
  - SHA-256 `7B9AC1862E445F13C447DF4E2BC16FD1E4914AA6DD263C1E7BDC8DC279E908B9`
- Added detailed notes in `experiments/e87-badge\e87_findings.md`.
- Started local control phase for E87 badge.
- Extended `experiments/e87-badge\probe_e87_ble.py` with:
  - `--explore` read-only command exploration
  - `--notify` and `--notify-setup` notification experiments
  - `--brightness`
  - `--send-file-state`
  - `--send-file-type`
  - `--package-video` offline DialTool-compatible package builder
- Tested notification path. Raw notification did not show text. `--notify-setup --notify "SPD 25 KMH"` made the badge show boot logo, return to existing GIF, and reset brightness, so avoid repeating that broad notification setup.
- Mapped badge media transfer path:
  - set file type command `0xDC`, `BADGE = 12`
  - set send file state command `0x87`
  - media/file transfer uses `UpdateManager` commands `0xC0`, `0xC2`, responses `0xC1`, `0xC3`, final `0xC5`
  - `DialTool.sendVideoFile` wraps raw media in a `BC AF` file header and video header.
- Added app-like MJPEG AVI conversion to `experiments/e87-badge\probe_e87_ble.py` via `--convert-video-avi`.
- Tested raw GIF package and app-like MJPEG AVI badge packages at `368x368` with slot/bg values `0`, `1`, `2`, and `3`; all were rejected immediately after the `0xC0` header with final status `0xC5 08`, before any data chunks, so the current GIF was not overwritten.
- Tested app-like `requestData(191)` followed by video dial info query `0x49`; request-data returns status/capability packets but `0x49` stays silent. Next best step is capturing a real successful ZRun push and diffing its preflight/header against our generated package.
- Added live-text candidate commands to `probe_e87_ble.py` for the speedometer goal:
  - navigation `0xDF` via `--nav-demo` / `--nav-text`
  - weather `0x2B` via `--weather-text`
  - stock `0x45` via `--stock-speed`
  - news `0x46` via `--news-text`
  - interpretation/translator `0x8D` via `--interpret-text`
  - combined sweep via `--live-text-demo`
- Ran `--nav-demo` and `--live-text-demo`. Navigation/stock/news are no-response writes; weather returned ACK `FF 2B 00`; interpretation returned ACK `FF 8D 00`. Best live speedometer candidates are now weather `0x2B` and interpretation `0x8D`, pending visual confirmation on the badge.
- Created `experiments/e87-bike-bridge`, Android package `com.marco.e87bikebridge`, as a phone-side field tester for the E87 speedometer idea.
- The bridge app can scan BLE devices, connect a chosen device as the E87/Q87 badge, send Qix bind and `SPD xx KMH` live-text packets, connect a chosen device as the e-bike, list readable/notifiable characteristics, subscribe to one, and parse either standard CSC `0x2A5B` or simple speed-like byte payloads.
- Verified `E87BikeBridge` builds with `.\gradlew.bat assembleDebug`.
- Installed `com.marco.e87bikebridge` to phone `[ADB device ID removed]`, launched it, and granted `BLUETOOTH_SCAN` / `BLUETOOTH_CONNECT` via the Android permission dialog. A phone-side scan worked and produced nearby BLE results. Visual badge confirmation is still needed.
- Improved the app's BLE scan UX after scan results felt laggy: scan-result rendering is now throttled to about every 700 ms, the visible list is capped to the strongest 25 results, likely badge results are sorted to the top, and scanning stops when connecting.
- Added `Connect Known Badge`, which auto-connects to the first scan result matching a known badge address, `E87`/`Q87`/`ZRun` name, or advertised Qix service. Known address observations are `[device address removed]` from ZRun and `[device address removed]` / `[device address removed]` from Windows BLE scans. The app displays/logs the phone-visible BLE address when it connects.
- Rebuilt and reinstalled the updated debug APK to phone `[ADB device ID removed]`; Bluetooth permissions remained granted. UI smoke check confirmed the `Connect Known Badge` button and known-address display are visible.
- Confirmed `Connect Known Badge` on the phone connects to `Q87` at phone-visible address `[device address removed]` with RSSI around `-53` to `-56`. The app received the bind response fragments showing pact/firmware data and got ACKs for speed packets: `9E8F01FF02008D00` for command `0x8D` and `9E2D01FF02002B00` for command `0x2B`.
- Fixed Android notification setup by serializing badge CCCD writes; both `c2e6fd01` and `c2e6fd03` now enable with status `0`. App events are mirrored to logcat under tag `E87Bridge`.
- Optimized speed updates for lower latency: interpretation/translator enable (`0x8D` kind `6`) is sent once after connection, then each speed update sends only the two ACKed tiny commands: interpretation text `0x8D` and weather `0x2B`. Removed the repeated translator enable and no-response navigation packet from the per-speed update path.
- Added a no-advertising fallback for `Connect Known Badge`: if Android already has the badge connected at the GATT layer, the app connects directly without scanning; if an auto badge scan times out, it tries the remembered/known badge address directly. Verified this worked while the badge was not advertising: log showed `known badge already connected at OS [device address removed] (Q87)`, then successful services, bind, and ACKed speed sends.
- Pulled and decompiled the likely bike app `com.s75bike` version `1.2.2`. It scans for names containing `SUPER73` and uses service `00001554-1212-efde-1523-785feabcd123`, notifier `0000155e-1212-efde-1523-785feabcd123`, write register `0000155f-1212-efde-1523-785feabcd123`, and register ID `00001564-1212-efde-1523-785feabcd123`. Speed notifications are `02 01 LL HH ...`, with little-endian `u16(bytes[2:4]) / 100.0` km/h. Details saved in `experiments/super73\findings.md`.
- Added Super73/S75 support to `E87BikeBridge`: likely bike scan marking, auto setup for the Super73 service, notifier subscription, request writes for settings/ride/battery/distance, and exact speed parsing. Physical bike test is pending because the bike was not nearby; do not spend more test time on live bike scanning until the bike is awake/available.
- Added badge-only debug mode for the no-bike case. `Start Badge Demo` cycles fake speeds once per second through the same badge live-text path, and `Stop Demo` cancels it. Verified on the real badge at `[device address removed]`: demo speeds `25, 28, 31, 34, 36, 33, 30 km/h` were sent, with ACKs `9E8F01FF02008D00` for `0x8D` and `9E2D01FF02002B00` for `0x2B` on each tick.
- User observed that the badge may only connect/show data while on its connect screen, not from the home GIF face. Checked ZRun's `InterpretationActivity` and found the missing display-context command: on activity open it sends `command_a2d_into_interpretation(true)`, which is `0x8D` payload `01 01`; on activity destroy it sends `0x8D` payload `01 00`. Starting translation sends `0x8D` payload `06 01`.
- Updated `E87BikeBridge` so badge connection and manual speed sends first send `0x8D 01 01` to enter the interpretation/live-text screen and `0x8D 06 01` to enable streaming. Added `Wake Badge Live Screen` for a badge-only test of the home-GIF vs live-screen hypothesis; this button force-resends the wake/setup commands even if the app already thinks the live screen is active. Per-speed updates now send `0x8D` original text `BIKE SPEED`, `0x8D` translated text `SPD xx KMH`, and the existing `0x2B` weather fallback.
- Rebuilt and reinstalled the debug APK to phone `[ADB device ID removed]`; BLE permissions remained granted and the bridge was launched.
- Verified forced wake over BLE with the real badge connected: tapping `Wake Badge Live Screen` logged two immediate `0x8D` ACKs (`9E8F01FF02008D00`) for the forced `0x8D 01 01` / `0x8D 06 01` setup, followed by `wake-test SPD 25 KMH`, two more `0x8D` ACKs for original/translated text, and `0x2B` weather ACK `9E2D01FF02002B00`. Badge visual confirmation of what appeared on the round display is still pending.

## Key First-Party Modules

- `com.impossible.bondtouch.bluetooth`
- `com.impossible.bondtouch.bondtouchble.bluetooth`
- `com.impossible.bondtouch.bondtouchcommon.companion`
- `com.impossible.bondtouch.heartble.bluetooth`

## Notes

- Do not use cloud API keys, account tokens, or bypass flows. The useful interoperability target is local BLE behavior: device names, services, characteristics, and message payloads.
- The APK has clear Kotlin metadata in many BLE classes, so smali is readable enough for protocol mapping even without full JADX output.
