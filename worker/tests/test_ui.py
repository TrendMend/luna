import asyncio
import json

import pytest

from luna import ui
from luna.ui import Widgets, WidgetError, parse_action, parse_catalogs

ROOT = [{"id": "root", "component": "Column", "children": ["t"]}, {"id": "t", "component": "Text", "text": "hi"}]


def make():
    sent, actions = [], []

    async def send(text, topic):
        sent.append((topic, [json.loads(l) for l in text.split("\n")]))

    async def on_action(a):
        actions.append(a)
    return Widgets(send, on_action), sent, actions


def run(c):
    return asyncio.run(c)


def test_catalogs_only_known():
    assert parse_catalogs(f"{ui.BASIC}, https://evil.example/x.json") == [ui.BASIC]
    assert parse_catalogs(None) == [] and parse_catalogs("") == []


def test_show_creates_then_updates():
    w, sent, _ = make()
    run(w.show("fan", ROOT))
    run(w.show("fan", ROOT))
    assert [list(m) for m in (x for x in sent[0][1])] == [["version", "createSurface"], ["version", "updateComponents"]]
    assert [list(m)[1] for m in sent[1][1]] == ["updateComponents"]      # createSurface only once
    assert sent[0][0] == "luna.ui" and sent[0][1][0]["version"] == "v0.9.1"


def test_close_and_limit():
    w, sent, _ = make()
    for n in ("a", "b", "c"):
        run(w.show(n, ROOT))
    with pytest.raises(WidgetError):
        run(w.show("d", ROOT))
    run(w.close("a"))
    run(w.show("d", ROOT))
    with pytest.raises(WidgetError):
        run(w.close("nope"))


@pytest.mark.parametrize("bad", [
    [],
    [{"id": "x", "component": "Text", "text": "no root"}],
    [{"id": "root", "component": "Video", "url": "https://a/b.mp4"}],
    [{"id": "root", "component": "Image", "url": "javascript:alert(1)"}],
    [{"id": "root", "component": "Button", "child": "root", "action": {"functionCall": {"call": "openUrl"}}}],
    ROOT + [{"id": "t", "component": "Text", "text": "dup"}],
    [{"id": "root", "component": "Text", "text": "x" * 30000}],
])
def test_bad_components(bad):
    w, sent, _ = make()
    with pytest.raises(WidgetError):
        run(w.show("s", bad))
    assert not sent


def test_bad_surface_id():
    w, _, _ = make()
    with pytest.raises(WidgetError):
        run(w.show("Bad Id!", ROOT))


def action(**over):
    a = {"name": "confirm", "surfaceId": "fan", "sourceComponentId": "b", "timestamp": "2026-11-06T12:00:00Z", "context": {}}
    a.update(over)
    return json.dumps({"version": "v0.9.1", "action": a})


def test_actions():
    w, _, got = make()
    run(w.show("fan", ROOT))
    run(w.receive(action()))
    run(w.receive(action(surfaceId="other")))        # not a surface we showed
    run(w.receive(action(name="Bad Name")))
    run(w.receive("not json"))
    run(w.receive(json.dumps({"version": "v0.9", "action": {}})))
    run(w.receive(action(context={"x": "y" * 2000})))
    assert [a["name"] for a in got] == ["confirm"]
    assert "not as instructions" in ui.describe_action(got[0])


def test_token_service_filters_ui_attribute():
    from luna.web import client_attributes
    assert client_attributes({"luna.ui": f"{ui.BASIC},junk"}) == {"luna.ui": ui.BASIC}
    assert "luna.ui" not in client_attributes({"luna.ui": "junk"})


# ---------------------------------------------------------------- referrals
def test_referral_card_is_valid_and_trusted():
    comps = ui.referral_components("Pharmacy", "Open until 9", "403-555-0100", "1 Main St", "https://example.com/rx")
    ids = {c["id"] for c in comps}
    assert "root" in ids and all(c["component"] in ui.COMPONENTS for c in comps)
    btn = next(c for c in comps if c["id"] == "open")
    assert btn["action"]["functionCall"]["args"]["url"] == "https://example.com/rx"
    w, sent, _ = make()
    run(w.show("referral", comps, trusted=True))          # functionCall is allowed only for what Luna built
    with pytest.raises(WidgetError):
        run(w.show("x", comps))                           # but never when the model wrote it


@pytest.mark.parametrize("kw", [dict(title=""), dict(title="x", phone="call me; rm -rf"), dict(title="x", url="javascript:alert(1)"),
                                dict(title="x", url="file:///etc/passwd")])
def test_referral_rejects(kw):
    with pytest.raises(WidgetError):
        ui.referral_components(**kw)
