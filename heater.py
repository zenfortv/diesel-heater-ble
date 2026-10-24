"""BLE link to an AirHeaterBLE-family diesel heater (Vevor / BYD / generic Chinese).

Protocol (reverse engineered by iotmaestro/vevor-heater-ble, spin877/Bruciatore_BLE,
Spettacolo83/homeassistant-diesel-heater):
  service FFE0, characteristic FFE1 (write + notify), fallback FFF0/FFF1.
  command = AA 55 <pin/100> <pin%100> <cmd> <arg lo> <arg hi> <checksum>
    cmd 1 = status poll, 2 = mode (1 level / 2 temperature), 3 = power (0/1),
    4 = level 1-10 or target temp 8-36
  the heater answers every write with one status notification
  (AA55 18-20 bytes, AA66 20 bytes, or 48-byte XOR "password"-encrypted variants).
Parsing is done by the diesel-heater-ble library (MIT, Spettacolo83).
"""
from __future__ import annotations

import asyncio
import logging
import random
import time
from typing import Any, Callable

from diesel_heater_ble import (
    ProtocolAA55,
    ProtocolAA55Encrypted,
    ProtocolAA66,
    ProtocolAA66Encrypted,
    _decrypt_data,
    _encrypt_data,
)
from diesel_heater_ble.const import ERROR_NAMES, RUNNING_STEP_NAMES

log = logging.getLogger("heater")

UUID_PAIRS = [
    ("0000ffe0-0000-1000-8000-00805f9b34fb", "0000ffe1-0000-1000-8000-00805f9b34fb"),
    ("0000fff0-0000-1000-8000-00805f9b34fb", "0000fff1-0000-1000-8000-00805f9b34fb"),
]

CMD_STATUS, CMD_MODE, CMD_POWER, CMD_VALUE = 1, 2, 3, 4
# from the AirHeaterBLE app bundle (assets/apps/__UNI__9A3735B/www/app-service.js), decimal numbers.
# warehog's table writes these with a wrong 0x prefix (his "0x13" is really 13 = timer enable).
CMD_CLOCK = 10           # heater clock, minutes since midnight
CMD_TIMER_START = 11     # built-in daily timer: start time, minutes since midnight
CMD_TIMER_DURATION = 12  # built-in daily timer: run time in minutes
CMD_TIMER_ENABLE = 13    # built-in daily timer on/off
CMD_AUTO_START_STOP = 18  # "i_stop": burner stops at target temp, restarts when cold
MODE_LEVEL, MODE_TEMP = 1, 2

_AA55, _AA55E, _AA66, _AA66E = ProtocolAA55(), ProtocolAA55Encrypted(), ProtocolAA66(), ProtocolAA66Encrypted()


def detect_and_parse(data: bytes) -> tuple[str, dict[str, Any]] | None:
    """Same detection rules as the Home Assistant integration (_detect_protocol)."""
    data = bytearray(data)
    if len(data) < 17:
        return None
    header = (data[0] << 8) | data[1]
    if header == 0xAA55 and len(data) in (18, 20):
        return _AA55.name, _AA55.parse(data)
    if header == 0xAA66 and len(data) == 20:
        return _AA66.name, _AA66.parse(data)
    if len(data) == 48:
        dec = _decrypt_data(data)
        inner = (dec[0] << 8) | dec[1]
        proto = _AA55E if inner == 0xAA55 else _AA66E if inner == 0xAA66 else None
        if proto:
            s = proto.parse(dec)
            # extra fields of the encrypted variants: heater clock + built-in timer readback
            s["clock_min"] = (dec[19] << 8) | dec[20]
            s["timer_start_min"] = (dec[21] << 8) | dec[22]
            s["timer_duration_min"] = (dec[23] << 8) | dec[24]
            s["timer_enabled"] = dec[25] == 1
            s["auto_start_stop"] = dec[31] == 1
            return proto.name, s
    return None


