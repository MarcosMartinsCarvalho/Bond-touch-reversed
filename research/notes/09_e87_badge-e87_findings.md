# E87 / Q87 Badge Findings

## Device

- Advertised names seen: `E87`, `Q87`
- ZRun UI MAC: `[device address removed]`
- BLE scan/connection address seen by Windows: `[device address removed]`
- Current firmware from bind response: `11.1.0.7`
- Protocol/pact version: `2.7`
- Platform: `0`
- Serial/model number used by ZRun OTA API: `1558`

## BLE Services

- Generic Access: `00001800-0000-1000-8000-00805f9b34fb`
- JieLi service:
  - service `0000ae00-0000-1000-8000-00805f9b34fb`
  - write `0000ae01-0000-1000-8000-00805f9b34fb`
  - notify `0000ae02-0000-1000-8000-00805f9b34fb`
- Qix app protocol service:
  - service `c2e6fd00-e966-1000-8000-bef9c223df6a`
  - write `c2e6fd02-e966-1000-8000-bef9c223df6a`
  - notify `c2e6fd01-e966-1000-8000-bef9c223df6a`
  - control point `c2e6fd03-e966-1000-8000-bef9c223df6a`
  - additional write `c2e6fd04-e966-1000-8000-bef9c223df6a`
  - additional notify `c2e6fd05-e966-1000-8000-bef9c223df6a`
- Battery service:
  - service `0000180f-0000-1000-8000-00805f9b34fb`
  - characteristic `00002a19-0000-1000-8000-00805f9b34fb`

## Qix Packet Shape

- Packet marker: `0x9E`
- Request bind command: `0x60`
- Bind response command: `0x61`
- Packet structure:
  - byte 0: marker `9E`
  - byte 1: checksum, simple sum of bytes from flag through payload
  - byte 2: flag/status
  - byte 3: command
  - byte 4-5: little-endian payload length
  - byte 6..: payload

Captured bind request:

```text
TX 9E0B02600D0002B47E316A0000B47E316A0000
```

Captured bind response payload:

```text
00322E3731312E312E302E3700000016060080104001000020312E302E30
```

Decoded:

- state: `0`
- pact version: `2.7`
- firmware version: `11.1.0.7`
- serial number: `1558`

## OTA API

Endpoint:

```text
https://www.zgntec.com/hy-cloud/app/prod/upgrade
```

Current-version request:

```json
{"model":"1558","type":"OTA","version":"1.0.7"}
```

Current-version response:

```json
{"code":"CD000004","msg":"已是最新版本","body":null}
```

Older-version probe:

```json
{"model":"1558","type":"OTA","version":"1.0.0"}
```

Response gives an OTA binary:

```json
{
  "code": "CD000001",
  "msg": "处理成功",
  "body": {
    "upgrade_content": "OTA升级",
    "download_address": "https://share-1329296078.cos.ap-guangzhou.myqcloud.com/OTA/prod/1558/QX7613_E87_C32_OTA_APP_V1.0.3_20260104.bin",
    "upgrade_title": "1",
    "upgrade_version": "1.0.3",
    "upgrade_size": "1.03M"
  }
}
```

Downloaded firmware:

- file: `QX7613_E87_C32_OTA_APP_V1.0.3_20260104.bin`
- size: `1,079,363` bytes
- SHA-256: `7B9AC1862E445F13C447DF4E2BC16FD1E4914AA6DD263C1E7BDC8DC279E908B9`
- header starts with `BCAF01` and ASCII `1.0.3`
- readable markers include `JLUFW`, `lcflash_ota.bin`, `flash`, `OTA_TOUT`, `app_dir_head`

Local firmware inspector:

```powershell
cd "[local path removed]"
.\inspect_e87_firmware.ps1
```

Inspector result:

- OTA/update header length used by ZRun/Qix `UpdateManager`: `27` bytes
- payload length from header: `1,079,336`
- actual payload length: `1,079,336`
- magic: `BC AF`
- update type byte: `0x01`
- version field: `1.0.3`
- marker offsets:
  - `AC707N`: `0x42B`
  - `NORFLASH_DTR_EN`: `0x12E9`
  - `NORFLASH_WPS_EN`: `0x12FE`
  - `quicklz`: `0x101EB5`
  - `lcflash_ota.bin`: `0xFC4AB`, `0xFC4CB`
  - `app_dir_head`: `0x102055`
  - `OTA_TOUT`: `0x102008`
  - `JLUFW`: `0x10222B`
