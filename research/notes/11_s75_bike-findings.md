# S75 / Super73 Bike BLE Findings

Pulled from phone package `com.s75bike` version `1.2.2`.

APK path on phone:

- `[installed APK path removed]`

Local files:

- `experiments/super73/apk/com.s75bike-base.apk`
- `experiments/super73/jadx_src`

JADX finished with decompiler errors, but the Bluetooth manager source was usable.

## BLE Profile

The app scans for device names containing `SUPER73`.

Service and characteristics:

- Service: `00001554-1212-efde-1523-785feabcd123`
- Notify/register notifier: `0000155e-1212-efde-1523-785feabcd123`
- Register write: `0000155f-1212-efde-1523-785feabcd123`
- Register ID write: `00001564-1212-efde-1523-785feabcd123`
- CCCD: `00002902-0000-1000-8000-00805f9b34fb`

## Speed Format

Speed arrives on notifier characteristic `155e`.

Speed notification:

```text
02 01 LL HH ...
```

Parse:

```text
speed_kmh = little_endian_u16(bytes[2:4]) / 100.0
```

Examples:

- raw `0x09C4` = `2500` = `25.00 km/h`

## Other Notifications

- `02 02 ...` total distance: bytes `6..7` little-endian, divided by `10`, km.
- `02 03 ...` ride data: byte `8` is battery range in km.
- `03 00 ...` settings: assist, light, mode.
- `04 01 ...` battery voltage: bytes `2..3` little-endian, divided by `1000`, volts.

## Request Writes

The app writes a register ID first, then 100 ms later writes a 10-byte padded payload to the register characteristic.

Requests:

- Settings: register ID `03 00`, payload `03 00 00 00 00 00 00 00 00 00`
- Total distance: register ID `02 02`, payload `02 02 00 00 00 00 00 00 00 00`
- Ride data: register ID `02 03`, payload `02 03 00 00 00 00 00 00 00 00`
- Battery: register ID `04 01`, payload `04 01 00 00 00 00 00 00 00 00`

## Bridge Status

`E87BikeBridge` now recognizes Super73/S75 bikes, subscribes to `155e`, issues the same read requests, and parses `02 01` speed notifications into km/h before forwarding speed to the E87/Q87 badge.

Physical bike test is still pending. During the ADB scan after patching, no `SUPER73` advertisement was visible, so the bike was likely off/asleep/out of range.
