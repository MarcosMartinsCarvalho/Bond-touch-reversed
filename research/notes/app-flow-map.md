# Bond Touch Android App Flow Map

Date: 2026-06-15

This is the second static-analysis pass over the pulled Bond Touch Android app. It maps the app buttons, settings screens, connection flow, and message/touch paths that matter for building an independent app for the user's own bracelet.

This file is intentionally practical: what the user taps, what class handles it, what BLE or repository call follows, and what still needs live verification with the physical band.

## Scope And Readiness

Known from static APK analysis:

- The app has enough local BLE logic to map scanning, device generation detection, pairing/auth initialization, saved address/generation/AUID state, settings writes, local replay, touch-library sends, widget sends, and live vibration tests.
- The "tap/double-tap to confirm" pairing step is real. The official UI tells the user the bracelet should be flashing, then waits for the BLE manager to report the device as connected/paired.
- For legacy/v2/v4/Mini style devices, the local vibration/message write path is well mapped.
- For Bond Touch More/v3, the auth and normal message packet format are mapped, and the settings "send single vibration" command is identified, but exact immediate-rumble behavior should be verified on hardware.

Answer to "is it ready to build a working Android Studio app?":

- Ready enough to build a serious Android MVP that scans, connects, discovers generation, runs the auth/init sequence, saves local pairing state, and exposes test buttons.
- Not ready enough to guarantee first-run success on every band without live BLE captures. The remaining risky parts are v3 immediate vibration behavior, timing/queue busy states, and exact pairing-confirmation edge cases.

## Source Anchors

Primary classes inspected in the JADX source tree:

```text
bondtouchcommon/connect/ConnectInfoActivity.java
fragments/ConnectInfoFragment.java
bondtouchcommon/connect/ConnectPermissionsActivity.java
bondtouchcommon/connect/ConnectActivity.java
bondtouchcommon/connect/ConnectSearchingFragment.java
bondtouchcommon/connect/ConnectSearchingFragmentApi25.java
bondtouchcommon/connect/ConnectSetupFragment.java
bondtouchcommon/connect/viewmodels/ConnectViewModel.java

bondtouchble/bluetooth/BleBondClient.java
bondtouchble/bluetooth/BleBondService.java
bondtouchble/bluetooth/BleBondPrefs.java
bondtouchble/bluetooth/v2/BleBondManagerV2.java
bondtouchble/bluetooth/v2/BleBondManagerV2Base.java
bondtouchble/bluetooth/v3/BleBondManagerV3.java
bondtouchble/bluetooth/v4/BleBondManagerV4.java
bondtouchble/bluetooth/mini/BleBondManagerMini.java

bluetooth/BleBondRepository.java
repository/MessagesRepository.java
viewmodels/TouchHistoryViewModel.java
viewmodels/TouchMeaningLibraryViewModel.java
viewmodels/TouchMeaningEditViewModel.java
widget/action/SendMeaningWorker.java

fragments/SettingsListFragment.java
fragments/SettingsDeviceFragment.java
viewmodels/SettingsDeviceViewModel.java
viewmodels/SettingsUserProfileColorViewModel.java
viewmodels/SettingsUserProfileVibrationViewModel.java
viewmodels/SettingsUserProfileVibrationV3ViewModel.java
fragments/SettingsUserProfileVibrationFragment.java
fragments/SettingsUserProfileVibrationV3Fragment.java

fragments/v3/dashboard/DashboardUserFragment.java
fragments/v3/dashboard/DashboardPartnerFragment.java
fragments/v3/dashboard/DashboardMessageSelectFragment.java
fragments/v3/dashboard/DashboardMessageCustomEditFragment.java
components/DashboardUserPartnerView.java
```

## Android Components And Permissions

Important manifest components:

- `KickoffActivity`: launcher.
- `DashboardActivity`: main app dashboard after login/setup.
- `SelectBondDeviceActivity`: lets user pick Bond Touch bracelet, Bond Lite/watch, or Heart product flow.
- `ConnectInfoActivity`: "Prepare your bracelet and start pairing".
- `ConnectPermissionsActivity`: Bluetooth/location permission gate before scanning.
- `bondtouchcommon.connect.ConnectActivity`: active bracelet scan/setup/paired/error screen.
- `TouchMeaningActivity` and `TouchMeaningEditActivity`: preset touch/meaning library and edit/replay flows.
- `DashboardMessageActivity`: dashboard partner text/status message editor, not the vibration pattern sender.
- `bondtouchcommon.dfu.DfuActivity`: firmware update path for DFU names.
- `BleBondService`: foreground connected-device service around `BleBondClient`.
- `BleBondCompanionService`: Android Companion Device Service hook.
- `BondMeaningsWidgetReceiver`: Android Glance widget receiver for sending meanings.

Relevant permissions:

- `android.hardware.bluetooth_le` required.
- Old Android: `BLUETOOTH`, `BLUETOOTH_ADMIN`, location permissions.
- New Android: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS`.
- Foreground service: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- Companion device presence/background permissions.

For a custom Android app, the minimal practical permission set is:

- Android 12+: `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`.
- Android 11 and below: location permission for BLE scan.
- A foreground service with connected-device type if the app should keep the bracelet connected/reconnecting in the background.

## Top-Level User Flow

The app's official flow is roughly:

1. User chooses the bracelet product in `SelectBondDeviceActivity`.
2. App opens `ConnectInfoActivity`.
3. User taps "Start pairing".
4. `ConnectInfoFragment` calls `ConnectInfoViewModel.onGoToPairing()`.
5. `ConnectInfoActivity` receives the status and calls `goNext()`.
6. `goNext()` starts `ConnectPermissionsActivity` with default scanner type.
7. `ConnectPermissionsActivity` checks Bluetooth/location permissions and starts `ConnectActivity`.
8. `ConnectActivity` scans with Companion Device Manager by default.
9. User selects a device or the app falls back to an already associated/saved device.
10. `ConnectSetupFragment` starts the BLE connection and shows "double-tap/press while flashing".
11. `BleBondClient` connects, discovers services, detects generation, creates the generation manager, and runs the auth/init flow.
12. The bracelet state machine emits paired/connected events.
13. The app saves address, generation, AUID, and user index, then pushes current color/vibration/menu settings.

## Connect And Pairing UI

### Select Device

`SelectBondDeviceActivity` uses Compose screens:

- Bracelet selection goes to `ConnectInfoActivity`.
- Bond Lite/watch selection goes to Bond Lite setup or subscription flow.
- Heart selection goes to Heart flow.
- If the bracelet or Heart is already paired, the select activity can finish itself on start.

### Prepare Screen

`ConnectInfoActivity` displays `ConnectInfoFragment`.

The main button is the "Start pairing" button. Static path:

```text
ConnectInfoFragment.buttonMain click
  -> ConnectInfoViewModel.onGoToPairing()
  -> ConnectInfoActivity observes ConnectInfoStatus
  -> ConnectInfoActivity.goNext()
  -> start ConnectPermissionsActivity
