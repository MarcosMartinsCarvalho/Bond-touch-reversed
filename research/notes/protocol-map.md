# Bond Touch BLE Protocol Map

This is a first-pass map from the pulled Android APK. Values should be validated with live BLE scans/captures before building the final PC bridge.

## Device Names

Observed app filters:

- `BOND`
- `Bond More`
- `BOND 4`
- `BOND MINI`

DFU names:

- `BOND_DFU`
- `BOND_M_DFU`
- `BOND_4_DFU`
- `BOND_MINI_DFU`

## Common UUIDs

- CCCD descriptor: `00002902-0000-1000-8000-00805f9b34fb`
- Device Information service: `0000180a-0000-1000-8000-00805f9b34fb`
- Battery service: `0000180f-0000-1000-8000-00805f9b34fb`
- Battery level characteristic: `00002a19-0000-1000-8000-00805f9b34fb`
- Firmware revision characteristic: `00002a26-0000-1000-8000-00805f9b34fb`

## Legacy / v1-v2 UUIDs

- Device info service: `d3c00001-add3-1d4b-0000-0000b5006bb1`
- User application service: `d3c00002-add3-1d4b-0001-0000b5006bb1`
- Battery characteristic: `d3c0ff06-add3-1d4b-0000-0000b5006bb1`
- Characteristic FFA1: `d3c0ffa1-add3-1d4b-0001-0000b5006bb1`
- Characteristic FFA2: `d3c0ffa2-add3-1d4b-0001-0000b5006bb1`
- Characteristic FFA4: `d3c0ffa4-add3-1d4b-0001-0000b5006bb1`
- Characteristic FFA5: `d3c0ffa5-add3-1d4b-0001-0000b5006bb1`
- Characteristic FFA6: `d3c0ffa6-add3-1d4b-0001-0000b5006bb1`
- Characteristic FFA7: `d3c0ffa7-add3-1d4b-0001-0000b5006bb1`
- v4 extra characteristic FFA8: `d3c0ffa8-add3-1d4b-0001-0000b5006bb1`

## v3 User Application UUIDs

- User application service v3: `d3c00003-add3-1d4b-0000-0000b5006bb1`
- Characteristic FFB1: `d3c0ffb1-add3-1d4b-0000-0000b5006bb1`
- Characteristic FFB2: `d3c0ffb2-add3-1d4b-0000-0000b5006bb1`

## Initialization / Pairing Auth

See `research/notes/initialization-sequence.md` for the current auth-state map.

Short version:

- v3 writes start-auth `01 30` to FFB1 after enabling FFB1 notifications.
- v3 responds to `WAITING_TOKEN` with `03 31 32 33 34 35 36 37 38`.
- v3 responds to `WAITING_AUID` with `07 <auid-low-byte>`.
- v3 responds to paired-signal/state with paired-correct `01 31`.
- v2 writes token `31 32 33 34 35 36 37 38` to FFA1.
- v2 paired-correct is a padded 20-byte FFA6 write: `31 00 11 22 33 44 00 ...`.
- Double-tap is explicit in both v2 and v3 app code.

## Discovery, Generation, And Local IDs

The app scans by optional address plus device name filters. `startScanInfinitely(address, names)` uses Android BLE scan mode `0`; DFU scan uses the DFU names for 8000 ms with scan mode `2`.

Generation selection:

- If v3 service `d3c00003-add3-1d4b-0000-0000b5006bb1` exists, the app reads `d3c0ffb3-add3-1d4b-0000-0000b5006bb1` and creates the BTM/v3 manager.
- Otherwise it reads legacy API char `d3c0ff10-add3-1d4b-0000-0000b5006bb1`.
- Legacy API byte `0x42` means BT2.
- Legacy API byte `0x44` means BT4.
- Legacy API byte `0x45` means BT_MINI.
- Anything else falls back to BT1.

Local shared preferences are under `BleBondService`.

Important keys:

- `SAVED_BLE_DEVICE_ADDRESS`
- `SAVED_GENERATION`
- `SAVED_AUID`
- `SAVED_USER_INDEX`
- `SAVED_BLE_DEVICE_FIRMWARE`

`SAVED_AUID` is generated locally with `Random.nextInt()` if absent. v2 sends all 4 bytes little-endian; v3 sends only the low byte.

## Motor Constants

From `BondBleConstants`:

- strong: `0x64` / 100
- high: `0x50` / 80
- medium: `0x3c` / 60
- low: `0x28` / 40
- levels 1-5: `0x1c`, `0x21`, `0x3a`, `0x49`, `0x58`
- balance levels 1-5: `0x00` through `0x04`

## v2 Single Vibration

Source path:

- `v2/BleBondManagerV2Base.smali`
- `v2/BleAppCommandHelper.smali`
- `v2/LedAndMotorCommand.smali`

The app's `sendSingleVibration(gatt, strength, ...)` does:

- service: `d3c00002-add3-1d4b-0001-0000b5006bb1`
- characteristic: `d3c0ffa6-add3-1d4b-0001-0000b5006bb1`
- payload builder: `buildSingleVibrationMessage(strength)`

Payload structure from `LedAndMotorCommand.getValues()`:

```text
0:  command              0x26 LED_MOTOR_CTRL
1:  reserved             0x00
2:  queue                0x00 without queue
3:  vibrationStrength    caller input, byte
4:  vibrationTimeMs LE   low byte
5:  vibrationTimeMs LE   high byte
6:  ledMode              0x04 Off
7:  redLevel             0x00
8:  greenLevel           0x00
9:  blueLevel            0x00
10: ledBrightness        0x00
11-12: ledTransitionTimeMs LE
13-14: ledHoldTimeMs LE
15-16: ledSleepTimeMs LE
17-36: ledExecuteTimeMs LE copied to 20 bytes total
```

For the app's single-vibration helper:

- `vibrationTimeMs = 0x07d0` / 2000 ms
- `ledMode = Off` / `0x04`
- all LED colors/timers are zero

Command ids from `AppCmd`:

- `0x25` DFU
- `0x26` LED_MOTOR_CTRL
- `0x27` CONFIG
- `0x29` FACTORY_MODE
- `0x2a` REBOOT
- `0x31` PAIRED_CORRECT
- `0x32` EXECUTE_QUEUE
- `0x33` LED_MOTOR_TEST
- `0x34` DOUBLE_TAP_MODE

LED modes:

- `0x01` On
- `0x02` Breathe
- `0x03` Blink
- `0x04` Off

## v2 Message Queue

`BleBondManagerV2Base.sendMessage()` writes message chunks to FFA6.

If start and end colors are the same:

- Pattern is split into alternating touch/release durations.
- Each pair becomes `buildLedAndMotorMessage(true, touch, release, color, vibration)`.
- The app appends execute-queue `32 00 11 ...`.

If start and end colors differ:

- The app calls `sendMessageWithMeaning()`.
- Touches are expanded into 200 ms `LED_MOTOR_TEST` chunks.
- Color is interpolated from start to end across the total pattern.
- Release chunks are off/zero.
- The app appends execute-queue `32 00 22 ...`.

## v3 Vibration Intensity

Source path:

- `v3/BleAppCommandHelper.smali`
- `v3/WriteFFB1Command*.smali`
- `v3/VibrationOperation.smali`

Command constants:

- `IDENTIFIER_APP_CMD = 0x01`
- `APP_CMD_VIBRATION = 0x48`
- `VibrationOperation.SET = 0`
- `VibrationOperation.GET = 1`
- `VibrationOperation.STORE = 2`

`buildVibrationIntensityCommand(vibrationIntensity, balanceLevel, operation)` writes to FFB1 as:

```text
0: 0x01    identifier app command
1: 0x48    app command vibration
2: op      0 set, 1 get, 2 store
3: intensity
4: balance level
5: 0x03
```

This appears to configure intensity, not necessarily play an immediate vibration. The v3 FFB2 message replay packet shape is mapped below, but live capture is still needed to confirm pattern units and whether this FFB1 command can be used as an immediate rumble command.

## v3 FFB2 Message Packets

`BleBondManagerV3.sendMessage()` writes a header and pattern chunks to FFB2.

`WriteFFB2Command$DefaultCommand` uses 20-byte packets, writes each value as one byte, and pads unused bytes with `0xff`.

Header shape:

```text
0: p6 from sendMessage signature, likely route/menu target
1: pattern item count
2: chunk count, ceil(pattern item count / 20)
3: start red
4: start green
5: start blue
6: end red
7: end green
8: end blue
9: reply flag, 1 or 0
10-19: 0xff padding
```

Then the app writes the pattern list in 20-item chunks, each padded with `0xff`.

Caveat: pattern entries are cast to single bytes here. Live capture must confirm whether the units are already scaled before this method.

## v4 And Mini

BT4 and Mini extend `BleBondManagerV2Base`, not the v3 manager.

BT4:

- Firmware support string `2.0.3`.
- Device type `BT4`.
- `initAuthFlow()` calls `readState(gatt)`.
- Uses FFA8 `d3c0ffa8-add3-1d4b-0001-0000b5006bb1` during bonding.
- Adds partner menu support by writing user-menu and selected-user config values.
- `sendMessage()` delegates to v2-base, then writes selected user index.

Mini:

- Firmware support string `1.0.4`.
- Device type `BT_MINI`.
- `initAuthFlow()` calls `readState(gatt)`.
- Uses FFA8 during bonding.
- Replaces incoming/outgoing touch patterns with fixed pattern `[500, 250, 500, 250, 500]`.

## Widget / Preset Touch Send Path

The home-screen widget sends saved meanings through app data plumbing rather than a distinct BLE command.

Flow:

```text
BondMeaningsWidgetContentKt SlotCell
  -> SendMeaningAction(meaning_id, bond_id)
  -> SendMeaningWorker
  -> TouchMeaningDao.getMeaningById(meaning_id)
  -> TouchMeaning.getNormalizedPattern()
  -> MessagesRepository.sendMeaningFromLibrary(..., isFromWidget=true)
```

`MessagesRepository.sendMeaningFromLibrary()` creates `TouchMessageContent(timestamp, normalizedPattern, generation)` and a `SentMessage`, then sends/saves it through the same message repository path used by the main app.

For our local app design this means:

- Store presets as named normalized patterns.
- Quick buttons and widgets can call the same BLE send-message code as manual touches.
- No separate "widget vibration" BLE packet was found in static analysis.

## Next Mapping Targets

- Live capture of the initialization sequence in `research/notes/initialization-sequence.md`.
- Actual hardware generation of the user's band.
- v3 immediate vibration behavior.
- v4/mini FFA8 bonding notifications.
- Live BLE scan of the bracelet while disconnected from the official app.
- Optional Android HCI snoop capture while sending a touch from the official app, then compare with smali payload builders.
