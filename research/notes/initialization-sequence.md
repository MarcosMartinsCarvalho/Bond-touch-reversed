# Bond Touch Initialization Sequence

Static map from the pulled Android APK. This is the app-side flow only; the band's advertising behavior and LED behavior still need live BLE/HCI capture.

## High-Level User Flow

1. Wake/advertise the band.
   - User-facing behavior: tap the band so it starts advertising or becomes easier to discover.
   - Static APK evidence: the app scans for Bond device names, but the exact firmware wake/advertise trigger is not visible from APK code.
2. Select the discovered band in the app.
   - `ConnectViewModel.onDeviceSelected()` stores the `BluetoothDevice` and moves the UI to `ConnectStep.Setup`.
   - `ConnectViewModel.connect()` calls `BleBondClient.setDevice(bluetoothDevice)` unless the app already considers the device paired.
3. Android connects over BLE and discovers services.
4. `BleBondClient` reads the bracelet API version.
   - If service `d3c00003-add3-1d4b-0000-0000b5006bb1` exists, it reads BTM/v3 API characteristic `d3c0ffb3-add3-1d4b-0000-0000b5006bb1`.
   - Otherwise it reads legacy API characteristic `d3c0ff10-add3-1d4b-0000-0000b5006bb1`.
5. `BleBondClient` instantiates a manager based on the API/device type:
   - v1, v2, v3, v4, or mini.
   - The app passes a locally stored AUID from `BleBondPrefs.getAuid()` into the manager.
   - Legacy API byte `0x42` selects BT2, `0x44` selects BT4, and `0x45` selects BT_MINI.
   - Presence of service `d3c00003-add3-1d4b-0000-0000b5006bb1` selects BTM/v3.
6. The protocol-specific manager runs auth/init.
7. The band reports states/events. The app responds with token/AUID/paired-correct writes.
8. When the band reports `CONNECTED`, the app reads battery/firmware/analytics and the UI eventually moves to `Paired`.

## v3 / BTM Initialization

Source:

- `base/smali_classes2/com/impossible/bondtouch/bondtouchble/bluetooth/v3/BleBondManagerV3.smali`
- `base/smali_classes2/com/impossible/bondtouch/bondtouchble/bluetooth/v3/BleAppCommandHelper.smali`
- `base/smali_classes2/com/impossible/bondtouch/bondtouchble/bluetooth/v3/WriteFFB1Command*.smali`

Service and characteristics:

- Service: `d3c00003-add3-1d4b-0000-0000b5006bb1`
- FFB1: `d3c0ffb1-add3-1d4b-0000-0000b5006bb1`
- FFB2: `d3c0ffb2-add3-1d4b-0000-0000b5006bb1`
- CCCD: `00002902-0000-1000-8000-00805f9b34fb`

Startup sequence:

1. `BleBondManagerV3` constructor calls `initAuthFlow(gatt)` immediately.
2. `initAuthFlow()` posts a delayed connection-priority request after 3000 ms.
   - It calls `gatt.requestConnectionPriority(0)`.
3. It validates the v3 user-application service and required characteristics.
4. It enables notifications on FFB1.
5. It writes start-auth to FFB1:

```text
01 30
```

`01` is `IDENTIFIER_APP_CMD`; `30` is `APP_CMD_START_AUTH`.

FFB1 command encoding:

- `WriteFFB1Command$SingleByteCommand(identifier, byteValue)` writes `[identifier, byteValue]`.
- `WriteFFB1Command$DefaultCommand(identifier, payload)` writes `[identifier, payload...]`.
- Write type is set to `2`.

Expected v3 state machine on FFB1 notifications:

```text
04 82    SYS_STATE WAITING_TOKEN
         app writes: 03 31 32 33 34 35 36 37 38

04 83    SYS_STATE WAITING_AUID
         app writes: 07 <auid-low-byte>

04 84    SYS_STATE OOBE
         app treats as AUID error

04 85    SYS_STATE CONNECTED
         app resumes command queue, clears waiting/busy, reads analytics/battery/firmware

04 8A    SYS_STATE WAIT_FOR_ACT_PAIRED
         app writes paired-correct: 01 31

04 8D    SYS_STATE WAIT_DOUBLE_TAP
         app only logs the state; user action is expected on the bracelet

04 8E    SYS_STATE TEST_COLORS
         app only logs the state; likely band-side LED prompt

04 8F    SYS_STATE WAITING_START_AUTHENTICATION
         app only logs the state
```