```

The intent includes:

```text
ConnectActivity.ARG_SCANNER_TYPE = ConnectActivity.ScannerType.default
```

The default is Companion Device Manager scanning.

### Permissions

`ConnectPermissionsActivity`:

- Reads the scanner type from the intent, defaulting to Companion Device Manager.
- Requests Bluetooth permissions where needed.
- Requests location permission/location service only where the chosen scan type/platform requires it.
- On success starts `ConnectActivity` with the same scanner type.

### Scan Screen

`ConnectActivity.replaceConnectFragment()` maps view-model steps to fragments:

- `Searching(CompanionDeviceManager)` -> `ConnectSearchingFragment`.
- `Searching(BleScanner)` -> `ConnectSearchingFragmentApi25`.
- `Setup` -> `ConnectSetupFragment`.
- `Paired` -> `ConnectPairedFragment`.
- Error states -> `ConnectErrorFragment`.

`ConnectSearchingFragment` uses Android Companion Device Manager:

- Filters names with regex-like patterns for `BOND`, `BOND_DFU`, `Bond More`.
- Cleans old associations and disconnects existing connected devices.
- If paired Bond devices are already associated, waits about 15 seconds then tries one.
- If no devices appear, times out after about 35 seconds.
- If selected device name is a DFU name, starts `DfuActivity`.
- On Android 12+ it calls `startObservingDevicePresence(address)`.
- Otherwise it passes the selected `BluetoothDevice` to `ConnectViewModel.onDeviceSelected()`.

`ConnectSearchingFragmentApi25` is the direct BLE fallback:

- Uses `BleBondScanHelper`.
- Scans for normal names plus DFU names:
  - `BOND`
  - `Bond More`
  - `BOND 4`
  - `BOND MINI`
  - `BOND_DFU`
  - `BOND_M_DFU`
  - `BOND_4_DFU`
  - `BOND_MINI_DFU`
- Tapping a DFU device starts `DfuActivity`.
- Tapping a normal bracelet calls `ConnectViewModel.onDeviceSelected(device)`.

### Setup / Flashing / Confirmation

`ConnectSetupFragment` is the screen matching the user's description.

Observed strings:

- Non-Mini: "Your bracelet should be flashing right now. Double-tap it to continue."
- Mini: "Your bracelet should be flashing right now. Press it to continue."

Behavior:

- `onStart()` immediately calls `connectToDevice()`.
- `connectToDevice()` starts a 60 second timeout and calls `ConnectViewModel.connect()`.
- The UI changes image/copy based on Mini, Bond More, or regular bracelet.
- "Bracelet not flashing?" opens a help bottom sheet.
- The fragment listens to BLE connection state:
  - `CONNECTED` -> remove timeout and call `viewModel.onDevicePaired()`.
  - `OOBE` -> log only.
  - `DISCONNECTED` -> log only.
- Timeout currently calls the pair-failed path.

Important inference:

- The app does not manually wait for a raw "double tap" in the setup fragment. The tap/double-tap confirmation is handled in the BLE manager state machine. The setup UI just starts the connection and waits for the BLE manager/client to report connected/paired.

## BLE Client Connection Pipeline

`BleBondClient` is the central local BLE class.

Connect path:

```text
ConnectViewModel.connect()
  -> bleBondClient.setDevice(device)
  -> BleBondClient.connect(device, autoConnect=false)
  -> BluetoothDevice.connectGatt(...)
  -> onConnectionStateChange CONNECTED
  -> discoverServices()
  -> readApiVersion()
  -> initBondManager(generation)
  -> manager.initialize/auth flow
  -> manager listener onDevicePaired()
  -> save prefs and push user settings
