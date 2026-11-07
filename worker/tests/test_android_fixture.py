"""The Android app's parser test (android/.../WorkerMessagesTest.kt) reads worker-widgets.jsonl: the messages this code really
sends. This test keeps that file in step with luna.ui. After changing luna.ui, regenerate it with
`UPDATE_FIXTURE=1 pytest tests/test_android_fixture.py`, then run the Android unit tests."""
import asyncio
import os
import pathlib

from luna.ui import Widgets, referral_components

FIXTURE = pathlib.Path(__file__).resolve().parents[2] / "android/app/src/test/resources/worker-widgets.jsonl"

sent: list[str] = []


async def send(text, topic):
    assert topic == "luna.ui"
    sent.extend(text.split("\n"))


async def scenario():
    w = Widgets(send, lambda a: None)
    # A model-built widget: a question with two choices (event actions), as WIDGET_RULES asks for.
    await w.show("fan_choice", [
        {"id": "root", "component": "Card", "child": "col"},
        {"id": "col", "component": "Column", "children": ["q", "row"]},
        {"id": "q", "component": "Text", "variant": "h2", "text": "Turn the fan on?"},
        {"id": "row", "component": "Row", "children": ["yes", "no"]},
        {"id": "yes_t", "component": "Text", "text": "Yes"},
        {"id": "yes", "component": "Button", "child": "yes_t", "variant": "primary",
         "action": {"event": {"name": "fan_on", "context": {"room": "bedroom"}}}},
        {"id": "no_t", "component": "Text", "text": "No"},
        {"id": "no", "component": "Button", "child": "no_t", "action": {"event": {"name": "fan_no"}}},
    ])
    # A list that reads from the data model, with a Divider and an Icon.
    await w.show("todo", [
        {"id": "root", "component": "Column", "children": ["title", "div", "item1", "item2", "ico"]},
        {"id": "title", "component": "Text", "variant": "h2", "text": {"path": "/title"}},
        {"id": "div", "component": "Divider"},
        {"id": "item1", "component": "Text", "text": {"path": "/items/0"}},
        {"id": "item2", "component": "Text", "text": {"path": "/items/1"}},
        {"id": "ico", "component": "Icon", "name": "check"},
    ], data={"title": "Today", "items": ["Take pills at 9", "Call Sam"]})
    # The trusted referral card (phone, address, and the openUrl function call).
    await w.show("ref_clinic", referral_components("Alberta Health Link", "Free nurse advice, any time", phone="811",
                                                   address="Edmonton, AB", url="https://www.albertahealthservices.ca/"),
                 trusted=True)
    await w.close("fan_choice")




def test_android_fixture_matches_what_the_worker_sends():
    sent.clear()
    asyncio.run(scenario())
    text = chr(10).join(sent) + chr(10)
    if os.environ.get("UPDATE_FIXTURE"):
        with open(FIXTURE, "w", encoding="utf-8", newline=chr(10)) as f:
            f.write(text)
    with open(FIXTURE, encoding="utf-8", newline="") as f:
        assert f.read() == text, "worker-widgets.jsonl is stale: run with UPDATE_FIXTURE=1"
