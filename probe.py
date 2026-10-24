"""Read-only check of a real heater: list GATT services, send status polls, print raw + parsed replies.

Run:  .venv\\Scripts\\python.exe probe.py <address> [passkey]
Only cmd 1 (status) is sent - nothing changes on the heater.
"""
import asyncio
import sys

from bleak import BleakClient, BleakScanner

from diesel_heater_ble import _decrypt_data
from heater import UUID_PAIRS, detect_and_parse


async def main(address: str, passkey: int) -> None:
    dev = await BleakScanner.find_device_by_address(address, timeout=15.0)
    if dev is None:
        print("not advertising - is the phone app still connected?")
        return
    for attempt in range(1, 4):
        try:
            await probe(dev, passkey)
            return
        except Exception as e:  # noqa: BLE001
            print(f"attempt {attempt} failed: {type(e).__name__} {e}")
            await asyncio.sleep(3)


async def probe(dev, passkey: int) -> None:
    address = dev.address
    async with BleakClient(dev, timeout=30.0, winrt={"use_cached_services": False}) as client:
        print("connected", address)
        for svc in client.services:
            print("service", svc.uuid)
            for c in svc.characteristics:
                print("   char", c.uuid, c.properties)
        char = None
        for svc_uuid, ch_uuid in UUID_PAIRS:
            svc = client.services.get_service(svc_uuid)
            if svc and svc.get_characteristic(ch_uuid):
                char = svc.get_characteristic(ch_uuid)
                for c in svc.characteristics:
                    if "notify" in c.properties:
                        await client.start_notify(c, on_notify)
                break
        if char is None:
            print("no FFE0/FFF0 service")
            return
        for _ in range(3):
            p = bytearray([0xAA, 0x55, passkey // 100, passkey % 100, 1, 0, 0, 0])
            p[7] = sum(p[2:7]) % 256
            print("\n>>", p.hex())
            await client.write_gatt_char(char, bytes(p), response="write" in char.properties)
            await asyncio.sleep(2.5)


def on_notify(_c, data: bytearray) -> None:
    print("<<", len(data), "bytes", bytes(data).hex())
    if len(data) == 48:
        print("   decrypted", bytes(_decrypt_data(bytearray(data))).hex())
    res = detect_and_parse(data)
    if res:
        proto, s = res
        print("  ", proto)
        for k, v in s.items():
            print(f"     {k}: {v}")
    else:
        print("   (not recognised)")


if __name__ == "__main__":
    asyncio.run(main(sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 1234))
