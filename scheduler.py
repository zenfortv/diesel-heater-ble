"""Heating timers the AirHeaterBLE app does not have (same model as the Android app and web/index.html).

Persisted in schedules.json:
  alarms    - [{"at":"07:00", "dur":45 (minutes, 0 = on until stopped), "days":[0..6 Mon=0] (empty = once, on "date"),
                "mode":"temp|level|keep", "value":21, "enabled":true}]
              like an alarm clock: on at "at", and the run time becomes the switch-off countdown
  off_timer - epoch seconds: switch off at that moment; off_src = who set it ("countdown" or "timer 07:00")
              5 min before it, snooze(+N min) or snooze(None) = keep on until stopped
Plus a safety cap: max_run_hours (0 = disabled) switches off a heater left on too long.
Old files with "weekly" / "once" are converted on load.

Events are edge-triggered: the heater is commanded only when an event time passes,
so manual changes in between are respected. A failed command is retried for 10 minutes.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Any

from heater import HeaterLink

log = logging.getLogger("scheduler")
RETRY_WINDOW_S = 600
STALE_S = 300
WARN_S = 300
TICK_S = 5


def _hm(s: str) -> int:
    h, m = s.split(":")
    return int(h) * 60 + int(m)


class Scheduler:
    def __init__(self, heater: HeaterLink, path: Path) -> None:
        self.heater = heater
        self.path = path
        self.data: dict[str, Any] = {"alarms": [], "off_timer": None, "max_run_hours": 0}
        self.history: list[dict] = []
        self._pending: list[dict] = []
        self._last = time.time()
        self._on_since: float | None = None
        if path.exists():
            self.data.update(json.loads(path.read_text(encoding="utf-8")))
        if self._migrate():
            self.save()

    def _migrate(self) -> bool:
        d = self.data
        if "weekly" not in d and "once" not in d:
            return False
        d.setdefault("alarms", [])
        for r in d.pop("weekly", []):
            m = _hm(r["off"]) - _hm(r["on"])
            d["alarms"].append({"id": uuid.uuid4().hex[:8], "at": r["on"], "dur": m if m > 0 else m + 1440,
                                "days": r.get("days", []), "mode": r.get("mode", "keep"), "value": r.get("value"),
                                "enabled": r.get("enabled", True)})
        for o in d.pop("once", []):
            t = datetime.fromisoformat(o["at"])
            if o.get("action", "on") == "on" and o.get("enabled", True) and t > datetime.now():
                d["alarms"].append({"id": uuid.uuid4().hex[:8], "at": t.strftime("%H:%M"), "dur": 0, "days": [],
                                    "date": t.date().isoformat(), "mode": o.get("mode", "keep"),
                                    "value": o.get("value"), "enabled": True})
        return True

    def save(self) -> None:
        self.path.write_text(json.dumps(self.data, indent=2), encoding="utf-8")

    # ---- CRUD -------------------------------------------------------------
    @staticmethod
    def _fix_date(it: dict) -> None:
        """a one-time timer fires the next time its hour comes round"""
        if not it.get("days"):
            if it.get("enabled", True):
                now = datetime.now()
                d = now.date() if now.hour * 60 + now.minute < _hm(it["at"]) else now.date() + timedelta(days=1)
                it["date"] = d.isoformat()
        else:
            it.pop("date", None)

    def add(self, item: dict) -> dict:
        item = {"dur": 60, **item, "id": uuid.uuid4().hex[:8]}
        item.setdefault("enabled", True)
        self._fix_date(item)
        self.data["alarms"].append(item)
        self.save()
        return item

    def update(self, item_id: str, patch: dict) -> dict | None:
        for it in self.data["alarms"]:
            if it["id"] == item_id:
                it.update({k: v for k, v in patch.items() if k != "id"})
                self._fix_date(it)
                self.save()
                return it
        return None

    def delete(self, item_id: str) -> None:
        self.data["alarms"] = [it for it in self.data["alarms"] if it["id"] != item_id]
        self.save()

    def set_off_timer(self, minutes: float | None, src: str = "countdown") -> None:
        if minutes:
            self.data.update(off_timer=time.time() + minutes * 60, off_src=src)
        else:
            self.data["off_timer"] = None
            self.data.pop("off_src", None)
        self.save()

    def snooze(self, minutes: float | None) -> None:
        """keep it warm N more minutes after the planned switch-off, or (None) on until stopped"""
        src = self.data.get("off_src", "countdown")
        if not minutes:
            self.set_off_timer(None)
            self._record({"action": "keep on", "src": src}, "until stopped")
        else:
            self.data.update(off_timer=max(self.data.get("off_timer") or time.time(), time.time()) + minutes * 60, off_src=src)
            self.save()
            self._record({"action": "snooze", "src": src}, f"+{int(minutes)} min")

    def set_max_run(self, hours: float) -> None:
        self.data["max_run_hours"] = max(0.0, float(hours))
        self.save()

    def snapshot(self) -> dict:
        return {**self.data, "next": self.next_events(6), "history": self.history[-15:], "warn_s": WARN_S}

    # ---- event computation ------------------------------------------------
    def _events_between(self, a: float, b: float) -> list[tuple[datetime, dict]]:
        start, end = datetime.fromtimestamp(a), datetime.fromtimestamp(b)
        ev = []
        day = start.date()
        while day <= end.date():
            for r in self.data["alarms"]:
                if not r.get("enabled", True):
                    continue
                if r.get("days") and day.weekday() not in r["days"]:
                    continue
                if not r.get("days") and r.get("date") != day.isoformat():
                    continue
                t = datetime.combine(day, datetime.strptime(r["at"], "%H:%M").time())
                ev.append((t, {"action": "on", "mode": r.get("mode", "keep"), "value": r.get("value"),
                               "src": f"timer {r['at']}", "alarm_id": r["id"], "dur": r.get("dur", 0),
                               "once": not r.get("days")}))
            day += timedelta(days=1)
        ot = self.data.get("off_timer")
        if ot:
            ev.append((datetime.fromtimestamp(ot), {"action": "off", "src": self.data.get("off_src", "countdown"), "off_timer": True}))
        return sorted([(t, e) for t, e in ev if start < t <= end], key=lambda x: x[0])

    def next_events(self, n: int) -> list[dict]:
        now = time.time()
        ev = self._events_between(now, now + 8 * 86400)
        return [{"at": t.isoformat(timespec="minutes"), **{k: v for k, v in e.items() if k in ("action", "mode", "value", "src", "dur")}}
                for t, e in ev[:n]]

    # ---- execution --------------------------------------------------------
    async def _execute(self, e: dict) -> bool:
        h = self.heater
        if e["action"] == "off":
            return await h.power(False)
        ok = await h.power(True)
        if ok and e.get("mode") == "temp" and e.get("value"):
            ok = await h.set_temperature(int(e["value"]))
        elif ok and e.get("mode") == "level" and e.get("value"):
            ok = await h.set_level(int(e["value"]))
        return ok

    def _record(self, e: dict, result: str) -> None:
        self.history.append({"t": datetime.now().isoformat(timespec="seconds"), "action": e["action"],
                             "src": e.get("src"), "result": result})
        log.info("%s %s -> %s", e.get("src"), e["action"], result)

    async def run(self) -> None:
        while True:
            try:
                await self._tick()
            except Exception:  # noqa: BLE001
                log.exception("scheduler tick failed")
            await asyncio.sleep(TICK_S)

    async def _tick(self) -> None:
        now = time.time()
        for t, e in self._events_between(self._last, now):
            if e.get("once"):
                self.update(e["alarm_id"], {"enabled": False})
            if e.get("off_timer"):
                self.set_off_timer(None)
            if now - t.timestamp() > STALE_S and not e.get("off_timer"):
                self._record(e, "missed (server was not running)")
                continue
            e["deadline"] = now + RETRY_WINDOW_S
            e["at"] = t.timestamp()
            self._pending.append(e)
        self._last = now

        # safety cap on continuous running
        st = self.heater.state
        running = bool(st.get("running_state"))
        self._on_since = (self._on_since or now) if running else None
        cap = self.data.get("max_run_hours") or 0
        if cap and self._on_since and now - self._on_since > cap * 3600:
            self._pending.append({"action": "off", "src": f"safety cap {cap}h", "deadline": now + RETRY_WINDOW_S})
            self._on_since = now  # avoid re-queueing every tick

        # only the latest pending event matters (an 'off' after an 'on' wins)
        if len(self._pending) > 1:
            for e in self._pending[:-1]:
                self._record(e, "superseded")
            self._pending = self._pending[-1:]
        for e in list(self._pending):
            if await self._execute(e):
                self._record(e, "ok")
                self._pending.remove(e)
                if e["action"] == "on":  # the run time becomes the switch-off countdown
                    if e.get("dur"):
                        self.data.update(off_timer=e["at"] + e["dur"] * 60, off_src=e["src"])
                    else:
                        self.data["off_timer"] = None
                        self.data.pop("off_src", None)
                    self.save()
            elif now > e["deadline"]:
                self._record(e, "FAILED (heater unreachable)")
                self._pending.remove(e)
