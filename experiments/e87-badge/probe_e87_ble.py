import argparse
import asyncio
import struct
import subprocess
import time
from pathlib import Path

from bleak import BleakClient, BleakScanner


SERVICE_UUID = "c2e6fd00-e966-1000-8000-bef9c223df6a"
NOTIFY_UUID = "c2e6fd01-e966-1000-8000-bef9c223df6a"
WRITE_UUID = "c2e6fd02-e966-1000-8000-bef9c223df6a"
CONTROL_UUID = "c2e6fd03-e966-1000-8000-bef9c223df6a"
EXTRA_NOTIFY_UUID = "c2e6fd05-e966-1000-8000-bef9c223df6a"
DEFAULT_CHUNK = 20


def checksum(data: bytes) -> int:
    return sum(data) & 0xFF


def le48(value: int) -> bytes:
    return bytes((value >> (8 * i)) & 0xFF for i in range(6))


def build_bind_packet() -> bytes:
    now = int(time.time())
    payload = bytearray(13)
    payload[0] = 0x02
    payload[1:7] = le48(now)
    payload[7:13] = le48(now)
    body = bytes([0x02, 0x60, len(payload) & 0xFF, len(payload) >> 8]) + payload
    return bytes([0x9E, checksum(body)]) + body


def build_packet(command: int, payload: bytes = b"", request: bool = False, needs_response: bool = True, more: bool = False, serial: int = 0) -> bytes:
    split = len(payload) + 6 > 20
    flag = ((0x80 if request else 0) | ((serial & 0x0F) << 3) | (0x04 if split else 0) | (0x02 if needs_response else 0) | (0x01 if more else 0)) & 0xFF
    body = bytes([flag, command & 0xFF, len(payload) & 0xFF, (len(payload) >> 8) & 0xFF]) + payload
    return bytes([0x9E, checksum(body)]) + body


def build_notification_packet(text: str, notify_type: int = 1, serial: int = 0) -> bytes:
    encoded = text.encode("utf-8")
    payload = bytes([notify_type & 0xFF, len(encoded) & 0xFF, (len(encoded) >> 8) & 0xFF]) + encoded
    return build_packet(0x41, payload, request=False, needs_response=False, more=False, serial=serial)


def build_navigation_packet(nav_type: int, value: int | str, serial: int = 0, text_len_byte: bool = True) -> bytes:
    if nav_type in {0, 1, 5, 6}:
        payload = bytes([nav_type & 0xFF, int(value) & 0xFF])
    elif nav_type in {2, 3}:
        payload = bytes([nav_type & 0xFF]) + struct.pack("<I", int(value) & 0xFFFFFFFF)
    elif nav_type == 4:
        encoded = str(value).encode("utf-8")
        if text_len_byte:
            payload = bytes([0x04, len(encoded) & 0xFF]) + encoded
        else:
            payload = b"\x04" + encoded
    else:
        raise ValueError(f"Unsupported navigation type: {nav_type}")
    return build_packet(0xDF, payload, request=False, needs_response=False, more=False, serial=serial)


def build_weather_packet(city: str, temp: int = 25, low: int = 20, high: int = 28, state: int = 0, pollution: int = 0, serial: int = 0) -> bytes:
    city_bytes = city.encode("utf-8") or b" "
    weather = bytes([
        state & 0xFF,
        pollution & 0xFF,
        (pollution >> 8) & 0xFF,
        temp & 0xFF,
        low & 0xFF,
        high & 0xFF,
    ])
    payload = bytes([len(city_bytes) & 0xFF]) + city_bytes + b"\x01" + weather
    return build_packet(0x2B, payload, request=False, needs_response=True, more=False, serial=serial)


def build_news_packet(title: str, news_type: int = 0, serial: int = 0) -> bytes:
    encoded = title.encode("utf-8")
    payload = bytes([news_type & 0xFF, 0x01, len(encoded) & 0xFF]) + encoded
    return build_packet(0x46, payload, request=False, needs_response=False, more=False, serial=serial)


