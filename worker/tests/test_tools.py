import asyncio
import copy
import json

import pytest

from aiohttp import web
from aiohttp.test_utils import TestServer

from luna.agent import check_config
from luna.config import load_config
from luna.ha import HA, Audit
from livekit.agents import ToolError
from luna.tools import Luna

CFG = load_config("config.yaml")
CFG["caregivers"]["min_interval_seconds"] = 60
HARM = "remote.grandpa_s_hub"


class FakeHA:
    """Minimal Home Assistant: records service calls, models switches and the Harmony hub."""

    def __init__(self):
        self.calls = []
        self.states = {"switch.light_switch": "off", "light.kajplats_e26_ws_globe_1100lm_2": "off",
                       "sensor.kitchen_temperature": "21.3", "sensor.bedroom_temperature": "unavailable",
                       "binary_sensor.front_door_contact": "off", "binary_sensor.back_door_contact": "on",
                       "binary_sensor.double_door_contact": "unavailable", "binary_sensor.front_door": "on"}
        self.attrs = {HARM: {"current_activity": "PowerOff"}, "sensor.kitchen_temperature": {"unit_of_measurement": "°C"}}
        self.stuck = False
        self.never_starts = False
        self.fail_notify = set()

    def app(self):
        app = web.Application()
        app.router.add_post("/api/services/{d}/{s}", self.service)
        app.router.add_get("/api/states/{e}", self.state)
        app.router.add_get("/api/services", self.list_services)
        app.router.add_get("/v1/forecast", self.forecast)
        return app

    async def forecast(self, req):
        assert req.query["latitude"] == "51.0447" and req.query["timezone"] == "auto"
        return web.json_response({
            "current": {"temperature_2m": -3.4, "apparent_temperature": -9.0, "weather_code": 73, "wind_speed_10m": 18.2, "precipitation": 0.4},
            "daily": {"weather_code": [73, 3, 0], "temperature_2m_max": [-1.0, 2.2, 6.0], "temperature_2m_min": [-8.0, -5.1, -2.0],
                      "precipitation_probability_max": [80, 20, 0]}})

    async def service(self, req):
        d, s = req.match_info["d"], req.match_info["s"]
        body = await req.json()
        self.calls.append((d, s, body))
        if d == "notify" and s in self.fail_notify:
            return web.Response(status=500, text="boom")
        ent = body.get("entity_id")
        if not self.stuck and (d, s) in (("homeassistant", "turn_on"), ("homeassistant", "turn_off")) and ent in self.states:
            self.states[ent] = "on" if s == "turn_on" else "off"
        if (d, s) == ("remote", "turn_on") and not self.never_starts:
            self.attrs[HARM]["current_activity"] = body["activity"]
        if (d, s) == ("remote", "turn_off"):
            self.attrs[HARM]["current_activity"] = "PowerOff"
        return web.json_response([])

    async def state(self, req):
        e = req.match_info["e"]
        if e == HARM:
            return web.json_response({"entity_id": e, "state": "on", "attributes": self.attrs[HARM]})
        if e not in self.states:
            return web.Response(status=404)
        return web.json_response({"entity_id": e, "state": self.states[e], "attributes": self.attrs.get(e, {})})

    async def list_services(self, req):
        return web.json_response([{"domain": "notify", "services": {"mobile_app_pixel_10_pro": {}, "mobile_app_ron_s_phone": {}}}])


@pytest.fixture
def env(tmp_path, monkeypatch):
    fake = FakeHA()
    monkeypatch.setenv("LUNA_STATE_DIR", str(tmp_path / "state"))

    async def mk():
        srv = TestServer(fake.app())
        await srv.start_server()
        return srv

    loop = asyncio.new_event_loop()
    srv = loop.run_until_complete(mk())
    cfg = copy.deepcopy(CFG)
    cfg["weather"]["base_url"] = str(srv.make_url("")).rstrip("/")
    cfg["harmony"]["settle_seconds"] = 0
    cfg["harmony"]["activity_timeout"] = 0.3
    cfg["harmony"]["cable"]["favorites"] = {"news": "205", "weather": "210"}
    ha = HA(str(srv.make_url("")), "tok")
    luna = Luna(cfg, ha, Audit(str(tmp_path / "audit.jsonl")))
    real_sleep = asyncio.sleep
    monkeypatch.setattr(asyncio, "sleep", lambda *_: real_sleep(0.01))  # keep tests fast
    yield fake, luna, loop, tmp_path, cfg
    loop.run_until_complete(ha.close())
    loop.run_until_complete(srv.close())
    loop.close()


