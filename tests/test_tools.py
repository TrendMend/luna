import asyncio
import json

import pytest
import yaml
from aiohttp import web
from aiohttp.test_utils import TestServer

from luna.agent import check_config
from luna.ha import HA, Audit
from luna.tools import Luna

CFG = yaml.safe_load(open("config.yaml"))
CFG["caregivers"]["min_interval_seconds"] = 60


class FakeHA:
    """Minimal Home Assistant: records service calls; 'switch.light_switch' obeys on/off unless stuck."""

    def __init__(self):
        self.calls = []
        self.states = {"switch.light_switch": "off", "climate.air_conditioner": "off", "remote.bedroom_tv": "on"}
        self.stuck = False
        self.fail_notify = set()

    def app(self):
        app = web.Application()
        app.router.add_post("/api/services/{d}/{s}", self.service)
        app.router.add_get("/api/states/{e}", self.state)
        app.router.add_get("/api/services", self.list_services)
        return app

    async def service(self, req):
        d, s = req.match_info["d"], req.match_info["s"]
        body = await req.json()
        self.calls.append((d, s, body))
        if d == "notify" and s in self.fail_notify:
            return web.Response(status=500, text="boom")
        ent = body.get("entity_id")
        if not self.stuck and ent in self.states and (d, s) in (("homeassistant", "turn_on"), ("homeassistant", "turn_off")):
            self.states[ent] = "on" if s == "turn_on" else "off"
        return web.json_response([])

    async def state(self, req):
        e = req.match_info["e"]
        if e not in self.states:
            return web.Response(status=404)
        return web.json_response({"entity_id": e, "state": self.states[e]})

    async def list_services(self, req):
        return web.json_response([{"domain": "notify", "services": {"mobile_app_pixel_10_pro": {}, "mobile_app_ron_s_phone": {}}}])


@pytest.fixture
def env(tmp_path, monkeypatch):
    fake = FakeHA()

    async def mk():
        srv = TestServer(fake.app())
        await srv.start_server()
        return srv

    loop = asyncio.new_event_loop()
    srv = loop.run_until_complete(mk())
    ha = HA(str(srv.make_url("")), "tok")
    luna = Luna(CFG, ha, Audit(str(tmp_path / "audit.jsonl")))
    real_sleep = asyncio.sleep
    monkeypatch.setattr(asyncio, "sleep", lambda *_: real_sleep(0))  # no waiting in tests
    yield fake, luna, loop, tmp_path
    loop.run_until_complete(ha.close())
    loop.run_until_complete(srv.close())
    loop.close()


def run(loop, tool, *a, **k):
    """`tool` is the bound FunctionTool; the first argument is the (unused) RunContext."""
    return loop.run_until_complete(tool(None, *a, **k))


def audit(tmp):
    return [json.loads(l) for l in (tmp / "audit.jsonl").read_text().splitlines()]


def test_light_on_and_readback(env):
    fake, luna, loop, tmp = env
    out = run(loop, luna.control_device, "the bedroom light".replace("the ", ""), "on")
    assert out.startswith("Done") and ("homeassistant", "turn_on", {"entity_id": "switch.light_switch"}) in fake.calls
    assert audit(tmp)[-1]["kind"] == "control"


def test_alias_resolves(env):
    fake, luna, loop, _ = env
    assert run(loop, luna.control_device, "Lamp", "off").startswith("Done")


def test_unknown_device_refused_and_nothing_called(env):
    fake, luna, loop, _ = env
    out = run(loop, luna.control_device, "front door lock", "off")
    assert "don't control" in out and fake.calls == []


def test_disallowed_action_refused(env):
    fake, luna, loop, _ = env
    out = run(loop, luna.control_device, "bedroom light", "brightness", 50)
    assert "isn't allowed" in out and fake.calls == []


def test_value_bounds(env):
    fake, luna, loop, _ = env
    assert "between 16 and 30" in run(loop, luna.control_device, "air conditioner", "set_temperature", 45)
    assert fake.calls == []
    assert run(loop, luna.control_device, "air conditioner", "set_temperature", 22).startswith("Done")


def test_stuck_device_is_reported_honestly(env):
    fake, luna, loop, _ = env
    fake.stuck = True
    out = run(loop, luna.control_device, "bedroom light", "on")
    assert "still reports 'off'" in out


def test_tv_command_allowlist(env):
    fake, luna, loop, _ = env
    assert run(loop, luna.tv_command, "Volume Up").startswith("Pressed")
    assert fake.calls[-1] == ("remote", "send_command", {"entity_id": "remote.bedroom_tv", "command": "VOLUME_UP"})
    n = len(fake.calls)
    assert "can't do" in run(loop, luna.tv_command, "factory reset") and len(fake.calls) == n


def test_tv_channel_digits_and_calls(env):
    fake, luna, loop, _ = env
    luna.tv["channels"] = {"five": {"digits": "5"}, "news": {"calls": [{"service": "script.turn_on", "data": {"entity_id": "script.x"}}]}}
    assert run(loop, luna.tv_channel, "Five").startswith("Switched")
    assert run(loop, luna.tv_channel, "news").startswith("Switched")
    assert ("script", "turn_on", {"entity_id": "script.x"}) in fake.calls
    assert "don't know" in run(loop, luna.tv_channel, "sports")


def test_notify_urgent_and_normal_and_ratelimit(env):
    fake, luna, loop, tmp = env
    assert run(loop, luna.notify_caregivers, "Mum is scared", True) == "Caregivers have been notified."
    urgent = [c for c in fake.calls if c[0] == "notify"]
    assert len(urgent) == 2 and urgent[0][2]["data"]["priority"] == "high"
    assert run(loop, luna.notify_caregivers, "wants tea", False).startswith("Caregivers have been notified")
    assert "already told" in run(loop, luna.notify_caregivers, "wants tea again", False)
    assert run(loop, luna.notify_caregivers, "help", True) == "Caregivers have been notified."  # urgent never limited


def test_notify_partial_and_total_failure(env):
    fake, luna, loop, _ = env
    fake.fail_notify = {"mobile_app_ron_s_phone"}
    assert "could not be reached" in run(loop, luna.notify_caregivers, "help", True)
    fake.fail_notify = {"mobile_app_ron_s_phone", "mobile_app_pixel_10_pro"}
    assert "could NOT reach" in run(loop, luna.notify_caregivers, "help", True)


def test_check_config_flags_missing_entities(env):
    fake, luna, loop, _ = env
    del fake.states["climate.air_conditioner"]
    probs = loop.run_until_complete(check_config(luna.ha, CFG))
    assert any("climate.air_conditioner" in p for p in probs) and len(probs) == 1
