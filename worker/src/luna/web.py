"""Token endpoint + web UI for the assistant.

POST /api/token follows LiveKit's standard token-endpoint format, so the official web / Android /
iOS / Flutter starters work against it unchanged (TokenSource.endpoint / TokenSource.fromEndpoint).

Security model: this endpoint hands out the ability to talk to an assistant that controls a home, so
- every request needs a per-device access key (LUNA_ACCESS_KEYS="tablet:key1,phone:key2": revoke one
  device by deleting its entry);
- the SERVER decides the agent and room: any room_config a client sends is ignored, so a client
  cannot dispatch other agents or change room settings;
- tokens are short-lived and can only join their own fresh room.
Serve it over HTTPS: browsers only allow microphone access on https:// or localhost."""
import argparse
import asyncio
import functools
import hmac
import logging
import os
import pathlib
import re
import secrets
import struct
import sys
import time
import zlib

from aiohttp import web
from livekit import api

from .config import load_config
from .ha import Audit
from . import reminders
from .ui import ATTR_UI, parse_catalogs
from .opening import ATTR_DEVICE, ATTR_SAY, ATTR_TRIGGER, ATTR_WAKE, ID, clean_say

log = logging.getLogger("luna.web")
# The browser UI lives in <repo>/web. LUNA_WEB_DIR points elsewhere (the container image and the NixOS module set it).
WEB_DIR = pathlib.Path(os.environ.get("LUNA_WEB_DIR") or pathlib.Path(__file__).resolve().parents[3] / "web")
TOKEN_TTL_SECONDS = 600


PNG_SIGNATURE = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])


@functools.lru_cache(maxsize=4)
def make_icon(size: int) -> bytes:
    """A plain PNG app icon (light-blue disc on dark blue), generated so no binary asset is committed."""
    bg, fg, r = (18, 38, 58), (126, 200, 227), size * 0.38
    rows = []
    for y in range(size):
        row = bytearray([0])
        for x in range(size):
            inside = (x - size / 2 + .5) ** 2 + (y - size / 2 + .5) ** 2 <= r * r
            row += bytes(fg if inside else bg)
        rows.append(bytes(row))
    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    return (PNG_SIGNATURE + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(b"".join(rows))) + chunk(b"IEND", b""))


MANIFEST = {"name": "Luna", "short_name": "Luna", "start_url": "/", "display": "standalone",
            "background_color": "#12263a", "theme_color": "#1d3557",
            "icons": [{"src": f"/icon-{n}.png", "sizes": f"{n}x{n}", "type": "image/png", "purpose": "any maskable"} for n in (192, 512)]}


def client_attributes(raw) -> dict[str, str]:
    """The only participant attributes a client may set (LiveKit's token request field `participant_attributes`)."""
    out: dict[str, str] = {}
    if not isinstance(raw, dict):
        return out
    for key in (ATTR_WAKE, ATTR_TRIGGER):
        v = raw.get(key)
        if isinstance(v, str) and ID.fullmatch(v):
            out[key] = v
    catalogs = parse_catalogs(raw.get(ATTR_UI))
    if catalogs:
        out[ATTR_UI] = ",".join(catalogs)
    say = clean_say(raw.get(ATTR_SAY))
    if say:
        out[ATTR_SAY] = say
    return out


def parse_keys(spec: str) -> dict[str, str]:
    """'tablet:abc,phone:def' -> {'abc': 'tablet', 'def': 'phone'} (key -> device name)."""
    out = {}
    for item in filter(None, (x.strip() for x in spec.split(","))):
        name, _, key = item.partition(":")
        if not key or len(key) < 16:
            raise ValueError(f"access key for '{name}' must be at least 16 characters")
        out[key] = name
    return out