class FakeCtx:
    """Stands in for livekit's RunContext and records how a tool used it."""

    def __init__(self):
        self.updates, self.fillers, self.no_interrupt = [], [], False

    def disallow_interruptions(self):
        self.no_interrupt = True

    async def wait_for_playout(self):
        self.played_out = True

    async def update(self, message, **_):
        self.updates.append(message)

    def with_filler(self, source, **kw):
        import contextlib

        @contextlib.asynccontextmanager
        async def cm():
            self.fillers.append((source, kw))
            yield
        return cm()


LAST = {}


def run(loop, tool, *a, **k):
    """Call a tool the way the framework does. A ToolError is what the model would be told, so return its text."""
    from livekit.agents.llm import ToolError
    ctx = LAST["ctx"] = FakeCtx()
    try:
        return loop.run_until_complete(tool(ctx, *a, **k))
    except ToolError as e:
        return str(e)


def audit(tmp):
    return [json.loads(l) for l in (tmp / "audit.jsonl").read_text().splitlines()]


def calls(fake, d, s):
    return [c[2] for c in fake.calls if (c[0], c[1]) == (d, s)]


# ---------------------------------------------------------------- lights
def test_both_lights_on_off(env):
    fake, luna, loop, tmp, _ = env
    assert run(loop, luna.control_device, "bedroom light", "on").startswith("Done")
    assert run(loop, luna.control_device, "corner lamp", "on").startswith("Done")
    assert run(loop, luna.control_device, "corner light", "brightness", 30).startswith("Done")
    assert {"entity_id": "light.kajplats_e26_ws_globe_1100lm_2", "brightness_pct": 30} in calls(fake, "light", "turn_on")
    assert audit(tmp)[0]["kind"] == "control"


def test_unknown_or_disallowed_is_refused(env):
    fake, luna, loop, *_ = env
    assert "don't control" in run(loop, luna.control_device, "front door lock", "off")
    assert "isn't allowed" in run(loop, luna.control_device, "bedroom light", "brightness", 50)
    assert "don't control" in run(loop, luna.control_device, "air conditioner", "on")  # not exposed
    assert fake.calls == []


def test_stuck_light_reported_honestly(env):
    fake, luna, loop, *_ = env
    fake.stuck = True
    assert "still reports 'off'" in run(loop, luna.control_device, "bedroom light", "on")


# ---------------------------------------------------------------- fan / readings
def test_fan_buttons(env):
    fake, luna, loop, *_ = env
    assert run(loop, luna.control_fan, "Speed").startswith("Pressed")
    assert calls(fake, "button", "press") == [{"entity_id": "button.bedroom_fan_speed"}]
    assert "can't do" in run(loop, luna.control_fan, "explode")


def test_readings(env):
    fake, luna, loop, *_ = env
    assert run(loop, luna.get_readings, "kitchen") == "kitchen temperature: 21.3 °C"
    assert "bedroom temperature: not available" in run(loop, luna.get_readings, "bedroom temperature")
    assert "don't have a reading" in run(loop, luna.get_readings, "garage")


# ---------------------------------------------------------------- harmony / cable
def test_cable_channel_starts_activity_then_sends_digits_in_order(env):
    fake, luna, loop, *_ = env
    out = run(loop, luna.cable_channel, "news")
    assert "channel 205" in out and "turn the cable activity on first" in out
    order = [(c[0], c[1]) for c in fake.calls]
    assert order.index(("remote", "turn_on")) < order.index(("remote", "send_command"))  # activity BEFORE digits
    assert calls(fake, "remote", "turn_on") == [{"entity_id": HARM, "activity": "Watch Cable"}]
    assert calls(fake, "remote", "send_command") == [
        {"entity_id": HARM, "device": "Arris DVR", "command": ["2", "0", "5"], "delay_secs": 0.4}]


def test_cable_channel_skips_start_when_already_on(env):
    fake, luna, loop, *_ = env
    fake.attrs[HARM]["current_activity"] = "Watch Cable"
    out = run(loop, luna.cable_channel, "210")
    assert "turn the cable" not in out and calls(fake, "remote", "turn_on") == []
    assert calls(fake, "remote", "send_command")[0]["command"] == ["2", "1", "0"]


def test_cable_activity_that_never_starts_is_reported_and_no_digits_sent(env):
    fake, luna, loop, *_ = env
    fake.never_starts = True
    out = run(loop, luna.cable_channel, "205")
    assert "did not start" in out and calls(fake, "remote", "send_command") == []