def build_stock_packet(code: str, name: str, current: float, high: float, low: float, increase: float, serial: int = 0) -> bytes:
    code_bytes = code.encode("utf-8")
    name_bytes = name.encode("utf-8")
    now = int(time.time())
    body = bytearray()
    body += struct.pack("<I", now)
    body += bytes([len(code_bytes) & 0xFF]) + code_bytes
    body += bytes([len(name_bytes) & 0xFF]) + name_bytes
    body += struct.pack("<I", int(current * 100) & 0xFFFFFFFF)
    body += struct.pack("<I", int(high * 100) & 0xFFFFFFFF)
    body += struct.pack("<I", int(low * 100) & 0xFFFFFFFF)
    body += bytes([0 if increase >= 0 else 1])
    body += struct.pack("<I", int(abs(increase) * 100) & 0xFFFFFFFF)
    payload = b"\x01" + bytes(body)
    return build_packet(0x45, payload, request=False, needs_response=False, more=False, serial=serial)


def build_interpretation_packet(kind: int, text: str | None = None, enabled: bool | None = None, serial: int = 0) -> bytes:
    if kind == 6:
        payload = bytes([0x06, 0x01 if enabled else 0x00])
    elif kind in {7, 8} and text is not None:
        encoded = text.encode("utf-8")
        payload = bytes([kind & 0xFF, len(encoded) & 0xFF, (len(encoded) >> 8) & 0xFF]) + encoded
    else:
        raise ValueError("Interpretation kind must be 6 with enabled, or 7/8 with text.")
    return build_packet(0x8D, payload, request=False, needs_response=True, more=False, serial=serial)


def crc16_qix(data: bytes) -> int:
    value = 0xFFFF
    for byte in data:
        x = (((value << 8) & 0xFFFF) | ((value >> 8) & 0xFFFF)) ^ byte
        x ^= (x & 0xFF) >> 4
        x ^= (x << 12) & 0xFFFF
        value = x ^ (((x & 0xFF) << 5) & 0xFFFF)
    return value & 0xFFFF


def build_file_header(body: bytes, file_kind: int, slot_or_bg: int) -> bytes:
    header = bytearray(27)
    header[0] = 0xBC
    header[1] = 0xAF
    header[2] = file_kind & 0xFF
    header[3] = slot_or_bg & 0xFF
    header[4] = (slot_or_bg >> 8) & 0xFF
    header[13:17] = struct.pack("<I", len(body))
    crc = crc16_qix(body)
    header[25] = crc & 0xFF
    header[26] = (crc >> 8) & 0xFF
    return bytes(header)


def build_video_file_package(path: Path, width: int, height: int, bg_flag: int = 0, app_dial_mode: bool = False) -> bytes:
    raw = path.read_bytes()
    magic = b"AV" if app_dial_mode else b"0V"
    video_header = magic + struct.pack("<HH", width, height) + bytes([0x10, 0x80])
    body = video_header + raw
    return build_file_header(body, file_kind=12, slot_or_bg=bg_flag) + body


def convert_to_badge_avi(source: Path, out: Path, width: int, height: int, fps: int, quality: int, duration: float | None):
    try:
        import imageio_ffmpeg
    except ImportError as exc:
        raise RuntimeError("Install imageio-ffmpeg first: python -m pip install imageio-ffmpeg") from exc

    command = [
        imageio_ffmpeg.get_ffmpeg_exe(),
        "-i",
        str(source),
    ]
    if duration:
        command.extend(["-t", str(duration)])
    command.extend(
        [
            "-vf",
            f"scale={width}:{height}:force_original_aspect_ratio=disable,setsar=1,fps={fps}",
            "-c:v",
            "mjpeg",
            "-q:v",
            str(quality),
            "-an",
            "-f",
            "avi",
            "-y",
            str(out),
        ]
    )
    subprocess.run(command, check=True)


def build_update_packet(command: int, payload: bytes, serial: int) -> bytes:
    split = len(payload) + 6 > DEFAULT_CHUNK
    flag = (((serial & 0x0F) << 3) | (0x04 if split else 0) | 0x01) & 0xFF
    body = bytes([flag, command & 0xFF, len(payload) & 0xFF, (len(payload) >> 8) & 0xFF]) + payload
    return bytes([0x9E, checksum(body)]) + body


async def write_qix(client: BleakClient, uuid: str, packet: bytes, chunk_size: int = DEFAULT_CHUNK, delay: float = 0.01):
    for offset in range(0, len(packet), chunk_size):
        chunk = packet[offset : offset + chunk_size]
        await client.write_gatt_char(uuid, chunk, response=False)
        await asyncio.sleep(delay)