```

Generation detection:

- If v3 service exists, use Bond Touch More/v3 manager.
- Otherwise read legacy API version characteristic.
- Legacy byte `0x42` -> BT2.
- Legacy byte `0x44` -> BT4.
- Legacy byte `0x45` -> BT_MINI.
- Else -> BT1.

Saved local state:

```text
SharedPreferences name: BleBondService
SAVED_BLE_DEVICE_ADDRESS
SAVED_GENERATION
SAVED_AUID
SAVED_USER_INDEX
SAVED_BLE_DEVICE_FIRMWARE
```

`SAVED_AUID` is generated locally with `Random.nextInt()` if missing.

Reconnect behavior:

- If Bluetooth turns on and a device is paired, `forceConnect()` runs.
- If disconnected while paired, client reconnects with `autoConnect=true`.
- If address is saved but no Android device object is available, it can scan indefinitely for the saved address and Bond names.
- GATT 133 retries are capped before closing.

Unpair behavior:

- `BleBondClient.unpair()` clears saved prefs/state, stops scanning, disassociates Companion Device Manager address, stops observing presence on newer Android, and closes GATT.

## Pair/Auth State Machines

The detailed byte map is in `protocol-map.md` and `initialization-sequence.md`. Practical summary:

### v3 / Bond Touch More

Service and chars:

```text
service: d3c00003-add3-1d4b-0000-0000b5006bb1
FFB1:    d3c0ffb1-add3-1d4b-0000-0000b5006bb1
FFB2:    d3c0ffb2-add3-1d4b-0000-0000b5006bb1
```

Auth writes:

```text
start auth:      01 30
token:           03 31 32 33 34 35 36 37 38
AUID:            07 <auid-low-byte>
paired correct:  01 31
vibration cfg:   01 48 <op> <intensity> <balance> 03
```

State notifications seen:

```text
04 82 WAITING_TOKEN        -> write token
04 83 WAITING_AUID         -> write AUID low byte
04 84 OOBE                 -> AUID error/unpaired state
04 85 CONNECTED            -> connected, read stats/battery/firmware
04 8A WAIT_FOR_ACT_PAIRED  -> write paired-correct
04 8D WAIT_DOUBLE_TAP      -> app logs; user action is firmware-side
04 8E TEST_COLORS          -> app logs; likely flashing/white prompt
04 8F WAITING_START_AUTHENTICATION -> app logs
```

Event notifications seen:

```text
02 B2 no-action double tap -> app emits OnDoubleTapEvent
02 13 paired signal        -> write paired-correct
02 B3 menu activated
02 B4 user selected
```

### v2 / Legacy / v4 / Mini Family

Main service/chars:

```text
service: d3c00002-add3-1d4b-0001-0000b5006bb1
FFA1:    token/auth write
FFA2:    AUID write
FFA4:    state notify/read
FFA5:    event notify
FFA6:    app command/message write
FFA8:    extra command path for v4/Mini
```

Auth writes:

```text
token to FFA1:     31 32 33 34 35 36 37 38
AUID to FFA2:      4 bytes little-endian
paired correct:    FFA6 write, command 31 padded to 20 bytes
```

State notifications:

```text
82 WAITING_TOKEN -> write token
83 WAITING_AUID  -> write AUID
84 OOBE
85 CONNECTED     -> queue free/read firmware
86 MSG
88 QUEUE_EXECUTING
```

Event notifications:

```text
11 AUID_RIGHT
12 AUID_ERROR
13 PAIRED_SIGNAL -> paired-correct
14 SEND_MSG
15 SEND_MSG_TIMEOUT
16 TAP_DETECTED_SIGNAL
17 NO_ACTION_DOUBLE_TAP_DETECTED -> OnDoubleTapEvent
```

## App Lifecycle And Foreground Service

`BleBondService` wraps `BleBondClient`.

Behavior:

- Starts a foreground notification with notification id `83` when allowed.
- Foreground notification includes Bluetooth enabled, connected, battery, charging, and low battery status.
- Service returns `START_STICKY`.
- When app enters foreground and a bracelet is paired, it calls foreground mode.
- When app leaves foreground and no bracelet is paired, it stops foreground/self.

`BleBondRepository` registers app lifecycle callbacks:

- On app foreground, starts the BLE foreground service.
- Collects user profile changes and pushes `UserBraceletData` to `BleBondClient`.
- Collects partner/menu changes and pushes `PartnersMenu` to the bracelet unless tutorial mode is active.
- Collects BLE events and updates local database/API/analytics.

## Dashboard Buttons And Screens

### Add People / Add Partner

`DashboardUserFragment`:

- Button text is "Add people" if the device supports multi-partner, otherwise "Add partner".
- Click navigates to `Destination.InvitationGraph`.
- No BLE write.

### Get To Know Your Bracelet

`DashboardUserFragment.knowBraceletButton`:

- Enabled only when BLE state says connected.
- Text:
  - Bracelet: "Get to know your bracelet"
  - Bond Lite/watch: "Get to know your app"
- Click path:
  - If Bond Lite/watch, navigates to `Destination.BtlTutorialGraph`.
  - Else asks the view model for the current Bond Touch device and starts the tutorials activity via `NavigationHelper.getTutorialsIntent(...)`.

BLE implication:

- This is a tutorial/test path. Tutorial screens replay predefined `TestMessage` touch patterns through `BleBondRepository.sendTestMessage()`, which then calls the normal local BLE message path.

### Partner Photo / User Photo

`DashboardPartnerFragment` and `DashboardUserPartnerView`:

- User photo opens user overlay.
- Partner photo opens partner overlay.
- Waiting partner photo opens waiting-for-partner overlay.
- Encounter icon navigates to encounters.
- No direct BLE write.

### Partner Message Bubble

`DashboardUserPartnerView.partnerBondMessage` click calls `DashboardPartnerFragment.dashboardMessageListener`.

Path:

```text
partner message bubble click
  -> DashboardMessageActivity with BOND_ID_ARG
  -> DashboardMessageSelectFragment
  -> DashboardMessageViewModel
