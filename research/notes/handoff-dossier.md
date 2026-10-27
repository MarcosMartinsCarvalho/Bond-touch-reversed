# Bond Touch Reverse Engineering Handoff

Date: 2026-06-15

This document is the cold-start handoff for continuing the Bond Touch local BLE reverse engineering work. It is based on static analysis of the pulled Android APK only. Anything marked as a hypothesis must be verified with the physical band and a BLE/HCI capture.

For the second-pass map of app buttons, settings, dashboard/widget flows, and how those paths reach BLE, start with:

```text
research/notes\app-flow-map.md
```

For the Band 4 color/LED command map, start with:

```text
research/notes\color-command-map.md
```

## Goal

Build enough understanding of the Bond Touch BLE protocol to make an independent PC app that can connect to the user's own band and trigger vibration from PC games or other local events.

Scope for this work:

- Local BLE scanning, connection, device generation detection, initialization, pairing confirmation, and vibration/message payloads.
- Right-to-repair/interoperability behavior for the user's own hardware.
- No cloud credential extraction, account bypassing, or use against devices/accounts not owned by the user.

## Workspace

Root:

```text
[local path removed]
```

Important folders:

```text
research/notes\                         Notes and protocol maps
01_apks\                          Pulled APK split set
02_decompiled\                    APKTool/JADX decompile outputs
03_ble\                           Reserved for BLE captures
tools/ble-probe\                     Python BLE probe/bridge seed
tools\                            Local reverse-engineering tools
scripts\                          Pull/decode helper scripts
apps/android/bridge\    Android phone app prototype
```

Pulled app package:

```text
com.impossible.bondtouch
```

Pulled APK split set:

```text
01_apks\com.impossible.bondtouch-20260615-101906\base.apk
01_apks\com.impossible.bondtouch-20260615-101906\split_config.arm64_v8a.apk
01_apks\com.impossible.bondtouch-20260615-101906\split_config.xxxhdpi.apk
```

Main decompiled tree used for static mapping:

```text
02_decompiled\apktool-com.impossible.bondtouch-20260615-101906\base\smali_classes2\com\impossible\bondtouch\bondtouchble\bluetooth
```

## Tooling State

Installed or prepared:

- Android Platform Tools / ADB
- Java
- APKTool
- JADX GUI
- JADX CLI 1.5.5 in `tools\jadx-1.5.5`
- Python 3.11 virtual environment in `tools/ble-probe\.venv`
- Python BLE library `bleak==3.0.2`

Useful commands:

```powershell
.\scripts\pull_bond_apk.ps1
.\scripts\decode_bond_apk.ps1
.\tools/ble-probe\.venv\Scripts\python.exe .\tools/ble-probe\bond_ble_probe.py scan
.\tools/ble-probe\.venv\Scripts\python.exe .\tools/ble-probe\bond_ble_probe.py payloads
.\apps/android/bridge\gradlew.bat -p .\apps/android/bridge assembleDebug
```

When the band is available:

```powershell
.\tools/ble-probe\.venv\Scripts\python.exe .\tools/ble-probe\bond_ble_probe.py classify --target "BOND"
.\tools/ble-probe\.venv\Scripts\python.exe .\tools/ble-probe\bond_ble_probe.py services --target "BOND"
```

## Key App Classes

BLE client and app glue:

```text
BleBondClient.smali
BleBondClient$gattCallback$1.smali
BleBondClient$bleBondManagerListener$1.smali
BleBondClientKt.smali
BleBondPrefs.smali
BleBondScanHelper.smali
BondBleConstants.smali
BondBleState.smali
BleBondTouchDevice.smali
```

Protocol managers:

```text
v1\BleBondManagerV1.smali
v2\BleBondManagerV2.smali
v2\BleBondManagerV2Base.smali
v2\BleAppCommandHelper.smali
v2\LedAndMotorCommand.smali
v3\BleBondManagerV3.smali
v3\BleAppCommandHelper.smali
v3\WriteFFB1Command*.smali
v3\WriteFFB2Command*.smali
v4\BleBondManagerV4.smali
v4\BleBondManagerV4Kt.smali
mini\BleBondManagerMini.smali
mini\BleBondManagerMiniKt.smali
```

