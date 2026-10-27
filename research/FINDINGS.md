# Complete findings / research notebook

Reviewed 2026-10-27. Band 4 Pop connection and vibration were physically confirmed in the historical worklog. Other-generation maps are static analysis unless explicitly stated otherwise. Original notes below may retain earlier unverified status; consult README.md for the current evidence summary. No new physical tests were performed in this review.

## Packet layout clarification

The 37-byte LED/motor packet has LED execute time at decimal offsets17–18 and padding19–36 (hex11–12 and13–24). Older notes describe this as a 20-byte execute-time area; only its first two bytes contain the u16 value. CONFIG write is24 bytes (command/reserved/property/rw +20 parameter bytes), read is20 bytes. No exact universal periodic keep-alive command is established by the original controller code.

---

Source: `research/notes/app-flow-map.md`

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


---

Source: `research/notes/bond-notify-redesign-2026-10-27.md`

# Bond Notify native UI refresh

Project now lives at `[local path removed]`.

## Implementation

- `GlassStyle.java` centralizes light/dark ink, secondary text, accent and surface colors.
- Native Canvas drawables provide translucent neutral surfaces, a shaded backdrop and a reflective rim. This is the skill's inexpensive native fallback, not the browser SVG displacement implementation. No live backdrop blur or refractive shader is used.
- Drawables recreate gradients on bounds changes and draw only during normal Android view rendering. No timers, continuous animation, wake locks, WebView, new runtime dependencies, or background tasks were added.
- System theme selects the light/dark palette. Dark resources also theme native dialog/dropdown text correctly.
- MainActivity consumes window insets for edge-to-edge Android layouts.
- App rules now show app icons and enable switches. Rule rendering skips unchanged rule lists on BLE broadcasts.
- Rule, alarm and pre-alarm editors share a scrollable editor with switches, a pattern menu, strength/duration/cooldown sliders, color controls, and a test action.
- App picker uses a recycled ListView and lazy icon loading. Search filters names and package IDs. Adding an existing app loads its saved rule rather than replacing settings with defaults.
- Dialog widths are bounded by available screen width, picker height by screen height; native scrolling and resize-on-keyboard support smaller displays.

## Build

JDK: `[local path removed]`.
SDK: `[local path removed]`.

The moved machine had stale JAVA_HOME pointing to E: and Android Studio JBR using Java 25, incompatible with this Gradle version. gradle.properties now uses the installed JDK 17; local.properties points to D: SDK.

PowerShell build:

```powershell
$env:JAVA_HOME='[local path removed]
.\gradlew.bat assembleDebug lintDebug --console=plain
```

APK remains `app\build\outputs\apk\debug\app-debug.apk`.

Validation: `assembleDebug lintDebug` completed successfully on 2026-10-27. Lint retains warnings about existing compatibility branches, localization, and the battery exemption request. The broad package-query permission is intentionally retained for the existing sideloaded app picker, with a local lint annotation; the receiver's pre-33 branch is also annotated after verifying its explicit modern flag branch.

No phone was visible in `[local path removed]` during implementation. Device screenshots, theme/font-scale/rotation inspection, BLE runtime behavior and battery measurements still need on-device verification. Do not describe the static styling as full optical refraction or claim measured battery savings.


---

Source: `research/notes/color-command-map.md`

# Bond Band 4 Color Command Map

Date: 2026-06-15

This file maps the Band 4 / v2-family color behavior found after the first live app test succeeded.

## Key Finding

The first Android prototype buzzed correctly, but the old **White Flash Buzz** did not flash because it wrote RGB and brightness while leaving `ledExecuteTimeMs` at `0`.

Official `buildSingleColorVibrationMessage(strength, color)` sets:

```text
command              0x26 LED_MOTOR_CTRL
queue                0x00
vibration strength   caller value
vibration time       2000 ms / d0 07
led mode             0x01 On
rgb                  from 0xRRGGBB
brightness           0x50 / 80
transition           0
hold                 0
sleep                0
led execute time     2000 ms / d0 07
```

So the LED runtime is separate from the vibration runtime.

## Immediate LED And Motor Command

Write to:

```text
service  d3c00002-add3-1d4b-0001-0000b5006bb1
char     d3c0ffa6-add3-1d4b-0001-0000b5006bb1
```

Packet length is 37 bytes:

```text
00     command, 0x26
01     reserved, 0x00
02     queue
03     vibration strength
04-05  vibration time ms, little-endian uint16
06     LED mode
07     red
08     green
09     blue
0a     LED brightness
0b-0c  LED transition time ms, little-endian uint16
0d-0e  LED hold time ms, little-endian uint16
0f-10  LED sleep time ms, little-endian uint16
11-24  LED execute time ms as little-endian uint16, padded to 20 bytes
```

LED modes:

```text
01 On
02 Breathe
03 Blink
04 Off
```

Official color buzz example, white, strength 80:

```text
26 00 00 50 d0 07 01 ff ff ff 50 00 00 00 00 00 00 d0 07 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

Official display-color behavior uses vibration `0`, LED `On`, brightness `80`, and LED execute `0xffff`:

```text
26 00 00 00 00 00 01 RR GG BB 50 00 00 00 00 00 00 ff ff 00 ... padded
```

Hide/undisplay color uses LED mode `Off` with all timings zero.

## Persistent Color Settings

Stored/default color settings use command `0x27 CONFIG`, not `0x26 LED_MOTOR_CTRL`.

Write packet shape:

```text
00     command, 0x27 CONFIG
01     reserved, 0x00
02     config property id
03     rw flag, 0x01 for write
04-07  color as 4-byte big-endian integer, usually 00 RR GG BB
08-23  zero padding
```

Total length is 24 bytes because the config parameter area is padded to 20 bytes.

Read packet shape:

```text
00     command, 0x27 CONFIG
01     reserved, 0x00
02     config property id
03     rw flag, 0x10 for read
04-13  zero padding
```

Read packet total length is 20 bytes.

Color-related config property ids:

```text
21 LOW_BAT_COLOR
22 OOBE_COLOR
23 OOBE_PAIRED_COLOR
24 DOUBLE_TAP_COLOR
25 TOUCH_COLOR
26 TOUCH_WARNING_COLOR
27 ERROR_COLOR
28 CHARGING_COLOR
29 CHARGE_DONE_COLOR
```

Other nearby config ids:

```text
11 OUID
12 TOKEN
13 MAX_TOUCH_COUNT
14 MIN_TOUCH_DURATION
15 MAX_TOUCH_DURATION
16 MESSAGE_TIMEOUT
17 LOW_BAT_THRESHOLD
18 MOTOR_STRENGTH
19 BLE_PARAMS
30 ACCEL_SENSITIVITY
31 DISCONNECTED_PARAM
33 ANCS_PARAM
35 FACTORY_MODE
37 SELECTED_USER
ef RESET_CONFIG
```

Example: write touch color to pink `0xFF2D95`:

```text
27 00 25 01 00 ff 2d 95 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

