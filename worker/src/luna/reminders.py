"""Timers and reminders (docs/protocol.md, "Reminders").

A conversation lasts seconds, but a timer has to fire minutes or hours later, so reminders live in the long-running token
service (`luna.web`), not in the conversation:

  worker tools --HTTP (internal, HMAC-authenticated)--> token service: Store (a JSON file) + Scheduler
  Scheduler, when one is due --LiveKit data message `luna.call` {"say": ...}--> the device's control room
  device (the listener, later the Android app) starts a conversation that opens by saying it.

So a device only gets reminders if it keeps a control-room connection. If it is not connected the Scheduler keeps trying
until `GRACE` seconds after the due time, then drops the reminder and says so in the audit log."""
import asyncio
import hashlib
import hmac
import json
import logging
import os
import pathlib
import re
import secrets
import time
from datetime import datetime, timedelta, tzinfo
from dataclasses import asdict, dataclass

import aiohttp
from aiohttp import web

from .opening import clean_say

log = logging.getLogger("luna.reminders")
GRACE = 600                    # seconds after the due time during which delivery is retried
MAX_PENDING = 20               # per device
MAX_AHEAD = 366 * 86400
DEVICE = re.compile(r"[A-Za-z0-9_-]{1,32}")


def internal_token(secret: str) -> str:
    """What the worker presents to the token service. Derived from the LiveKit secret both already have, so there is no
    extra thing to configure, and it is useless for anything else (it is a different HMAC than a LiveKit token)."""
    return hmac.new(secret.encode(), b"luna-internal-reminders", hashlib.sha256).hexdigest()


@dataclass
class Reminder:
    id: str
    device: str
    due: float                 # unix time
    say: str                   # spoken word for word when it fires
    kind: str                  # "timer" | "reminder"
    label: str = ""


class Store:
    """All pending reminders in one JSON file, rewritten atomically. One process (the token service) writes it."""

    def __init__(self, path):
        self.path = pathlib.Path(path)
        self.items: dict[str, Reminder] = {}
        if self.path.exists():
            try:
                self.items = {r["id"]: Reminder(**r) for r in json.loads(self.path.read_text(encoding="utf-8"))}
            except (OSError, ValueError, TypeError, KeyError) as e:
                log.error("could not read %s (%s); starting empty", self.path, e)

    def _save(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps([asdict(r) for r in self.items.values()], ensure_ascii=False), encoding="utf-8")
        os.replace(tmp, self.path)

    def add(self, device: str, due: float, say: str, kind: str, label: str = "", now: float | None = None) -> Reminder:
        now = time.time() if now is None else now
        if not DEVICE.fullmatch(device or ""):
            raise ValueError("bad device")
        if kind not in ("timer", "reminder"):
            raise ValueError("bad kind")
        if not (now - 5 <= due <= now + MAX_AHEAD):
            raise ValueError("that time is in the past or more than a year away")
        text = clean_say(say)
        if not text:
            raise ValueError("nothing to say")
        if len([r for r in self.items.values() if r.device == device]) >= MAX_PENDING:
            raise ValueError(f"at most {MAX_PENDING} reminders at once")
        r = Reminder(secrets.token_hex(3), device, float(due), text, kind, (clean_say(label) or "")[:60])
        self.items[r.id] = r
        self._save()
        return r

    def for_device(self, device: str) -> list[Reminder]:
        return sorted((r for r in self.items.values() if r.device == device), key=lambda r: r.due)

    def remove(self, rid: str, device: str | None = None) -> bool:
        r = self.items.get(rid)
        if not r or (device is not None and r.device != device):
            return False
        del self.items[rid]
        self._save()
        return True

    def due(self, now: float) -> list[Reminder]:
        return sorted((r for r in self.items.values() if r.due <= now), key=lambda r: r.due)


class Scheduler:
    """Fires due reminders. `deliver(device, say) -> bool` says whether the device was reached."""

    def __init__(self, store: Store, deliver, audit=None, clock=time.time, interval: float = 5.0):
        self.store, self.deliver, self.audit, self.clock, self.interval = store, deliver, audit, clock, interval

    def _audit(self, event, **fields):
        if self.audit:
            self.audit.write(event, **fields)

    async def tick(self):
        now = self.clock()
        for r in self.store.due(now):
            try:
                ok = await self.deliver(r.device, r.say)
            except Exception as e:                      # one bad delivery must not stop the others, or the loop
                log.warning("delivering %s failed: %s", r.id, e)
                ok = False
            if ok:
                self.store.remove(r.id)
                self._audit("reminder_fired", id=r.id, device=r.device, reminder_type=r.kind, late=round(now - r.due))
            elif now - r.due > GRACE:
                self.store.remove(r.id)
                self._audit("reminder_missed", id=r.id, device=r.device, reminder_type=r.kind, say=r.say)
                log.error("reminder %s for %s could not be delivered; dropped", r.id, r.device)

    async def run(self):
        while True:
            try:
                await self.tick()
            except Exception:
                log.exception("scheduler tick")
            await asyncio.sleep(self.interval)