- Entropy is high through most of the payload and drops near the final metadata/FAT-like region. This suggests the main firmware area is packed, encrypted, or compressed rather than easy plain-code patching.

## Practical Interpretation

- This is almost certainly a JieLi/Qix wearable-style firmware target, not ESP32.
- The ZRun app will not offer an update because it sends `1.0.7` and the server reports latest.
- The server still hosts an older `1.0.3` OTA image when queried with old versions.
- Do not flash the old OTA unless intentionally testing downgrade/recovery; it may reduce functionality or brick the badge.
- The BLE OTA transport is understood well enough to build a sender: ZRun reads the downloaded file and calls `UpdateManager.startUpdate(bytes)`, which sends the first 27 bytes with command `0xC0`, then data chunks with command `0xC2`.
- There is no obvious app-side signing step in ZRun's Java path. Any acceptance checks happen on the badge after it receives the update header/chunks.
- A full firmware rewrite is still a serious reverse-engineering job because display init, BLE stack, storage layout, power management, and recovery path still need low-level hardware knowledge.
- The safer staged path is: reproduce ZRun's OTA sender with the original `.bin`, test whether the badge accepts a minimally changed copy, then attempt asset/resource patching before considering full app-code replacement.
- Best next reverse path remains local BLE control over the Qix service for the e-bike display experiments, while building firmware tooling in parallel.

## Local BLE Command Experiments

Tool:

```text
probe_e87_ble.py
```

Useful modes:

```powershell
python .\probe_e87_ble.py --explore
python .\probe_e87_ble.py --notify "SPD 25 KMH"
python .\probe_e87_ble.py --notify-setup --notify "SPD 25 KMH"
```

Read-only commands mapped so far:

- `0x60` bind request, normal write `FD02`, response `0x61`
- `0xDA` request picture size, normal write `FD02`, response `0xDB`
- `0xC6` request badge info, normal write `FD02`, response `0xC7`
- `0x1A` custom theme data, request-control write `FD03`
- `0x19` theme data, request-control write `FD03`
- `0x1C` push dial info, request-control write `FD03`
- `0x49` video dial info, request-control write `FD03`

Explorer observations:

- `0xDA` responds, but current payload decoded as all zero dimensions.
- `0xC6` responds with short payload `1001`; not enough for full `BadgeInfoReturn`.
- Request-control commands sent to `FD03` ACK on `FD03` but did not yet produce decoded data responses.
- Notifications are command `0x41`, payload:
  - byte 0: notification type
  - byte 1-2: little-endian UTF-8 byte length
  - byte 3..: UTF-8 text
- Notification switch is command `0x40`, payload for pact `2.7` is a 32-bit little-endian mask. `FFFFFFFF` produced an ACK on `FD03`.
- `--notify-setup --notify "SPD 25 KMH"` sent successfully, but the badge showed the boot logo, returned to the existing GIF, and brightness reset. Do not repeat this all-bits setup casually.
- The badge product path appears to be media/badge transfer, not notification display.
- Badge-specific app preflight calls:
  - `command_a2d_setSendFileType(fm.v.BADGE)` -> command `0xDC`, payload `0C`
  - `command_a2d_setSendFileSt(true/false)` -> command `0x87`, payload `01` or `00`
- `DialTool.sendVideoFile(...)` wraps the media as:
  - 27-byte file header starting `BC AF`
  - file kind byte `0C` for video/badge payload
  - 8-byte video header: `41 56` for app/device-dial mode or `30 56` for badge mode, then little-endian width/height, then `10 80`
  - raw source file bytes
- `UpdateManager.startUpdate(...)` sends:
  - `0xC0` update/file header request
  - waits for `0xC1` response with allowed chunk length and offset
  - sends chunks with `0xC2`
  - receives `0xC3` progress/next-offset responses
  - receives `0xC5` final result
- `probe_e87_ble.py` can now build a DialTool-style package offline:

```powershell
python .\probe_e87_ble.py --package-video .\input.gif --package-width 240 --package-height 240 --package-badge-mode
```

It can also send brightness and file-preflight commands, but these should be used carefully:

```powershell
python .\probe_e87_ble.py --brightness 80
python .\probe_e87_ble.py --send-file-type 12
python .\probe_e87_ble.py --send-file-state start
python .\probe_e87_ble.py --send-file-state stop
```

## Latest Media Push Result

- The app converts selected GIF/video media to MJPEG AVI before wrapping:
  - `scale=<w>:<h>:force_original_aspect_ratio=disable,setsar=1`
  - default fps: `12`
  - default MJPEG quality: `7`
  - no audio for the simple GIF test path