## Android Prototype Update

`apps/android/bridge` now includes a **Color Lab**:

- Select color: White, Red, Green, Blue, Pink, Cyan.
- `Color Buzz`: official-style color + vibration packet with LED execute `2000 ms`.
- `Show Color`: LED on with execute `0xffff`.
- `Hide Color`: LED off.
- `Breathe Color`: experimental breathe packet using LED mode `0x02`.
- Stored settings:
  - Set Touch -> config `0x25`
  - Set Double Tap -> config `0x24`
  - Set Warning -> config `0x26`
  - Set Error -> config `0x27`

## Next Live Tests

1. Test **Color Buzz** with each palette color.
2. Test **Show Color**, then **Hide Color**.
3. Test **Breathe Color** and adjust transition/hold/sleep/execute timings if needed.
4. Test stored settings one at a time:
   - Set Touch color, then trigger a normal touch.
   - Set Double Tap color, then double tap the band.
   - Set Warning/Error only after the immediate color commands are confirmed.
5. If the band sends a config-read notification after writes, map which characteristic reports the response.


---

Source: `research/notes/handoff-dossier.md`

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


---

Source: `research/notes/initialization-sequence.md`

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


---

Source: `research/notes/notification-haptics-app.md`

# Notification Haptics App

Date: 2026-06-16

## Purpose

`apps/android/notify` is a new, separate Android app for direct phone-to-bracelet notification and alarm haptics. It is not the Minecraft bridge, not the PC bridge, and not the music/bass detector. It should be kept separate so `apps/android/bridge` remains available for future BLE editing and experiments.

## Android Research Decisions

Official Android docs used:

- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Foreground services overview: https://developer.android.com/develop/background-work/services/fgs
- NotificationListenerService API: https://developer.android.com/reference/android/service/notification/NotificationListenerService
- Schedule alarms: https://developer.android.com/develop/background-work/services/alarms
- Doze and App Standby: https://developer.android.com/training/monitoring-device-state/doze-standby

Design conclusions:

- Notifications should be handled with `NotificationListenerService`, not polling.
- BLE should live in a foreground service declared as `android:foregroundServiceType="connectedDevice"`.
- Android 14+ requires the matching `FOREGROUND_SERVICE_CONNECTED_DEVICE` manifest permission and a runtime prerequisite such as granted Bluetooth permission.
- Foreground services show a status-bar notification. The app makes this a quiet, local-only, secret-visibility notification with no vibration/sound.
- Doze and App Standby can defer normal background work. For this app, the best lightweight approach is event-driven notification callbacks plus a foreground BLE service, not polling timers.
- A permanent partial wake lock was intentionally avoided. It would increase battery use and is not the right way to survive Doze.
- Alarm support for other apps is notification-based. The app watches `Notification.CATEGORY_ALARM` and alarm-like clock packages/text rather than scheduling its own alarms.

## App Architecture

Package: `com.marco.bondnotifyhaptics`

- `MainActivity.java`
  - Plain Java Android UI, no Compose or external dependencies.
  - Permission buttons for Bluetooth, notifications, notification listener settings, and battery optimization settings.
  - BLE scan and saved bracelet connect/stop/test controls.
  - Per-app rule editor.
  - Alarm rule editor.
  - Quiet-hours toggle.

- `BondNotificationListener.java`
  - Receives posted/removed notifications.
  - Ignores the app's own notifications, ongoing non-alarm notifications, transport/service/progress/status categories.
  - Requires selected package rules for normal app notifications.
  - Detects alarm notifications separately and can repeat haptics until the alarm notification is removed.
  - Applies per-package cooldowns and quiet hours.

- `BondHapticService.java`
  - Foreground BLE service.
  - Saves the selected bracelet address/name and reconnects with backoff.
  - Handles Band 4 / v2-family auth and command writes.
  - Queues haptics until ready.
  - Stop Service disables reconnect and removes the foreground notification.

- `BondConfig.java`
  - SharedPreferences keys and rule serialization.
  - Patterns: `single`, `double`, `triple`, `heartbeat`, `warning`, `long`, `breathe`.
  - Color palette cycling and rule sanitizing.

## Build and Install

The new project needed the same Gradle environment files as the working Android bridge:

- `gradle.properties`
  - pins Gradle to Android Studio JBR at `[local path removed]`
- `local.properties`
  - points SDK to `[local path removed]`

Build command:

```powershell
cd "[local path removed]"
.\gradlew.bat assembleDebug
```

Install command:

```powershell
& "[local path removed]" install -r "[local path removed]"
```

As of the 2026-06-16 build, `assembleDebug` succeeds and the app was installed to phone `[ADB device ID removed]` with bundled ADB.

## Behavior Choices

- Default service state is off until the user scans/connects or taps Connect Saved.
- `MainActivity.onStart()` only starts the BLE foreground service when:
  - the app is enabled,
  - a bracelet address is saved,
  - phone/Bluetooth permissions are granted.
- Leaving the app while scanning stops the scan.
- The notification listener will not start haptics unless the app is enabled.
- The app keeps the foreground BLE service only while enabled. Stop Service removes the persistent notification.

## Next Useful Improvements