```

Important distinction:

- This edits a partner/dashboard text/status message, not the vibration pattern.
- Selecting a suggested text or saving custom text calls `BondRepository.setBondMessage(bondId, message, timestamp)`.
- No BLE vibration command is sent from this path.

## Touch History Replay

`TouchHistoryViewModel`:

- `sendMessage(ReceivedMessage)` -> `BleBondRepository.sendMessage(receivedMessage, false)`.
- `sendMessage(SentMessage)` -> `BleBondRepository.sendMessage(sentMessage)`.
- It reads messages from `MessagesRepository`.

BLE implication:

- History replay uses the same local BLE message path as normal received/sent messages.
- This is useful for a custom app: store a pattern once, replay by feeding it into the BLE send-message function.

## Touch Meaning Library

Touch meanings are the preset/reusable vibration patterns.

`TouchMeaning` contains:

```text
id
bondId
name
normalizedPattern
timestamp
startColor
endColor
generation
favourite
deprecated
```

Library send path:

```text
TouchMeaningLibraryViewModel send item
  -> TouchMeaningRepository.getMeaningById(id)
  -> MessagesRepository.sendMeaningFromLibrary(
       meaningId,
       bondId,
       normalizedPattern,
       generation,
       isFromWidget=false
     )
  -> creates SentMessage(TouchMessageContent(timestamp, pattern, generation))
  -> saveAndSendTouchToApi(...)
```

Two important notes:

- In the official social app, sending a meaning is primarily a server/API send to the partner, not necessarily a local bracelet vibration by itself.
- Replaying/editing a meaning locally uses `BleBondRepository.sendMeaningReplayMessage(...)`, which does write to the bracelet.

Meaning edit replay path:

```text
TouchMeaningEditViewModel.replayTouchMessage()
  -> builds TouchMessageContent(timestamp, pattern, generation)
  -> wraps in MeaningMessage(random UUID, content)
  -> bleBondRepository.sendMeaningReplayMessage(
       meaningMessage,
       startColor,
       endColor,
       MenuIndex.FIRST
     )
```

This is one of the clearest local preset-to-bracelet paths.

## Android Widget

The app has an Android Glance widget for "handsfree" preset touches.

Path:

```text
Widget cell click
  -> SendMeaningAction with meaning_id, bond_id, widget_id
  -> WorkManager SendMeaningWorker
  -> TouchMeaningDao.getMeaningById(meaning_id)
  -> reject missing/empty/deprecated meaning
  -> MessagesRepository.sendMeaningFromLibrary(
       id,
       bondId,
       normalizedPattern,
       generation,
       isFromWidget=true
     )
  -> broadcast com.impossible.bondtouch.widget.REFRESH_GLANCE
```

The widget does not introduce a new BLE protocol. It is a shortcut into the same touch-meaning send path.

For a custom app:

- A home-screen widget can be simple: each button maps to a named pattern and calls your local BLE send/replay path.
- If you do not need the official cloud/social send behavior, skip `MessagesRepository.sendMeaningFromLibrary()` style API work and write the pattern to the bracelet directly.

## Message Repository And BLE Repository

`BleBondRepository` is the bridge between app data and `BleBondClient`.

### Incoming Server Message To Bracelet

When a `ReceivedMessage` should play on the local bracelet:

```text
BleBondRepository.onMessageReceived(message)
  -> reject if tutorial mode or message too old
  -> if DND, mark status but do not play
  -> sendMessage(message, replyEnabled=true)
  -> find partner menu index from bondId
  -> find partner color from bondId
  -> if meaningId exists, use meaning start/end colors
  -> sendMessageToBracelet(...)