## Discovery

The app recognizes these normal device names:

```text
BOND
Bond More
BOND 4
BOND MINI
```

DFU names:

```text
BOND_DFU
BOND_M_DFU
BOND_4_DFU
BOND_MINI_DFU
```

`BleBondScanHelper.startScan(address, durationMs, names, scanMode)` builds Android `ScanFilter`s:

- If names are supplied, one filter per device name.
- If address is supplied, the filter includes `setDeviceAddress(address)`.
- `startScanInfinitely(address, names)` uses duration `-1` and scan mode `0`.
- `startScanDfu(address)` scans for DFU names for `0x1f40` ms / 8000 ms using scan mode `2`.
- Scan results are wrapped as `BleDeviceInfo(scanRecord.deviceName, bluetoothDevice)` and de-duplicated.

Practical PC equivalent:

- Scan by advertised local name first.
- Also allow manual address selection because the band may advertise inconsistently until tapped/woken.
- Save the selected address locally once confirmed.

## Local Preferences And AUID

The app uses Android shared preferences named:

```text
BleBondService
```

Important keys:

```text
SAVED_BLE_DEVICE_ADDRESS
SAVED_GENERATION
SAVED_AUID
SAVED_USER_INDEX
SAVED_BLE_DEVICE_FIRMWARE
LAST_CHAT_NOTIFICATION_TIMESTAMP
LAST_CHAT_SECRET_NOTIFICATION_TIMESTAMP
```

`BleBondPrefs.getAuid()` behavior:

- If `SAVED_AUID` exists, return it.
- Otherwise generate `new Random().nextInt()`.
- Store it as an int under `SAVED_AUID`.

`BleBondPrefs.isPaired()` behavior:

- Reads `SAVED_BLE_DEVICE_ADDRESS`.
- Returns true only if it is non-empty and passes `BluetoothAdapter.checkBluetoothAddress`.

Unpairing removes:

```text
SAVED_BLE_DEVICE_ADDRESS
SAVED_GENERATION
SAVED_AUID
SAVED_USER_INDEX
SAVED_BLE_DEVICE_FIRMWARE
```

Important protocol detail:

- v2 sends the full AUID as 4-byte little-endian to FFA2.
- v3 sends only the low byte of AUID in `07 <auid-low-byte>`.

For a PC app, generate and persist your own AUID per paired band. Do not regenerate it every launch unless deliberately unpairing.

## Device Generation Selection

Generation enum identifiers:

```text
BT1      identifier "1"
BT2      identifier "2"
BTM/v3   identifier "3"
BT4      identifier "4"
BT_MINI  identifier "mini"
```

After GATT service discovery, `BleBondClient.readApiVersion(gatt)` does:

1. If service `d3c00003-add3-1d4b-0000-0000b5006bb1` exists:
   - Read v3 API characteristic `d3c0ffb3-add3-1d4b-0000-0000b5006bb1`.
   - The callback always creates the BTM/v3 manager.
2. Otherwise:
   - Read legacy API characteristic `d3c0ff10-add3-1d4b-0000-0000b5006bb1`.
   - API byte `0x42` creates BT2 manager.
   - API byte `0x44` creates BT4 manager.
   - API byte `0x45` creates BT_MINI manager.
   - Anything else creates BT1 manager.

All managers receive:

- `BleBondManagerListener`
- Local AUID
- Android context
- Current `BluetoothGatt`

## Common UUIDs

Standard:

```text
CCCD descriptor                  00002902-0000-1000-8000-00805f9b34fb
Device Information service        0000180a-0000-1000-8000-00805f9b34fb
Battery service                   0000180f-0000-1000-8000-00805f9b34fb
Battery Level characteristic      00002a19-0000-1000-8000-00805f9b34fb
Firmware Revision characteristic  00002a26-0000-1000-8000-00805f9b34fb
```

