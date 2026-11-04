"""Thin Home Assistant REST client plus the allowlist logic the assistant's tools rely on.

Everything the assistant may touch is declared in config.yaml; code refuses anything else."""
import json
import logging
import os
import pathlib
import time

import aiohttp

log = logging.getLogger("luna.ha")


class HAError(Exception):
    pass


class HA:
    def __init__(self, url: str, token: str, dry_run: bool = False):
        self.url = url.rstrip("/")
        self.dry_run = dry_run  # reads work; writes (service calls) are only logged
        self._headers = {"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
        self._session: aiohttp.ClientSession | None = None

    def _s(self) -> aiohttp.ClientSession:
        if self._session is None or self._session.closed:
            self._session = aiohttp.ClientSession(headers=self._headers, timeout=aiohttp.ClientTimeout(total=10))
        return self._session

    async def close(self):
        if self._session and not self._session.closed:
            await self._session.close()

    async def call(self, domain: str, service: str, data: dict | None = None):
        if self.dry_run:
            log.warning("DRY-RUN would call %s.%s %s", domain, service, data)
            return []
        try:
            async with self._s().post(f"{self.url}/api/services/{domain}/{service}", json=data or {}) as r:
                if r.status >= 400:
                    raise HAError(f"{domain}.{service} -> HTTP {r.status}: {(await r.text())[:200]}")
                return await r.json()
        except (aiohttp.ClientError, TimeoutError) as e:
            raise HAError(f"{domain}.{service} failed: {e}") from e

    async def state(self, entity_id: str) -> dict | None:
        try:
            async with self._s().get(f"{self.url}/api/states/{entity_id}") as r:
                if r.status == 404:
                    return None
                if r.status >= 400:
                    raise HAError(f"state {entity_id} -> HTTP {r.status}")
                return await r.json()
        except (aiohttp.ClientError, TimeoutError) as e:
            raise HAError(f"state {entity_id} failed: {e}") from e

    async def services(self) -> set[str]:
        try:
            async with self._s().get(f"{self.url}/api/services") as r:
                r.raise_for_status()
                return {f"{d['domain']}.{s}" for d in await r.json() for s in d["services"]}
        except (aiohttp.ClientError, TimeoutError) as e:
            raise HAError(f"listing services failed: {e}") from e


# action name -> function(entity_id, value) -> (domain, service, data)
ACTIONS = {
    "on": lambda e, v: ("homeassistant", "turn_on", {"entity_id": e}),
    "off": lambda e, v: ("homeassistant", "turn_off", {"entity_id": e}),
    "brightness": lambda e, v: ("light", "turn_on", {"entity_id": e, "brightness_pct": int(v)}),
    "set_temperature": lambda e, v: ("climate", "set_temperature", {"entity_id": e, "temperature": float(v)}),
    "volume": lambda e, v: ("media_player", "volume_set", {"entity_id": e, "volume_level": float(v) / 100}),
    "mute": lambda e, v: ("media_player", "volume_mute", {"entity_id": e, "is_volume_muted": True}),
    "unmute": lambda e, v: ("media_player", "volume_mute", {"entity_id": e, "is_volume_muted": False}),
}
VALUE_LIMITS = {"set_temperature": (16, 30), "brightness": (1, 100), "volume": (0, 100)}
EXPECTED_STATE = {"on": "on", "off": "off"}


class Devices:
    """Name -> entity allowlist from config.yaml `devices:`."""

    def __init__(self, cfg: dict | None):
        self.by_name: dict[str, dict] = {}
        for name, d in (cfg or {}).items():
            # YAML 1.1 reads a bare on/off as True/False; map them back so configs work either way.
            acts = [{True: "on", False: "off"}.get(a, a) for a in d.get("actions", ["on", "off"])]
            entry = {"name": name, "entity": d["entity"], "actions": [str(a) for a in acts]}
            for key in [name, *d.get("aliases", [])]:
                self.by_name[key.strip().lower()] = entry

    def names(self) -> list[str]:
        return sorted({d["name"] for d in self.by_name.values()})

    def entities(self) -> list[str]:
        return sorted({d["entity"] for d in self.by_name.values()})

    def resolve(self, name: str) -> dict:
        d = self.by_name.get(name.strip().lower())
        if not d:
            raise HAError(f"I don't control '{name}'. I can control: {', '.join(self.names())}.")
        return d

    def plan(self, name: str, action: str, value: float | None):
        d = self.resolve(name)
        action = action.strip().lower()
        if action not in d["actions"] or action not in ACTIONS:
            raise HAError(f"'{action}' isn't allowed for {d['name']}. Allowed: {', '.join(d['actions'])}.")
        if action in VALUE_LIMITS:
            if value is None:
                raise HAError(f"'{action}' needs a value.")
            lo, hi = VALUE_LIMITS[action]
            if not lo <= float(value) <= hi:
                raise HAError(f"{action} must be between {lo} and {hi}.")
        return d, action, ACTIONS[action](d["entity"], value)


class Audit:
    """Append-only JSON-lines log of everything the assistant did (for caregivers)."""

    def __init__(self, path: str | None = None):
        self.path = pathlib.Path(path or os.environ.get("LUNA_AUDIT_LOG", "logs/audit.jsonl"))
        self.path.parent.mkdir(parents=True, exist_ok=True)

    def write(self, kind: str, **fields):
        rec = {"t": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "kind": kind, **fields}
        with self.path.open("a", encoding="utf-8") as f:
            f.write(json.dumps(rec, ensure_ascii=False) + "\n")


class Caregivers:
    def __init__(self, ha: HA, cfg: dict | None, audit: Audit, person: str = ""):
        cfg = cfg or {}
        self.ha, self.audit, self.person = ha, audit, person
        self.services = [s.split(".", 1)[1] for s in cfg.get("notify", [])]
        self.urgent_script = cfg.get("urgent_script")
        self.min_interval = float(cfg.get("min_interval_seconds", 120))
        self._last_normal: float | None = None

    async def notify(self, message: str, urgent: bool) -> str:
        if self.ha.dry_run:
            log.warning("DRY-RUN notify urgent=%s: %s", urgent, message)
            self.audit.write("notify_dry_run", urgent=urgent, message=message)
            return "(Dry run: nothing was sent.) Caregivers would have been notified."
        now = time.monotonic()
        if not urgent and self._last_normal is not None and now - self._last_normal < self.min_interval:
            self.audit.write("notify_skipped", message=message, reason="rate-limited")
            return "Caregivers were already told a moment ago, so I did not send another message."
        title = "URGENT" if urgent else "Message"
        if self.person:
            title += f" from {self.person}"
        data = {"title": title, "message": message}
        if urgent:
            data["data"] = {"ttl": 0, "priority": "high", "channel": "alarm_stream",
                            "push": {"interruption-level": "critical"}}
        ok, failed = 0, []
        for svc in self.services:
            try:
                await self.ha.call("notify", svc, data)
                ok += 1
            except HAError as e:
                failed.append(svc)
                log.error("notify %s failed: %s", svc, e)
        if urgent and self.urgent_script:
            try:
                await self.ha.call("script", self.urgent_script.split(".", 1)[1], {})
            except HAError as e:
                log.error("urgent script failed: %s", e)
        if not urgent and ok:
            self._last_normal = now
        self.audit.write("notify", urgent=urgent, message=message, sent=ok, failed=failed)
        if ok and not failed:
            return "Caregivers have been notified."
        if ok:
            return f"Notified {ok} caregiver device(s), but {len(failed)} could not be reached."
        return "I could NOT reach any caregiver. Tell the person plainly that the message did not go through."
