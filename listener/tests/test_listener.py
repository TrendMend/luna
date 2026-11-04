import asyncio
import json

import numpy as np
import pytest
from aiohttp import web

from luna_listener.app import App
from luna_listener.config import ConfigError, validate
from luna_listener.control import parse_call
from luna_listener.tokens import TokenError, attributes_for, fetch_token
from luna_listener.triggers import Trigger
from luna_listener.wakeword import CHUNK, OpenWakeWordDetector, WakeWordSource

BASE = {"device": "nuc", "server": {"token_url": "http://x/api/token"},
        "wake_words": {"hey_luna": {"model": "m"}}, "detection": {"cooldown_seconds": 3}}


def cfg(**over):
    c = json.loads(json.dumps(BASE))
    c.update(over)
    return validate(c)


# ---- config --------------------------------------------------------------------------------------
def test_config_defaults_and_validation():
    c = cfg()
    assert c["audio"] == {} and c["session"] == {}
    for bad in ({"device": "a b"}, {"server": {}}, {"wake_words": {"Hey Luna": {"model": "m"}}},
                {"wake_words": {"x": {}}}, {"wake_words": {"x": {"model": "m", "threshold": 2}}}):
        with pytest.raises(ConfigError):
            cfg(**bad)


# ---- wake word source (fake detector) -----------------------------------------------------------
class FakeDetector:
    def __init__(self, scores):
        self.scores, self.resets = list(scores), 0

    def predict(self, chunk):
        return {"hey_luna": self.scores.pop(0) if self.scores else 0.0}

    def reset(self):
        self.resets += 1


def feed(src, chunks):
    src.on_audio(np.zeros(CHUNK * chunks, dtype=np.int16))


def test_wake_word_needs_patience_and_clears_state():
    det = FakeDetector([0.9, 0.1, 0.9, 0.9])
    src = WakeWordSource(det, {"hey_luna": 0.5}, patience=2)
    feed(src, 2)
    assert src.take() == []                       # one hit, then a miss: streak reset
    feed(src, 2)
    assert src.take() == [Trigger("wake_word", wake_word="hey_luna")]
    assert det.resets == 1                        # buffers cleared so it cannot fire twice on one utterance


def test_wake_word_below_threshold_never_fires():
    src = WakeWordSource(FakeDetector([0.4] * 10), {"hey_luna": 0.5}, patience=1)
    feed(src, 10)
    assert src.take() == []