class TokenServer:
    def __init__(self, agent_name: str, keys: dict[str, str], lk_key: str, lk_secret: str, public_url: str,
                 store: "reminders.Store | None" = None, deliver=None, audit=None):
        self.agent_name, self.keys = agent_name, keys
        self.store, self.deliver, self.audit = store, deliver, audit      # timers and reminders (reminders.py); optional
        self.lk_key, self.lk_secret, self.public_url = lk_key, lk_secret, public_url
        self._fails: dict[str, list[float]] = {}

    def _device(self, request: web.Request) -> str | None:
        supplied = request.headers.get("Authorization", "").removeprefix("Bearer ").strip()
        found = None
        for key, name in self.keys.items():  # constant-time compare against every key
            if hmac.compare_digest(supplied.encode(), key.encode()):
                found = name
        return found

    def _throttled(self, ip: str) -> bool:
        now = time.monotonic()
        recent = [t for t in self._fails.get(ip, []) if now - t < 60]
        self._fails[ip] = recent
        return len(recent) >= 10

    async def token(self, request: web.Request) -> web.Response:
        ip = request.remote or "?"
        if self._throttled(ip):
            return web.json_response({"error": "too many attempts"}, status=429)
        device = self._device(request)
        if not device:
            self._fails.setdefault(ip, []).append(time.monotonic())
            log.warning("rejected token request from %s", ip)
            return web.json_response({"error": "unauthorized"}, status=401)
        try:
            body = await request.json()
        except Exception:
            body = {}
        body = body if isinstance(body, dict) else {}
        control = body.get("purpose") == "control"
        # Control room: a standing, per-device room where a listener waits for calls (LiveKit data messages).
        # It has no agent and can neither publish nor subscribe to audio. Conversation rooms are fresh per session.
        room = f"luna-control-{device}" if control else f"luna-{device}-{secrets.token_hex(4)}"
        attrs = {**client_attributes(body.get("participant_attributes")), ATTR_DEVICE: device}   # device is set last: not spoofable
        builder = (api.AccessToken(self.lk_key, self.lk_secret)
                   .with_identity(f"{device}-{'control' if control else secrets.token_hex(3)}")
                   .with_name(str(body.get("participant_name") or device)[:40])
                   .with_attributes(attrs)
                   .with_ttl(__import__("datetime").timedelta(seconds=TOKEN_TTL_SECONDS)))
        if control:
            builder = builder.with_grants(api.VideoGrants(room_join=True, room=room, can_publish=False, can_subscribe=False,
                                                          can_publish_data=False))
        else:
            builder = (builder.with_grants(api.VideoGrants(room_join=True, room=room, can_publish=True, can_subscribe=True,
                                                           can_publish_data=False))
                       .with_room_config(api.RoomConfiguration(                # NOT the client's room_config
                           agents=[api.RoomAgentDispatch(agent_name=self.agent_name)])))
        token = builder
        log.info("token issued for device %s, room %s", device, room)
        return web.json_response({"server_url": self.public_url, "participant_token": token.to_jwt()}, status=201)

    async def health(self, request: web.Request) -> web.Response:
        return web.json_response({"ok": True})

    async def index(self, request: web.Request) -> web.FileResponse:
        return web.FileResponse(WEB_DIR / "index.html")

    async def asset(self, request: web.Request) -> web.FileResponse:
        name = request.match_info["name"]
        if name not in ("a2ui.js", "a2ui.css"):
            raise web.HTTPNotFound()
        return web.FileResponse(WEB_DIR / name)

    async def manifest(self, request: web.Request) -> web.Response:
        return web.json_response(MANIFEST, content_type="application/manifest+json")

    async def icon(self, request: web.Request) -> web.Response:
        size = int(request.match_info["size"])
        if size not in (192, 512):
            raise web.HTTPNotFound()
        return web.Response(body=make_icon(size), content_type="image/png")

    def app(self) -> web.Application:
        app = web.Application()
        app.router.add_get("/manifest.webmanifest", self.manifest)
        app.router.add_get("/icon-{size}.png", self.icon)
        app.router.add_post("/api/token", self.token)
        app.router.add_get("/healthz", self.health)
        app.router.add_get("/", self.index)
        app.router.add_get("/{name}", self.asset)
        if self.store is not None:
            reminders.add_routes(app, self.store, self.lk_secret)
            if self.deliver is not None:
                async def scheduler(app):      # fires due reminders for as long as the service runs
                    task = asyncio.create_task(reminders.Scheduler(self.store, self.deliver, self.audit).run())
                    yield
                    task.cancel()
                app.cleanup_ctx.append(scheduler)
        if (WEB_DIR / "vendor").is_dir():
            app.router.add_static("/vendor", WEB_DIR / "vendor")
        return app


