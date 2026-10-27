import argparse
import asyncio
from typing import Iterable, Optional, Sequence

from bleak import BleakClient, BleakScanner


BOND_NAMES = ("BOND", "Bond More", "BOND 4", "BOND MINI")

SERVICE_USER_V2 = "d3c00002-add3-1d4b-0001-0000b5006bb1"
CHAR_V2_APP = "d3c0ffa6-add3-1d4b-0001-0000b5006bb1"
CHAR_V2_API = "d3c0ff10-add3-1d4b-0000-0000b5006bb1"

SERVICE_USER_V3 = "d3c00003-add3-1d4b-0000-0000b5006bb1"
CHAR_FFB1 = "d3c0ffb1-add3-1d4b-0000-0000b5006bb1"
CHAR_FFB2 = "d3c0ffb2-add3-1d4b-0000-0000b5006bb1"
CHAR_V3_API = "d3c0ffb3-add3-1d4b-0000-0000b5006bb1"

CHAR_FFA8 = "d3c0ffa8-add3-1d4b-0001-0000b5006bb1"


def u16le(value: int) -> bytes:
    value = max(0, min(0xFFFF, int(value)))
    return bytes((value & 0xFF, (value >> 8) & 0xFF))


def u32le(value: int) -> bytes:
    value = int(value) & 0xFFFFFFFF
    return bytes(
        (
            value & 0xFF,
            (value >> 8) & 0xFF,
            (value >> 16) & 0xFF,
            (value >> 24) & 0xFF,
        )
    )


def as_u8(value: int) -> int:
    return max(0, min(255, int(value)))


def byte_cast(value: int) -> int:
    return int(value) & 0xFF


def build_v2_single_vibration(strength: int, duration_ms: int = 2000) -> bytes:
    strength = as_u8(strength)
    led_execute_time = u16le(0).ljust(20, b"\x00")
    return b"".join(
        (
            bytes((0x26, 0x00, 0x00, strength)),
            u16le(duration_ms),
            bytes((0x04, 0x00, 0x00, 0x00, 0x00)),
            u16le(0),
            u16le(0),
            u16le(0),
            led_execute_time,
        )
    )


def build_v2_token() -> bytes:
    return b"12345678"


def build_v2_auid(auid: int) -> bytes:
    return u32le(auid)


def build_v2_paired_correct() -> bytes:
    return bytes((0x31, 0x00, 0x11, 0x22, 0x33, 0x44)).ljust(20, b"\x00")


def build_v2_execute_queue(with_meaning: bool = False) -> bytes:
    mode = 0x22 if with_meaning else 0x11
    return bytes((0x32, 0x00, mode)).ljust(20, b"\x00")


def build_v3_init_auth() -> bytes:
    return bytes((0x01, 0x30))


def build_v3_token() -> bytes:
    return bytes((0x03,)) + b"12345678"


def build_v3_auid(auid: int) -> bytes:
    # Static app code truncates the locally persisted int AUID to one byte here.
    return bytes((0x07, auid & 0xFF))


def build_v3_paired_correct() -> bytes:
    return bytes((0x01, 0x31))


def build_v3_vibration_intensity(intensity: int, balance: int, operation: int = 0) -> bytes:
    return bytes((0x01, 0x48, as_u8(operation), as_u8(intensity), as_u8(balance), 0x03))


def build_v3_ffb2_chunk(values: Sequence[int]) -> bytes:
    return bytes(byte_cast(v) for v in values).ljust(20, b"\xff")


def build_v3_ffb2_header(
    route_or_menu: int,
    pattern_count: int,
    chunk_count: int,
    start_color: int,
    end_color: int,
    reply_enabled: bool,
) -> bytes:
    start_r = (start_color >> 16) & 0xFF
    start_g = (start_color >> 8) & 0xFF
    start_b = start_color & 0xFF
    end_r = (end_color >> 16) & 0xFF
    end_g = (end_color >> 8) & 0xFF
    end_b = end_color & 0xFF
    return build_v3_ffb2_chunk(
        (
            route_or_menu,
            pattern_count,
            chunk_count,
            start_r,
            start_g,
            start_b,
            end_r,
            end_g,
            end_b,
            1 if reply_enabled else 0,
        )
    )