- Add editable quiet-hours start/end instead of the current fixed 23:00-07:00 toggle.
- Add a small rule import/export screen for backing up selected apps and patterns.
- Add a notification test sender inside the app after notification access is granted.
- Add optional package presets for common apps after testing on the user's phone.
- Add a foreground-service health check tile showing reconnect attempt, battery, firmware, and last BLE state.


---

Source: `research/notes/protocol-map.md`

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


---

Source: `research/notes/worklog.md`

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


---

Source: `apps/android/bridge/README.md`

# Bond Band Bridge

Local right-to-repair BLE controller for a Bond Touch Band 4 / Band 4 Pop.

This is a first working app scaffold based on the decompiled protocol notes in `../../research/notes`. It does not use Bond cloud APIs or account data. It scans locally over BLE, connects to the band, runs the expected Band 4 auth flow, logs the raw state/event bytes, and writes vibration commands.

## First test

1. Install the debug APK on your phone.
2. Fully close the official Bond Touch app so it does not keep the band connection.
3. Open this app and grant Bluetooth permissions.
4. Wake the band by tapping it.
5. Tap **Scan for Bond Band**.
6. Pick the `BOND 4` / `BOND` result.
7. If the band flashes white, double tap it to confirm.
8. When the app says `READY`, try **Strong Buzz**.

## What is implemented

- Band scan with `BOND`, `BOND 4`, `Bond More`, and `BOND MINI` name matching.
- Legacy/v2-v4 service discovery.
- Band 4 init flow:
  - read API generation byte when available
  - enable FFA4 state notifications
  - enable FFA5 event notifications
  - enable FFA8 notifications when present
  - read FFA4 state
  - write token `12345678` to FFA1 when state is `0x82`
  - write local AUID to FFA2 when state is `0x83`
  - write paired-correct command to FFA6 when event is `0x13`
  - mark connected when state is `0x85`
- Vibration write through FFA6 using the LED/motor command format mapped from the official app.
- Color Lab controls:
  - selected-color buzz using official LED execute timing
  - show/hide selected color
  - experimental breathe selected color
  - write stored touch, double-tap, warning, and error colors
- Haptic song test:
  - **Bad Apple Haptic Test** schedules an accented vibration rhythm at about 138 BPM.
  - **Stop Pattern** cancels pending scheduled pulses and clears queued BLE writes.
- Phone music/bass haptics:
  - **Phone Bass Haptics** uses Android playback capture to detect bass from audio playing on the phone.
  - Android will show a capture/cast permission prompt. Some apps block playback capture, so the meter can stay flat for those apps.
  - It mirrors the Windows bass detector controls: sensitivity, trigger, beat threshold, cooldown, pulse limit, strength range, duration, pattern, and color toggle.
  - Detected hits use the same local FFA6 haptic pattern sender as Minecraft and the PC bass app.

## Important UUIDs

- User app service: `d3c00002-add3-1d4b-0001-0000b5006bb1`
- API/generation char: `d3c0ff10-add3-1d4b-0000-0000b5006bb1`
- FFA1 token char: `d3c0ffa1-add3-1d4b-0001-0000b5006bb1`
- FFA2 AUID char: `d3c0ffa2-add3-1d4b-0001-0000b5006bb1`
- FFA4 state char: `d3c0ffa4-add3-1d4b-0001-0000b5006bb1`
- FFA5 event char: `d3c0ffa5-add3-1d4b-0001-0000b5006bb1`
- FFA6 command char: `d3c0ffa6-add3-1d4b-0001-0000b5006bb1`
- FFA8 Band 4 extra char: `d3c0ffa8-add3-1d4b-0001-0000b5006bb1`


---

Source: `06_minecraft_bridge/README.md`

# BondCraft Minecraft Haptics Bridge

This folder contains the Minecraft-to-Bond-Band loop.

Active flow:

1. Minecraft Java 1.21.1 NeoForge mod emits local game events.
2. Minecraft applies the in-game haptic settings and sends final patterns.
3. PC bridge forwards those final patterns to the Android phone.
4. Android app keeps the BLE connection alive and plays the requested vibration/color pattern.

## Pieces

- `neoforge_mod`: Minecraft Java 1.21.1 NeoForge client mod.
- `pc_companion`: tiny Python/Tkinter forwarder from Minecraft to phone.
- `..\apps/android/bridge`: Android app with BLE control, final-pattern playback, and screen-off bridge locks.

## Built Outputs

- Mod jar: `dist\bondcraft-0.1.0.jar`
- Android APK: `dist\BondBandBridge-game.apk`

## Run Order

1. Install/open the Android Bond Band Bridge app.
2. Connect the band in the Android app.
3. Start the PC bridge:
   - Double-click `run_companion.bat`, or
   - Run `python pc_companion\bondcraft_companion.py`
4. In the PC bridge, set `Phone host` to the phone IP shown in the Android app.
   - USB option: press `ADB Forward` and keep phone host `127.0.0.1`.
5. Press `Send Test`.
6. Copy `dist\bondcraft-0.1.0.jar` into your modpack instance's `mods` folder.
7. Launch Minecraft Java 1.21.1 with NeoForge `21.1.217`.
8. Press `B` in-game to open the BondCraft haptic settings GUI.

## Minecraft Config

NeoForge creates the client config after launch in your active instance/config folder:

`config\bondcraft-client.toml`

Important values:

- `enabled`: master switch.
- `bridgeUrl`: default `http://127.0.0.1:8765/event`.
- `creeperRadius`: blocks scanned for creeper warnings.
- `hostileRadius`: blocks scanned for other hostile mob warnings.
- `stepDistanceCm`: distance between step events.
- `verboseLogging`: logs every emitted event in the Minecraft log.

The detailed haptic rules are saved in:

`config\bondcraft-haptics.json`

You can edit them from the in-game `B` menu. It includes global min/max strength, duration, scale, color toggle, burst limit, and per-event controls for pattern, strength, duration, cooldown, and enable/disable.

The `B` menu is split into categories: Core, Move, World, Tools, Threats, Cobblemon, and All. Mouse wheel or the page buttons move through long categories. The `Calm` button turns down noisy movement/mining rules without deleting your other tuning.