def test_partial_frames_accumulate():
    det = FakeDetector([0.9])
    src = WakeWordSource(det, {"hey_luna": 0.5}, patience=1)
    for _ in range(8):
        src.on_audio(np.zeros(CHUNK // 8, dtype=np.int16))
    assert src.take() == [Trigger("wake_word", wake_word="hey_luna")]


def test_real_openwakeword_model_stays_quiet_on_silence():
    pytest.importorskip("openwakeword")
    try:
        det = OpenWakeWordDetector({"hey_jarvis": "hey_jarvis"})
    except FileNotFoundError:
        pytest.skip("models not downloaded (luna-listener download-models)")
    src = WakeWordSource(det, {"hey_jarvis": 0.5}, patience=1)
    feed(src, 30)                                 # 2.4 s of silence
    assert src.take() == []


# ---- calls from Home Assistant -------------------------------------------------------------------
def test_parse_call():
    assert parse_call("luna.call", b'{"say": "Time for your pills."}') == Trigger("call", say="Time for your pills.")
    assert parse_call("luna.call", b"{}") == Trigger("call")
    assert parse_call("luna.call", b"") == Trigger("call")
    assert parse_call("other.topic", b"{}") is None
    assert parse_call("luna.call", b"not json") is None
    assert parse_call("luna.call", b"[1]") is None
    long = parse_call("luna.call", json.dumps({"say": "x" * 999}).encode())
    assert len(long.say) == 300
    assert parse_call("luna.call", json.dumps({"say": "a\x00b\n"}).encode()).say == "ab"
    assert parse_call("luna.call", b'{"say": 5}').say is None


# ---- tokens --------------------------------------------------------------------------------------
def test_attributes_for_trigger():
    assert attributes_for(Trigger("wake_word", wake_word="hey_luna")) == {"luna.trigger": "wake_word", "luna.wake_word": "hey_luna"}
    assert attributes_for(Trigger("call", say="Hi")) == {"luna.trigger": "call", "luna.say": "Hi"}


def test_fetch_token_sends_key_and_attributes(monkeypatch):
    seen = {}

    async def handler(req):
        seen["auth"], seen["body"] = req.headers.get("Authorization"), await req.json()
        return web.json_response({"server_url": "ws://lk", "participant_token": "tok"}, status=201)

    async def go():
        app = web.Application()
        app.router.add_post("/api/token", handler)
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, "127.0.0.1", 0)
        await site.start()
        port = site._server.sockets[0].getsockname()[1]
        c = cfg(server={"token_url": f"http://127.0.0.1:{port}/api/token"})
        try:
            return await fetch_token(c, purpose="control", attributes={"luna.trigger": "call"})
        finally:
            await runner.cleanup()

    monkeypatch.setenv("LUNA_ACCESS_KEY", "secret-key-0123456789")
    assert asyncio.run(go()) == ("ws://lk", "tok")
    assert seen["auth"] == "Bearer secret-key-0123456789"
    assert seen["body"] == {"participant_name": "nuc", "purpose": "control", "participant_attributes": {"luna.trigger": "call"}}


def test_fetch_token_without_key_explains_how_to_get_one(monkeypatch):
    monkeypatch.delenv("LUNA_ACCESS_KEY", raising=False)
    with pytest.raises(TokenError, match="add-device nuc"):
        asyncio.run(fetch_token(cfg()))


# ---- the main loop -------------------------------------------------------------------------------
class FakeSource:
    def __init__(self):
        self.heard, self.resets = [], 0

    def on_audio(self, frame):
        self.heard.append(frame)

    def reset(self):
        self.resets += 1

    async def run(self, emit):
        await asyncio.sleep(3600)


class FakeSession:
    def __init__(self, trigger, outcome="agent_left"):
        self.trigger, self.mic, self.outcome = trigger, [], outcome
        self.release = asyncio.Event()

    def push_mic(self, frame):
        self.mic.append(frame)

    async def run(self):
        await self.release.wait()
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


class Clock:
    t = 100.0

    def __call__(self):
        return self.t


def make_app(clock=None, outcome="agent_left"):
    src, made = FakeSource(), []

    def factory(trig):
        s = FakeSession(trig, outcome)
        made.append(s)
        return s

    return App(cfg(), None, [src], factory, clock or Clock()), src, made


FRAME = np.zeros(160, dtype=np.int16)


def test_audio_goes_to_local_sources_only_while_idle_and_to_livekit_only_in_a_conversation():
    async def go():
        app, src, made = make_app()
        app.route_mic(FRAME)
        assert len(src.heard) == 1                 # idle: local detector hears it
        task = asyncio.create_task(app.handle(Trigger("wake_word", wake_word="hey_luna")))
        await asyncio.sleep(0)
        app.route_mic(FRAME)
        assert len(src.heard) == 1 and len(made[0].mic) == 1     # in conversation: detector does not, LiveKit does
        made[0].release.set()
        assert await task == "agent_left"
        assert src.resets == 1 and app.session is None
    asyncio.run(go())


def test_triggers_are_ignored_while_busy_and_during_cooldown():
    async def go():
        clock = Clock()
        app, src, made = make_app(clock)
        first = asyncio.create_task(app.handle(Trigger("wake_word", wake_word="hey_luna")))
        await asyncio.sleep(0)
        assert await app.handle(Trigger("call")) is None             # busy
        made[0].release.set()
        await first
        assert await app.handle(Trigger("call")) is None             # cooling down (3 s)
        app.route_mic(FRAME)
        assert src.heard == []                                       # and the detector is deaf during cooldown
        clock.t += 3.1
        second = asyncio.create_task(app.handle(Trigger("call", say="Hi")))
        await asyncio.sleep(0)
        assert made[1].trigger.say == "Hi"
        made[1].release.set()
        await second
    asyncio.run(go())


def test_a_failed_conversation_returns_to_idle():
    async def go():
        app, src, made = make_app(outcome=RuntimeError("token service down"))
        task = asyncio.create_task(app.handle(Trigger("call")))
        await asyncio.sleep(0)
        made[0].release.set()
        assert "token service down" in await task
        assert app.session is None and src.resets == 1
    asyncio.run(go())


def test_only_enabled_wake_words_are_listened_for():
    c = cfg(wake_words={"a": {"model": "m"}, "b": {"model": "m", "enabled": False}, "c": {"model": "m", "enabled": True}})
    assert list(c["wake_words"]) == ["a", "c"]
    with pytest.raises(ConfigError):                      # disabled entries are still checked
        cfg(wake_words={"b": {"model": "m", "enabled": False, "threshold": 5}})


def test_shipped_config_loads_and_every_builtin_model_exists():
    pytest.importorskip("openwakeword")
    import pathlib
    from luna_listener.config import load_config
    root = pathlib.Path(__file__).resolve().parents[1]
    c = load_config(root / "config.yaml")
    assert "hey_jarvis" in c["wake_words"]
    import yaml
    every = yaml.safe_load((root / "config.yaml").read_text())["wake_words"]
    try:
        OpenWakeWordDetector({wid: w["model"] for wid, w in every.items()})   # all six at once, on and off
    except FileNotFoundError:
        pytest.skip("models not downloaded")
