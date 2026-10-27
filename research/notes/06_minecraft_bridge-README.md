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
