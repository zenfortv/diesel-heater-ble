"""Offline checks: packet format, protocol parsing, scheduler events (uses the simulator)."""
import asyncio, sys, time
from datetime import datetime, timedelta
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from heater import HeaterLink, FakeHeater, detect_and_parse
from diesel_heater_ble import _encrypt_data
from scheduler import Scheduler

# packet: status poll with pin 1234 = AA55 0C 22 01 00 00 2F (matches spin877 capture)
h = HeaterLink("x")
assert h._packet(1, 0).hex() == "aa550c220100002f", h._packet(1, 0).hex()
assert h._packet(3, 1).hex() == "aa550c2203010032"

# AA55 frame: on, running, temp mode 22C, 12.6V, case 120, room 19
f = bytearray(20); f[0:2]=b"\xaa\x55"; f[3]=1; f[5]=3; f[8]=2; f[9]=22; f[10]=4
f[11:13]=(126).to_bytes(2,"little"); f[13:15]=(120).to_bytes(2,"little"); f[15:17]=(19).to_bytes(2,"little")
p, s = detect_and_parse(bytes(f))
assert p == "AA55" and s["set_temp"] == 22 and s["supply_voltage"] == 12.6 and s["cab_temperature"] == 19, s
# encrypted 48-byte variant is detected too
e = bytearray(48); e[0:2]=b"\xaa\x55"; e[3]=1; e[8]=1; e[10]=7
p2, s2 = detect_and_parse(bytes(_encrypt_data(e)))
e[31] = 1; e[23:25] = (300).to_bytes(2, "big")
p2, s2 = detect_and_parse(bytes(_encrypt_data(e)))
assert p2 == "AA55 encrypted" and s2["running_state"] == 1 and s2["auto_start_stop"] and s2["timer_duration_min"] == 300, (p2, s2)

async def sched_test():
    path = Path(__file__).parent / "_sched.json"; path.unlink(missing_ok=True)
    fh = FakeHeater("sim", poll_s=0.1)
    sc = Scheduler(fh, path)
    now = datetime.now()
    # one-shot ON 2s in the past relative to tick window
    sc._last = time.time() - 60
    sc.add("once", {"at": (now - timedelta(seconds=30)).isoformat(timespec="seconds"), "action": "on", "mode": "temp", "value": 25})
    await sc._tick()
    assert fh._sim["on"] == 1 and fh._sim["mode"] == 2 and fh._sim["value"] == 25, fh._sim
    assert sc.data["once"][0]["enabled"] is False
    # countdown off
    sc.data["off_timer"] = time.time() - 1; sc._last = time.time() - 10
    await sc._tick()
    assert fh._sim["on"] == 0 and sc.data["off_timer"] is None
    # weekly slot crossing midnight shows in next events
    sc.add("weekly", {"days": list(range(7)), "on": "23:00", "off": "01:00", "mode": "level", "value": 7})
    nxt = sc.next_events(4)
    assert nxt and {e["action"] for e in nxt} == {"on", "off"}, nxt
    on_t = datetime.fromisoformat([e for e in nxt if e["action"]=="on"][0]["at"])
    off_t = min(datetime.fromisoformat(e["at"]) for e in nxt if e["action"]=="off" and datetime.fromisoformat(e["at"]) > on_t)
    assert off_t - on_t == timedelta(hours=2), (on_t, off_t)
    # weekly ON fires
    sc.data["weekly"][0].update(on=(now - timedelta(minutes=1)).strftime("%H:%M"), off=(now + timedelta(minutes=30)).strftime("%H:%M"))
    sc._last = time.time() - 120
    await sc._tick()
    assert fh._sim["on"] == 1 and fh._sim["mode"] == 1 and fh._sim["value"] == 7, fh._sim
    print("history:", [x["src"] + ":" + x["result"] for x in sc.history])
    path.unlink()

asyncio.run(sched_test())
print("ALL OK")

# built-in timer: app sends clock(10), start(11), duration(12), enable(13); data little-endian in bytes 5-6
assert h._packet(11, 390).hex() == "aa550c220b8601c0"   # 06:30 = 390 min = 0x0186
async def builtin():
    fh = FakeHeater("sim")
    assert await fh.set_builtin_timer("06:30", 45, True)
    st = fh.state
    assert st["protocol"] == "AA55 encrypted" and st["timer_start_min"] == 390 and st["timer_duration_min"] == 45 and st["timer_enabled"], st
    await fh.set_auto_start_stop(True)
    assert fh.state["auto_start_stop"] is True
asyncio.run(builtin())
print("BUILTIN OK")

# clock drift: simulated heater starts 7 min fast; auto-sync measures and fixes it
async def clock():
    fh = FakeHeater("sim", clock_sync_min=60)
    await fh._cmd(1, 0)
    lt = time.localtime(); real = lt.tm_hour * 60 + lt.tm_min
    assert (fh.state["clock_min"] - real) % 1440 == 7
    await fh.sync_clock(force=False)          # first unforced check -> corrects
    assert fh.clock["last_drift_min"] == 7 and fh.clock["last_sync"]
    await fh._cmd(1, 0)
    assert fh.state["clock_min"] == real or fh.state["clock_min"] == (real + 1) % 1440
    t0 = fh._last_clock_check
    await fh.sync_clock(force=False)          # within the hour -> no-op
    assert fh._last_clock_check == t0
    # pretend a day passed and the clock drifted 3 min slow
    fh._prev_correction = (time.time() - 86400, 0); fh._last_clock_check = 0; fh._sim["clock_off"] = -3
    await fh._cmd(1, 0); await fh.sync_clock(force=False)
    assert fh.clock["last_drift_min"] == -3 and fh.clock["drift_min_per_day"] == -3.0, fh.clock
    assert fh._sim["clock_off"] == 0
asyncio.run(clock())
print("CLOCK OK")
