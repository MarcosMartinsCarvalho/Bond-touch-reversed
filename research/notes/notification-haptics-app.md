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
