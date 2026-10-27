# Notify Haptics

Android app package `com.marco.bondnotifyhaptics`. JDK17, SDK36, Android26+.

Build from this folder: `gradlew.bat assembleDebug`. Select the band and grant Bluetooth, notification-listener and notification permissions.

See [setup and features](../../../research/notes/08_notification_haptics-README.md) and [architecture](../../../docs/HOW_IT_WORKS.md). `MainActivity` edits rules; `BondConfig` stores them; `BondNotificationListener` selects events; `BondHapticService` owns BLE and queues output.
