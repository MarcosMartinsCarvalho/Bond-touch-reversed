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
