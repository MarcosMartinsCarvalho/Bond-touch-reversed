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
