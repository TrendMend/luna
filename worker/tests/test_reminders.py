import asyncio
import copy
import json
import time
from datetime import datetime, timedelta, timezone

import pytest
from aiohttp.test_utils import TestClient, TestServer

from luna import reminders as rm
from luna.config import load_config
from luna.ha import HA, Audit
from luna.reminders import Client, RemindersError, Scheduler, Store
from luna.tools import Luna
from luna.web import TokenServer, parse_keys

from test_tools import FakeCtx   # noqa: F401  (the same fake RunContext the other tool tests use)

SECRET = "lksecret-0123456789abcdef0123456789"
NOW = 1_800_000_000.0


# ---------------------------------------------------------------- the store
def test_store_add_list_remove_and_persist(tmp_path):
    p = tmp_path / "r.json"
    s = Store(p)
    a = s.add("tablet", NOW + 60, "Your timer is done.", "timer", "pasta", now=NOW)
    b = s.add("tablet", NOW + 30, "Reminder: pills", "reminder", now=NOW)
    s.add("phone", NOW + 10, "x", "timer", now=NOW)
    assert [r.id for r in s.for_device("tablet")] == [b.id, a.id]          # soonest first, and per device
    assert [r.say for r in Store(p).for_device("tablet")] == ["Reminder: pills", "Your timer is done."]   # survives a restart
    assert not s.remove(a.id, device="phone")                              # another device cannot cancel it
    assert s.remove(a.id, device="tablet") and not s.remove(a.id)
    assert [r.say for r in s.due(NOW + 45)] == ["x", "Reminder: pills"]      # only what is due, soonest first
    assert s.due(NOW + 5) == []


@pytest.mark.parametrize("kw", [
    dict(device="bad device!", due=NOW + 60, say="x", kind="timer"),
    dict(device="d", due=NOW + 60, say="x", kind="alarm"),
    dict(device="d", due=NOW - 3600, say="x", kind="timer"),
    dict(device="d", due=NOW + 400 * 86400, say="x", kind="timer"),
    dict(device="d", due=NOW + 60, say="   ", kind="timer"),
])
def test_store_rejects(tmp_path, kw):
    with pytest.raises(ValueError):
        Store(tmp_path / "r.json").add(now=NOW, **kw)


def test_store_limit_per_device(tmp_path):
    s = Store(tmp_path / "r.json")
    for i in range(rm.MAX_PENDING):
        s.add("d", NOW + 60 + i, "x", "timer", now=NOW)
    with pytest.raises(ValueError, match="at most"):
        s.add("d", NOW + 99, "x", "timer", now=NOW)
    s.add("other", NOW + 99, "x", "timer", now=NOW)


def test_store_survives_a_corrupt_file(tmp_path):
    (tmp_path / "r.json").write_text("{not json", encoding="utf-8")
    assert Store(tmp_path / "r.json").items == {}


# ---------------------------------------------------------------- the scheduler
def test_scheduler_fires_once_and_retries_then_drops(tmp_path):
    s = Store(tmp_path / "r.json")
    audit = Audit(str(tmp_path / "audit.jsonl"))
    r = s.add("tablet", NOW + 10, "Your timer is done.", "timer", now=NOW)
    clock = {"t": NOW}
    sent, reachable = [], {"v": False}

    async def deliver(device, say):
        if not reachable["v"]:
            return False
        sent.append((device, say))
        return True
    sch = Scheduler(s, deliver, audit, clock=lambda: clock["t"])
    run = asyncio.run
    run(sch.tick())
    assert not sent                                    # not due yet
    clock["t"] = NOW + 11
    run(sch.tick())
    assert not sent and r.id in s.items                # due, device unreachable: kept to try again
    reachable["v"] = True
    run(sch.tick())
    assert sent == [("tablet", "Your timer is done.")] and r.id not in s.items
    run(sch.tick())
    assert len(sent) == 1                              # never fires twice

    reachable["v"] = False
    s.add("tablet", NOW + 20, "late", "reminder", now=NOW)
    clock["t"] = NOW + 20 + rm.GRACE + 1
    run(sch.tick())
    assert not s.items                                 # gave up after the grace period...
    kinds = [json.loads(line)["kind"] for line in (tmp_path / "audit.jsonl").read_text().splitlines()]
    assert kinds == ["reminder_fired", "reminder_missed"]   # ...and said so in the audit log


def test_a_failing_delivery_does_not_stop_the_others(tmp_path):
    s = Store(tmp_path / "r.json")
    s.add("a", NOW + 1, "boom", "timer", now=NOW)
    s.add("b", NOW + 2, "ok", "timer", now=NOW)
    sent = []

    async def deliver(device, say):
        if device == "a":
            raise RuntimeError("livekit down")
        sent.append(device)
        return True
    asyncio.run(Scheduler(s, deliver, clock=lambda: NOW + 5).tick())
    assert sent == ["b"] and len(s.items) == 1


