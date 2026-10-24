"""The web page (web/index.html) has its own JavaScript copy of the protocol for direct Bluetooth.
This feeds the same frames to the Python parser (heater.detect_and_parse) and to the JS parser (via node)
and checks that both decode them the same way, and that both build identical command packets.

Run:  .venv\\Scripts\\python.exe tests\\test_web_protocol.py      (needs node on PATH)
"""
import json
import random
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT))
from diesel_heater_ble import _encrypt_data  # noqa: E402
from heater import HeaterLink, detect_and_parse  # noqa: E402

FIELDS = ["running_state", "error_code", "running_step", "running_mode", "set_level", "set_temp", "supply_voltage",
          "case_temperature", "cab_temperature", "clock_min", "timer_start_min", "timer_duration_min",
          "timer_enabled", "auto_start_stop"]


def frames() -> list[bytes]:
    rnd = random.Random(7)
    out = []
    for _ in range(40):
        # plain AA55 (20 and 18 bytes), plain AA66, encrypted AA55 / AA66 with random payloads
        mode = rnd.choice([0, 1, 2])
        b = bytearray(rnd.randrange(256) for _ in range(20))
        b[0:2], b[3], b[4], b[5], b[8] = b"\xaa\x55", rnd.randrange(2), rnd.randrange(11), rnd.choice([0, 1, 2, 3, 4]), mode
        b[9] = rnd.randrange(1, 37)
        out += [bytes(b), bytes(b[:18])]
        c = bytearray(b)
        c[0:2] = b"\xaa\x66"
        out.append(bytes(c))
        for hdr in (b"\xaa\x55", b"\xaa\x66"):
            e = bytearray(rnd.randrange(256) for _ in range(48))
            e[0:2], e[8], e[25], e[31] = hdr, mode, rnd.randrange(2), rnd.randrange(2)
            e[27] = rnd.choice([0, 0, 1])
            e[19:21] = rnd.randrange(1440).to_bytes(2, "big")
            out.append(bytes(_encrypt_data(e)))
    return out


def py_side(fs: list[bytes]) -> list[dict | None]:
    res = []
    for f in fs:
        r = detect_and_parse(f)
        res.append(None if r is None else {k: r[1].get(k) for k in FIELDS if r[1].get(k) is not None})
    return res


def js_side(fs: list[bytes], packets: list[tuple[int, int, int]]) -> dict:
    html = (ROOT / "web" / "index.html").read_text(encoding="utf-8")
    proto = re.search(r"const SERVICES =.*?(?=/\* -+ devices)", html, re.S).group(0)
    js = ("const clamp = (v, a, b) => Math.max(a, Math.min(b, v));\n" + proto +
          "\nconst inp = JSON.parse(require('fs').readFileSync(0, 'utf8'));\n"
          "const out = {frames: inp.frames.map(h => parseFrame(Uint8Array.from(Buffer.from(h, 'hex')))),\n"
          "  packets: inp.packets.map(([p, c, a]) => Buffer.from(packet(p, c, a)).toString('hex'))};\n"
          "process.stdout.write(JSON.stringify(out));")
    run = subprocess.run(["node", "-e", js], input=json.dumps({"frames": [f.hex() for f in fs], "packets": packets}),
                         capture_output=True, text=True, check=True)
    return json.loads(run.stdout)


def main() -> None:
    fs = frames()
    packets = [(1234, 1, 0), (1234, 3, 1), (1234, 10, 1439), (1234, 11, 390), (9999, 12, 600), (0, 13, 1)]
    js = js_side(fs, packets)
    link = HeaterLink("x")
    bad = 0
    for f, p, j in zip(fs, py_side(fs), js["frames"]):
        jj = None if j is None else {k: j[k] for k in FIELDS if j.get(k) is not None}
        if p != jj and not (p and jj and all(abs(p[k] - jj.get(k, 1e9)) < 1e-9 if isinstance(p[k], float) else p[k] == jj.get(k) for k in p) and p.keys() == jj.keys()):
            bad += 1
            print("MISMATCH", f.hex(), "\n  py", p, "\n  js", jj)
    for (pin, c, a), h in zip(packets, js["packets"]):
        link.passkey = pin
        if link._packet(c, a).hex() != h:
            bad += 1
            print("PACKET MISMATCH", pin, c, a, link._packet(c, a).hex(), h)
    print(f"{len(fs)} frames, {len(packets)} packets compared")
    print("WEB PROTOCOL OK" if not bad else f"{bad} MISMATCHES")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