Cobblemon support is optional. If Cobblemon is installed, BondCraft watches Cobblemon battle screens and emits conservative haptics for battle start, damage, crits, super-effective/not-effective/no-effect cues, fainting, and type-flavored hits like fire, water, wind/flying, electric, ice, ground, rock, grass, psychic, ghost, dark, fairy, dragon, poison, steel, bug, fighting, and normal.

Preset files:

- Import: put a downloaded preset at `config\bondcraft-haptics-import.json`, then press `Import`.
- Export: press `Export` to write `config\bondcraft-haptics-export.json`.

## Rebuild

Mod:

```bat
cd neoforge_mod
gradlew.bat build
```

Android app:

```bat
cd ..\apps/android/bridge
gradlew.bat assembleDebug
```

PC bridge:

```bat
cd pc_companion
python -m py_compile bondcraft_companion.py
```


---

Source: `minecraft/bridge/python/README.md`

# BondCraft PC Phone Bridge

This PC app only forwards Minecraft haptic payloads to the Android phone app. All game haptic tuning lives in Minecraft.

Flow:

1. Minecraft mod applies its haptic rules, then sends `POST /event` to `http://127.0.0.1:8765/event`.
2. This bridge forwards the same JSON immediately to the phone at `http://PHONE_IP:7878/event`.
3. The Android app plays the requested pattern/color and writes the BLE command to the Bond Band.

Quick start:

1. Open the Android app, connect to the band, and leave the app open.
2. Put the phone IP shown in the Android app into `Phone host`.
3. Or connect USB and press `ADB Forward`, then keep `Phone host` as `127.0.0.1`.
4. Press `Send Test`.
5. Launch Minecraft with the BondCraft NeoForge mod installed, then press `B` to tune haptics.

No Python packages are required.


---

Source: `07_audio_bass_haptics/README.md`

# Bond Bass Haptics

Lightweight Windows app that listens to the PC output device, detects bass hits, and sends haptic pulses to the BondBandBridge Android companion on port 7878.

Quick setup:

1. Open the Android companion app and connect the band.
2. Plug in USB and press `ADB Forward`, or set `Phone host` to the phone IP.
3. Press `Send Test`.
4. Press `Start Listening` and play audio on the PC.

Useful tuning:

- `Sensitivity`: higher reacts to quieter bass.
- `Trigger level`: higher ignores weak bass.
- `Beat threshold %`: higher requires a sharper bass punch.
- `Cooldown ms`: lower feels faster, higher prevents spam.
- `Max pulses / 10 sec`: hard safety limit for busy songs.
- `Min strength` / `Max strength`: vibration range sent to the phone app.


---

Source: `08_notification_haptics/README.md`

# Bond Notify Haptics

Separate Android app for phone-only Bond/Bond Touch bracelet haptics from notifications and alarms.

This app is intentionally separate from `apps/android/bridge`. It does not use the PC bridge, Minecraft bridge, microphone, or Android audio playback capture. The goal is a lightweight always-available phone app that keeps the bracelet connected and buzzes only for selected phone notifications or alarm notifications.

## Project

- Android project: `apps/android/notify`
- Package id: `com.marco.bondnotifyhaptics`
- Debug APK: `apps/android/notify/app/build/outputs/apk/debug/app-debug.apk`
- Build command:

```powershell
cd "[local path removed]"
.\gradlew.bat assembleDebug
```

- Install command when ADB sees the phone:

```powershell
& "[local path removed]" install -r "[local path removed]"
```

Use the bundled ADB above. The system `adb` has previously caused version mismatches on this machine.

## Phone Setup

1. Open **Bond Notify Haptics**.
2. Tap **Grant Phone Permissions**.
3. Tap **Notification Access** and enable the listener for this app.
4. Tap **Battery Settings** and allow unrestricted/ignore battery optimization if this should stay connected all day.
5. Tap **Scan**, choose the Bond bracelet, then use **Test Bracelet Haptic**.
6. Add only the apps that should buzz the bracelet.
7. Tune each rule's pattern, strength, duration, cooldown, and LED color.

## Current Features

- Event-driven notification listener, no polling loop.
- Selected-app notification haptics only.
- Alarm haptics with optional repeat until the alarm notification disappears.
- Quiet hours toggle, currently fixed to 23:00 through 07:00.
- Per-app pattern, strength, duration, cooldown, and color.
- BLE foreground service using the Android `connectedDevice` foreground-service type.
- Quiet persistent foreground notification while enabled and connected/reconnecting.
- Backoff reconnect to the saved bracelet.
- Stop Service disables reconnects and removes the foreground notification.
- No indefinite wake lock, to keep battery use lower.

## BLE Notes

The BLE implementation reuses the working local protocol from the Android bridge:

- User app service: `d3c00002-add3-1d4b-0001-0000b5006bb1`
- FFA1 token write: `12345678`
- FFA2 local AUID write, little-endian int
- FFA4 state notify/read
- FFA5 event notify
- FFA6 command write
- Ready state: `0x85`
- Pair-confirm event: `0x13`, answered with FFA6 paired-correct command `0x31`
- Vibration/color command: FFA6 `LED_MOTOR_CTRL` command `0x26`, 37-byte payload

The app currently targets the v2-family / Band 4 Pop flow proven in `apps/android/bridge`.

## Known Limits

- Alarm support listens for alarm-like notifications. It does not hook other apps' private alarm schedules.
- Some clock apps may need rule tuning if they do not use `Notification.CATEGORY_ALARM` or obvious alarm text.
- Android can stop notification listeners and foreground services if the user force-stops the app, revokes notification access, disables Bluetooth permission, or keeps battery optimization strict.
- The app only connects after permissions are granted and a bracelet has been saved/enabled. This is deliberate to avoid a useless idle foreground service.


---

Source: `experiments/e87-badge/e87_findings.md`

# E87 / Q87 Badge Findings

## Device

- Advertised names seen: `E87`, `Q87`
- ZRun UI MAC: `[device address removed]`
- BLE scan/connection address seen by Windows: `[device address removed]`
- Current firmware from bind response: `11.1.0.7`
- Protocol/pact version: `2.7`
- Platform: `0`
- Serial/model number used by ZRun OTA API: `1558`