def build() -> TokenServer:
    cfg = load_config(os.environ.get("LUNA_CONFIG", "config.yaml"))
    keys = parse_keys(os.environ.get("LUNA_ACCESS_KEYS", ""))
    if not keys:
        raise SystemExit("LUNA_ACCESS_KEYS is empty: set e.g. LUNA_ACCESS_KEYS='tablet:<16+ random chars>'")
    url = os.environ["LIVEKIT_URL"]
    key, secret = os.environ["LIVEKIT_API_KEY"], os.environ["LIVEKIT_API_SECRET"]
    state = pathlib.Path(os.environ.get("LUNA_STATE_DIR", "state"))
    return TokenServer(cfg["agent"]["name"], keys, key, secret, os.environ.get("LIVEKIT_PUBLIC_URL", url),
                       store=reminders.Store(state / "reminders.json"), deliver=reminders.livekit_deliver(url, key, secret),
                       audit=Audit())


def serve():
    logging.basicConfig(level=logging.INFO)
    web.run_app(build().app(), host=os.environ.get("LUNA_WEB_HOST", "0.0.0.0"), port=int(os.environ.get("LUNA_WEB_PORT", "8080")))


DEVICE_NAME = re.compile(r"[A-Za-z0-9_-]{1,32}")


def new_device(name: str, url: str | None = None) -> tuple[str, str | None]:
    """Make an access key for a device. Returns (the LUNA_ACCESS_KEYS entry, the setup link or None).
    The key rides in the URL *fragment* (#key=...), which browsers never send to any server."""
    if not DEVICE_NAME.fullmatch(name):
        raise ValueError("device name: letters, digits, '-' and '_' only (max 32)")
    key = secrets.token_urlsafe(24)   # 32 URL-safe characters, 192 bits
    return f"{name}:{key}", (f"{url.rstrip('/')}/#key={key}" if url else None)


def add_device(args) -> int:
    try:
        entry, link = new_device(args.name, args.url)
    except ValueError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    print(f"Add this to LUNA_ACCESS_KEYS (comma-separated, then restart luna-web):\n\n    {entry}\n")
    if link:
        import segno   # imported here so serving does not depend on it
        print("Open this link on the device (or scan the QR code); it saves the key in the browser:\n")
        print(f"    {link}\n")
        segno.make(link, error="m").terminal(compact=True)
    else:
        print("Add --url https://luna.example (where the device reaches the web UI) to also get a link and QR code.\n"
              "Otherwise open the page on the device and paste the key (the part after the colon) when asked.")
    print("\nTo revoke this device later, delete its entry and restart luna-web.")
    return 0


def main(argv: list[str] | None = None) -> int | None:
    p = argparse.ArgumentParser(prog="luna-web", description="Token endpoint and web UI for Luna.")
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("serve", help="run the server (default)")
    a = sub.add_parser("add-device", help="generate an access key for a device, with a link and QR code")
    a.add_argument("name", help="device name, e.g. tablet")
    a.add_argument("--url", default=os.environ.get("LUNA_PUBLIC_WEB_URL"),
                   help="where the device opens the web UI (default: $LUNA_PUBLIC_WEB_URL)")
    args = p.parse_args(argv)
    if args.cmd == "add-device":
        return add_device(args)
    serve()


if __name__ == "__main__":
    sys.exit(main())