Legacy/v2/v4/mini:

```text
Device info service       d3c00001-add3-1d4b-0000-0000b5006bb1
User application service  d3c00002-add3-1d4b-0001-0000b5006bb1
FFA1                      d3c0ffa1-add3-1d4b-0001-0000b5006bb1
FFA2                      d3c0ffa2-add3-1d4b-0001-0000b5006bb1
FFA4                      d3c0ffa4-add3-1d4b-0001-0000b5006bb1
FFA5                      d3c0ffa5-add3-1d4b-0001-0000b5006bb1
FFA6                      d3c0ffa6-add3-1d4b-0001-0000b5006bb1
FFA7                      d3c0ffa7-add3-1d4b-0001-0000b5006bb1
FFA8                      d3c0ffa8-add3-1d4b-0001-0000b5006bb1
```

v3 / BTM:

```text
User application service  d3c00003-add3-1d4b-0000-0000b5006bb1
FFB1                      d3c0ffb1-add3-1d4b-0000-0000b5006bb1
FFB2                      d3c0ffb2-add3-1d4b-0000-0000b5006bb1
FFB3 API version          d3c0ffb3-add3-1d4b-0000-0000b5006bb1
```

## Initialization Flow

High-level:

1. User taps/wakes the band so it advertises.
2. App scans by name/address and user selects the band.
3. Android connects and discovers services.
4. App reads API version/generation.
5. App creates the protocol manager.
6. Manager enables notifications and starts auth.
7. Band requests token/AUID and may request physical confirmation.
8. User double-taps when prompted.
9. App writes paired-correct.
10. Band reports connected.
11. App stores address/generation and pushes user settings.

The remembered "white flash, then double tap to confirm" likely maps to a band-side state. In v3, the app only logs `WAIT_DOUBLE_TAP` and `TEST_COLORS`; it does not send a visible white LED command in the v3 auth code path that was mapped. That makes the white flash likely firmware-driven, but this needs live capture.

## v2 / BT2 Protocol

Main manager:

```text
v2\BleBondManagerV2.smali
v2\BleBondManagerV2Base.smali
```

Init:

1. `BleBondManagerV2Base.initialize(gatt)` validates services.
2. Reads battery.
3. Requests connection priority after 1000 ms.
4. Enables notifications on battery, FFA5 app events, and FFA4 state.
5. `BleBondManagerV2.initAuthFlow(gatt)` writes token to FFA1:

```text
31 32 33 34 35 36 37 38
```

v2 state notifications on FFA4:

```text
82  WAITING_TOKEN      app writes token to FFA1
83  WAITING_AUID       app writes AUID to FFA2 as 4-byte little-endian
84  OOBE               setup/auth problem
85  CONNECTED          queue free, reads firmware
86  MSG                queue paused, waiting touch true
88  QUEUE_EXECUTING    busy true
```

v2 app events on FFA5:

```text
11  AUID_RIGHT
12  AUID_ERROR
13  PAIRED_SIGNAL                  app writes paired-correct to FFA6
14  SEND_MSG                       touch/message payload
15  SEND_MSG_TIMEOUT               touch timeout
16  TAP_DETECTED_SIGNAL
17  NO_ACTION_DOUBLE_TAP_DETECTED  app calls onDoubleTap()
```

`BleBondManagerV2.onBondEvent()` parses FFA5 fields:

```text
offset 0, uint8     event id
offset 1, uint8     event counter
offset 2, uint32    event touch duration
offset 6, uint32    event release duration
offset 10, uint32   stop flag marker, stop if 0xffffffff
deviceId            manager-specific; BT2 passes 0
```

Touch reconstruction:

- Touch and release durations are floored to 50 ms.
- First touch starts the pattern with touch duration only.
- Later touches append release duration then touch duration.
- Stop flag or timeout ends the touch and emits `onTouchReceivedFromBracelet(pattern, FIRST)`.

Command IDs from `AppCmd`:

