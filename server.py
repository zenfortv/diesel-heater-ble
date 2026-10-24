"""Diesel heater web server: BLE link + timers + phone/laptop web app.

Run:  .venv\\Scripts\\python.exe server.py      then open http://<this-pc-ip>:8765
Config: config.json (created on first run).
"""
from __future__ import annotations

import asyncio
import json
import logging
import socket
from pathlib import Path

from aiohttp import WSMsgType, web

from heater import FakeHeater, HeaterLink
from scheduler import Scheduler

ROOT = Path(__file__).parent
CONFIG = ROOT / "config.json"
DEFAULTS = {"address": "", "passkey": 1234, "port": 8765, "simulate": True, "token": "", "poll_seconds": 2,
            "clock_sync_minutes": 60}

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s", datefmt="%H:%M:%S")
log = logging.getLogger("server")


def load_config() -> dict:
    cfg = dict(DEFAULTS)
    if CONFIG.exists():
        cfg.update(json.loads(CONFIG.read_text(encoding="utf-8")))
    else:
        CONFIG.write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    return cfg


@web.middleware
async def auth(request: web.Request, handler):
    token = request.app["cfg"]["token"]
    if token and request.path.startswith(("/api", "/ws")):
        given = request.query.get("token") or request.headers.get("X-Token") or request.cookies.get("token")
        if given != token:
            raise web.HTTPUnauthorized(text="bad token")
    return await handler(request)


def build_app(cfg: dict) -> web.Application:
    app = web.Application(middlewares=[auth])
    app["cfg"] = cfg
    sockets: set[web.WebSocketResponse] = set()
    app["sockets"] = sockets

    def broadcast(state: dict) -> None:
        msg = json.dumps({"type": "state", "state": state})
        for ws in list(sockets):
            asyncio.ensure_future(ws.send_str(msg))

    cls = FakeHeater if cfg["simulate"] or not cfg["address"] else HeaterLink
    heater = cls(cfg["address"] or "SIMULATOR", cfg["passkey"], cfg["poll_seconds"], broadcast,
                 cfg["clock_sync_minutes"])
    sched = Scheduler(heater, ROOT / "schedules.json")
    app["heater"], app["sched"] = heater, sched

    async def on_startup(app):
        heater.start()
        app["sched_task"] = asyncio.create_task(sched.run())

    async def on_cleanup(app):
        app["sched_task"].cancel()
        await heater.stop()

    app.on_startup.append(on_startup)
    app.on_cleanup.append(on_cleanup)

    r = web.RouteTableDef()

    @r.get("/api/state")
    async def state(_):
        return web.json_response({"heater": heater.state, "paused": heater.paused,
                                  "simulated": isinstance(heater, FakeHeater), "schedule": sched.snapshot()})

    @r.post("/api/power")
    async def power(req):
        body = await req.json()
        return web.json_response({"ok": await heater.power(bool(body["on"]))})

    @r.post("/api/level")
    async def level(req):
        return web.json_response({"ok": await heater.set_level((await req.json())["level"])})

    @r.post("/api/temperature")
    async def temperature(req):
        return web.json_response({"ok": await heater.set_temperature((await req.json())["temperature"])})

    @r.post("/api/auto_start_stop")
    async def auto_start_stop(req):
        return web.json_response({"ok": await heater.set_auto_start_stop(bool((await req.json())["on"]))})

    @r.post("/api/builtin_timer")
    async def builtin_timer(req):
        b = await req.json()
        return web.json_response({"ok": await heater.set_builtin_timer(b["start"], b["duration"], bool(b["enabled"]))})

    @r.post("/api/sync_clock")
    async def sync_clock(_):
        return web.json_response({"ok": await heater.sync_clock(force=True)})

    @r.post("/api/pause")
    async def pause(req):
        await heater.set_paused(bool((await req.json())["paused"]))
        return web.json_response({"ok": True})

    @r.get("/api/schedule")
    async def get_schedule(_):
        return web.json_response(sched.snapshot())

    @r.post("/api/schedule/{kind:weekly|once}")
    async def add_item(req):
        return web.json_response(sched.add(req.match_info["kind"], await req.json()))

    @r.patch("/api/schedule/{kind:weekly|once}/{id}")
    async def patch_item(req):
        it = sched.update(req.match_info["kind"], req.match_info["id"], await req.json())
        return web.json_response(it) if it else web.HTTPNotFound()

    @r.delete("/api/schedule/{kind:weekly|once}/{id}")
    async def del_item(req):
        sched.delete(req.match_info["kind"], req.match_info["id"])
        return web.json_response({"ok": True})

    @r.post("/api/off_timer")
    async def off_timer(req):
        sched.set_off_timer((await req.json()).get("minutes"))
        return web.json_response(sched.snapshot())

    @r.post("/api/max_run")
    async def max_run(req):
        sched.set_max_run((await req.json())["hours"])
        return web.json_response(sched.snapshot())

    @r.get("/ws")
    async def ws_handler(req):
        ws = web.WebSocketResponse(heartbeat=20)
        await ws.prepare(req)
        sockets.add(ws)
        await ws.send_str(json.dumps({"type": "state", "state": {**heater.state, "paused": heater.paused}}))
        try:
            async for msg in ws:
                if msg.type == WSMsgType.ERROR:
                    break
        finally:
            sockets.discard(ws)
        return ws

    @r.get("/")
    async def index(_):
        return web.FileResponse(ROOT / "web" / "index.html")

    @r.get("/{name:sw.js|manifest.json|icon.svg}")  # page uses relative links (same file runs on GitHub Pages)
    async def web_file(req):
        return web.FileResponse(ROOT / "web" / req.match_info["name"])

    r.static("/static", ROOT / "web")
    app.add_routes(r)
    return app


def lan_ip() -> str:
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
            s.connect(("10.255.255.255", 1))
            return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"


if __name__ == "__main__":
    cfg = load_config()
    mode = "SIMULATOR" if cfg["simulate"] or not cfg["address"] else f"heater {cfg['address']}"
    log.info("%s - open http://%s:%d on phone or laptop", mode, lan_ip(), cfg["port"])
    web.run_app(build_app(cfg), host="0.0.0.0", port=cfg["port"], print=None)
