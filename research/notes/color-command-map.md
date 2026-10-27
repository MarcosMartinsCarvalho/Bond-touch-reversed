# Bond Band 4 Color Command Map

Date: 2026-06-15

This file maps the Band 4 / v2-family color behavior found after the first live app test succeeded.

## Key Finding

The first Android prototype buzzed correctly, but the old **White Flash Buzz** did not flash because it wrote RGB and brightness while leaving `ledExecuteTimeMs` at `0`.

Official `buildSingleColorVibrationMessage(strength, color)` sets:

```text
command              0x26 LED_MOTOR_CTRL
queue                0x00
vibration strength   caller value
vibration time       2000 ms / d0 07
led mode             0x01 On
rgb                  from 0xRRGGBB
brightness           0x50 / 80
transition           0
hold                 0
sleep                0
led execute time     2000 ms / d0 07
```

So the LED runtime is separate from the vibration runtime.

## Immediate LED And Motor Command

Write to:

```text
service  d3c00002-add3-1d4b-0001-0000b5006bb1
char     d3c0ffa6-add3-1d4b-0001-0000b5006bb1
```

Packet length is 37 bytes:

```text
00     command, 0x26
01     reserved, 0x00
02     queue
03     vibration strength
04-05  vibration time ms, little-endian uint16
06     LED mode
07     red
08     green
09     blue
0a     LED brightness
0b-0c  LED transition time ms, little-endian uint16
0d-0e  LED hold time ms, little-endian uint16
0f-10  LED sleep time ms, little-endian uint16
11-24  LED execute time ms as little-endian uint16, padded to 20 bytes
```

LED modes:

```text
01 On
02 Breathe
03 Blink
04 Off
```

Official color buzz example, white, strength 80:

```text
26 00 00 50 d0 07 01 ff ff ff 50 00 00 00 00 00 00 d0 07 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

Official display-color behavior uses vibration `0`, LED `On`, brightness `80`, and LED execute `0xffff`:

```text
26 00 00 00 00 00 01 RR GG BB 50 00 00 00 00 00 00 ff ff 00 ... padded
```

Hide/undisplay color uses LED mode `Off` with all timings zero.

## Persistent Color Settings

Stored/default color settings use command `0x27 CONFIG`, not `0x26 LED_MOTOR_CTRL`.

Write packet shape:

```text
00     command, 0x27 CONFIG
01     reserved, 0x00
02     config property id
03     rw flag, 0x01 for write
04-07  color as 4-byte big-endian integer, usually 00 RR GG BB
08-23  zero padding
```

Total length is 24 bytes because the config parameter area is padded to 20 bytes.

Read packet shape:

```text
00     command, 0x27 CONFIG
01     reserved, 0x00
02     config property id
03     rw flag, 0x10 for read
04-13  zero padding
```

Read packet total length is 20 bytes.

Color-related config property ids:

```text
21 LOW_BAT_COLOR
22 OOBE_COLOR
23 OOBE_PAIRED_COLOR
24 DOUBLE_TAP_COLOR
25 TOUCH_COLOR
26 TOUCH_WARNING_COLOR
27 ERROR_COLOR
28 CHARGING_COLOR
29 CHARGE_DONE_COLOR
```

Other nearby config ids:

```text
11 OUID
12 TOKEN
13 MAX_TOUCH_COUNT
14 MIN_TOUCH_DURATION
15 MAX_TOUCH_DURATION
16 MESSAGE_TIMEOUT
17 LOW_BAT_THRESHOLD
18 MOTOR_STRENGTH
19 BLE_PARAMS
30 ACCEL_SENSITIVITY
31 DISCONNECTED_PARAM
33 ANCS_PARAM
35 FACTORY_MODE
37 SELECTED_USER
ef RESET_CONFIG
```

Example: write touch color to pink `0xFF2D95`:

```text
27 00 25 01 00 ff 2d 95 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00
```

## Android Prototype Update

`apps/android/bridge` now includes a **Color Lab**:

- Select color: White, Red, Green, Blue, Pink, Cyan.
- `Color Buzz`: official-style color + vibration packet with LED execute `2000 ms`.
- `Show Color`: LED on with execute `0xffff`.
- `Hide Color`: LED off.
- `Breathe Color`: experimental breathe packet using LED mode `0x02`.
- Stored settings:
  - Set Touch -> config `0x25`
  - Set Double Tap -> config `0x24`
  - Set Warning -> config `0x26`
  - Set Error -> config `0x27`

## Next Live Tests

1. Test **Color Buzz** with each palette color.
2. Test **Show Color**, then **Hide Color**.
3. Test **Breathe Color** and adjust transition/hold/sleep/execute timings if needed.
4. Test stored settings one at a time:
   - Set Touch color, then trigger a normal touch.
   - Set Double Tap color, then double tap the band.
   - Set Warning/Error only after the immediate color commands are confirmed.
5. If the band sends a config-read notification after writes, map which characteristic reports the response.