```

`sendMessageToBracelet(TouchMessage, startColor, endColor, menuIndex, replyEnabled)`:

```text
generation = prefs device type -> app generation enum, else SECOND
pattern = message.content.getPattern(generation)
if pattern empty: use Mini fixed pattern
bleBondClient.sendMessageToBracelet(BondTouchMessage(id, pattern), colors, menuIndex, replyEnabled)
```

Status behavior from decompile:

- The app updates received-message status around playback attempts.
- BLE events later mark messages as played or failed.
- The decompiled condition names are a little tangled, so treat database status exact values as low priority for a local-only app.

### Bracelet Touch To Server

When the physical bracelet senses a touch:

```text
BLE manager emits OnTouchReceived(durations, menuIndex)
  -> BleBondRepository maps menuIndex to partner bondId
  -> if tutorial mode, TutorialMessageRepository handles it
  -> else BondRepository.getPartnerByBondId(...)
  -> MessagesRepository.onTouchMessageFromBracelet(partner, durations, generation)
  -> creates SentMessage(TouchMessageContent(timestamp, durations, generation))
  -> saveAndSendTouchToApi(bondId, timestamp, sentMessage)
```

For a local-only app:

- You can listen for touch notifications from the band and turn them into local game/PC events without any cloud API.
- If the goal is only "make my band vibrate from my phone", this incoming-touch path is optional.

### BLE Event Handling

`BleBondRepository` watches `BleBondClient.eventFlow`:

- `OnSendMessageSuccess` -> mark local message played.
- `OnSendMessageFailed` -> mark local message failed.
- `OnTouchReceived` -> convert bracelet touch into message/API/tutorial flow.
- `OnBatterySettingsRead`, `OnDeviceNumberRead`, `OnFirmwareVersionRead`, `OnFlashStatsRead`, `OnHealthStatsRead`, `OnLotNumberRead` -> analytics/metadata.
- `OnBraceletHeartbeat` -> app considers BLE connected.
- `OnDoubleTapEvent` -> in tutorial mode, starts single-breathe behavior.

## Settings Screens

### Main Settings List

`SettingsListFragment` important rows:

- Profile -> `Destination.PersonalDetailsGraph`.
- My bracelet -> `Destination.MyBraceletGraph`.
- Tutorials -> `Destination.TutorialsHome`.
- DND -> `Destination.Dnd`.
- My people -> `Destination.MyPeopleGraph`.
- Partner -> `Destination.Partner(activePartnerBondId)`.
- Private space -> `Destination.SettingsPrivateSpaceGraph`.
- Help -> `Destination.HelpGraph`.

It also displays bracelet connection/battery status from `BondBleState`.

### My Bracelet / Device Settings

`SettingsDeviceFragment` shows:

- Connection status.
- Battery level and charging state.
- Firmware version once connected.
- Light color entry.
- Vibration level entry where supported.
- Battery alert guide where supported.
- Disconnect/forget button.

Important actions:

- Disconnect/forget shows confirm dialog.
- Positive action calls `SettingsDeviceViewModel.forgetBond()`.
- View model calls `DevicesRepository.forgetBond()`.
- That ultimately reaches `BleBondRepository.forgetDevice()` / `BleBondClient.unpair()`.

### Color Setting

`SettingsUserProfileColorViewModel`:

- `setColor(newColor)` updates local UI state.
- `enableBraceletColor(color)` calls `bleBondClient.setColor(color)` unless device is Bond Lite/watch or Bond Touch More/v3.
- `disableBraceletColor()` calls `bleBondClient.disableColor()`.
- `saveColor()` persists the user color via `UserRepository.updateUserColor(color)` if changed.

BLE implication:

- Older bracelet color preview is a direct BLE color command.
- v3/Bond Touch More color behavior is handled differently; this specific color preview method skips BTM.

### Legacy/Mini Vibration Setting

`SettingsUserProfileVibrationViewModel`:

- On first load, UI can send the current vibration once.
- `onVibrationChanged(level)`:
  - Maps picker level to actual vibration value.
  - Updates UI state.
  - Calls `bleBondClient.sendSingleColorVibration(vibration, userColor)`.
- `sendFirstVibration()` calls the same direct BLE preview path.
- `saveUser()` persists selected vibration if changed.

Vibration mapping:

```text
legacy picker 0 -> 100
legacy picker 1 -> 80
legacy picker 2 -> 60
legacy picker 3 -> 40
default -> 80
```

### Bond Touch More/v3 Vibration Setting

`SettingsUserProfileVibrationV3ViewModel`:

- `setVibration(vibration)` updates state and marks that a vibration should be sent.
- Fragment observes state and calls `sendSingleVibration(validVibration, validBalance)`.
- `sendSingleVibration(vibrationLevel, balanceLevel)` calls `bleBondClient.sendSingleVibration(...)`.
- `setBalanceLevel(balanceLevel)` updates fine tuning and can trigger another test vibration.
- `activateTutorialBottomIndicator(color)` calls BLE tutorial indicator.
- `disableColor()` calls BLE disable color.
- `saveUser()` persists vibration and balance.

v3/Bond Touch More picker mapping:

```text
picker 0 -> 28
picker 1 -> 33
picker 2 -> 58
picker 3 -> 73
picker 4 -> 88
default -> 80 in mapper fallback
```

Balance level:

```text
0..4, default 2
```

## Local BLE Write Surfaces To Recreate

These are the useful calls for a custom app.

### Direct Vibration Test

App-level calls:

```text
BleBondClient.sendSingleVibration(vibrationLevel, balanceLevel)
BleBondClient.sendSingleColorVibration(vibrationLevel, color)
```

Legacy/v2/v4/Mini direct motor payload is based on `LED_MOTOR_CTRL` command `0x26` and a 37-byte packet written to the app command characteristic.

Example v2-style single vibration with strength 100:

```text
26 00 00 64 d0 07 04 00 00 00 ...
```

Meaning:

```text
00: command 0x26
01: reserved
02: queue
03: vibration strength
04-05: vibration time ms little-endian
06: LED mode
07-09: RGB
0a: brightness
0b-0c: transition ms
0d-0e: hold ms
0f-10: sleep ms
11-24: LED execute time padded
```

### Pattern/Message Playback

App-level call:

```text
BleBondClient.sendMessageToBracelet(
  BondTouchMessage(id, pattern),
  startColor,
  endColor,
  menuIndex,
  replyEnabled
)
```

Patterns are lists of durations. Generation conversion is handled by `TouchMessageContent.getPattern(generation)` in the official app.

For v3 FFB2 messages:

```text
byte 0: target/menu
byte 1: pattern count
byte 2: chunk count
byte 3-5: start RGB
byte 6-8: end RGB
byte 9: reply flag
byte 10-19: ff padding
then pattern bytes in 20-byte chunks, padded ff
```

### Background Notification / Chat Buzz

`BleBondRepository.onNewChatNotification()`:

- Secret chat notification -> `bleBondClient.sendRainbowMessage(partnerColor)`.
- Normal chat notification -> `bleBondClient.sendBackgroundVibration(partnerColor)`.

This is useful as another "vibrate from event" pattern, but the raw payload still needs to be verified from the BLE manager if you want to clone it exactly.

### Arrival Notification

`BleBondRepository.playArrivalTouch()`:

- Calls `bleBondClient.sendArrivalNotification(color, ...)`.
- This is a built-in local notification vibration/light sequence.

## Data Model Cheatsheet

`TouchMessageContent`:

```text
timestamp
pattern
generation
```

`TouchMeaning`:

```text
internalId
id
userPhoneNumber
partnerPhoneNumber
bondId
name
normalizedPattern
timestamp
isNew
startColor
endColor
generation
favourite
deprecated
```

`BondTouchMessage`:

```text
id
pattern
```

`PartnersInfo` in `BleBondRepository` maps:

```text
bondId <-> MenuIndex
bondId -> partner color
```

For a single-user custom app, you can hardcode one `MenuIndex` such as `FIRST` for initial testing, then support more menu slots later.

## Custom Android App Blueprint

### Minimal Local-Only MVP

Build this first:

1. Request BLE permissions.
2. Scan for normal device names:
   - `BOND`
   - `Bond More`
   - `BOND 4`
   - `BOND MINI`
3. Show scan results and let user tap their band.
4. Connect GATT with `autoConnect=false`.
5. Discover services.
6. Detect generation:
   - v3 service present -> v3.
   - else read legacy API characteristic and map byte.
7. Generate/load local AUID.
8. Run generation-specific auth/init sequence.
9. Show setup UI while waiting:
   - "Tap/double-tap while flashing" for regular bands.
   - "Press while flashing" for Mini.
10. When connected, persist:
   - address
   - generation
   - AUID
11. Add buttons:
   - "Test vibration"
   - "Short pulse"
   - "Long pulse"
   - "Pattern 1"
   - "Reconnect"
   - "Forget band"
12. Write BLE payloads directly, without any official server/API.

### Recommended Internal App Structure

```text
BleScanner
BleConnectionService
BondGenerationDetector
BondAuthManagerV2
BondAuthManagerV3
BondMessageEncoderV2
BondMessageEncoderV3
BondPatternLibrary
GameEventBridgeClient
```

Keep generation managers separate. The official app does this, and it makes protocol bugs easier to isolate.

### Android Studio Implementation Notes

- Use Kotlin with coroutines/Flow if vibe-coding from scratch.
- Use Android BLE APIs directly, or a small BLE helper library only if it lets you control descriptor writes, characteristic writes, and notification ordering.
- Queue all GATT operations. Android BLE gets flaky if you read/write/enable notifications concurrently.
- After connecting, enable notifications before writing auth bytes.
- Keep the screen/setup timer user-friendly. Official app uses about 60 seconds on setup.
- Store local AUID and do not regenerate it every launch. Regenerating can put the device into OOBE/AUID error behavior.

### PC Game Integration Options

Phone owns BLE, PC sends events to phone:

- Android app opens a local WebSocket/UDP/HTTP listener on Wi-Fi.
- PC game mod/script sends event names like `damage`, `heartbeat`, `boost`, `low_health`.
- Phone maps event to vibration pattern and writes BLE.

PC owns BLE:

- Use a PC BLE library such as Python `bleak`.
- This may work, but Android is easier first because the official app logic, permissions, and reconnect assumptions are phone-centered.

Recommended first architecture:

```text
PC game/mod -> local network message -> Android app -> Bond Touch BLE
```

This avoids fighting Windows BLE while still letting PC games drive the band.

## Hardware Test Checklist

When the band is available, capture these in order:

1. Advertisement names and addresses before tapping/waking.
2. Advertisement names and addresses after tapping/waking.
3. Full service list after connection.
4. API version characteristic value.
5. Notification stream during pairing setup.
6. Exact bytes around:
   - white flash prompt
   - double-tap/press confirmation
   - paired-correct write
   - connected state
7. Direct vibration test on legacy/v4/Mini if applicable.
8. Direct vibration test on v3/Bond More if applicable.
9. Full normal pattern playback write.
10. Busy/queue behavior when sending two patterns quickly.
11. Reconnect behavior after app close/open.
12. Reconnect behavior when bracelet is asleep, then tapped awake.

Use `03_ble/` for captures and logs.

## Known Unknowns

- Whether v3 `sendSingleVibration()` immediately vibrates or only updates vibration configuration/test mode. Static code says the app calls it during the vibration settings screen, so it likely creates a user-visible test, but hardware should confirm.
- Exact v3 pattern byte scaling from long duration list to FFB2 bytes should be validated with a real capture.
- Exact queue/busy retry timing should be tested live.
- Whether a band already paired to the official app accepts a different AUID cleanly, or whether you should reuse the saved AUID from your own app once paired.
- How Mini press confirmation differs at the BLE notification level from double-tap models.
- Whether Android Companion Device Manager association is necessary for reliable background reconnect in a custom app. It is helpful but likely not required for foreground testing.

## Practical Next Step

The next useful engineering step is not more static decompiling. It is a small Android BLE MVP with a verbose log screen:

```text
scan -> connect -> discover -> detect generation -> auth -> show state bytes -> test vibration
```

Then compare the live bytes to `protocol-map.md` and update the unknowns above.