```text
25 DFU
26 LED_MOTOR_CTRL
27 CONFIG
29 FACTORY_MODE
2a REBOOT
31 PAIRED_CORRECT
32 EXECUTE_QUEUE
33 LED_MOTOR_TEST
34 DOUBLE_TAP_MODE
```

LED modes from `LedCtrl`:

```text
01 On
02 Breathe
03 Blink
04 Off
```

### v2 LedAndMotorCommand

`LedAndMotorCommand.getValues()` creates 37 bytes:

```text
00     command
01     reserved 00
02     queue
03     vibration strength
04-05  vibration time ms, little-endian uint16
06     led mode
07     red
08     green
09     blue
0a     led brightness
0b-0c  led transition time ms, little-endian uint16
0d-0e  led hold time ms, little-endian uint16
0f-10  led sleep time ms, little-endian uint16
11-24  led execute time copied/padded to 20 bytes
```

Single vibration helper:

```text
service  d3c00002-add3-1d4b-0001-0000b5006bb1
char     d3c0ffa6-add3-1d4b-0001-0000b5006bb1
command  26 LED_MOTOR_CTRL
queue    00
strength caller input, usually 100 / 0x64
duration 2000 ms / 0x07d0
ledMode  04 Off
```

Example for strength 100:

```text
26 00 00 64 d0 07 04 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

Paired-correct payload:

```text
31 00 11 22 33 44 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

Execute queue payloads:

```text
32 00 11 00 ... padded to 20    normal queue
32 00 22 00 ... padded to 20    "meaning" queue
```

### v2 Send Message

`BleBondManagerV2Base.sendMessage(gatt, message, startColor, endColor, vibration, partnerMenuIndex, replyEnabled)` has two branches:

If `startColor == endColor`:

- Convert app color through `ColorMapperHelper.getV2Color`.
- Split `BondTouchMessage.pattern` into alternating touch/release durations.
- If touch list is longer than release list, append a 0 release.
- For each touch/release pair, build `buildLedAndMotorMessage(true, touch, release, color, vibration)`.
- Queue those writes to FFA6.
- Append execute queue with mode `0x11`.

If `startColor != endColor`:

- Calls `sendMessageWithMeaning`.
- `buildMeaningMessage(pattern, startColor, endColor, vibration)` converts touches into 200 ms `LED_MOTOR_TEST` chunks and interpolates color across the total pattern duration.
- Release gaps produce off chunks.
- Appends execute queue with mode `0x22`.

For a PC game vibration bridge, the simplest first target is not full message replay. It is `sendSingleVibration` on FFA6 after the band is connected and initialized.

## v3 / BTM Protocol

Main manager:

```text
v3\BleBondManagerV3.smali
v3\BleAppCommandHelper.smali
v3\WriteFFB1Command*.smali
v3\WriteFFB2Command*.smali
```

Init:

1. Constructor calls `initAuthFlow(gatt)` immediately.
2. Delayed connection-priority request after 3000 ms.
3. Validate v3 service and characteristics.
4. Enable FFB1 notifications.
5. Write init auth to FFB1:

```text
01 30
```

FFB1 command encoding:

```text
SingleByteCommand(identifier, byteValue)  -> [identifier, byteValue]
DefaultCommand(identifier, payload)       -> [identifier, payload...]
```

Important FFB1 app writes:

```text
01 30                         start auth
03 31 32 33 34 35 36 37 38    token "12345678"
07 <auid-low-byte>             AUID
01 31                         paired-correct
01 48 op intensity balance 03  vibration intensity/config
```

v3 state notifications on FFB1:

```text
04 82  WAITING_TOKEN                 app writes token
04 83  WAITING_AUID                  app writes low byte AUID
04 84  OOBE                          app treats as AUID error
04 85  CONNECTED                     queue resumes, reads analytics/battery/firmware
04 8A  WAIT_FOR_ACT_PAIRED           app writes paired-correct
04 8D  WAIT_DOUBLE_TAP               app logs only; user action expected
04 8E  TEST_COLORS                   app logs only
04 8F  WAITING_START_AUTHENTICATION  app logs only
```

v3 event notifications:

