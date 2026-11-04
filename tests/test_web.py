import asyncio
import base64
import json

import pytest
from aiohttp.test_utils import TestClient, TestServer

from luna.web import TokenServer, parse_keys

KEY = "tablet-key-0123456789abcdef"
OTHER = "phone-key-fedcba9876543210"


def claims(jwt: str) -> dict:
    p = jwt.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(p + "=" * (-len(p) % 4)))


@pytest.fixture
def client():
    srv = TokenServer("luna", parse_keys(f"tablet:{KEY},phone:{OTHER}"), "lkkey", "lksecret-0123456789abcdef0123456789", "wss://luna.example")
    loop = asyncio.new_event_loop()

    async def mk():
        c = TestClient(TestServer(srv.app()))
        await c.start_server()
        return c
    c = loop.run_until_complete(mk())
    yield c, loop, srv
    loop.run_until_complete(c.close())
    loop.close()


def post(client, headers=None, body=None):
    c, loop, _ = client

    async def go():
        r = await c.post("/api/token", json=body or {}, headers=headers or {})
        return r.status, await r.json()
    return loop.run_until_complete(go())


def test_parse_keys_rejects_weak_keys_and_names_devices():
    assert parse_keys(f"tablet:{KEY}") == {KEY: "tablet"}
    with pytest.raises(ValueError):
        parse_keys("tablet:short")


def test_no_key_or_wrong_key_is_refused(client):
    assert post(client)[0] == 401
    assert post(client, {"Authorization": "Bearer nope"})[0] == 401
    assert post(client, {"Authorization": f"Bearer {KEY}x"})[0] == 401


def test_standard_endpoint_response_shape(client):
    status, data = post(client, {"Authorization": f"Bearer {KEY}"})
    assert status == 201                                    # LiveKit's endpoint spec says 201
    assert data["server_url"] == "wss://luna.example" and data["participant_token"].count(".") == 2


def test_token_dispatches_luna_server_side_and_is_scoped(client):
    status, data = post(client, {"Authorization": f"Bearer {KEY}"})
    c = claims(data["participant_token"])
    assert c["roomConfig"]["agents"][0]["agentName"] == "luna"
    v = c["video"]
    assert v["roomJoin"] is True and v["room"].startswith("luna-tablet-")
    assert v["canPublish"] is True and v["canSubscribe"] is True
    assert not v.get("roomAdmin") and not v.get("canPublishData")
    assert c["exp"] - c["nbf"] <= 600 and c["sub"].startswith("tablet-")      # short-lived; identity names the device


def test_client_supplied_room_config_and_room_name_are_ignored(client):
    evil = {"room_name": "someone-elses-room",
            "room_config": {"agents": [{"agent_name": "attacker-agent"}], "max_participants": 999}}
    status, data = post(client, {"Authorization": f"Bearer {OTHER}"}, evil)
    c = claims(data["participant_token"])
    assert [a["agentName"] for a in c["roomConfig"]["agents"]] == ["luna"]
    assert c["video"]["room"].startswith("luna-phone-") and c["video"]["room"] != "someone-elses-room"
    assert "maxParticipants" not in c["roomConfig"]


def test_each_session_gets_a_fresh_room(client):
    rooms = {claims(post(client, {"Authorization": f"Bearer {KEY}"})[1]["participant_token"])["video"]["room"] for _ in range(3)}
    assert len(rooms) == 3


def test_repeated_bad_keys_are_throttled(client):
    codes = [post(client, {"Authorization": "Bearer wrong"})[0] for _ in range(12)]
    assert codes[:10] == [401] * 10 and codes[10:] == [429, 429]
    assert post(client, {"Authorization": f"Bearer {KEY}"})[0] == 429       # same IP stays blocked for the minute


def test_health_and_index(client):
    c, loop, _ = client

    async def go():
        h = await c.get("/healthz")
        i = await c.get("/")
        return h.status, i.status
    assert loop.run_until_complete(go()) == (200, 200)


def test_page_is_installable_on_android(client):
    c, loop, _ = client

    async def go():
        m = await c.get("/manifest.webmanifest")
        manifest = await m.json(content_type=None)
        i = await c.get("/icon-192.png")
        big = await c.get("/icon-512.png")
        bad = await c.get("/icon-7.png")
        page = await (await c.get("/")).text()
        return manifest, await i.read(), await big.read(), bad.status, page
    manifest, small, big, bad, page = loop.run_until_complete(go())
    assert manifest["display"] == "standalone" and {i["sizes"] for i in manifest["icons"]} == {"192x192", "512x512"}
    assert small[:8] == bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) and big[:8] == small[:8] and bad == 404
    assert "/api/token" in page and "lk.transcription" in page and "livekit-client" in page


# --- luna-web add-device -----------------------------------------------------------------------
import pytest

from luna.web import main as web_main, new_device


def test_new_device_entry_round_trips_through_parse_keys():
    entry, link = new_device("tablet", "https://luna.home/")
    keys = parse_keys(entry)
    (key, name), = keys.items()
    assert name == "tablet" and len(key) >= 16
    assert link == f"https://luna.home/#key={key}"      # key in the fragment, never the query string


def test_new_device_keys_are_unique_and_link_optional():
    a, la = new_device("a")
    b, _ = new_device("a")
    assert a != b and la is None


@pytest.mark.parametrize("bad", ["", "ta blet", "a:b", "a,b", "x" * 33])
def test_new_device_rejects_names_that_would_break_the_setting(bad):
    with pytest.raises(ValueError):
        new_device(bad)


def test_cli_prints_entry_link_and_qr(capsys):
    assert web_main(["add-device", "phone", "--url", "https://luna.home"]) == 0
    out = capsys.readouterr().out
    assert "phone:" in out and "https://luna.home/#key=" in out
    assert "█" in out or "▀" in out or "▄" in out   # QR block characters were drawn


def test_cli_rejects_bad_name(capsys):
    assert web_main(["add-device", "bad name"]) == 2
