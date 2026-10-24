"""Find BLE diesel heaters nearby (AirHeaterBLE / Vevor family).

Usage:  .venv\\Scripts\\python.exe scan.py [seconds]
Close the AirHeaterBLE app on the phone first: the heater accepts one connection at a time
and stops advertising while connected.
"""
import asyncio
import sys

from bleak import BleakScanner

HEATER_SERVICES = {
    "0000ffe0-0000-1000-8000-00805f9b34fb",  # AA55 / AA66 (AirHeaterBLE)
    "0000fff0-0000-1000-8000-00805f9b34fb",  # alt / ABBA / Hcalory MVP1
    "0000bd39-0000-1000-8000-00805f9b34fb",  # Hcalory MVP2
}
NAME_HINTS = ("heater", "vevor", "air", "byd", "hcalory", "diesel", "ffe")


async def main(seconds: float) -> None:
    print(f"Scanning {seconds:.0f}s ...")
    found = await BleakScanner.discover(timeout=seconds, return_adv=True)
    rows = []
    for addr, (dev, adv) in found.items():
        name = adv.local_name or dev.name or ""
        uuids = {u.lower() for u in adv.service_uuids}
        likely = bool(uuids & HEATER_SERVICES) or any(h in name.lower() for h in NAME_HINTS)
        rows.append((likely, adv.rssi, addr, name, ",".join(sorted(uuids))[:60]))
    rows.sort(key=lambda r: (not r[0], -r[1]))
    for likely, rssi, addr, name, uuids in rows:
        print(f"{'>>' if likely else '  '} {addr}  {rssi:>4} dBm  {name:<24} {uuids}")
    print("\n'>>' = probably a heater. Put its address in config.json -> \"address\".")


if __name__ == "__main__":
    asyncio.run(main(float(sys.argv[1]) if len(sys.argv) > 1 else 10))