def looks_like_bond(name: Optional[str]) -> bool:
    if not name:
        return False
    return any(token.lower() in name.lower() for token in BOND_NAMES)


def hex_bytes(payload: bytes) -> str:
    return payload.hex(" ")


async def discover(timeout: float):
    found = await BleakScanner.discover(timeout=timeout, return_adv=True)
    if isinstance(found, dict):
        return [item[0] for item in found.values()]
    return list(found)


async def resolve_device(target: Optional[str], timeout: float):
    if not target:
        devices = await discover(timeout)
        matches = [d for d in devices if looks_like_bond(d.name)]
        if not matches:
            raise SystemExit("No Bond-like device found. Pass --target with address or name.")
        if len(matches) > 1:
            print("Multiple Bond-like devices found:")
            for d in matches:
                print(f"  {d.address}  {d.name}")
            raise SystemExit("Pass --target to choose one.")
        return matches[0]

    devices = await discover(timeout)
    for device in devices:
        name = device.name or ""
        if target.lower() in (device.address.lower(), name.lower()) or target.lower() in name.lower():
            return device

    return target


async def cmd_scan(args):
    devices = await discover(args.timeout)
    for d in sorted(devices, key=lambda item: (item.name or "", item.address)):
        marker = "*" if looks_like_bond(d.name) else " "
        print(f"{marker} {d.address:38} {d.name or '(no name)'}")


def print_services(services: Iterable):
    for service in services:
        print(f"{service.uuid}  {service.description}")
        for char in service.characteristics:
            props = ",".join(char.properties)
            print(f"  {char.uuid}  [{props}]  {char.description}")


async def cmd_services(args):
    device = await resolve_device(args.target, args.timeout)
    async with BleakClient(device, timeout=args.connect_timeout) as client:
        print(f"Connected: {client.is_connected}")
        print_services(client.services)


async def cmd_classify(args):
    device = await resolve_device(args.target, args.timeout)
    async with BleakClient(device, timeout=args.connect_timeout) as client:
        uuids = {service.uuid.lower() for service in client.services}
        chars = {
            char.uuid.lower()
            for service in client.services
            for char in service.characteristics
        }

        print(f"Connected: {client.is_connected}")
        print(f"Name: {getattr(device, 'name', None) or '(unknown)'}")
        print(f"Address: {getattr(device, 'address', device)}")

        if SERVICE_USER_V3 in uuids:
            print("Protocol guess: v3 / BTM family")
            api_char = CHAR_V3_API
        elif SERVICE_USER_V2 in uuids:
            if CHAR_FFA8 in chars:
                print("Protocol guess: v2-family with FFA8 extension (BT4 or MINI)")
            else:
                print("Protocol guess: v1/v2 legacy family")
            api_char = CHAR_V2_API
        else:
            print("Protocol guess: unknown; Bond user application service not found")
            api_char = None

        if api_char:
            try:
                value = await client.read_gatt_char(api_char)
                print(f"API characteristic {api_char}: {hex_bytes(bytes(value))}")
            except Exception as exc:  # BLE stacks vary; this is diagnostic tooling.
                print(f"API characteristic read failed: {exc}")


async def cmd_vibrate_v2(args):
    device = await resolve_device(args.target, args.timeout)
    payload = build_v2_single_vibration(args.strength, args.duration_ms)
    print(f"Payload: {hex_bytes(payload)}")

    if args.dry_run:
        return

    async with BleakClient(device, timeout=args.connect_timeout) as client:
        await client.write_gatt_char(CHAR_V2_APP, payload, response=not args.no_response)
        print("Write sent.")