## BLE Services

- Generic Access: `00001800-0000-1000-8000-00805f9b34fb`
- JieLi service:
  - service `0000ae00-0000-1000-8000-00805f9b34fb`
  - write `0000ae01-0000-1000-8000-00805f9b34fb`
  - notify `0000ae02-0000-1000-8000-00805f9b34fb`
- Qix app protocol service:
  - service `c2e6fd00-e966-1000-8000-bef9c223df6a`
  - write `c2e6fd02-e966-1000-8000-bef9c223df6a`
  - notify `c2e6fd01-e966-1000-8000-bef9c223df6a`
  - control point `c2e6fd03-e966-1000-8000-bef9c223df6a`
  - additional write `c2e6fd04-e966-1000-8000-bef9c223df6a`
  - additional notify `c2e6fd05-e966-1000-8000-bef9c223df6a`
- Battery service:
  - service `0000180f-0000-1000-8000-00805f9b34fb`
  - characteristic `00002a19-0000-1000-8000-00805f9b34fb`

## Qix Packet Shape

- Packet marker: `0x9E`
- Request bind command: `0x60`
- Bind response command: `0x61`
- Packet structure:
  - byte 0: marker `9E`
  - byte 1: checksum, simple sum of bytes from flag through payload
  - byte 2: flag/status
  - byte 3: command
  - byte 4-5: little-endian payload length
  - byte 6..: payload

Captured bind request:

```text
TX 9E0B02600D0002B47E316A0000B47E316A0000
```

Captured bind response payload:

```text
00322E3731312E312E302E3700000016060080104001000020312E302E30
```

Decoded:

- state: `0`
- pact version: `2.7`
- firmware version: `11.1.0.7`
- serial number: `1558`

## OTA API

Endpoint:

```text
https://www.zgntec.com/hy-cloud/app/prod/upgrade
```

Current-version request:

```json
{"model":"1558","type":"OTA","version":"1.0.7"}
```

Current-version response:

```json
{"code":"CD000004","msg":"已是最新版本","body":null}
```

Older-version probe:

```json
{"model":"1558","type":"OTA","version":"1.0.0"}
```

Response gives an OTA binary:

```json
{
  "code": "CD000001",
  "msg": "处理成功",
  "body": {
    "upgrade_content": "OTA升级",
    "download_address": "https://share-1329296078.cos.ap-guangzhou.myqcloud.com/OTA/prod/1558/QX7613_E87_C32_OTA_APP_V1.0.3_20260104.bin",
    "upgrade_title": "1",
    "upgrade_version": "1.0.3",
    "upgrade_size": "1.03M"
  }
}
```

Downloaded firmware:

- file: `QX7613_E87_C32_OTA_APP_V1.0.3_20260104.bin`
- size: `1,079,363` bytes
- SHA-256: `7B9AC1862E445F13C447DF4E2BC16FD1E4914AA6DD263C1E7BDC8DC279E908B9`
- header starts with `BCAF01` and ASCII `1.0.3`
- readable markers include `JLUFW`, `lcflash_ota.bin`, `flash`, `OTA_TOUT`, `app_dir_head`

Local firmware inspector:

```powershell
cd "[local path removed]"
.\inspect_e87_firmware.ps1
```

Inspector result:

- OTA/update header length used by ZRun/Qix `UpdateManager`: `27` bytes
- payload length from header: `1,079,336`
- actual payload length: `1,079,336`
- magic: `BC AF`
- update type byte: `0x01`
- version field: `1.0.3`
- marker offsets:
  - `AC707N`: `0x42B`
  - `NORFLASH_DTR_EN`: `0x12E9`
  - `NORFLASH_WPS_EN`: `0x12FE`
  - `quicklz`: `0x101EB5`
  - `lcflash_ota.bin`: `0xFC4AB`, `0xFC4CB`
  - `app_dir_head`: `0x102055`
  - `OTA_TOUT`: `0x102008`
  - `JLUFW`: `0x10222B`
- Entropy is high through most of the payload and drops near the final metadata/FAT-like region. This suggests the main firmware area is packed, encrypted, or compressed rather than easy plain-code patching.

## Practical Interpretation

- This is almost certainly a JieLi/Qix wearable-style firmware target, not ESP32.
- The ZRun app will not offer an update because it sends `1.0.7` and the server reports latest.
- The server still hosts an older `1.0.3` OTA image when queried with old versions.
- Do not flash the old OTA unless intentionally testing downgrade/recovery; it may reduce functionality or brick the badge.
- The BLE OTA transport is understood well enough to build a sender: ZRun reads the downloaded file and calls `UpdateManager.startUpdate(bytes)`, which sends the first 27 bytes with command `0xC0`, then data chunks with command `0xC2`.
- There is no obvious app-side signing step in ZRun's Java path. Any acceptance checks happen on the badge after it receives the update header/chunks.
- A full firmware rewrite is still a serious reverse-engineering job because display init, BLE stack, storage layout, power management, and recovery path still need low-level hardware knowledge.
- The safer staged path is: reproduce ZRun's OTA sender with the original `.bin`, test whether the badge accepts a minimally changed copy, then attempt asset/resource patching before considering full app-code replacement.
- Best next reverse path remains local BLE control over the Qix service for the e-bike display experiments, while building firmware tooling in parallel.

## Local BLE Command Experiments

Tool:

```text
probe_e87_ble.py
```

Useful modes:

```powershell
python .\probe_e87_ble.py --explore
python .\probe_e87_ble.py --notify "SPD 25 KMH"
python .\probe_e87_ble.py --notify-setup --notify "SPD 25 KMH"
```

Read-only commands mapped so far:

- `0x60` bind request, normal write `FD02`, response `0x61`
- `0xDA` request picture size, normal write `FD02`, response `0xDB`
- `0xC6` request badge info, normal write `FD02`, response `0xC7`
- `0x1A` custom theme data, request-control write `FD03`
- `0x19` theme data, request-control write `FD03`
- `0x1C` push dial info, request-control write `FD03`
- `0x49` video dial info, request-control write `FD03`

Explorer observations:

- `0xDA` responds, but current payload decoded as all zero dimensions.
- `0xC6` responds with short payload `1001`; not enough for full `BadgeInfoReturn`.
- Request-control commands sent to `FD03` ACK on `FD03` but did not yet produce decoded data responses.
- Notifications are command `0x41`, payload:
  - byte 0: notification type
  - byte 1-2: little-endian UTF-8 byte length
  - byte 3..: UTF-8 text
- Notification switch is command `0x40`, payload for pact `2.7` is a 32-bit little-endian mask. `FFFFFFFF` produced an ACK on `FD03`.
- `--notify-setup --notify "SPD 25 KMH"` sent successfully, but the badge showed the boot logo, returned to the existing GIF, and brightness reset. Do not repeat this all-bits setup casually.
- The badge product path appears to be media/badge transfer, not notification display.
- Badge-specific app preflight calls:
  - `command_a2d_setSendFileType(fm.v.BADGE)` -> command `0xDC`, payload `0C`
  - `command_a2d_setSendFileSt(true/false)` -> command `0x87`, payload `01` or `00`
- `DialTool.sendVideoFile(...)` wraps the media as:
  - 27-byte file header starting `BC AF`
  - file kind byte `0C` for video/badge payload
  - 8-byte video header: `41 56` for app/device-dial mode or `30 56` for badge mode, then little-endian width/height, then `10 80`
  - raw source file bytes
- `UpdateManager.startUpdate(...)` sends:
  - `0xC0` update/file header request
  - waits for `0xC1` response with allowed chunk length and offset
  - sends chunks with `0xC2`
  - receives `0xC3` progress/next-offset responses
  - receives `0xC5` final result
- `probe_e87_ble.py` can now build a DialTool-style package offline:

```powershell
python .\probe_e87_ble.py --package-video .\input.gif --package-width 240 --package-height 240 --package-badge-mode
```

It can also send brightness and file-preflight commands, but these should be used carefully:

```powershell
python .\probe_e87_ble.py --brightness 80
python .\probe_e87_ble.py --send-file-type 12
python .\probe_e87_ble.py --send-file-state start
python .\probe_e87_ble.py --send-file-state stop
```

## Latest Media Push Result

- The app converts selected GIF/video media to MJPEG AVI before wrapping:
  - `scale=<w>:<h>:force_original_aspect_ratio=disable,setsar=1`
  - default fps: `12`
  - default MJPEG quality: `7`
  - no audio for the simple GIF test path
- Added `probe_e87_ble.py --convert-video-avi` to generate an app-like AVI with bundled `imageio-ffmpeg`.
- Built and tested:
  - raw GIF wrapped as badge package
  - MJPEG AVI wrapped as `0V` badge package at `368x368`
  - slot/bg values `0`, `1`, `2`, and `3`
- All variants were rejected immediately after the `0xC0` 27-byte header with `0xC5 08`.
- Since rejection happens before any `0xC2` data chunks, these attempts did not overwrite the current GIF.

## Video Dial Info Query

- `BTCommandManager.command_a2d_video_info()` sends command `0x49` as a request-config command to control point `FD03`.
- Direct `0x49` queries stayed silent.
- App-like `requestData(191)` worked and returned normal status packets, including battery and capability-like payloads, but `0x49` still stayed silent afterward.
- The ZRun app only pushes video media after it has a `VideoDialInfoReturn` with width, height, and `bgSupportFlag`. We still need that exact config or a capture of a successful app push.

Best next step: use ZRun to push one known test GIF/video successfully while capturing logcat/BLE traffic, then diff the real 27-byte header and preflight sequence against `probe_e87_ble.py`.

## Live Text / Speedometer Candidate Commands

Added small live-content senders to `probe_e87_ble.py`:

```powershell
python .\probe_e87_ble.py --nav-demo
python .\probe_e87_ble.py --nav-text "SPD 25 KMH"
python .\probe_e87_ble.py --weather-text "SPD25"
python .\probe_e87_ble.py --stock-speed 25
python .\probe_e87_ble.py --news-text "SPD 25 KMH"
python .\probe_e87_ble.py --interpret-text "SPD 25 KMH"
python .\probe_e87_ble.py --live-text-demo
```

Candidate protocols:

- Navigation: command `0xDF`, no-response write.
  - type `0`: state, payload `[00,state]`
  - type `1`: orientation, payload `[01,orientation]`
  - type `2`: remaining distance, payload `[02,u32le]`
  - type `3`: remaining time, payload `[03,u32le]`
  - type `4`: text, tested both `[04,len,text]` and `[04,text]`
  - type `5`: navigation mode, payload `[05,mode]`
  - type `6`: navigation type, payload `[06,type]`
- Weather: command `0x2B`, payload city label + one weather record. The badge ACKed with `FF 2B 00`.
- News: command `0x46`, one title payload, no-response write.
- Stock: command `0x45`, one stock-like record. Speed can be encoded as current price, no-response write.
- Interpretation/translator: command `0x8D`.
  - `[06,01]` enables/switches interpretation.
  - `[07,lenLE,text]` original text.
  - `[08,lenLE,text]` translated text.
  - The badge ACKed `0x8D` packets with `FF 8D 00`.

Current conclusion:

- Full media/GIF transfer is too slow for a live speedometer.
- The practical route is a phone bridge that reads e-bike BLE speed and sends one of these small Qix packets every ~1 second.
- `0x8D` interpretation and `0x2B` weather are the strongest candidates so far because the badge ACKs them.
- Visual confirmation is still needed: while testing remotely we can verify BLE acceptance, but not whether the badge changed screens.

## Android Bridge App

Created a first phone-side field-test app:

```text
experiments/e87-bike-bridge
```

Package:

```text
com.marco.e87bikebridge
```

Capabilities:

- BLE scan from the phone.
- Pick a scanned device as the E87/Q87 badge.
- Connect to the Qix badge service and send bind + speed packets.
- Pick a scanned device as the e-bike.
- Discover readable/notifiable e-bike characteristics.
- Subscribe to a chosen characteristic and attempt speed parsing.
- Parse standard Cycling Speed and Cadence measurement `0x2A5B` if present.
- Fallback parser tries simple uint8 / uint16 speed-like values.
- Manual speed controls can send `SPD xx KMH` to the badge before e-bike parsing is known.