```text
02 B2  no-action double tap detected  app calls onDoubleTap()
02 13  paired signal                  app writes paired-correct
02 B3  user selection menu activated
02 B4  user selected
```

There is a mismatch in static code: one constants file lists paired signal as `0x16`, but the dispatch path that sends paired-correct checks literal `0x13`. Treat `02 13` as the practical target to verify.

### v3 Vibration

`sendSingleVibration(gatt, intensity, balance)` calls `buildVibrationIntensityCommand(intensity, balance, SET)` and writes to FFB1:

```text
01 48 00 <intensity> <balance> 03
```

Static interpretation:

- This definitely configures vibration intensity/balance.
- It may or may not trigger immediate vibration on hardware.
- Live test required before using it as the PC game rumble command for v3/BTM.

### v3 Send Message / FFB2

`BleBondManagerV3.sendMessage(...)` writes message data to FFB2.

`WriteFFB2Command$DefaultCommand` behavior:

- Default command size is 20 bytes.
- Each provided Long/int value becomes one unsigned byte.
- Any remaining bytes are padded with `0xff`.
- Writes use write type `2`.

v3 message header:

```text
byte 0   p6 from sendMessage signature, likely route/menu/message target
byte 1   pattern item count
byte 2   chunk count = ceil(pattern item count / 20)
byte 3   start color red
byte 4   start color green
byte 5   start color blue
byte 6   end color red
byte 7   end color green
byte 8   end color blue
byte 9   reply flag, 1 true or 0 false
byte 10-19 0xff padding
```

Then the pattern is chunked into groups of 20 bytes and each chunk is written to FFB2 padded with `0xff`.

Caveats:

- v3 pattern values are cast to single bytes. If app-level pattern durations are larger than 255 ms, another scaling step may happen before this method or this protocol encodes units differently. Live capture is needed.
- The fifth int argument in the v3 `sendMessage` signature was not used in the visible method body.
- The first header byte comes from the sixth int argument. It likely routes the message to a menu/partner index, but this needs confirmation.

## v4 / BT4

Main manager:

```text
v4\BleBondManagerV4.smali
v4\BleBondManagerV4Kt.smali
```

Static facts:

- Extends `BleBondManagerV2Base`, not v3.
- Supported firmware string: `2.0.3`.
- Firmware raw resource: `dfu_v203_hw4g`.
- Device type: `BT4`.
- Uses legacy service `d3c00002...` and FFA1/FFA2/FFA4/FFA5/FFA6, plus FFA8.
- FFA8 UUID: `d3c0ffa8-add3-1d4b-0001-0000b5006bb1`.
- `initAuthFlow(gatt)` calls `readState(gatt)` instead of immediately writing token. It then reacts to state notifications through the v2-base state handler.
- `requestConnectionPriority(gatt)` is a no-op.

v4 bonding:

- `startBonding(gatt)` disables ANCS.
- If Android has no bonded device for this address, it enables notifications on FFA8.
- If Android already has a bond, it logs that ANCS is disabled.

v4 events:

- `onBondEvent(gatt, characteristic)` parses v2-style FFA5 event fields.
- It additionally reads a device/menu id at offset `0x0e` / decimal 14 and passes it to the base event handler.

v4 touch handling:

- Accumulates touch/release durations in `TouchInfo`.
- Applies a 50 ms minimum duration.
- On stop flag or timeout, maps device id 1/2/3 to menu index FIRST/SECOND/THIRD.
- If selected menu changed, emits `onUserSelected(menuIndex)`.
- Emits `onTouchReceivedFromBracelet(pattern, menuIndex)`.

v4 menu handling:

- `isMenuEnabled()` is true if second or third partner is active.
- `getSelectedMenuIndex()` caps the saved selected index to the highest active menu item.
- `sendUserColorMenu(gatt, partner, index)` writes `ConfigurationValue$UserMenu`.
- Active partner sets `index | 0x80`.
- Inactive partner clears high bit with `index & 0x7f`.
- Active color is mapped through `ColorMapperHelper.getV2Color`.
- Inactive color is black.
- `sendSelectedUserIndex(gatt, menuIndex)` writes `ConfigurationValue$SelectedUser`.
- `sendMessage(...)` first calls v2-base sendMessage, then writes selected user index based on the partner/menu argument.