def test_cable_unknown_name(env):
    fake, luna, loop, *_ = env
    assert "don't know a cable channel" in run(loop, luna.cable_channel, "hockey")
    assert fake.calls == []


def test_remember_channel_persists_and_is_used(env):
    fake, luna, loop, tmp, cfg = env
    assert "remember" in run(loop, luna.remember_cable_channel, "Hockey", "412")
    assert "remember" in run(loop, luna.remember_cable_channel, "Golf", "58")
    assert "digits" in run(loop, luna.remember_cable_channel, "Bad", "4a2")
    assert json.loads((tmp / "state" / "favorites.json").read_text()) == {"hockey": "412", "golf": "58"}
    fresh = Luna(cfg, luna.ha, Audit(str(tmp / "a2.jsonl")))  # a new session reloads from disk
    fake.attrs[HARM]["current_activity"] = "Watch Cable"
    run(loop, fresh.cable_channel, "hockey")
    assert calls(fake, "remote", "send_command")[-1]["command"] == ["4", "1", "2"]


def test_buttons_and_activities(env):
    fake, luna, loop, *_ = env
    assert run(loop, luna.tv_button, "Volume Up").startswith("Pressed")
    assert calls(fake, "remote", "send_command")[-1] == {
        "entity_id": HARM, "device": "Hisense TV", "command": ["VolumeUp"], "delay_secs": 0.0}
    assert "can't do" in run(loop, luna.tv_button, "format disk")
    assert run(loop, luna.tv_activity, "dashboard").startswith("Open Dashboard is on")  # dashboard on the TV
    assert calls(fake, "remote", "turn_on")[-1]["activity"] == "Open Dashboard"
    assert "was already on" in run(loop, luna.tv_activity, "dashboard")
    assert run(loop, luna.tv_activity, "off").startswith("TV system turned off")
    assert "don't know" in run(loop, luna.tv_activity, "netflix on the moon")


# ---------------------------------------------------------------- live tv / scripts
def test_live_tv_pinned_name_uses_nonblocking_script_start(env):
    fake, luna, loop, *_ = env
    assert "102" in run(loop, luna.live_tv_channel, "Only Spies Station")
    assert calls(fake, "script", "turn_on") == [{"entity_id": "script.jellyfin_live_tv", "variables": {"channel_id": "102"}}]


def test_live_tv_fuzzy_match_tolerates_speech_slips(env):
    fake, luna, loop, *_ = env
    assert "101" in run(loop, luna.live_tv_channel, "classic station")      # singular / partial
    assert calls(fake, "script", "turn_on")[-1]["variables"]["channel_id"] == "101"
    assert "305" in run(loop, luna.live_tv_channel, "channel 305")


def test_live_tv_unknown_name_is_not_guessed(env):
    fake, luna, loop, *_ = env
    assert "couldn't find" in run(loop, luna.live_tv_channel, "zzzz nothing")
    assert calls(fake, "script", "turn_on") == []


def test_show_on_tv_allowlist(env):
    fake, luna, loop, *_ = env
    assert run(loop, luna.show_on_tv, "Photo Gallery").startswith("Showing")
    assert calls(fake, "script", "turn_on") == [{"entity_id": "script.open_gallery_on_tv"}]
    assert "can't show" in run(loop, luna.show_on_tv, "delete everything")


# ---------------------------------------------------------------- caregivers
def test_notify_urgent_and_normal_and_ratelimit(env):
    fake, luna, loop, *_ = env
    assert run(loop, luna.notify_caregivers, "Mum is scared", True) == "Caregivers have been notified."
    urgent = calls(fake, "notify", "mobile_app_pixel_10_pro")
    assert urgent[0]["data"]["priority"] == "high" and len(calls(fake, "notify", "mobile_app_ron_s_phone")) == 1
    assert run(loop, luna.notify_caregivers, "wants tea", False).startswith("Caregivers have been notified")
    assert "already told" in run(loop, luna.notify_caregivers, "wants tea again", False)
    assert run(loop, luna.notify_caregivers, "help", True) == "Caregivers have been notified."  # urgent never limited


def test_notify_partial_and_total_failure(env):
    fake, luna, loop, *_ = env
    fake.fail_notify = {"mobile_app_ron_s_phone"}
    assert "could not be reached" in run(loop, luna.notify_caregivers, "help", True)
    fake.fail_notify = {"mobile_app_ron_s_phone", "mobile_app_pixel_10_pro"}
    assert "could NOT reach" in run(loop, luna.notify_caregivers, "help", True)