The app sends each speed update through several small badge surfaces:

- interpretation/translator `0x8D`
- weather `0x2B`
- navigation text `0xDF`

Build verified with:

```powershell
.\gradlew.bat assembleDebug
```

Installed on phone as `com.marco.e87bikebridge`. The OPPO build blocked direct `adb pm grant`, so the normal Android permission dialog was accepted via UI automation. `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are now granted.

Current limitation: this is an on-screen field tester, not yet a persistent foreground background bridge. Keep it open during experiments. Add a foreground service after visual confirmation of the badge display path and e-bike characteristic.


---

Source: `10_e87_bike_bridge/README.md`

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


---

Source: `experiments/super73/findings.md`

# S75 / Super73 Bike BLE Findings

Pulled from phone package `com.s75bike` version `1.2.2`.

APK path on phone:

- `[installed APK path removed]`

Local files:

- `experiments/super73/apk/com.s75bike-base.apk`
- `experiments/super73/jadx_src`

JADX finished with decompiler errors, but the Bluetooth manager source was usable.

## BLE Profile

The app scans for device names containing `SUPER73`.

Service and characteristics:

- Service: `00001554-1212-efde-1523-785feabcd123`
- Notify/register notifier: `0000155e-1212-efde-1523-785feabcd123`
- Register write: `0000155f-1212-efde-1523-785feabcd123`
- Register ID write: `00001564-1212-efde-1523-785feabcd123`
- CCCD: `00002902-0000-1000-8000-00805f9b34fb`

## Speed Format

Speed arrives on notifier characteristic `155e`.

Speed notification:

```text
02 01 LL HH ...
```

Parse:

```text
speed_kmh = little_endian_u16(bytes[2:4]) / 100.0
```

Examples:

- raw `0x09C4` = `2500` = `25.00 km/h`

## Other Notifications

- `02 02 ...` total distance: bytes `6..7` little-endian, divided by `10`, km.
- `02 03 ...` ride data: byte `8` is battery range in km.
- `03 00 ...` settings: assist, light, mode.
- `04 01 ...` battery voltage: bytes `2..3` little-endian, divided by `1000`, volts.

## Request Writes

The app writes a register ID first, then 100 ms later writes a 10-byte padded payload to the register characteristic.

Requests:

- Settings: register ID `03 00`, payload `03 00 00 00 00 00 00 00 00 00`
- Total distance: register ID `02 02`, payload `02 02 00 00 00 00 00 00 00 00`
- Ride data: register ID `02 03`, payload `02 03 00 00 00 00 00 00 00 00`
- Battery: register ID `04 01`, payload `04 01 00 00 00 00 00 00 00 00`

## Bridge Status

`E87BikeBridge` now recognizes Super73/S75 bikes, subscribes to `155e`, issues the same read requests, and parses `02 01` speed notifications into km/h before forwarding speed to the E87/Q87 badge.

Physical bike test is still pending. During the ADB scan after patching, no `SUPER73` advertisement was visible, so the bike was likely off/asleep/out of range.


---

Source: `HapticSettingsStore.java / defaults()`

# Minecraft haptic rule defaults

Source: `minecraft/mod/src/main/java/com/marco/bondcraft/HapticSettingsStore.java`, `defaults()`. Later calm-mode/migration settings can modify these values. Dynamic strength/duration additions and global limits apply.

| Event | Enabled | Pattern | Strength | Duration ms | Cooldown ms | Color | Priority |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `session_start` | true | single | 40 | 90 | 2200 | #FFFFFF | 40 |
| `damage` | true | single | 82 | 135 | 180 | #FF3030 | 90 |
| `heal` | true | single | 38 | 90 | 450 | #36D675 | 45 |
| `low_health` | true | heartbeat | 72 | 120 | 1600 | #FF2020 | 85 |
| `death` | true | long | 100 | 900 | 2500 | #660000 | 100 |
| `respawn` | true | double | 55 | 120 | 900 | #FFFFFF | 75 |
| `hunger_low` | true | double | 44 | 95 | 2400 | #FFD33D | 50 |
| `food_restored` | false | single | 35 | 80 | 800 | #FFD33D | 20 |
| `air_low` | true | warning | 74 | 95 | 550 | #37B8FF | 80 |
| `xp_gain` | false | single | 26 | 45 | 350 | #7DFF4D | 25 |
| `level_up` | true | triple | 64 | 100 | 1100 | #7DFF4D | 70 |
| `step` | false | single | 12 | 22 | 650 | none | 8 |
| `sprint_step` | false | single | 16 | 24 | 520 | none | 10 |
| `step_stone` | false | single | 15 | 24 | 650 | none | 10 |
| `step_wood` | false | single | 14 | 26 | 650 | #A86A32 | 10 |
| `step_grass` | false | single | 10 | 22 | 700 | #48B84A | 8 |
| `step_sand` | false | fall | 12 | 26 | 700 | #E2C26D | 8 |
| `step_gravel` | false | stutter | 14 | 24 | 700 | #8E8E8E | 9 |
| `step_snow` | false | single | 10 | 28 | 700 | #DCEEFF | 8 |
| `step_metal` | false | single | 22 | 34 | 650 | #C0C6CC | 12 |
| `jump` | true | rise | 28 | 45 | 350 | none | 25 |
| `falling` | false | rise | 48 | 80 | 1500 | #B38CFF | 45 |
| `land` | true | fall | 72 | 120 | 450 | #C48A4A | 85 |
| `crouch_start` | false | single | 22 | 45 | 150 | none | 15 |
| `crouch_stop` | false | single | 18 | 35 | 150 | none | 12 |
| `sprint_start` | false | rise | 34 | 55 | 250 | none | 18 |
| `sprint_stop` | false | fall | 26 | 45 | 250 | none | 16 |
| `swim_start` | true | wave | 34 | 65 | 450 | #37B8FF | 30 |
| `swim_stop` | false | single | 24 | 45 | 450 | #37B8FF | 20 |
| `water_enter` | true | wave | 42 | 85 | 600 | #37B8FF | 45 |
| `lava_enter` | true | triple | 100 | 135 | 300 | #FF6B00 | 100 |
| `fire` | true | warning | 88 | 90 | 500 | #FF4500 | 90 |
| `sleep` | true | breathe | 28 | 110 | 1200 | #776CFF | 30 |
| `dimension_change` | true | long | 72 | 180 | 1500 | #B38CFF | 75 |
| `weather_rain` | true | wave | 26 | 55 | 2800 | #37B8FF | 25 |
| `rain_ground` | false | stutter | 18 | 26 | 900 | #37B8FF | 12 |
| `weather_thunder` | true | long | 86 | 170 | 2800 | #FFFFFF | 70 |
| `thunder_rumble` | true | fall | 62 | 190 | 4200 | #FFFFFF | 55 |
| `night` | true | breathe | 34 | 100 | 3200 | #776CFF | 30 |
| `mine_start` | true | single | 26 | 40 | 280 | none | 25 |
| `mining` | false | single | 18 | 30 | 380 | none | 10 |
| `mining_stone` | true | stutter | 22 | 30 | 360 | #9A9A9A | 20 |
| `mining_wood` | true | fall | 20 | 42 | 360 | #A86A32 | 20 |
| `mining_ore` | true | stutter | 34 | 40 | 260 | #7DFF4D | 35 |
| `mining_dirt` | false | single | 18 | 36 | 220 | #8A5A32 | 12 |
| `mining_sand` | false | fall | 16 | 38 | 240 | #E2C26D | 12 |
| `mining_gravel` | false | stutter | 20 | 34 | 240 | #8E8E8E | 12 |
| `attack_swing` | true | single | 54 | 70 | 150 | #FF3030 | 55 |
| `bow_charge` | false | rise | 32 | 70 | 550 | #FFD33D | 25 |
| `bow_shot` | true | fall | 68 | 95 | 140 | #FFD33D | 65 |
| `fishing_cast` | true | rise | 40 | 85 | 650 | #37B8FF | 35 |
| `blocking` | true | single | 52 | 80 | 420 | #FFFFFF | 45 |
| `eating` | true | double | 34 | 75 | 420 | #FFD33D | 25 |
| `drinking` | true | double | 38 | 75 | 420 | #37B8FF | 25 |
| `creeper_warning` | true | warning | 66 | 100 | 700 | #4DFF4D | 75 |
| `creeper_close` | true | triple | 100 | 105 | 280 | #55FF55 | 100 |
| `hostile_nearby` | true | double | 54 | 90 | 1300 | #FF3030 | 60 |
| `effect_added` | true | single | 36 | 70 | 420 | #B38CFF | 25 |
| `effect_removed` | false | single | 26 | 60 | 420 | #B38CFF | 15 |
| `elytra_start` | true | rise | 48 | 90 | 850 | #5DDCFF | 45 |
| `elytra_stop` | true | fall | 38 | 90 | 850 | #5DDCFF | 35 |
| `mount` | true | single | 35 | 75 | 600 | none | 25 |
| `dismount` | true | single | 32 | 70 | 600 | none | 25 |
| `hotbar_change` | false | single | 12 | 25 | 80 | none | 8 |
| `cobblemon_battle_start` | true | double | 42 | 90 | 1800 | #FFFFFF | 45 |
| `cobblemon_ball_throw` | true | rise | 44 | 85 | 650 | #FF3030 | 55 |
| `cobblemon_move` | false | single | 22 | 50 | 650 | #FFFFFF | 12 |
| `cobblemon_damage` | true | single | 42 | 80 | 650 | #FF3030 | 50 |
| `cobblemon_crit` | true | triple | 68 | 90 | 1000 | #FFD33D | 80 |
| `cobblemon_super_effective` | true | rise | 58 | 85 | 900 | #7DFF4D | 75 |
| `cobblemon_not_effective` | true | fall | 30 | 70 | 900 | #8E8E8E | 35 |
| `cobblemon_no_effect` | true | stutter | 24 | 45 | 1100 | #8E8E8E | 35 |
| `cobblemon_faint` | true | long | 70 | 350 | 1800 | #B38CFF | 80 |
| `cobblemon_type_fire` | true | warning | 54 | 80 | 750 | #FF4500 | 60 |
| `cobblemon_type_water` | true | wave | 46 | 90 | 750 | #37B8FF | 55 |
| `cobblemon_type_wind` | true | wave | 38 | 80 | 750 | #DCEEFF | 45 |
| `cobblemon_type_electric` | true | stutter | 60 | 55 | 750 | #FFD33D | 65 |
| `cobblemon_type_ice` | true | fall | 42 | 95 | 750 | #5DDCFF | 50 |
| `cobblemon_type_ground` | true | fall | 50 | 110 | 800 | #C48A4A | 55 |
| `cobblemon_type_rock` | true | single | 56 | 100 | 800 | #9A9A9A | 55 |
| `cobblemon_type_grass` | true | rise | 34 | 75 | 800 | #36D675 | 40 |
| `cobblemon_type_psychic` | true | breathe | 36 | 85 | 950 | #B38CFF | 45 |
| `cobblemon_type_ghost` | true | breathe | 36 | 90 | 950 | #776CFF | 45 |
| `cobblemon_type_dark` | true | long | 38 | 120 | 950 | #660000 | 45 |
| `cobblemon_type_fairy` | true | wave | 34 | 80 | 900 | #FF6BCE | 40 |
| `cobblemon_type_dragon` | true | ramp | 58 | 90 | 950 | #B38CFF | 65 |
| `cobblemon_type_poison` | true | stutter | 40 | 70 | 900 | #A04DFF | 45 |
| `cobblemon_type_steel` | true | single | 58 | 85 | 800 | #C0C6CC | 55 |
| `cobblemon_type_bug` | true | stutter | 32 | 50 | 850 | #7DFF4D | 35 |
| `cobblemon_type_fighting` | true | double | 54 | 75 | 750 | #FF3030 | 55 |
| `cobblemon_type_normal` | false | single | 30 | 65 | 750 | #FFFFFF | 25 |