# ---- the token service's side: internal HTTP routes ---------------------------------------------
def add_routes(app: web.Application, store: Store, secret: str):
    token = internal_token(secret)

    def authed(request: web.Request):
        got = request.headers.get("Authorization", "")
        if not hmac.compare_digest(got.encode(), f"Bearer {token}".encode()):
            raise web.HTTPUnauthorized()

    def view(r: Reminder) -> dict:
        return asdict(r)

    async def add(request: web.Request):
        authed(request)
        try:
            b = await request.json()
            r = store.add(b["device"], float(b["due"]), b["say"], b["kind"], b.get("label", ""))
        except (ValueError, KeyError, TypeError) as e:
            return web.json_response({"error": str(e)}, status=400)
        return web.json_response(view(r))

    async def listing(request: web.Request):
        authed(request)
        return web.json_response([view(r) for r in store.for_device(request.query.get("device", ""))])

    async def cancel(request: web.Request):
        authed(request)
        ok = store.remove(request.match_info["id"], request.query.get("device"))
        return web.json_response({"ok": ok}, status=200 if ok else 404)

    app.router.add_post("/api/internal/reminders", add)
    app.router.add_get("/api/internal/reminders", listing)
    app.router.add_delete("/api/internal/reminders/{id}", cancel)


def livekit_deliver(url: str, key: str, secret: str):
    """A `deliver` function that sends `luna.call` into the device's control room, if anybody is listening there."""
    async def deliver(device: str, say: str) -> bool:
        from livekit import api
        room = f"luna-control-{device}"
        async with api.LiveKitAPI(url, key, secret) as lk:
            try:
                present = await lk.room.list_participants(api.ListParticipantsRequest(room=room))
            except Exception:
                return False
            if not present.participants:
                return False
            await lk.room.send_data(api.SendDataRequest(
                room=room, data=json.dumps({"say": say}).encode(), kind=api.DataPacket.Kind.RELIABLE, topic="luna.call"))
            return True
    return deliver


# ---- the worker's side: a small client ----------------------------------------------------------
class RemindersError(Exception):
    pass


class Client:
    def __init__(self, base_url: str, secret: str):
        self.base, self.headers = base_url.rstrip("/"), {"Authorization": f"Bearer {internal_token(secret)}"}

    async def _call(self, method: str, path: str, **kw):
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.request(method, self.base + path, headers=self.headers, **kw) as r:
                    if r.status == 401:
                        raise RemindersError("the reminder service did not accept our key")
                    if r.status == 404:
                        raise RemindersError("there is no such reminder")
                    if r.status == 400:
                        raise RemindersError((await r.json()).get("error", "not accepted"))
                    r.raise_for_status()
                    return await r.json()
        except (aiohttp.ClientError, TimeoutError, ValueError) as e:
            raise RemindersError(f"the reminder service did not answer ({e})") from e

    async def add(self, device: str, due: float, say: str, kind: str, label: str = "") -> dict:
        return await self._call("POST", "/api/internal/reminders", json={"device": device, "due": due, "say": say, "kind": kind, "label": label})

    async def list(self, device: str) -> list[dict]:
        return await self._call("GET", "/api/internal/reminders", params={"device": device})

    async def cancel(self, device: str, rid: str) -> None:
        await self._call("DELETE", f"/api/internal/reminders/{rid}", params={"device": device})


# ---- time helpers (used by the worker's tools) --------------------------------------------------
def get_tz(name: str | None) -> tzinfo:
    """`timezone:` from config.yaml (e.g. America/Edmonton); the machine's own zone if unset or not available."""
    if name:
        try:
            from zoneinfo import ZoneInfo
            return ZoneInfo(name)
        except Exception:
            log.warning("time zone '%s' is not available (is the tzdata package installed?); using the system zone", name)
    return datetime.now().astimezone().tzinfo


def parse_local(when: str, tz: tzinfo, now: datetime) -> datetime:
    """'2026-11-06 15:30' or '15:30' (today, or tomorrow if that has passed) in the person's time zone."""
    w = (when or "").strip().replace("T", " ")
    for fmt in ("%Y-%m-%d %H:%M", "%Y-%m-%d %H:%M:%S"):
        try:
            return datetime.strptime(w, fmt).replace(tzinfo=tz)
        except ValueError:
            pass
    try:
        t = datetime.strptime(w, "%H:%M")
    except ValueError:
        raise ValueError("give the time as 'YYYY-MM-DD HH:MM' (24-hour clock), or just 'HH:MM'")
    at = now.replace(hour=t.hour, minute=t.minute, second=0, microsecond=0)
    return at if at > now else at + timedelta(days=1)


def spoken_when(due: datetime, now: datetime) -> str:
    """'in 9 minutes', 'at 3:30 PM', 'tomorrow at 3:30 PM', 'on Friday at 3:30 PM'."""
    secs = (due - now).total_seconds()
    if secs < 90:
        return "in a minute" if secs >= 30 else "in a few seconds"
    if secs < 3600:
        return f"in {round(secs / 60)} minutes"
    clock = due.strftime("%I:%M %p").lstrip("0")
    days = (due.date() - now.date()).days
    if days == 0:
        return f"at {clock}"
    if days == 1:
        return f"tomorrow at {clock}"
    if days < 7:
        return f"on {due.strftime('%A')} at {clock}"
    return f"on {due.strftime('%B')} {due.day} at {clock}"