# ---------------------------------------------------------------- time helpers
def test_parse_local_and_spoken_when():
    tz = timezone(timedelta(hours=-7))
    now = datetime(2026, 11, 6, 14, 0, tzinfo=tz)
    assert rm.parse_local("2026-11-07 09:30", tz, now) == datetime(2026, 11, 7, 9, 30, tzinfo=tz)
    assert rm.parse_local("15:30", tz, now) == datetime(2026, 11, 6, 15, 30, tzinfo=tz)       # later today
    assert rm.parse_local("09:00", tz, now) == datetime(2026, 11, 7, 9, 0, tzinfo=tz)         # already passed: tomorrow
    with pytest.raises(ValueError):
        rm.parse_local("half past three", tz, now)
    assert rm.spoken_when(now + timedelta(minutes=9), now) == "in 9 minutes"
    assert rm.spoken_when(now.replace(hour=15, minute=30), now) == "at 3:30 PM"
    assert rm.spoken_when(datetime(2026, 11, 7, 9, 5, tzinfo=tz), now) == "tomorrow at 9:05 AM"
    assert rm.spoken_when(datetime(2026, 11, 9, 9, 5, tzinfo=tz), now) == "on Monday at 9:05 AM"


def test_unknown_time_zone_falls_back_instead_of_failing():
    assert rm.get_tz("Not/AZone") is not None and rm.get_tz(None) is not None


# ---------------------------------------------------------------- HTTP + the worker's tools, end to end
@pytest.fixture
def stack(tmp_path):
    loop = asyncio.new_event_loop()
    store = Store(tmp_path / "state" / "reminders.json")
    srv = TokenServer("luna", parse_keys("tablet:tablet-key-0123456789abcdef"), "lkkey", SECRET, "wss://x", store=store)

    async def mk():
        c = TestClient(TestServer(srv.app()))
        await c.start_server()
        return c
    c = loop.run_until_complete(mk())
    base = str(c.make_url("")).rstrip("/")
    cfg = copy.deepcopy(load_config("config.yaml"))
    luna = Luna(cfg, HA("http://127.0.0.1:1", "t"), Audit(str(tmp_path / "audit.jsonl")))
    luna.reminders = Client(base, SECRET)
    luna.device = "tablet"
    yield luna, loop, store, c, base
    loop.run_until_complete(c.close())
    loop.close()


def call(loop, tool, *a, **k):
    from livekit.agents.llm import ToolError
    try:
        return loop.run_until_complete(tool(FakeCtx(), *a, **k))
    except ToolError as e:
        return "ERROR: " + str(e)


def test_set_list_cancel_by_voice(stack):
    luna, loop, store, _, _ = stack
    assert call(loop, luna.set_timer, 10, "pasta") == "Timer set for 10 minutes (pasta)."
    out = call(loop, luna.set_reminder, "23:59", "take your pills")
    assert out.startswith("Reminder set for") and "take your pills" in out
    assert len(store.for_device("tablet")) == 2
    t = next(r for r in store.items.values() if r.kind == "timer")
    assert abs(t.due - (time.time() + 600)) < 5 and t.say == "Your pasta timer is done."
    listing = call(loop, luna.list_reminders)
    assert "pasta" in listing and "take your pills" in listing
    assert call(loop, luna.cancel_reminder, "zebra").startswith("ERROR")
    assert call(loop, luna.cancel_reminder, "pasta") == "Cancelled 1."
    assert [r.kind for r in store.for_device("tablet")] == ["reminder"]
    assert call(loop, luna.cancel_reminder, "all") == "Cancelled 1."
    assert call(loop, luna.list_reminders) == "There are no timers or reminders set."


def test_tool_inputs_are_checked(stack):
    luna, loop, store, _, _ = stack
    assert call(loop, luna.set_timer, 0).startswith("ERROR")
    assert call(loop, luna.set_timer, 5000).startswith("ERROR")
    assert call(loop, luna.set_reminder, "sometime soon", "x").startswith("ERROR")
    luna.device = None
    assert "which device" in call(loop, luna.set_timer, 5)
    assert not store.items


def test_cancel_with_two_matches_asks(stack):
    luna, loop, store, _, _ = stack
    call(loop, luna.set_timer, 5, "tea")
    call(loop, luna.set_timer, 8, "tea again")
    assert "More than one" in call(loop, luna.cancel_reminder, "tea")
    assert len(store.items) == 2


def test_internal_api_needs_the_right_key_and_scopes_by_device(stack):
    luna, loop, store, c, base = stack
    call(loop, luna.set_timer, 5, "x")

    async def go():
        no = await c.get("/api/internal/reminders", params={"device": "tablet"})
        bad = await c.get("/api/internal/reminders", params={"device": "tablet"}, headers={"Authorization": "Bearer tablet-key-0123456789abcdef"})
        good = await c.get("/api/internal/reminders", params={"device": "tablet"}, headers={"Authorization": f"Bearer {rm.internal_token(SECRET)}"})
        other = await c.get("/api/internal/reminders", params={"device": "phone"}, headers={"Authorization": f"Bearer {rm.internal_token(SECRET)}"})
        return no.status, bad.status, good.status, len(await good.json()), len(await other.json())
    assert loop.run_until_complete(go()) == (401, 401, 200, 1, 0)       # a device's access key is not the internal key
    with pytest.raises(RemindersError, match="did not accept"):
        loop.run_until_complete(Client(base, "wrong-secret").list("tablet"))


def test_service_down_is_reported_not_raised(stack):
    luna, loop, *_ = stack
    luna.reminders = Client("http://127.0.0.1:1", SECRET)
    assert call(loop, luna.set_timer, 5).startswith("ERROR: That did not work")