# ---------------------------------------------------------------- startup check
def test_check_config_flags_missing_entities(env):
    fake, luna, loop, _, cfg = env
    del fake.states["light.kajplats_e26_ws_globe_1100lm_2"]
    probs = loop.run_until_complete(check_config(luna.ha, cfg))
    assert any("kajplats" in p for p in probs)


# ---------------------------------------------------------------- doors / weather / dry-run
def test_doors_open_closed_and_unknown_is_never_closed(env):
    fake, luna, loop, *_ = env
    out = run(loop, luna.check_doors, "")
    assert "front door: closed" in out and "back door: open" in out
    assert "double door: I can't tell right now" in out          # unavailable != closed
    assert "front door lock: unlocked" in out                    # lock sensor: on = unlocked
    assert run(loop, luna.check_doors, "back door") == "back door: open"
    assert "don't have a door" in run(loop, luna.check_doors, "garage")
    assert fake.calls == []                                      # read-only: no service calls at all


def test_lock_invert_flag(env):
    fake, luna, loop, *_ = env
    luna.doors.items["front door lock"][1]["invert"] = True
    assert run(loop, luna.check_doors, "front door lock") == "front door lock: locked"


def test_weather_direct_from_api(env):
    fake, luna, loop, *_ = env
    out = run(loop, luna.get_weather, "now")
    assert "snow" in out and "-3°C" in out and "feels like -9°C" in out and "80%" in out
    assert fake.calls == []                                      # not routed through Home Assistant
    assert "Tomorrow in Calgary: overcast" in run(loop, luna.get_weather, "tomorrow")
    assert "now, today, tomorrow" in run(loop, luna.get_weather, "next year")


def test_dry_run_reads_but_never_writes(env):
    fake, luna, loop, tmp, cfg = env
    luna.ha.dry_run = True
    assert run(loop, luna.control_device, "bedroom light", "on").startswith("Done")
    assert run(loop, luna.cable_channel, "205").startswith("Cable is on channel 205")   # no waiting for the hub
    assert "Dry run" in run(loop, luna.notify_caregivers, "help", True)
    assert "front door: closed" in run(loop, luna.check_doors, "front door")           # reads still work
    assert fake.calls == []                                                            # nothing reached HA


# ---------------------------------------------------------------- LiveKit conventions (from the docs review)
def test_failures_raise_toolerror_not_return_strings(env):
    from livekit.agents.llm import ToolError
    fake, luna, loop, *_ = env
    with pytest.raises(ToolError, match="don't control"):
        loop.run_until_complete(luna.control_device(FakeCtx(), "front door lock", "off"))
    fake.never_starts = True
    with pytest.raises(ToolError, match="did not start"):
        loop.run_until_complete(luna.cable_channel(FakeCtx(), "205"))
    fake.fail_notify = {"mobile_app_ron_s_phone", "mobile_app_pixel_10_pro"}
    with pytest.raises(ToolError, match="could NOT reach"):
        loop.run_until_complete(luna.notify_caregivers(FakeCtx(), "help", True))


def test_state_changing_tools_disallow_interruptions_read_only_ones_do_not(env):
    fake, luna, loop, *_ = env
    for tool, args in [(luna.control_device, ("bedroom light", "on")), (luna.control_fan, ("speed",)),
                       (luna.tv_button, ("mute",)), (luna.show_on_tv, ("photo gallery",)),
                       (luna.remember_cable_channel, ("golf", "58")), (luna.notify_caregivers, ("help", True)),
                       (luna.live_tv_channel, ("only spies station",)), (luna.cable_channel, ("news",)),
                       (luna.tv_activity, ("dashboard",))]:
        run(loop, tool, *args)
        assert LAST["ctx"].no_interrupt, f"{tool.id} must call disallow_interruptions()"
    for tool, args in [(luna.get_readings, ("kitchen",)), (luna.check_doors, ("",)), (luna.get_weather, ("now",))]:
        run(loop, tool, *args)
        assert not LAST["ctx"].no_interrupt, f"{tool.id} is read-only"


