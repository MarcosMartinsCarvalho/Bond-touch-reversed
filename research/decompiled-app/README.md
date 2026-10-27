# Decompiled Android app research

Target package: `com.impossible.bondtouch`. Original analysis used an APK split set locally pulled on2026-06-15. APKTool/smali supplied the first protocol map; targeted JADX output supplied the UI/repository flow analysis. A full JADX pass was slow and produced errors in some areas, so a readable method body is not assumed correct merely because a decompiler emitted it.

## What is here

- [Class/method map and app flow](../notes/app-flow-map.md)
- [Initialization and source anchors](../notes/initialization-sequence.md)
- [Protocol reference](../notes/protocol-map.md)
- [Complete notebook](../FINDINGS.md)
- [BLE class inventory](class-index.txt): paths from the local research tree, without copying the proprietary whole app
- `extract-local.ps1`: repeat the pull/decode on your own installed copy; outputs under ignored`local/`

Full proprietary APKs, resources and whole-app decompiled source are not re-licensed by the repository’s MIT license. This folder publishes the research map and reproducible workflow. It contains no account credentials or vendor cloud access keys.

## Reproduce locally

Install Android Platform Tools, APKTool, Java and JADX. Connect your own Android phone with debugging enabled and install the official app normally.

```powershell
.\research\decompiled-app\extract-local.ps1
# Optional targeted/full JADX code pass:
.\research\decompiled-app\extract-local.ps1 -RunJadx
```

The script uses `adb shell pm path` and pulls the split set, decodes each APK, and optionally runs JADX on base.apk. Each run writes a new timestamped directory; it does not delete earlier work. Do not commit the ignored local output.

## Most useful paths through the app

```text
Connection UI → ConnectViewModel → BleBondClient → generation-specific manager
Settings preview → BleBondClient → v2 FFA6 command / v3 FFB1 configuration
History/local meaning replay → BleBondRepository → BLE message encoding
Widget preset → SendMeaningWorker → MessagesRepository → social send path
Physical touch → BLE event parser → reconstructed durations → message repository
```

A widget is not a new vibration opcode. Dashboard partner text is not a touch-pattern editor. A social send can use the official server path, while local replay uses the BLE repository. The original controllers here only need local BLE; they do not reproduce the vendor account/cloud service.