For a first PC bridge, BT4 likely uses the v2 FFA6 single-vibration payload, but init/auth still needs to be completed first.

## Mini

Main manager:

```text
mini\BleBondManagerMini.smali
mini\BleBondManagerMiniKt.smali
```

Static facts:

- Extends `BleBondManagerV2Base`, not v3.
- Supported firmware string: `1.0.4`.
- Firmware raw resource: `dfu_v104_hwmini`.
- Device type: `BT_MINI`.
- Uses legacy service `d3c00002...` and FFA1/FFA2/FFA4/FFA5/FFA6, plus FFA8.
- FFA8 UUID: `d3c0ffa8-add3-1d4b-0001-0000b5006bb1`.
- `initAuthFlow(gatt)` calls `readState(gatt)`.
- Requests connection priority after 3000 ms.

Mini fixed pattern:

```text
500, 250, 500, 250, 500
```

Mini behavior:

- Incoming touch from bracelet always reports the fixed mini pattern with menu FIRST.
- `sendMessage(...)` replaces the outgoing message pattern with the fixed mini pattern before delegating to v2-base `sendMessage`.
- `startBonding(gatt)` disables ANCS, then enables FFA8 notifications if Android does not already have a bonded device.

## PC Bridge Architecture

Recommended stages:

1. Scan
   - Show name/address/RSSI if available.
   - Accept manual address.
   - If no band appears, ask user to tap/wake band.
2. Classify
   - Connect and list services.
   - If v3 service exists, use v3/BTM path.
   - Else read legacy API byte and choose BT2/BT4/mini/BT1.
3. Local profile
   - Persist address, generation, AUID, and user selected menu/index.
4. Initialize
   - Enable notifications for state/event characteristics.
   - Run generation-specific token/AUID/paired-correct state machine.
5. Confirm pairing
   - Surface state `WAIT_DOUBLE_TAP` or v2 double-tap event to the UI.
   - User double-taps band.
   - Send paired-correct when the band reports paired signal/state.
6. Command layer
   - For v2/BT4/mini, use FFA6 LED/motor commands.
   - For v3/BTM, test whether FFB1 vibration-intensity gives immediate rumble. If not, use FFB2 message replay once capture confirms units.
7. Game bridge
   - Map keyboard/controller/game events to a rate-limited vibration call.
   - Add cooldowns so the band is not spammed.
   - Keep a reconnect loop separate from game input handling.

## Current Probe Script

File:

```text
tools/ble-probe\bond_ble_probe.py
```

Implemented:

- `scan`: BLE scan and mark Bond-like names.
- `services`: connect and list GATT services/chars.
- `classify`: connect and guess protocol generation from services/API char.
- `payloads`: print known v2/v3 payload examples without connecting.
- `vibrate-v2`: send the mapped v2 single-vibration FFA6 payload.

Example no-device payload dump:

```powershell
.\tools/ble-probe\.venv\Scripts\python.exe .\tools/ble-probe\bond_ble_probe.py payloads --auid 0x12345678
```

Example later with band:

```powershell
.\tools/ble-probe\.venv\Scripts\python.exe .\tools/ble-probe\bond_ble_probe.py classify --target "BOND"
.\tools/ble-probe\.venv\Scripts\python.exe .\tools/ble-probe\bond_ble_probe.py vibrate-v2 --target "BOND" --strength 100
```

Important warning:

- `vibrate-v2` should be tried only after you know the band is a v2-family device and after connection/auth state is understood.
- Windows BLE stacks may require pairing at the OS level before writes succeed.

## Android Prototype App

File/project:

```text
apps/android/bridge
```

Build command:

```powershell
.\gradlew.bat assembleDebug
```

Debug APK:

```text
apps/android/bridge\app\build\outputs\apk\debug\app-debug.apk
```

