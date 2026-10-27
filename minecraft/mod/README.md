# BondCraft Haptics — NeoForge client mod

Minecraft Java1.21.1 · NeoForge21.1.217 · Java21 · mod ID`bondcraft`.

Turns client game events into final haptic patterns for the original Android BLE controller. This mod has no direct BLE stack: it posts to the local PC forwarder.

## Build and run

```powershell
# From the repository root
.\minecraft\mod\gradlew.bat -p minecraft/mod build
```

Install the resulting JAR from`build/libs` into the instance’s`mods` directory. Start the Android controller and PC forwarder, test the connection, then launch Minecraft.

Press **B** for categories, rule editors, Calm mode and preset import/export. Connection settings are in`config/bondcraft-client.toml`; detailed rules are in`config/bondcraft-haptics.json`.

## Source map

- `BondCraft` / `BondCraftClient`: mod setup and client tick hook.
- `MinecraftEventScanner`: changes in player/world/entity state and emission cooldowns.
- `CobblemonBattleScanner`: optional battle-screen cues.
- `HapticSettings`, `HapticRule`, `HapticSettingsStore`: configuration, defaults, dynamic parameters, bounds and throttling.
- `HapticSettingsScreen`: native in-game settings UI.
- `BridgeClient`: final JSON POST to`http://127.0.0.1:8765/event`.

[All91 static rules](../../research/notes/minecraft-rule-defaults.md) · [Pipeline and pulse timing](../../docs/HOW_IT_WORKS.md) · [PC forwarder](../bridge/python)

Based on the NeoForge MDK. Minecraft/NeoForge and Mojang mappings retain their upstream terms. Original project code follows this repository’s MIT license.
