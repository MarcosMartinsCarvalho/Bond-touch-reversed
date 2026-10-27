# Validation for the organised publication

Reviewed2026-10-27. No new physical BLE session was performed.

Passed in the reorganised checkout:

- Android BondBandBridge: `assembleDebug`, JDK17 / SDK36.
- Android BondNotifyHaptics: `assembleDebug`, JDK17 / SDK36.
- Android E87BikeBridge field tester: `assembleDebug`, JDK17 / SDK36.
- Windows bass haptics: .NET8.0.426 build.
- Alternative Windows Minecraft bridge: .NET8.0.426 build.
- Minecraft NeoForge mod: Gradle build with Java21 toolchain provisioning.
- Python files: syntax compilation.
- Five offline BLE packet tests: size, padding, endian encoding and v2/v3 auth/config differences.
- Relative Markdown links: no missing local targets.

The original workspace was copied into a new checkout. Portable adjustments remove pinned personal JDK paths and preset device addresses; Minecraft license metadata follows the repositoryâ€™s existing MIT selection. Full vendor artifacts/build caches/settings are excluded. The source manifest maps imported source paths to the organised locations.

The portfolio production build and browser checks for search, note expansion, code blocks, downloads, repository link and390px width passed. The browser demos are simulations.