The app is a dependency-light native Java Android project. It is intended as the first live Band 4 / Band 4 Pop test harness.

Implemented behavior:

- Requests Android BLE permissions.
- Scans for `BOND`, `BOND 4`, `Bond More`, and `BOND MINI` advertisements.
- Saves selected band address/name and a persistent local AUID.
- Connects with GATT LE transport and dumps every discovered service/characteristic to the in-app log.
- Reads the legacy API/generation byte when available.
- Enables notifications on battery, FFA4 state, FFA5 event, and FFA8 when present.
- Reads FFA4 state to start the Band 4 auth sequence.
- On state `0x82`, writes token `12345678` to FFA1.
- On state `0x83`, writes local AUID as little-endian 4 bytes to FFA2.
- On event `0x13`, writes paired-correct payload to FFA6.
- On state `0x85`, marks the band ready and leaves vibration buttons enabled.
- Sends FFA6 LED/motor commands for soft buzz, strong buzz, white flash buzz, and triple pulse.
- Live test confirmed it connects to the user's Band 4 Pop and triggers vibration.
- Color Lab update adds selected-color buzz, show color, hide color, breathe color, and stored color-setting writes.

Important caveat:

- Core connect/auth/vibration now has a successful live test on the user's Band 4 Pop. Color and config-setting commands still need per-button live verification. If a color button behaves differently than expected, copy the in-app protocol log into a new note under `03_ble\` and compare it against `research/notes\color-command-map.md`.

## What Still Needs Live Capture

The physical band was not available during this mapping session, so these are unresolved:

- Actual generation of the user's Duo band.
- Exact advertising/wake behavior when tapped.
- Whether it advertises continuously or only for a window after touch.
- Exact state/event sequence during first app pairing.
- Whether the white flash is caused purely by firmware state or by a write in an unmapped branch.
- For v3, whether `01 48 00 intensity balance 03` vibrates immediately or only stores/configures intensity.
- For v3 FFB2, exact units/scaling of pattern bytes.
- For v4/mini, exact FFA8 notification behavior during bonding.
- Whether direct PC connection can reuse an official-app-paired band without redoing local auth.

## Live Capture Plan

When the band is available:

1. Run PC scan with band idle.
2. Tap/wake band and run PC scan again.
3. Record name, address, RSSI, and services with `classify` and `services`.
4. Enable Android Bluetooth HCI snoop logging.
5. In official app, start a fresh pairing/setup.
6. Capture:
   - user tap to wake
   - app selection
   - white flash
   - double tap
   - final connected state
7. Export HCI log and compare with this map.

Packet patterns to confirm:

v2-family:

```text
FFA4 state notifications 82/83/85/86/88
FFA5 app events 13/14/15/17
FFA1 token write 31 32 33 34 35 36 37 38
FFA2 AUID little-endian
FFA6 paired-correct 31 00 11 22 33 44 ...
FFA6 single/message LED_MOTOR_CTRL 26 ...
```

v3:

```text
FFB1 notify enable
FFB1 write 01 30
FFB1 notifications 04 82, 04 83, 04 8D, 04 8A, 04 85
FFB1 writes 03 31 32 33 34 35 36 37 38, 07 <auid>, 01 31
FFB1 event 02 13 or 02 B2 around double tap
FFB2 writes during touch/message send
```

## Suggested Next Tasks

For the next AI or engineer:

1. Run `payloads` and verify the probe still starts.
2. When the band is present, run `scan`, `classify`, and `services`.
3. Add notification subscriptions to `bond_ble_probe.py` for:
   - v2: FFA4 and FFA5
   - v3: FFB1
