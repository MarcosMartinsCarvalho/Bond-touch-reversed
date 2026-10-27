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
