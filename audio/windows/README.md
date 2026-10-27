# Windows bass haptics

.NET8 Windows Forms + NAudio2.2.1. Captures the selected playback endpoint and detects bass hits, then posts final haptic JSON to the Android bridge on port7878.

Build: `dotnet build`. Run: `dotnet run`. Connect the bracelet in [Android bridge](../../apps/android/bridge), set Phone host or ADB forwarding, send a test, then start listening. Sensitivity, cooldown, burst cap, duration, pattern, color and strength bounds tune the detector. See [architecture](../../docs/HOW_IT_WORKS.md).