Important v3 app writes:

```text
01 30                         start auth
03 31 32 33 34 35 36 37 38    token string "12345678"
07 <auid-low-byte>             AUID
01 31                         paired-correct
```

v3 event parsing on FFB1:

- Byte 0 is the identifier.
- If byte 0 is `02`, the app treats byte 1 as an event id.
- Event `B2` is `EVENT_NO_ACTION_DOUBLE_TAP_DETECTED`; the app calls `onDoubleTap()`.
- Event `13` causes the app to log `APP_EVENT_PAIRED_SIGNAL` and write paired-correct `01 31`.
- Event `B3` activates the user-selection menu.
- Event `B4` means a user/menu item was selected.

Note: `Constants.smali` lists `EVENT_PAIRED_SIGNAL` as `0x16`, but the actual v3 dispatch code checks literal `0x13` before calling `sendPairedSignal()`. Treat `0x13` as the code path to verify in live capture.

Current interpretation of your remembered double-tap step:

- The app really does have an explicit double-tap callback.
- The band likely enters `WAIT_DOUBLE_TAP` (`04 8D`) or a related color-test/prompt state.
- After the physical double tap, the band should emit either a double-tap event (`02 B2`) and/or a paired-signal/state (`02 13` or `04 8A`).
- The app's "unlock/confirm" response is paired-correct: `01 31` to FFB1.
- The white flash appears likely to be band-side behavior triggered by the auth/prompt state, because the app only logs `TEST_COLORS` and does not write a visible color command in the v3 auth path.

## v2 Initialization

Source:

- `base/smali_classes2/com/impossible/bondtouch/bondtouchble/bluetooth/v2/BleBondManagerV2.smali`
- `base/smali_classes2/com/impossible/bondtouch/bondtouchble/bluetooth/v2/BleBondManagerV2Base.smali`
- `base/smali_classes2/com/impossible/bondtouch/bondtouchble/bluetooth/v2/BleAppCommandHelper.smali`

Service and characteristics:

- Device info service: `d3c00001-add3-1d4b-0000-0000b5006bb1`
- User application service: `d3c00002-add3-1d4b-0001-0000b5006bb1`
- FFA1: `d3c0ffa1-add3-1d4b-0001-0000b5006bb1`
- FFA2: `d3c0ffa2-add3-1d4b-0001-0000b5006bb1`
- FFA4: `d3c0ffa4-add3-1d4b-0001-0000b5006bb1`
- FFA5: `d3c0ffa5-add3-1d4b-0001-0000b5006bb1`
- FFA6: `d3c0ffa6-add3-1d4b-0001-0000b5006bb1`

Startup sequence:

1. `BleBondManagerV2Base.initialize(gatt)` checks required services and characteristics.
2. It reads battery level.
3. It requests connection priority.
4. It enables notifications:
   - battery characteristic
   - FFA5 for app events
   - FFA4 for state changes
5. `BleBondManagerV2.initAuthFlow(gatt)` sends the token to FFA1:

```text
31 32 33 34 35 36 37 38
```

Expected v2 state machine on FFA4 notifications:

```text
82    WAITING_TOKEN
      app writes token to FFA1: 31 32 33 34 35 36 37 38

83    WAITING_AUID
      app writes AUID to FFA2 as 4-byte unsigned little-endian

84    OOBE
      no paired path; treated as setup/auth problem elsewhere

85    CONNECTED
      app resumes queue, clears waiting/busy, reads firmware

86    MSG
      app pauses queue and marks waiting-touch true

88    QUEUE_EXECUTING
      app marks busy true
```

Expected v2 app events on FFA5:

```text
11    AUID_RIGHT
12    AUID_ERROR
13    PAIRED_SIGNAL
14    SEND_MSG
15    SEND_MSG_TIMEOUT
16    TAP_DETECTED_SIGNAL
17    NO_ACTION_DOUBLE_TAP_DETECTED
```

When v2 sees `PAIRED_SIGNAL` (`13`), it writes paired-correct to FFA6. `buildPairedMessage()` uses a `DefaultCommand`, so the payload is padded to 20 bytes:

```text
31 00 11 22 33 44 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

When v2 sees `NO_ACTION_DOUBLE_TAP_DETECTED` (`17`), it calls `onDoubleTap()`.

## v4 / BT4 Initialization

Source:

- `base/smali_classes2/com/impossible/bondtouch/bondtouchble/bluetooth/v4/BleBondManagerV4.smali`
- `base/smali_classes2/com/impossible/bondtouch/bondtouchble/bluetooth/v4/BleBondManagerV4Kt.smali`

Static findings:

- v4 extends `BleBondManagerV2Base`, not v3.
- Device type is `BT4`.
- Supported firmware is `2.0.3`.
- It uses the legacy user application service `d3c00002-add3-1d4b-0001-0000b5006bb1`.
- It also uses FFA8 `d3c0ffa8-add3-1d4b-0001-0000b5006bb1`.

Startup/auth behavior:

1. `BleBondManagerV4.initAuthFlow(gatt)` calls `readState(gatt)`.
2. The inherited v2-base state handler responds to:
   - `WAITING_TOKEN` by writing token to FFA1.
   - `WAITING_AUID` by writing AUID to FFA2.
   - `CONNECTED` by resuming the command queue and reading firmware.
3. `requestConnectionPriority(gatt)` is a no-op for v4.

Bonding-specific behavior:

- `startBonding(gatt)` disables ANCS.
- If Android has no bonded device for the address, it enables notifications on FFA8.
- Static analysis did not yet map what FFA8 emits; capture it during real setup.

v4 also adds partner menu behavior:

- It reads a device/menu id from event byte offset `0x0e`.
- Touches are routed to FIRST/SECOND/THIRD menu index based on device id.
- Active partner menu entries set high bit `0x80` in the menu index config byte.
- `sendMessage()` delegates to v2-base message sending, then writes selected user index.

## Mini Initialization

Source:

- `base/smali_classes2/com/impossible/bondtouch/bondtouchble/bluetooth/mini/BleBondManagerMini.smali`
- `base/smali_classes2/com/impossible/bondtouch/bondtouchble/bluetooth/mini/BleBondManagerMiniKt.smali`

Static findings:

- Mini extends `BleBondManagerV2Base`, not v3.
- Device type is `BT_MINI`.
- Supported firmware is `1.0.4`.
- It uses FFA8 `d3c0ffa8-add3-1d4b-0001-0000b5006bb1`.

Startup/auth behavior:

1. `BleBondManagerMini.initAuthFlow(gatt)` calls `readState(gatt)`.
2. The inherited v2-base state handler handles token/AUID/connected states.
3. It requests connection priority after 3000 ms.
4. `startBonding(gatt)` disables ANCS and enables FFA8 notifications if Android has no bonded device.

Mini fixed pattern:

```text
500, 250, 500, 250, 500
```

Incoming touches and outgoing messages are both forced to this fixed pattern.

## Live Capture Checklist

When the band is available, collect one official-app pairing run and compare against this map.

1. Enable Android Bluetooth HCI snoop logging.
2. Start a fresh pairing/setup in the official app.
3. Tap the band to make it advertise.
4. Select it in the app.
5. Record the visible LED prompt, especially white flashing.
6. Double tap the band when asked.
7. Export the HCI log.

Specific packet patterns to look for:

- v3 service present: `d3c00003...`
- v3 notification enable on FFB1.
- v3 write `01 30` to FFB1.
- v3 notifications `04 82`, `04 83`, `04 8D`, `04 8A`, `04 85`.
- v3 writes `03 31 32 33 34 35 36 37 38`, `07 <auid>`, and `01 31`.
- v3 double-tap related events `02 B2` and/or paired-signal `02 13`.
- v2 notifications on FFA4/FFA5 if the band is not v3/BTM.
- v2 token write to FFA1 and paired-correct padded write to FFA6.

## Open Questions

- Which protocol generation the actual duo band uses.
- Whether the white flash is purely firmware-driven or caused by an app command in another branch.
- Whether the paired confirmation always uses event `13`, state `8A`, or both.
- Exact FFA8 behavior for v4/mini bonding.
- Whether a PC bridge can reuse the same local AUID/address pairing state cleanly, or whether first-time PC pairing must replay the full confirmation flow.