def test_slow_tools_acknowledge_immediately_and_use_filler(env):
    fake, luna, loop, *_ = env
    run(loop, luna.cable_channel, "news")
    assert LAST["ctx"].updates and "channel 205" in LAST["ctx"].updates[0] and LAST["ctx"].fillers
    run(loop, luna.live_tv_channel, "only spies station")
    assert "102" in LAST["ctx"].updates[0] and LAST["ctx"].fillers
    run(loop, luna.tv_activity, "dashboard")
    assert LAST["ctx"].updates and LAST["ctx"].fillers
    run(loop, luna.tv_button, "mute")
    assert not LAST["ctx"].updates                       # quick tools stay synchronous


def test_slow_tools_ask_the_framework_to_confirm_duplicates(env):
    fake, luna, loop, *_ = env
    for name in ("cable_channel", "live_tv_channel", "tv_activity"):
        assert getattr(luna, name).info.on_duplicate == "confirm"
    assert luna.notify_caregivers.info.on_duplicate == "allow"   # a repeated urgent alert must go through


# ---------------------------------------------------------------- session / server wiring (docs review)
def test_turn_handling_is_fully_local_and_configured_from_yaml(monkeypatch):
    from luna.agent import turn_handling_options
    th = turn_handling_options(CFG["turns"])
    assert th["turn_detection"].__class__.__name__ == "TurnDetector"
    assert th["endpointing"] == {"mode": "dynamic", "min_delay": 0.8, "max_delay": 5.0}
    assert th["interruption"]["mode"] == "vad"          # adaptive interruption would call LiveKit's cloud
    assert th["interruption"]["min_words"] == 1 and th["interruption"]["enabled"] is True


def test_framework_accepts_our_session_options(monkeypatch):
    """Build a real AgentSession from config.yaml: invalid option names would raise here."""
    for k in ("DEEPGRAM_API_KEY", "OPENAI_API_KEY", "CARTESIA_API_KEY"):
        monkeypatch.setenv(k, "x")
    from livekit.agents import AgentSession
    from luna.agent import turn_handling_options
    from luna.providers import build

    async def go():
        return AgentSession(stt=build("STT", CFG["stt"]), llm=build("LLM", CFG["llm"]), tts=build("TTS", CFG["tts"]),
                            turn_handling=turn_handling_options(CFG["turns"]))
    assert asyncio.run(go()) is not None


def test_server_load_is_session_based_not_cpu():
    from luna.agent import SRV, server
    limit = SRV["max_sessions"]
    class S:  # a stand-in with as many running jobs as are allowed
        active_jobs = list(range(limit))
    assert server.load_fnc(S()) == 1.0 and server.load_fnc(type("E", (), {"active_jobs": []})()) == 0.0
    # one conversation still shutting down leaves room for the next (that is what the extra session is for)
    assert server.load_fnc(type("O", (), {"active_jobs": [1]})()) < 0.99 and limit >= 3


# ---------------------------------------------------------------- cable favourites (names from the cable box)
def test_cable_favourites_by_simple_names_aliases_and_spacing(env):
    fake, luna, loop, tmp, cfg = env
    fav = luna.favorites
    cfg_favs = CFG["harmony"]["cable"]["favorites"]
    fav.seed = {k.lower(): str(v) for k, v in cfg_favs.items()}
    for said, number in [("CTV", "3"), ("ctv calgary", "3"), ("Fox", "142"), ("the art channel", "165"), ("frame", "165"),
                         ("oldies", "419"), ("Stingray Country Classics", "425"), ("history channel", "45"), ("flavour", "25")]:
        assert fav.lookup(said) == (number, []), said


def test_cable_sportsnet_regions_and_ambiguity(env):
    fake, luna, loop, *_ = env
    luna.favorites.seed.update({"sportsnet east": "401", "sportsnet west": "402", "sportsnet pacific": "403"})
    assert luna.favorites.lookup("sports net east") == ("401", [])            # speech-to-text spacing slip
    assert luna.favorites.lookup("Sportsnet Pacific") == ("403", [])
    number, options = luna.favorites.lookup("sportsnet")
    assert number is None and options == ["sportsnet east", "sportsnet pacific", "sportsnet west"]
    out = run(loop, luna.cable_channel, "sportsnet")                          # the tool asks instead of guessing
    assert "Which one" in out and "sportsnet west" in out and calls(fake, "remote", "turn_on") == []


def test_aliases_to_one_number_are_not_ambiguous(env):
    fake, luna, loop, *_ = env
    luna.favorites.seed.update({"history": "45", "history channel": "45", "history television": "45"})
    assert luna.favorites.lookup("hist") == ("45", [])                         # three names, one channel: fine


