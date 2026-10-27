# Bond Notify native UI refresh

Project now lives at `[local path removed]`.

## Implementation

- `GlassStyle.java` centralizes light/dark ink, secondary text, accent and surface colors.
- Native Canvas drawables provide translucent neutral surfaces, a shaded backdrop and a reflective rim. This is the skill's inexpensive native fallback, not the browser SVG displacement implementation. No live backdrop blur or refractive shader is used.
- Drawables recreate gradients on bounds changes and draw only during normal Android view rendering. No timers, continuous animation, wake locks, WebView, new runtime dependencies, or background tasks were added.
- System theme selects the light/dark palette. Dark resources also theme native dialog/dropdown text correctly.
- MainActivity consumes window insets for edge-to-edge Android layouts.
- App rules now show app icons and enable switches. Rule rendering skips unchanged rule lists on BLE broadcasts.
- Rule, alarm and pre-alarm editors share a scrollable editor with switches, a pattern menu, strength/duration/cooldown sliders, color controls, and a test action.
- App picker uses a recycled ListView and lazy icon loading. Search filters names and package IDs. Adding an existing app loads its saved rule rather than replacing settings with defaults.
- Dialog widths are bounded by available screen width, picker height by screen height; native scrolling and resize-on-keyboard support smaller displays.

## Build

JDK: `[local path removed]`.
SDK: `[local path removed]`.

The moved machine had stale JAVA_HOME pointing to E: and Android Studio JBR using Java 25, incompatible with this Gradle version. gradle.properties now uses the installed JDK 17; local.properties points to D: SDK.

PowerShell build:

```powershell
$env:JAVA_HOME='[local path removed]
.\gradlew.bat assembleDebug lintDebug --console=plain
```

APK remains `app\build\outputs\apk\debug\app-debug.apk`.

Validation: `assembleDebug lintDebug` completed successfully on 2026-10-27. Lint retains warnings about existing compatibility branches, localization, and the battery exemption request. The broad package-query permission is intentionally retained for the existing sideloaded app picker, with a local lint annotation; the receiver's pre-33 branch is also annotated after verifying its explicit modern flag branch.

No phone was visible in `[local path removed]` during implementation. Device screenshots, theme/font-scale/rotation inspection, BLE runtime behavior and battery measurements still need on-device verification. Do not describe the static styling as full optical refraction or claim measured battery savings.
