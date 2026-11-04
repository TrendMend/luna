"""TV control: Harmony activities (with the start-up delay handled), cable channels, remembered
favourites, and Jellyfin Live TV channel lookup by name."""
import asyncio
import difflib
import json
import logging
import os
import pathlib
import re
import time

import aiohttp

from .ha import HA, HAError

log = logging.getLogger("luna.tv")


def norm(s: str) -> str:
    return re.sub(r"[^a-z0-9 ]+", "", s.lower()).strip()


class Harmony:
    """Everything goes through one lock: two overlapping requests must not interleave key presses."""

    def __init__(self, ha: HA, cfg: dict):
        self.ha = ha
        self.remote = cfg["remote"]
        self.settle = float(cfg.get("settle_seconds", 12))
        self.timeout = float(cfg.get("activity_timeout", 45))
        self.activities = {norm(k): v for k, v in (cfg.get("activities") or {}).items()}
        self.commands = {norm(k): tuple(v) for k, v in (cfg.get("commands") or {}).items()}
        self.cable = cfg.get("cable") or {}
        self.lock = asyncio.Lock()

    async def current(self) -> str | None:
        st = await self.ha.state(self.remote)
        return (st or {}).get("attributes", {}).get("current_activity")

    async def _ensure(self, label: str) -> bool:
        """Start `label` if it isn't running; wait for HA to report it, then for the hub to settle.
        Returns True if it had to be started."""
        if await self.current() == label:
            return False
        await self.ha.call("remote", "turn_on", {"entity_id": self.remote, "activity": label})
        if self.ha.dry_run:
            return True
        deadline = time.monotonic() + self.timeout
        while time.monotonic() < deadline:
            await asyncio.sleep(1.0)
            if await self.current() == label:
                await asyncio.sleep(self.settle)
                return True
        raise HAError(f"the '{label}' activity did not start within {int(self.timeout)} seconds")

    async def _send(self, device: str, commands: list[str], delay: float):
        await self.ha.call("remote", "send_command",
                           {"entity_id": self.remote, "device": device, "command": commands, "delay_secs": delay})

    def activity_names(self) -> list[str]:
        return sorted(self.activities) + ["off"]

    async def activity(self, spoken: str) -> str:
        key = norm(spoken)
        async with self.lock:
            if key in ("off", "turn off", "power off", "tv off"):
                await self.ha.call("remote", "turn_off", {"entity_id": self.remote})
                return "TV system turned off."
            label = self.activities.get(key)
            if not label:
                raise HAError(f"I don't know '{spoken}'. I can start: {', '.join(self.activity_names())}.")
            started = await self._ensure(label)
            return f"{label} is on." if started else f"{label} was already on."

    async def command(self, spoken: str) -> str:
        dev_cmd = self.commands.get(norm(spoken))
        if not dev_cmd:
            raise HAError(f"I can't do '{spoken}'. I can: {', '.join(sorted(self.commands))}.")
        async with self.lock:
            await self._send(dev_cmd[0], [dev_cmd[1]], 0.0)
        return f"Pressed {spoken}."

    async def cable_channel(self, number: str) -> str:
        if not re.fullmatch(r"\d{1,5}", number):
            raise HAError(f"'{number}' is not a channel number.")
        label = self.activities[norm(self.cable["activity"])]
        async with self.lock:
            started = await self._ensure(label)
            digits = list(number) + ([self.cable["enter"]] if self.cable.get("enter") else [])
            await self._send(self.cable["device"], digits, float(self.cable.get("digit_delay", 0.4)))
        return f"Cable is on channel {number}." + (" (I had to turn the cable activity on first.)" if started else "")


def squash(s: str) -> str:
    """Compare names ignoring spaces, so "sports net east" and "sportsnet east" are the same thing."""
    return norm(s).replace(" ", "")