class UpdatePushState:
    def __init__(self, package: bytes):
        if len(package) <= 27:
            raise ValueError("Package must be larger than the 27-byte header.")
        self.header = package[:27]
        self.data = package[27:]
        self.allow = 0
        self.offset = 0
        self.result = None
        self.event = asyncio.Event()
        self.serial = 0

    def next_serial(self) -> int:
        value = self.serial
        self.serial = (self.serial + 1) & 0x0F
        return value

    def handle(self, command: int, payload: bytes):
        if command == 0xC1 and len(payload) == 9:
            self.result = ("request", payload[0], struct.unpack("<I", payload[1:5])[0], struct.unpack("<I", payload[5:9])[0])
            self.allow = self.result[2]
            self.offset = self.result[3]
            self.event.set()
        elif command == 0xC3 and len(payload) == 5:
            self.result = ("data", payload[0], struct.unpack("<I", payload[1:5])[0])
            self.offset = self.result[2]
            self.event.set()
        elif command == 0xC5 and len(payload) == 1:
            self.result = ("final", payload[0])
            self.event.set()

    async def wait_result(self, timeout: float = 10.0):
        self.event.clear()
        await asyncio.wait_for(self.event.wait(), timeout=timeout)
        return self.result

    def header_packet(self) -> bytes:
        return build_update_packet(0xC0, self.header, self.next_serial())

    def chunk_packet(self) -> bytes:
        if self.allow <= 0:
            raise ValueError("Badge did not provide a positive allowed chunk length.")
        chunk = self.data[self.offset : self.offset + self.allow]
        payload = struct.pack("<II", len(chunk), self.offset) + chunk
        return build_update_packet(0xC2, payload, self.next_serial())

    def stop_packet(self) -> bytes:
        return build_update_packet(0xC4, b"\x03", self.next_serial())


def parse_packet(data: bytes):
    if len(data) < 6 or data[0] != 0x9E:
        return None
    expected = data[1]
    body = data[2:]
    if checksum(body) != expected:
        return None
    length = body[2] | (body[3] << 8)
    if len(body) < length + 4:
        return None
    return body[1], body[4 : 4 + length]


def version_string(values: bytes) -> str:
    return bytes(values).rstrip(b"\x00").decode("latin1")


def parse_bind_response(payload: bytes):
    if len(payload) < 17:
        return None
    return {
        "state": payload[0],
        "pact_version": version_string(payload[1:4]),
        "firmware_version": version_string(payload[4:14]),
        "platform": payload[14],
        "serial_number": payload[15] | (payload[16] << 8),
        "raw_payload": payload.hex().upper(),
    }


def u16be(payload: bytes, offset: int) -> int:
    return (payload[offset] << 8) | payload[offset + 1]


def u16le(payload: bytes, offset: int) -> int:
    return payload[offset] | (payload[offset + 1] << 8)


def decode_response(command: int, payload: bytes):
    if command == 0x61:
        return parse_bind_response(payload)
    if command == 0xDB and len(payload) >= 20:
        result = {
            "album_shrink": [u16be(payload, 0), u16be(payload, 2)],
            "album": [u16be(payload, 4), u16be(payload, 6)],
            "dial_shrink": [u16be(payload, 8), u16be(payload, 10)],
            "dial": [u16be(payload, 12), u16be(payload, 14)],
            "map": [u16be(payload, 16), u16be(payload, 18)],
        }
        if len(payload) >= 24:
            result["preview"] = [u16be(payload, 20), u16be(payload, 22)]
        return result
    if command == 0x1A and len(payload) == 8:
        return {
            "width": u16le(payload, 0),
            "height": u16le(payload, 2),
            "support_custom": u16le(payload, 4),
            "custom_state": u16le(payload, 6),
        }
    if command == 0x19 and len(payload) == 2:
        return {"theme_count": payload[0], "dial_index": payload[1]}
    if command == 0x1C and len(payload) == 4:
        return {"type": payload[0], "total_dial": payload[1], "is_show": payload[2], "show_dial": payload[3]}
    if command == 0x49 and len(payload) >= 8:
        return {
            "width": u16le(payload, 0),
            "height": u16le(payload, 2),
            "bg_support_flag": u16le(payload, 4),
            "bg_state_flag": u16le(payload, 6),
        }
    if command == 0xC7 and len(payload) >= 13:
        return {
            "enabled": payload[0],
            "width": u16le(payload, 1),
            "height": u16le(payload, 3),
            "picture_width": u16le(payload, 5),
            "picture_height": u16le(payload, 7),
            "memory": payload[9] | (payload[10] << 8) | (payload[11] << 16) | (payload[12] << 24),
        }
    if command == 0x27 and len(payload) == 2:
        return {"status": payload[0], "battery": payload[1]}
    if command == 0xC1 and len(payload) == 9:
        return {
            "update_request_status": payload[0],
            "allow_send_length": struct.unpack("<I", payload[1:5])[0],
            "offset": struct.unpack("<I", payload[5:9])[0],
        }
    if command == 0xC3 and len(payload) == 5:
        return {
            "update_data_status": payload[0],
            "next_offset": struct.unpack("<I", payload[1:5])[0],
        }
    if command == 0xC5 and len(payload) == 1:
        return {"update_final_status": payload[0]}
    return None