- Added `probe_e87_ble.py --convert-video-avi` to generate an app-like AVI with bundled `imageio-ffmpeg`.
- Built and tested:
  - raw GIF wrapped as badge package
  - MJPEG AVI wrapped as `0V` badge package at `368x368`
  - slot/bg values `0`, `1`, `2`, and `3`
- All variants were rejected immediately after the `0xC0` 27-byte header with `0xC5 08`.
- Since rejection happens before any `0xC2` data chunks, these attempts did not overwrite the current GIF.

## Video Dial Info Query

- `BTCommandManager.command_a2d_video_info()` sends command `0x49` as a request-config command to control point `FD03`.
- Direct `0x49` queries stayed silent.
- App-like `requestData(191)` worked and returned normal status packets, including battery and capability-like payloads, but `0x49` still stayed silent afterward.
- The ZRun app only pushes video media after it has a `VideoDialInfoReturn` with width, height, and `bgSupportFlag`. We still need that exact config or a capture of a successful app push.

Best next step: use ZRun to push one known test GIF/video successfully while capturing logcat/BLE traffic, then diff the real 27-byte header and preflight sequence against `probe_e87_ble.py`.

## Live Text / Speedometer Candidate Commands

Added small live-content senders to `probe_e87_ble.py`:

```powershell
python .\probe_e87_ble.py --nav-demo
python .\probe_e87_ble.py --nav-text "SPD 25 KMH"
python .\probe_e87_ble.py --weather-text "SPD25"
python .\probe_e87_ble.py --stock-speed 25
python .\probe_e87_ble.py --news-text "SPD 25 KMH"
python .\probe_e87_ble.py --interpret-text "SPD 25 KMH"
python .\probe_e87_ble.py --live-text-demo
```

Candidate protocols:

- Navigation: command `0xDF`, no-response write.
  - type `0`: state, payload `[00,state]`
  - type `1`: orientation, payload `[01,orientation]`
  - type `2`: remaining distance, payload `[02,u32le]`
  - type `3`: remaining time, payload `[03,u32le]`
  - type `4`: text, tested both `[04,len,text]` and `[04,text]`
  - type `5`: navigation mode, payload `[05,mode]`
  - type `6`: navigation type, payload `[06,type]`
- Weather: command `0x2B`, payload city label + one weather record. The badge ACKed with `FF 2B 00`.
- News: command `0x46`, one title payload, no-response write.
- Stock: command `0x45`, one stock-like record. Speed can be encoded as current price, no-response write.
- Interpretation/translator: command `0x8D`.
  - `[06,01]` enables/switches interpretation.
  - `[07,lenLE,text]` original text.
  - `[08,lenLE,text]` translated text.
  - The badge ACKed `0x8D` packets with `FF 8D 00`.

Current conclusion:

- Full media/GIF transfer is too slow for a live speedometer.
- The practical route is a phone bridge that reads e-bike BLE speed and sends one of these small Qix packets every ~1 second.
- `0x8D` interpretation and `0x2B` weather are the strongest candidates so far because the badge ACKs them.
- Visual confirmation is still needed: while testing remotely we can verify BLE acceptance, but not whether the badge changed screens.

## Android Bridge App

Created a first phone-side field-test app:

```text
experiments/e87-bike-bridge
```

Package:

```text
com.marco.e87bikebridge
```

Capabilities:

- BLE scan from the phone.
- Pick a scanned device as the E87/Q87 badge.
- Connect to the Qix badge service and send bind + speed packets.
- Pick a scanned device as the e-bike.
- Discover readable/notifiable e-bike characteristics.
- Subscribe to a chosen characteristic and attempt speed parsing.
- Parse standard Cycling Speed and Cadence measurement `0x2A5B` if present.
- Fallback parser tries simple uint8 / uint16 speed-like values.
- Manual speed controls can send `SPD xx KMH` to the badge before e-bike parsing is known.

The app sends each speed update through several small badge surfaces:

- interpretation/translator `0x8D`
- weather `0x2B`
- navigation text `0xDF`

Build verified with:

```powershell
.\gradlew.bat assembleDebug
```

Installed on phone as `com.marco.e87bikebridge`. The OPPO build blocked direct `adb pm grant`, so the normal Android permission dialog was accepted via UI automation. `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` are now granted.

Current limitation: this is an on-screen field tester, not yet a persistent foreground background bridge. Keep it open during experiments. Add a foreground service after visual confirmation of the badge display path and e-bike characteristic.
