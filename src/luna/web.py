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
import functools
import hmac
import logging
import os
import pathlib
import secrets
import struct
import time
import zlib

from aiohttp import web
from livekit import api

from .config import load_config

log = logging.getLogger("luna.web")
WEB_DIR = pathlib.Path(__file__).resolve().parent / "webui"   # shipped inside the package
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
    def __init__(self, agent_name: str, keys: dict[str, str], lk_key: str, lk_secret: str, public_url: str):
        self.agent_name, self.keys = agent_name, keys
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
        room = f"luna-{device}-{secrets.token_hex(4)}"            # server-chosen, fresh per session
        token = (api.AccessToken(self.lk_key, self.lk_secret)
                 .with_identity(f"{device}-{secrets.token_hex(3)}")
                 .with_name(str(body.get("participant_name") or device)[:40])
                 .with_ttl(__import__("datetime").timedelta(seconds=TOKEN_TTL_SECONDS))
                 .with_grants(api.VideoGrants(room_join=True, room=room, can_publish=True, can_subscribe=True,
                                              can_publish_data=False))
                 .with_room_config(api.RoomConfiguration(                # NOT the client's room_config
                     agents=[api.RoomAgentDispatch(agent_name=self.agent_name)])))
        log.info("token issued for device %s, room %s", device, room)
        return web.json_response({"server_url": self.public_url, "participant_token": token.to_jwt()}, status=201)

    async def health(self, request: web.Request) -> web.Response:
        return web.json_response({"ok": True})

    async def index(self, request: web.Request) -> web.FileResponse:
        return web.FileResponse(WEB_DIR / "index.html")

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
        if (WEB_DIR / "vendor").is_dir():
            app.router.add_static("/vendor", WEB_DIR / "vendor")
        return app


def build() -> TokenServer:
    cfg = load_config(os.environ.get("LUNA_CONFIG", "config.yaml"))
    keys = parse_keys(os.environ.get("LUNA_ACCESS_KEYS", ""))
    if not keys:
        raise SystemExit("LUNA_ACCESS_KEYS is empty: set e.g. LUNA_ACCESS_KEYS='tablet:<16+ random chars>'")
    url = os.environ["LIVEKIT_URL"]
    return TokenServer(cfg["agent"]["name"], keys, os.environ["LIVEKIT_API_KEY"], os.environ["LIVEKIT_API_SECRET"],
                       os.environ.get("LIVEKIT_PUBLIC_URL", url))


def main():
    logging.basicConfig(level=logging.INFO)
    web.run_app(build().app(), host=os.environ.get("LUNA_WEB_HOST", "0.0.0.0"), port=int(os.environ.get("LUNA_WEB_PORT", "8080")))


if __name__ == "__main__":
    main()