4. Implement a dry-run init state machine that logs intended writes before sending them.
5. With user confirmation, test v2-family single vibration after successful auth.
6. Capture official app pairing and annotate it into `03_ble\`.
7. Only after capture, implement full first-pairing from PC.
8. Then build a small local game bridge:
   - config file for address/generation/AUID
   - reconnect loop
   - rate-limited vibration API
   - optional keyboard/gamepad trigger test

## Widget And Preset Touches

The newer widget feature is present in the decoded APK and is worth treating as a UI/data path, not as a new BLE protocol.

Key files:

- `base/smali_classes3/com/impossible/bondtouch/widget/BondMeaningsWidget*.smali`
- `base/smali_classes3/com/impossible/bondtouch/widget/action/SendMeaningAction.smali`
- `base/smali_classes3/com/impossible/bondtouch/widget/action/SendMeaningWorker.smali`
- `base/smali_classes3/com/impossible/bondtouch/widget/data/WidgetCachedMeaning.smali`
- `base/smali_classes3/com/impossible/bondtouch/widget/data/WidgetConfigStore.smali`
- `base/smali_classes3/com/impossible/bondtouch/repository/MessagesRepository.smali`

Widget click path:

```text
Widget slot cell
  -> SendMeaningAction with params meaning_id + bond_id
  -> WorkManager SendMeaningWorker with meaning_id, bond_id, widget_id
  -> TouchMeaningDao.getMeaningById(meaning_id)
  -> reject if missing, empty id, or deprecated
  -> TouchMeaning.getNormalizedPattern()
  -> TouchMeaning.getGeneration()
  -> TouchMeaning.getBondId()
  -> MessagesRepository.sendMeaningFromLibrary(id, bondId, normalizedPattern, generation, isFromWidget=true)
```

`sendMeaningFromLibrary()` builds a `TouchMessageContent(timestamp, pattern, generation)`, wraps it in a `SentMessage`, sends/saves it through the normal message repository path, and records analytics with the `isFromWidget` flag. For the local PC/game app, the important reusable part is the `normalizedPattern + generation` concept. The official app's widget does not appear to reveal a new bracelet command; it feeds a saved preset/meaning into the same message-sending pipeline.

Widget storage/config details:

- `WidgetConfigStore` stores selected meaning slots under keys like `selected_meanings_<widgetId>`.
- `WidgetConfigStore` stores selected bond under keys like `bond_id_<widgetId>`.
- `WidgetCachedMeaning` records: `id`, `internalId`, `name`, `itemKind`, bitmap dimensions, `bondId`, partner color, and partner-image availability.
- `WidgetCachedMeaning` item kinds are literal strings `Basic` and `Pattern`.
- `SendMeaningWorker` writes widget send status strings `SUCCESS` or `ERROR`, broadcasts `com.impossible.bondtouch.widget.REFRESH_GLANCE`, then clears the status after about two seconds.

Implication for our Android app:

- A prototype can include quick buttons and an Android widget without needing a separate BLE discovery effort.
- Presets can be modeled as local named patterns. Pressing a preset should call the same local BLE send-message function as a normal touch.
- To mimic the official app exactly, map `TouchMeaning` fields and database schema later. To make our own working app, storing `name`, `pattern`, `generation`, optional colors, and optional target/partner index should be enough.

## Quick Truth Table

What is confirmed from static APK:

- Names, UUIDs, API version selection, prefs keys, AUID generation.
- v2 token/AUID/paired-correct state handling.
- v3 token/AUID/paired-correct state handling.
- v2 single-vibration payload shape.
- v2 message queue shape at a high level.
- v3 FFB2 message header/chunk shape.
- v4 and mini extend v2-base and use FFA8.
- Mini fixed pattern.
- Widget preset buttons load local `TouchMeaning` rows and call `MessagesRepository.sendMeaningFromLibrary(...)`.
- Widget/preset send uses normalized touch patterns and does not appear to introduce a new BLE command family.

What is inferred:

- White flash is likely firmware-side during auth prompt.
- v4/mini can likely use v2 FFA6 vibration commands once initialized.
- v3 `sendSingleVibration` may be config rather than immediate vibration.

What is unknown until hardware capture:

- Actual band generation.
- Exact first-pair timing and user double-tap event ordering.
- Whether direct PC first-pair can be completed with only local state.
- v3 immediate rumble path.
- Exact normalized pattern scaling for v3 FFB2 writes.