def test_end_conversation_asks_for_a_goodbye_and_tells_the_session(env):
    fake, luna, loop, *_ = env
    calls = []
    luna.on_end = lambda: calls.append(1)
    assert "goodbye" in run(loop, luna.end_conversation)       # the model is told to say it; the session hangs up after
    assert calls == [1]


def test_show_widget_takes_real_json_and_is_not_strict(tmp_path):
    from livekit.agents import llm
    from livekit.agents.llm._provider_format import openai as fmt
    from luna.ui import Widgets
    luna = Luna(copy.deepcopy(CFG), HA("http://127.0.0.1:1", "t"), Audit(str(tmp_path / "audit.jsonl")))
    sent = []

    async def send(text, topic):
        sent.append(topic)
    luna.widgets = Widgets(send, None)
    tool = next(t for t in luna.tools if t.id == "show_widget")
    schema = next(s for s in fmt.to_fnc_ctx(llm.ToolContext(luna.tools)) if s["function"]["name"] == "show_widget")
    assert not schema["function"].get("strict") and schema["function"]["parameters"]["properties"]["components"]["type"] == "array"
    comps = [{"id": "root", "component": "Column", "children": ["t"]}, {"id": "t", "component": "Text", "text": "hi"}]
    out = asyncio.run(tool({"surface_id": "demo", "components": comps}, None))
    assert sent and "demo" in out or sent
    out = asyncio.run(tool({"surface_id": "demo2", "components": json.dumps(comps)}, None))  # text JSON still tolerated
    assert len(sent) == 2


def test_builtin_widget_tools_have_strict_safe_schemas_and_show(tmp_path):
    from livekit.agents import llm
    from livekit.agents.llm._provider_format import openai as fmt
    from luna.ui import Widgets
    luna = Luna(copy.deepcopy(CFG), HA("http://127.0.0.1:1", "t"), Audit(str(tmp_path / "audit.jsonl")))
    sent = []

    async def send(text, topic):
        sent.append((topic, text))
    luna.widgets = Widgets(send, None)
    schemas = {s["function"]["name"]: s for s in fmt.to_fnc_ctx(llm.ToolContext(luna.tools))}
    for name in ("show_card", "show_list", "show_choices"):
        assert name in schemas and schemas[name]["function"]["parameters"].get("additionalProperties") is False
    tools = {t.id: t for t in luna.tools}
    asyncio.run(tools["show_card"](None, title="Score", big="4 - 2", icon="🏒", lines=["Final"]))
    asyncio.run(tools["show_list"](None, title="Groceries", items=["milk", "eggs"]))
    asyncio.run(tools["show_choices"](None, prompt="Which?", options=["A", "B"]))
    assert [t for t, _ in sent] == ["luna.ui"] * 3
    with pytest.raises(ToolError):
        asyncio.run(tools["show_choices"](None, prompt="Which?", options=["only"]))


def test_realtime_model_replaces_the_three_stages(monkeypatch):
    """`llm` naming a realtime model (Gemini Live) builds a session with no stt/tts; the default config keeps all three."""
    pytest.importorskip("livekit.plugins.google")
    for k in ("DEEPGRAM_API_KEY", "OPENAI_API_KEY", "CARTESIA_API_KEY", "GOOGLE_API_KEY"):
        monkeypatch.setenv(k, "x")
    from luna.agent import make_session
    from luna.config import merge

    async def go(cfg):
        return make_session(cfg, None)
    classic = asyncio.run(go(CFG))
    assert classic.stt is not None and classic.tts is not None
    live = merge(CFG, {"llm": {"plugin": "google.realtime", "class": "RealtimeModel", "model": "gemini-3.8-live", "voice": "Aoede"}})
    s = asyncio.run(go(live))
    assert s.stt is None and s.tts is None and s.llm.capabilities.audio_output


def test_fixed_words_use_say_or_the_realtime_model():
    from luna.agent import speak

    class Session:
        def __init__(self, tts):
            self.tts, self.calls = tts, []

        async def say(self, text):
            self.calls.append(("say", text))

        async def generate_reply(self, instructions):
            self.calls.append(("reply", instructions))
    with_tts, without = Session(object()), Session(None)
    asyncio.run(speak(with_tts, "Goodbye"))
    asyncio.run(speak(without, "Goodbye"))
    assert with_tts.calls == [("say", "Goodbye")]
    kind, text = without.calls[0]
    assert kind == "reply" and text.endswith("Goodbye") and "exactly" in text