class Favorites:
    """Cable favourites: config seeds them, the person can add more by voice; saved to disk.

    Several names may point at the same number (aliases)."""

    def __init__(self, seed: dict | None, path: str | None = None):
        self.path = pathlib.Path(path or os.path.join(os.environ.get("LUNA_STATE_DIR", "state"), "favorites.json"))
        self.seed = {norm(k): str(v) for k, v in (seed or {}).items()}
        self.learned: dict[str, str] = {}
        if self.path.exists():
            try:
                self.learned = json.loads(self.path.read_text())
            except (OSError, ValueError):
                log.error("could not read %s; starting empty", self.path)

    def all(self) -> dict[str, str]:
        return {**self.seed, **self.learned}

    def lookup(self, spoken: str) -> tuple[str | None, list[str]]:
        """-> (number, []) on a clear match; (None, names) if the request fits several channels;
        (None, []) if unknown."""
        key = norm(spoken)
        if re.fullmatch(r"\d{1,5}", key):
            return key, []
        favs = self.all()
        by_squash = {squash(n): (n, num) for n, num in favs.items()}
        sk = squash(spoken)
        if sk in by_squash:
            return by_squash[sk][1], []
        part = [(n, num) for q, (n, num) in by_squash.items() if sk and sk in q]
        if len({num for _, num in part}) == 1:
            return part[0][1], []
        if part:                                   # e.g. just "sportsnet": ask which one
            return None, sorted({n for n, _ in part})
        close = difflib.get_close_matches(sk, list(by_squash), n=1, cutoff=0.8)
        return (by_squash[close[0]][1], []) if close else (None, [])

    def remember(self, name: str, number: str):
        if not re.fullmatch(r"\d{1,5}", number) or not norm(name):
            raise HAError("I need a name and a channel number made of digits.")
        self.learned[norm(name)] = number
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.path.write_text(json.dumps(self.learned, indent=2))


class LiveTV:
    """Jellyfin Live TV channels: pinned names from config plus a name lookup against Jellyfin."""

    def __init__(self, ha: HA, cfg: dict, jf_url: str | None = None, jf_token: str | None = None):
        self.ha = ha
        self.script = cfg["script"].split(".", 1)[1]
        self.pinned = {norm(k): str(v) for k, v in (cfg.get("channels") or {}).items()}
        self.jf_url = (jf_url or "").rstrip("/")
        self.jf_token = jf_token
        self._cache: tuple[float, list[dict]] | None = None

    async def channels(self) -> list[dict]:
        if not self.jf_url or not self.jf_token:
            return []
        if self._cache and time.monotonic() - self._cache[0] < 600:
            return self._cache[1]
        try:
            async with aiohttp.ClientSession(timeout=aiohttp.ClientTimeout(total=10)) as s:
                async with s.get(f"{self.jf_url}/LiveTv/Channels", headers={"X-Emby-Token": self.jf_token}) as r:
                    r.raise_for_status()
                    items = (await r.json()).get("Items", [])
        except (aiohttp.ClientError, TimeoutError) as e:
            log.error("jellyfin channel list failed: %s", e)
            return self._cache[1] if self._cache else []
        chans = [{"name": i["Name"], "number": str(i.get("Number") or i.get("ChannelNumber") or "")} for i in items]
        self._cache = (time.monotonic(), chans)
        return chans

    async def resolve(self, spoken: str) -> tuple[str | None, list[str]]:
        """-> (channel number, []) on a clear match; (None, candidates) if ambiguous or unknown."""
        key = norm(spoken)
        if key in self.pinned:
            return self.pinned[key], []
        if re.fullmatch(r"\d{1,5}", key):
            return key, []
        chans = await self.channels()
        names = {norm(c["name"]): c for c in chans}
        if key in names and names[key]["number"]:
            return names[key]["number"], []
        sub = [n for n in names if key and (key in n or n in key)]
        if len(sub) == 1 and names[sub[0]]["number"]:
            return names[sub[0]]["number"], []
        if len(sub) > 1:
            return None, [names[n]["name"] for n in sub[:5]]
        close = difflib.get_close_matches(key, list(names), n=3, cutoff=0.7)
        if len(close) == 1 or (close and difflib.SequenceMatcher(None, key, close[0]).ratio() >= 0.9):
            return names[close[0]]["number"], []
        return None, [names[n]["name"] for n in close]

    async def play(self, number: str) -> str:
        # The HA script starts "Watch a Movie", waits for the NUC's Jellyfin client, then tunes.
        # script.turn_on returns at once; calling script.<name> directly would block ~30 s.
        await self.ha.call("script", "turn_on",
                           {"entity_id": f"script.{self.script}", "variables": {"channel_id": number}})
        return f"Starting Live TV channel {number}. It takes about half a minute."