async def cmd_payloads(args):
    pattern = [int(item.strip()) for item in args.pattern.split(",") if item.strip()]
    chunks = max(1, (len(pattern) + 19) // 20)

    print("v2 / legacy payloads")
    print(f"  FFA1 token:              {hex_bytes(build_v2_token())}")
    print(f"  FFA2 AUID:               {hex_bytes(build_v2_auid(args.auid))}")
    print(f"  FFA6 paired-correct:     {hex_bytes(build_v2_paired_correct())}")
    print(f"  FFA6 single vibration:   {hex_bytes(build_v2_single_vibration(args.strength, args.duration_ms))}")
    print(f"  FFA6 execute queue:      {hex_bytes(build_v2_execute_queue(False))}")
    print(f"  FFA6 execute meaning:    {hex_bytes(build_v2_execute_queue(True))}")
    print()
    print("v3 / BTM payloads")
    print(f"  FFB1 init auth:          {hex_bytes(build_v3_init_auth())}")
    print(f"  FFB1 token:              {hex_bytes(build_v3_token())}")
    print(f"  FFB1 AUID:               {hex_bytes(build_v3_auid(args.auid))}")
    print(f"  FFB1 paired-correct:     {hex_bytes(build_v3_paired_correct())}")
    print(
        "  FFB1 vibration intensity:"
        f" {hex_bytes(build_v3_vibration_intensity(args.intensity, args.balance, args.operation))}"
    )
    print(
        "  FFB2 message header:     "
        f"{hex_bytes(build_v3_ffb2_header(args.route, len(pattern), chunks, args.start_color, args.end_color, args.reply))}"
    )
    for index, start in enumerate(range(0, len(pattern), 20), start=1):
        print(f"  FFB2 pattern chunk {index}:  {hex_bytes(build_v3_ffb2_chunk(pattern[start:start + 20]))}")


def build_parser():
    parser = argparse.ArgumentParser(description="Bond Touch BLE probe")
    sub = parser.add_subparsers(dest="command", required=True)

    scan = sub.add_parser("scan", help="Scan for BLE devices")
    scan.add_argument("--timeout", type=float, default=8.0)
    scan.set_defaults(func=cmd_scan)

    services = sub.add_parser("services", help="List GATT services for a device")
    services.add_argument("--target", required=True, help="Device name fragment or address")
    services.add_argument("--timeout", type=float, default=8.0)
    services.add_argument("--connect-timeout", type=float, default=30.0)
    services.set_defaults(func=cmd_services)

    classify = sub.add_parser("classify", help="Guess Bond protocol generation from GATT services")
    classify.add_argument("--target", required=True, help="Device name fragment or address")
    classify.add_argument("--timeout", type=float, default=8.0)
    classify.add_argument("--connect-timeout", type=float, default=30.0)
    classify.set_defaults(func=cmd_classify)

    vib = sub.add_parser("vibrate-v2", help="Send mapped v2 single vibration command")
    vib.add_argument("--target", required=True, help="Device name fragment or address")
    vib.add_argument("--strength", type=int, default=100)
    vib.add_argument("--duration-ms", type=int, default=2000)
    vib.add_argument("--timeout", type=float, default=8.0)
    vib.add_argument("--connect-timeout", type=float, default=30.0)
    vib.add_argument("--dry-run", action="store_true")
    vib.add_argument("--no-response", action="store_true")
    vib.set_defaults(func=cmd_vibrate_v2)

    payloads = sub.add_parser("payloads", help="Print mapped payload examples without connecting")
    payloads.add_argument("--auid", type=lambda value: int(value, 0), default=0x12345678)
    payloads.add_argument("--strength", type=int, default=100)
    payloads.add_argument("--duration-ms", type=int, default=2000)
    payloads.add_argument("--intensity", type=int, default=100)
    payloads.add_argument("--balance", type=int, default=0)
    payloads.add_argument("--operation", type=int, default=0)
    payloads.add_argument("--route", type=int, default=1)
    payloads.add_argument("--start-color", type=lambda value: int(value, 0), default=0xFFFFFF)
    payloads.add_argument("--end-color", type=lambda value: int(value, 0), default=0xFFFFFF)
    payloads.add_argument("--pattern", default="500,250,500,250,500")
    payloads.add_argument("--reply", action="store_true")
    payloads.set_defaults(func=cmd_payloads)

    return parser


async def main():
    args = build_parser().parse_args()
    await args.func(args)


if __name__ == "__main__":
    asyncio.run(main())