async def find_device(address: str | None):
    print("Scanning for E87/Q87...")
    devices = await BleakScanner.discover(timeout=10, return_adv=True)
    for addr, (device, adv) in devices.items():
        name = device.name or adv.local_name or ""
        uuids = [u.lower() for u in (adv.service_uuids or [])]
        if (address and addr.upper() == address.upper()) or name in {"E87", "Q87"} or "0000fd00-0000-1000-8000-00805f9b34fb" in uuids:
            print(f"Found {name or '(unnamed)'} at {addr}")
            return device
    raise RuntimeError("No E87/Q87 advertisement found.")


async def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--address", required=True)
    parser.add_argument("--timeout", type=float, default=12.0)
    parser.add_argument("--explore", action="store_true")
    parser.add_argument("--query-video-info", action="store_true", help="Ask for video dial info with command 0x49.")
    parser.add_argument("--request-data", type=lambda value: int(value, 0), help="Send command 0x29 request-data bitmask, e.g. 191.")
    parser.add_argument("--notify", help="Send a short notification text to the badge.")
    parser.add_argument("--notify-type", type=int, default=1)
    parser.add_argument("--notify-setup", action="store_true", help="Enable all notification bits before sending --notify.")
    parser.add_argument("--nav-text", help="Send navigation text with command 0xDF type 4.")
    parser.add_argument("--nav-text-raw", action="store_true", help="Use type+text payload instead of type+length+text for --nav-text.")
    parser.add_argument("--nav-demo", action="store_true", help="Run a short navigation speedometer demo with changing speed text.")
    parser.add_argument("--weather-text", help="Send a weather city label with command 0x2B.")
    parser.add_argument("--news-text", help="Send one news title with command 0x46.")
    parser.add_argument("--stock-speed", type=float, help="Send speed as a stock-card current value with command 0x45.")
    parser.add_argument("--interpret-text", help="Send text through the interpretation/translator command 0x8D.")
    parser.add_argument("--live-text-demo", action="store_true", help="Try navigation, weather, stock, news, and interpretation speed text surfaces.")
    parser.add_argument("--brightness", type=int, help="Set badge brightness using command 0x1B. Value is one byte.")
    parser.add_argument("--send-file-state", choices=["start", "stop"], help="Send command 0x87 with 1 or 0.")
    parser.add_argument("--send-file-type", type=int, help="Send command 0xDC file type, e.g. 12 for BADGE, 11 for VIDEO.")
    parser.add_argument("--convert-video-avi", help="Convert a source video/GIF/image to app-style MJPEG AVI, but do not send it.")
    parser.add_argument("--convert-out", help="Output path for --convert-video-avi. Defaults to <source>.badge.avi.")
    parser.add_argument("--convert-fps", type=int, default=12)
    parser.add_argument("--convert-quality", type=int, default=7)
    parser.add_argument("--convert-duration", type=float, help="Optional output duration in seconds.")
    parser.add_argument("--package-video", help="Build a DialTool-style video/badge package from this file, but do not send it.")
    parser.add_argument("--package-width", type=int, default=240)
    parser.add_argument("--package-height", type=int, default=240)
    parser.add_argument("--package-bg-flag", type=int, default=0)
    parser.add_argument("--package-app-dial-mode", action="store_true", help="Use DialTool's AV video header. Badge mode uses 0V and is the default.")
    parser.add_argument("--push-package", help="Push a previously built .qixpkg package to the badge.")
    parser.add_argument("--push-timeout", type=float, default=20.0)
    args = parser.parse_args()

    if args.convert_video_avi:
        source = Path(args.convert_video_avi)
        out = Path(args.convert_out) if args.convert_out else source.with_suffix(source.suffix + ".badge.avi")
        convert_to_badge_avi(
            source,
            out,
            args.package_width,
            args.package_height,
            args.convert_fps,
            args.convert_quality,
            args.convert_duration,
        )
        print(f"Wrote {out}")
        print(f"size={out.stat().st_size}")
        return

    if args.package_video:
        source = Path(args.package_video)
        package = build_video_file_package(
            source,
            args.package_width,
            args.package_height,
            args.package_bg_flag,
            args.package_app_dial_mode,
        )
        out = source.with_suffix(source.suffix + ".qixpkg")
        out.write_bytes(package)
        print(f"Wrote {out}")
        print(f"size={len(package)} data_size={len(package) - 27} crc16=0x{package[26]:02X}{package[25]:02X}")
        print(f"head={package[:40].hex().upper()}")
        return

    device = await find_device(args.address)
    rx = bytearray()
    done = asyncio.Event()
    bind = None
    push_state = None
    responses = []

    def on_notify(sender, data: bytearray):
        nonlocal bind
        chunk = bytes(data)
        print(f"RX[{sender}] {chunk.hex().upper()}")
        if chunk.startswith(b"\x9e"):
            rx.clear()
        rx.extend(chunk)
        parsed = parse_packet(bytes(rx))
        if not parsed:
            parsed = parse_packet(chunk)
        if parsed:
            command, payload = parsed
            print(f"CMD 0x{command:02X} PAYLOAD {payload.hex().upper()}")
            decoded = decode_response(command, payload)
            if decoded is not None:
                print(f"DECODED {decoded}")
            responses.append((command, payload, decoded))
            if push_state:
                push_state.handle(command, payload)
            if command == 0x61 and decoded and isinstance(decoded, dict) and "serial_number" in decoded:
                bind = parse_bind_response(payload)
                done.set()

    print(f"Connecting to {device.address}...")
    async with BleakClient(device, timeout=20.0) as client:
        print(f"Connected: {client.is_connected}")
        print("Services:")
        for service in client.services:
            print(f"  {service.uuid}")
            for char in service.characteristics:
                print(f"    {char.uuid} {','.join(char.properties)}")

        notify_started = []
        for notify_uuid in (NOTIFY_UUID, CONTROL_UUID, EXTRA_NOTIFY_UUID):
            try:
                await client.start_notify(notify_uuid, on_notify)
                notify_started.append(notify_uuid)
                print(f"Notify enabled: {notify_uuid}")
            except Exception as exc:
                print(f"Notify skipped: {notify_uuid}: {exc}")
        packet = build_bind_packet()
        print(f"TX {packet.hex().upper()}")
        await client.write_gatt_char(WRITE_UUID, packet, response=False)
        try:
            await asyncio.wait_for(done.wait(), timeout=args.timeout)
        except asyncio.TimeoutError:
            pass
        if args.explore:
            queries = [
                ("battery", 0x27, b"", True),
                ("picture_size", 0xDA, b"", False),
                ("custom_theme", 0x1A, b"", True),
                ("theme", 0x19, b"", True),
                ("push_dial", 0x1C, b"", True),
                ("video_dial", 0x49, b"", True),
                ("badge_info", 0xC6, b"", False),
            ]
            for idx, (name, command, payload, request) in enumerate(queries, start=1):
                packet = build_packet(command, payload, request=request, serial=idx)
                print(f"QUERY {name} TX {packet.hex().upper()}")
                await client.write_gatt_char(CONTROL_UUID if request else WRITE_UUID, packet, response=False)
                await asyncio.sleep(1.0)
        if args.request_data is not None:
            packet = build_packet(0x29, bytes([args.request_data & 0xFF]), request=False, needs_response=True, serial=5)
            print(f"REQUEST_DATA TX {packet.hex().upper()}")
            await write_qix(client, WRITE_UUID, packet)
            await asyncio.sleep(3.0)
        if args.query_video_info:
            for idx in range(4):
                packet = build_packet(0x49, b"", request=True, needs_response=True, serial=idx)
                print(f"QUERY_VIDEO_INFO TX {packet.hex().upper()}")
                await write_qix(client, CONTROL_UUID, packet)
                await asyncio.sleep(2.0)
        if args.nav_demo:
            nav_packets = [
                ("nav_state_start", build_navigation_packet(0, 1, serial=1)),
                ("nav_mode_text", build_navigation_packet(5, 0, serial=2)),
                ("nav_type_ebike_guess", build_navigation_packet(6, 2, serial=3)),
                ("nav_distance", build_navigation_packet(2, 2500, serial=4)),
                ("nav_time", build_navigation_packet(3, 600, serial=5)),
                ("nav_orientation", build_navigation_packet(1, 0, serial=6)),
            ]
            for name, packet in nav_packets:
                print(f"NAV_DEMO {name} TX {packet.hex().upper()}")
                await write_qix(client, WRITE_UUID, packet)
                await asyncio.sleep(0.8)
            for idx, speed in enumerate([0, 7, 14, 21, 25, 28, 25, 18, 10, 0], start=7):
                text = f"SPD {speed:02d} KMH"
                for raw in (False, True):
                    packet = build_navigation_packet(4, text, serial=idx, text_len_byte=not raw)
                    variant = "raw" if raw else "len"
                    print(f"NAV_DEMO text_{variant} {text!r} TX {packet.hex().upper()}")
                    await write_qix(client, WRITE_UUID, packet)
                    await asyncio.sleep(0.7)
            await asyncio.sleep(3.0)
        if args.nav_text:
            packet = build_navigation_packet(4, args.nav_text, serial=10, text_len_byte=not args.nav_text_raw)
            print(f"NAV_TEXT TX {packet.hex().upper()}")
            await write_qix(client, WRITE_UUID, packet)
            await asyncio.sleep(3.0)
        if args.weather_text:
            packet = build_weather_packet(args.weather_text, serial=11)
            print(f"WEATHER_TEXT TX {packet.hex().upper()}")
            await write_qix(client, WRITE_UUID, packet)
            await asyncio.sleep(3.0)
        if args.news_text:
            packet = build_news_packet(args.news_text, serial=12)
            print(f"NEWS_TEXT TX {packet.hex().upper()}")
            await write_qix(client, WRITE_UUID, packet)
            await asyncio.sleep(3.0)
        if args.stock_speed is not None:
            speed = args.stock_speed
            packet = build_stock_packet("SPD", "KMH", current=speed, high=max(speed, 30.0), low=0.0, increase=0.0, serial=13)
            print(f"STOCK_SPEED TX {packet.hex().upper()}")
            await write_qix(client, WRITE_UUID, packet)
            await asyncio.sleep(3.0)
        if args.interpret_text:
            for name, packet in [
                ("interpret_on", build_interpretation_packet(6, enabled=True, serial=14)),
                ("interpret_original", build_interpretation_packet(7, text=args.interpret_text, serial=15)),
                ("interpret_translated", build_interpretation_packet(8, text=args.interpret_text, serial=0)),
            ]:
                print(f"INTERPRET_TEXT {name} TX {packet.hex().upper()}")
                await write_qix(client, WRITE_UUID, packet)
                await asyncio.sleep(1.2)
            await asyncio.sleep(3.0)
        if args.live_text_demo:
            for speed in [12, 18, 25, 28, 25]:
                text = f"SPD {speed:02d} KMH"
                packets = [
                    ("nav", build_navigation_packet(4, text, serial=1, text_len_byte=True)),
                    ("weather", build_weather_packet(f"SPD{speed:02d}", temp=speed, low=0, high=max(speed, 30), serial=2)),
                    ("stock", build_stock_packet("SPD", "KMH", current=float(speed), high=30.0, low=0.0, increase=0.0, serial=3)),
                    ("news", build_news_packet(text, serial=4)),
                    ("interpret_on", build_interpretation_packet(6, enabled=True, serial=5)),
                    ("interpret", build_interpretation_packet(8, text=text, serial=6)),
                ]
                for name, packet in packets:
                    print(f"LIVE_TEXT_DEMO speed={speed} {name} TX {packet.hex().upper()}")
                    await write_qix(client, WRITE_UUID, packet)
                    await asyncio.sleep(0.35)
                await asyncio.sleep(1.5)
            await asyncio.sleep(3.0)
        if args.brightness is not None:
            value = max(0, min(255, args.brightness))
            packet = build_packet(0x1B, bytes([value]), request=False, needs_response=True, serial=6)
            print(f"BRIGHTNESS TX {packet.hex().upper()}")
            await client.write_gatt_char(WRITE_UUID, packet, response=False)
            await asyncio.sleep(1.0)
        if args.send_file_state:
            value = 1 if args.send_file_state == "start" else 0
            packet = build_packet(0x87, bytes([value]), request=False, needs_response=True, serial=7)
            print(f"SEND_FILE_STATE TX {packet.hex().upper()}")
            await client.write_gatt_char(WRITE_UUID, packet, response=False)
            await asyncio.sleep(1.0)
        if args.send_file_type is not None:
            value = max(0, min(255, args.send_file_type))
            packet = build_packet(0xDC, bytes([value]), request=False, needs_response=True, serial=8)
            print(f"SEND_FILE_TYPE TX {packet.hex().upper()}")
            await client.write_gatt_char(WRITE_UUID, packet, response=False)
            await asyncio.sleep(1.0)
        if args.push_package:
            package_path = Path(args.push_package)
            push_state = UpdatePushState(package_path.read_bytes())
            print(f"PUSH package={package_path} total={len(push_state.header) + len(push_state.data)} data={len(push_state.data)}")

            file_type = build_packet(0xDC, b"\x0C", request=False, needs_response=True, serial=10)
            file_start = build_packet(0x87, b"\x01", request=False, needs_response=True, serial=11)
            print(f"PUSH_FILE_TYPE TX {file_type.hex().upper()}")
            await write_qix(client, WRITE_UUID, file_type)
            await asyncio.sleep(0.5)
            print(f"PUSH_FILE_START TX {file_start.hex().upper()}")
            await write_qix(client, WRITE_UUID, file_start)
            await asyncio.sleep(0.5)

            header_packet = push_state.header_packet()
            print(f"PUSH_HEADER TX {header_packet[:32].hex().upper()}... len={len(header_packet)}")
            await write_qix(client, WRITE_UUID, header_packet)
            result = await push_state.wait_result(args.push_timeout)
            print(f"PUSH_HEADER_RESULT {result}")
            if result[0] != "request" or result[1] != 1:
                raise RuntimeError(f"Badge rejected update/file header: {result}")

            while push_state.offset < len(push_state.data):
                chunk_packet = push_state.chunk_packet()
                print(f"PUSH_CHUNK offset={push_state.offset} allow={push_state.allow} packet_len={len(chunk_packet)}")
                await write_qix(client, WRITE_UUID, chunk_packet)
                result = await push_state.wait_result(args.push_timeout)
                print(f"PUSH_CHUNK_RESULT {result}")
                if result[0] == "final":
                    break
                if result[0] != "data" or result[1] != 0:
                    raise RuntimeError(f"Badge rejected data chunk: {result}")

            try:
                result = await push_state.wait_result(3.0)
                print(f"PUSH_FINAL_RESULT {result}")
            except asyncio.TimeoutError:
                print("PUSH_FINAL_RESULT timeout/no final result")
        if args.notify:
            if args.notify_setup:
                payload = bytes([0xFF, 0xFF, 0xFF, 0xFF])
                packet = build_packet(0x40, payload, request=False, needs_response=True, serial=8)
                print(f"NOTIFY_SETUP TX {packet.hex().upper()}")
                await client.write_gatt_char(WRITE_UUID, packet, response=False)
                await asyncio.sleep(1.0)
            packet = build_notification_packet(args.notify, args.notify_type, serial=9)
            print(f"NOTIFY TX {packet.hex().upper()}")
            await client.write_gatt_char(WRITE_UUID, packet, response=False)
            await asyncio.sleep(3.0)
        for notify_uuid in notify_started:
            try:
                await client.stop_notify(notify_uuid)
            except Exception as exc:
                print(f"Stop notify skipped: {notify_uuid}: {exc}")

    if bind:
        print("Bind response:")
        for key, value in bind.items():
            print(f"  {key}: {value}")
    else:
        print("No bind response parsed.")


if __name__ == "__main__":
    asyncio.run(main())