class HeaterLink:
    """Keeps a BLE connection, polls status, sends commands. One instance per heater."""

    def __init__(self, address: str, passkey: int = 1234, poll_s: float = 2.0,
                 on_update: Callable[[dict], None] | None = None, clock_sync_min: float = 60) -> None:
        self.address = address
        self.passkey = passkey
        self.poll_s = poll_s
        self.on_update = on_update or (lambda s: None)
        self.paused = False  # True = release BLE so the phone app can connect
        self.state: dict[str, Any] = {"connected": False}
        # the heater's clock drifts a lot; keep correcting it (0 = never)
        self.clock_sync_s = clock_sync_min * 60
        self.clock: dict[str, Any] = {"last_sync": None, "last_drift_min": None, "drift_min_per_day": None}
        self._last_clock_check = 0.0
        self._prev_correction: tuple[float, int] | None = None  # (time, drift) of last measured correction
        self._client = None
        self._char = None
        self._got = asyncio.Event()
        self._lock = asyncio.Lock()
        self._task: asyncio.Task | None = None

    # ---- public API -------------------------------------------------------
    def start(self) -> None:
        self._task = asyncio.create_task(self._run())

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
        await self._disconnect()

    async def set_paused(self, paused: bool) -> None:
        self.paused = paused
        if paused:
            await self._disconnect()
        self._publish()

    async def power(self, on: bool) -> bool:
        return await self._cmd(CMD_POWER, 1 if on else 0)

    async def set_level(self, level: int) -> bool:
        level = max(1, min(10, int(level)))
        return await self._ensure_mode(MODE_LEVEL) and await self._cmd(CMD_VALUE, level)

    async def set_temperature(self, temp: int) -> bool:
        temp = max(8, min(36, int(temp)))
        return await self._ensure_mode(MODE_TEMP) and await self._cmd(CMD_VALUE, temp)

    async def set_auto_start_stop(self, on: bool) -> bool:
        return await self._cmd(CMD_AUTO_START_STOP, 1 if on else 0)

    async def set_builtin_timer(self, start_hhmm: str, duration_min: int, enabled: bool) -> bool:
        """Program the heater's own daily timer (same sequence as the app: clock, start, duration, enable)."""
        h, m = (int(x) for x in start_hhmm.split(":"))
        return (await self.sync_clock(force=True)
                and await self._cmd(CMD_TIMER_START, h * 60 + m)
                and await self._cmd(CMD_TIMER_DURATION, max(1, int(duration_min)))
                and await self._cmd(CMD_TIMER_ENABLE, 1 if enabled else 0))

    async def sync_clock(self, force: bool = True) -> bool:
        """Set the heater clock to PC time. Unforced: only every clock_sync_s, and on heaters that
        report their clock only when it is off by a minute or more."""
        now = time.time()
        if not force and (not self.clock_sync_s or now - self._last_clock_check < self.clock_sync_s):
            return True
        self._last_clock_check = now
        lt = time.localtime(now)
        real = lt.tm_hour * 60 + lt.tm_min
        heater = self.state.get("clock_min")
        if heater is not None:
            drift = (heater - real + 720) % 1440 - 720  # minutes, wraps around midnight
            self.clock["last_drift_min"] = drift
            if drift and self._prev_correction:
                days = (now - self._prev_correction[0]) / 86400
                if days > 0.02:
                    self.clock["drift_min_per_day"] = round(drift / days, 1)
            if drift == 0 and not force:
                self._publish()
                return True
        ok = await self._cmd(CMD_CLOCK, real)
        if ok:
            self.clock["last_sync"] = now
            self._prev_correction = (now, 0)
            log.info("heater clock set to %02d:%02d (drift was %s min)", real // 60, real % 60,
                     self.clock["last_drift_min"] if heater is not None else "?")
            self._publish()
        return ok

    async def _ensure_mode(self, mode: int) -> bool:
        # like the ESPHome component: only switch mode when it differs
        if self.state.get("running_mode") == mode:
            return True
        return await self._cmd(CMD_MODE, mode)

    # ---- internals --------------------------------------------------------
    def _packet(self, cmd: int, arg: int) -> bytes:
        p = bytearray([0xAA, 0x55, self.passkey // 100, self.passkey % 100, cmd, arg % 256, (arg // 256) % 256, 0])
        p[7] = sum(p[2:7]) % 256
        return bytes(p)

    def _publish(self) -> None:
        self.state["paused"] = self.paused
        self.state["address"] = self.address
        self.state["clock_sync"] = dict(self.clock)
        self.on_update(dict(self.state))

    def _on_notify(self, _char, data: bytearray) -> None:
        res = detect_and_parse(data)
        if not res:
            log.debug("unparsed notification %s", bytes(data).hex())
            return
        proto, s = res
        s["protocol"] = proto
        s["running_step_name"] = RUNNING_STEP_NAMES.get(s.get("running_step"), str(s.get("running_step")))
        s["error_name"] = ERROR_NAMES.get(s.get("error_code"), f"E{s.get('error_code')}")
        s["connected"] = True
        s["updated"] = time.time()
        self.state = s
        self._got.set()
        self._publish()

    async def _cmd(self, cmd: int, arg: int, timeout: float = 4.0) -> bool:
        async with self._lock:
            if not self._client or not self._client.is_connected:
                log.warning("command %d ignored: not connected", cmd)
                return False
            self._got.clear()
            try:
                with_rsp = "write" in self._char.properties  # app + ESPHome use write-with-response
                await self._client.write_gatt_char(self._char, self._packet(cmd, arg), response=with_rsp)
                await asyncio.wait_for(self._got.wait(), timeout)
                await asyncio.sleep(0.3)  # heater needs a breath between consecutive writes
                return True
            except Exception as e:  # noqa: BLE001
                log.warning("command %d/%d failed: %s", cmd, arg, e)
                return False

    async def _connect(self) -> None:
        from bleak import BleakClient

        client = BleakClient(self.address, timeout=20.0,
                             disconnected_callback=lambda _c: log.info("heater disconnected"))
        await client.connect()
        char = None
        for svc_uuid, ch_uuid in UUID_PAIRS:
            svc = client.services.get_service(svc_uuid)
            if svc and svc.get_characteristic(ch_uuid):
                char = svc.get_characteristic(ch_uuid)
                # subscribe to every notifiable characteristic of the service (variants differ)
                for c in svc.characteristics:
                    if "notify" in c.properties:
                        await client.start_notify(c, self._on_notify)
                break
        if char is None:
            await client.disconnect()
            raise RuntimeError("heater service FFE0/FFF0 not found - is this the right address?")
        self._client, self._char = client, char
        log.info("connected to %s (%s)", self.address, char.uuid)

    async def _disconnect(self) -> None:
        c, self._client = self._client, None
        if c:
            try:
                await c.disconnect()
            except Exception:  # noqa: BLE001
                pass
        self.state["connected"] = False

    async def _run(self) -> None:
        backoff = 3
        while True:
            if self.paused:
                await asyncio.sleep(1)
                continue
            try:
                if not self._client or not self._client.is_connected:
                    self.state["connected"] = False
                    self._publish()
                    await self._connect()
                    backoff = 3
                await self._cmd(CMD_STATUS, 0)
                await self.sync_clock(force=False)
                await asyncio.sleep(self.poll_s)
            except asyncio.CancelledError:
                raise
            except Exception as e:  # noqa: BLE001
                log.warning("BLE: %s (retry in %ss)", e, backoff)
                await self._disconnect()
                self.state["last_error"] = str(e)
                self._publish()
                await asyncio.sleep(backoff)
                backoff = min(backoff * 2, 60)


class FakeHeater(HeaterLink):
    """Simulated AA55 heater for testing the app without hardware."""

    def __init__(self, *a, **kw) -> None:
        super().__init__(*a, **kw)
        self._sim = {"on": 0, "step": 0, "mode": MODE_LEVEL, "value": 5, "case": 20.0, "room": 14.0, "t_on": 0.0,
                     "clock_off": 7, "t_start": 360, "t_dur": 60, "t_en": 0, "istop": 0}  # clock 7 min fast

    def _frame(self) -> bytes:
        s = self._sim
        if s["on"]:
            el = time.time() - s["t_on"]
            s["step"] = 1 if el < 5 else 2 if el < 15 else 3
        elif s["step"] in (1, 2, 3):
            s["step"], s["t_off"] = 4, time.time()
        elif s["step"] == 4 and time.time() - s.get("t_off", 0) > 10:
            s["step"] = 0
        heat = s["step"] == 3
        s["case"] += ((150 if heat else 20) - s["case"]) * 0.08
        s["room"] += ((26 if heat else 12) - s["room"]) * 0.01
        # newer encrypted 48-byte AA55 frame (big-endian fields, timer readback at 19-25)
        level = s["value"] if s["mode"] == MODE_LEVEL else 5
        temp = s["value"] if s["mode"] == MODE_TEMP else 20
        lt = time.localtime()
        f = bytearray(48)
        f[0:2] = b"\xaa\x55"
        f[3], f[4], f[5] = s["on"], 0, s["step"]
        f[6:8] = (2500).to_bytes(2, "big")
        f[8], f[9], f[10] = s["mode"], temp, level
        f[11:13] = int(124 + random.random() * 3).to_bytes(2, "big")
        f[13:15] = int(s["case"]).to_bytes(2, "big")
        f[19:21] = ((lt.tm_hour * 60 + lt.tm_min + s["clock_off"]) % 1440).to_bytes(2, "big")
        f[21:23] = s["t_start"].to_bytes(2, "big")
        f[23:25] = s["t_dur"].to_bytes(2, "big")
        f[25], f[31] = s["t_en"], s["istop"]
        f[32:34] = int(s["room"] * 10).to_bytes(2, "big")
        return bytes(_encrypt_data(f))

    async def _cmd(self, cmd: int, arg: int, timeout: float = 4.0) -> bool:
        s = self._sim
        if cmd == CMD_POWER:
            if arg and not s["on"]:
                s["t_on"] = time.time()
            s["on"] = arg
        elif cmd == CMD_MODE:
            s["mode"] = arg
            s["value"] = 5 if arg == MODE_LEVEL else 20
        elif cmd == CMD_VALUE:
            s["value"] = arg
        elif cmd == CMD_CLOCK:
            lt = time.localtime()
            s["clock_off"] = arg - (lt.tm_hour * 60 + lt.tm_min)
        elif cmd == CMD_TIMER_START:
            s["t_start"] = arg
        elif cmd == CMD_TIMER_DURATION:
            s["t_dur"] = arg
        elif cmd == CMD_TIMER_ENABLE:
            s["t_en"] = arg
        elif cmd == CMD_AUTO_START_STOP:
            s["istop"] = arg
        self._on_notify(None, bytearray(self._frame()))
        return True

    async def _run(self) -> None:
        while True:
            if not self.paused:
                await self._cmd(CMD_STATUS, 0)
                await self.sync_clock(force=False)
            await asyncio.sleep(self.poll_s)

    async def _disconnect(self) -> None:
        self.state["connected"] = False
