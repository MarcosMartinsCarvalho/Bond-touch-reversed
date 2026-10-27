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
