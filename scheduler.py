"""On/off timers the AirHeaterBLE app does not have.

Three kinds, persisted in schedules.json:
  weekly   - {"days":[0..6 Mon=0], "on":"07:00", "off":"08:30", "mode":"temp|level|keep", "value":21}
             off earlier than on = runs past midnight
  once     - {"at":"2026-10-24T06:30", "action":"on|off", "mode":..., "value":...}
  off_timer- epoch seconds: switch off at that moment (countdown "off in N minutes")
Plus a safety cap: max_run_hours (0 = disabled) switches off a heater left on too long.

Events are edge-triggered: the heater is commanded only when an event time passes,
so manual changes in between are respected. A failed command is retried for 10 minutes.
"""
from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any

from heater import HeaterLink

log = logging.getLogger("scheduler")
RETRY_WINDOW_S = 600
TICK_S = 5


class Scheduler:
    def __init__(self, heater: HeaterLink, path: Path) -> None:
        self.heater = heater
        self.path = path
        self.data: dict[str, Any] = {"weekly": [], "once": [], "off_timer": None, "max_run_hours": 0}
        self.history: list[dict] = []
        self._pending: list[dict] = []
        self._last = time.time()
        self._on_since: float | None = None
        if path.exists():
            self.data.update(json.loads(path.read_text(encoding="utf-8")))

    def save(self) -> None:
        self.path.write_text(json.dumps(self.data, indent=2), encoding="utf-8")

    # ---- CRUD -------------------------------------------------------------
    def add(self, kind: str, item: dict) -> dict:
        item = {**item, "id": uuid.uuid4().hex[:8]}
        item.setdefault("enabled", True)
        self.data[kind].append(item)
        self.save()
        return item

    def update(self, kind: str, item_id: str, patch: dict) -> dict | None:
        for it in self.data[kind]:
            if it["id"] == item_id:
                it.update({k: v for k, v in patch.items() if k != "id"})
                self.save()
                return it
        return None

    def delete(self, kind: str, item_id: str) -> None:
        self.data[kind] = [it for it in self.data[kind] if it["id"] != item_id]
        self.save()

    def set_off_timer(self, minutes: float | None) -> None:
        self.data["off_timer"] = time.time() + minutes * 60 if minutes else None
        self.save()

    def set_max_run(self, hours: float) -> None:
        self.data["max_run_hours"] = max(0.0, float(hours))
        self.save()

    def snapshot(self) -> dict:
        return {**self.data, "next": self.next_events(6), "history": self.history[-15:]}

    # ---- event computation ------------------------------------------------
    def _weekly_events(self, start: datetime, end: datetime) -> list[tuple[datetime, dict]]:
        out = []
        day = (start - timedelta(days=1)).date()
        while day <= end.date():
            for r in self.data["weekly"]:
                if not r.get("enabled", True) or day.weekday() not in r.get("days", []):
                    continue
                on = datetime.combine(day, datetime.strptime(r["on"], "%H:%M").time())
                off = datetime.combine(day, datetime.strptime(r["off"], "%H:%M").time())
                if off <= on:
                    off += timedelta(days=1)
                label = f"weekly {r['on']}-{r['off']}"
                out.append((on, {"action": "on", "mode": r.get("mode", "keep"), "value": r.get("value"), "src": label}))
                out.append((off, {"action": "off", "src": label}))
            day += timedelta(days=1)
        return [(t, e) for t, e in out if start < t <= end]

    def _events_between(self, a: float, b: float) -> list[tuple[datetime, dict]]:
        start, end = datetime.fromtimestamp(a), datetime.fromtimestamp(b)
        ev = self._weekly_events(start, end)
        for o in self.data["once"]:
            t = datetime.fromisoformat(o["at"])
            if o.get("enabled", True) and start < t <= end:
                ev.append((t, {"action": o["action"], "mode": o.get("mode", "keep"), "value": o.get("value"),
                               "src": f"once {o['at']}", "once_id": o["id"]}))
        ot = self.data.get("off_timer")
        if ot and a < ot <= b:
            ev.append((datetime.fromtimestamp(ot), {"action": "off", "src": "countdown"}))
        return sorted(ev, key=lambda x: x[0])

    def next_events(self, n: int) -> list[dict]:
        now = time.time()
        ev = self._events_between(now, now + 8 * 86400)
        return [{"at": t.isoformat(timespec="minutes"), **{k: v for k, v in e.items() if k != "once_id"}}
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
        for _t, e in self._events_between(self._last, now):
            e["deadline"] = now + RETRY_WINDOW_S
            self._pending.append(e)
            if e.get("once_id"):
                self.update("once", e["once_id"], {"enabled": False})
            if e["src"] == "countdown":
                self.data["off_timer"] = None
                self.save()
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
            elif now > e["deadline"]:
                self._record(e, "FAILED (heater unreachable)")
                self._pending.remove(e)
