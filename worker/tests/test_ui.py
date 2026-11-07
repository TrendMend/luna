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


def test_weather_card_has_icon_and_forecast_and_passes_validation():
    from luna.info import forecast_days
    days = forecast_days({"time": ["2026-11-07", "2026-11-08", "2026-11-09"], "weather_code": [0, 61, 73],
                          "temperature_2m_max": [6, 4, -1], "temperature_2m_min": [-2, 0, -5],
                          "precipitation_probability_max": [10, 70, 40]}, "°C")
    assert [d["day"] for d in days] == ["Today", "Sun", "Mon"] and days[0]["icon"].startswith("☀")
    comps = ui.weather_components({"where": "Calgary", "when": "Now", "cond": "clear sky", "icon": days[0]["icon"], "temp": "4°C",
                                   "feels": "Feels like 1°C · Wind 9 km/h", "days": days})
    w, sent, _ = make()
    run(w.show("weather", comps, trusted=True))
    ids = {c["id"] for c in comps}
    assert sent and {"icon", "temp", "days", "d0i", "d2r"} <= ids
    # still fine without a forecast (e.g. an older payload)
    run(w.show("weather", ui.weather_components({"where": "X", "when": "Now", "cond": "fog", "temp": "1", "line": "High 2"}), trusted=True))


TABBED = {"where": "Calgary, AB", "day": "Saturday", "when": "Now", "cond": "cloudy", "icon": "☁️", "temp": "9°C", "alt_temp": "°F",
          "feels": "Feels like 7°C · Wind 11 km/h", "line": "High 12°C · Low 5°C · 10% precipitation",
          "rain_day": "0.00 mm", "alt_rain": "in", "wind_now": "11 km/h", "wind_from": "SE", "alt_wind": "mph",
          "hourly": [{"time": f"{h} PM", "icon": "☁️", "temp": f"{10 - h}°", "pop": "10%", "rain": "0.00 mm", "wind": "11 km/h",
                      "dir": "SE", "arrow": "↘"} for h in range(1, 13)],
          "days": [{"day": d, "icon": "☀️", "hi": "6°", "lo": "-2°", "rain": "10%"} for d in ("Today", "Sun", "Mon")]}


def test_tabbed_weather_card_is_googles_layout():
    comps = ui.weather_components(TABBED)
    by_id = {c["id"]: c for c in comps}
    assert len(by_id) == len(comps), "duplicate component ids"
    assert all(ui.COMPONENT_ID.fullmatch(c["id"]) for c in comps)
    assert by_id["where"]["text"] == "Saturday · Calgary, AB"                        # the day, then the place
    tabs = by_id["tabs"]
    assert tabs["component"] == "Tabs" and [t["title"] for t in tabs["tabs"]] == ["Temperature", "Precipitation", "Wind", "Week"]
    for tab in tabs["tabs"]:
        assert tab["child"] in by_id                                                  # every tab points at a real component
    # the big reading has the other unit's label beside it: "9°C | °F", "11 km/h | mph", "0.00 mm | in"
    assert [by_id[i]["text"] for i in ("tb", "ta", "rb", "ra", "wb", "wa")] == ["9°C", "| °F", "0.00 mm", "| in", "11 km/h", "| mph"]
    assert by_id["wfrom"]["text"] == "From SE" and by_id["rlabel"]["text"] == "Total rain for the day"
    # each tab has an hourly strip that scrolls sideways, with the fields that tab shows
    for strip, per_hour in (("thlist", 4), ("rhlist", 3), ("whlist", 4)):
        assert by_id[strip]["component"] == "List" and by_id[strip]["direction"] == "horizontal" and len(by_id[strip]["children"]) == 12
        assert len(by_id[by_id[strip]["children"][0]]["children"]) == per_hour
    assert by_id["wh0arrow"]["text"] == "↘" and by_id["wh0dir"]["text"] == "SE" and by_id["rh0rain"]["text"] == "0.00 mm"
    # every id a container names exists (so the renderer never meets a dangling child)
    for c in comps:
        for child in ([c["child"]] if "child" in c else []) + [x for x in c.get("children", []) if isinstance(x, str)]:
            assert child in by_id, f"{c['id']} names missing {child}"
    # Luna's own card: sent as trusted (past the 40-component limit for model-built widgets), and still a normal widget message
    w, sent, _ = make()
    run(w.show("weather", comps, trusted=True))
    update = next(m["updateComponents"] for m in sent[0][1] if "updateComponents" in m)
    assert any(c["component"] == "Tabs" for c in update["components"]) and len(update["components"]) > 100


def test_tabbed_weather_card_leaves_out_tabs_it_has_no_data_for():
    plain = {k: v for k, v in TABBED.items() if k not in ("rain_day", "wind_now", "days")}
    titles = [t["title"] for t in {c["id"]: c for c in ui.weather_components(plain)}["tabs"]["tabs"]]
    assert titles == ["Temperature"]
    no_hourly = {k: v for k, v in TABBED.items() if k != "hourly"}
    ids = {c["id"] for c in ui.weather_components(no_hourly)}
    assert "tabs" not in ids and {"icon", "temp", "days"} <= ids                       # the plain card, as before


def test_builtin_cards_build_and_validate():
    cases = [ui.info_components("Flames vs Oilers", "4 - 2", "\U0001f3d2", ["Final", "Flames win"]),
             ui.list_components("Groceries", ["milk", "eggs"], "\U0001f6d2"),
             ui.choices_components("Which one?", ["Anna", "Ben"]),
             ui.reminders_components([{"kind": "timer", "text": "pasta", "when": "in 9 minutes"}, {"kind": "reminder", "text": "pills", "when": "at 4 pm"}]),
             ui.reminders_components([]),
             ui.doors_components([{"label": "Front door", "state": "locked"}, {"label": "Back door", "state": "open"}, {"label": "Side", "state": "unknown"}])]
    for comps in cases:
        ids = [c["id"] for c in comps]
        assert len(ids) == len(set(ids)) and "root" in ids
        w, sent, _ = make()
        run(w.show("x", comps, trusted=True))
        assert sent
    for bad in (lambda: ui.info_components(""), lambda: ui.list_components("t", []), lambda: ui.choices_components("q", ["only one"])):
        with pytest.raises(WidgetError):
            bad()


def test_choice_button_comes_back_as_an_action():
    comps = ui.choices_components("Which?", ["Anna", "Ben"])
    btn = next(c for c in comps if c["id"] == "o1")
    assert btn["action"]["event"] == {"name": "choose", "context": {"option": "Ben"}}
    msg = json.dumps({"version": ui.VERSION, "action": {"name": "choose", "surfaceId": "choices", "sourceComponentId": "o1", "context": {"option": "Ben"}}})
    a = parse_action(msg, {"choices"})
    assert a and "Ben" in ui.describe_action(a)


def test_trusted_cards_replace_the_oldest_when_full():
    w, sent, _ = make()
    for n in ("a", "b", "c"):
        run(w.show(n, ROOT))
    run(w.show("weather", ROOT, trusted=True))
    assert list(w.surfaces) == ["b", "c", "weather"]
    assert sent[-1][1][0] == {"version": ui.VERSION, "deleteSurface": {"surfaceId": "a"}} or any("deleteSurface" in m for m in sent[-2][1])
    with pytest.raises(WidgetError):
        run(w.show("d", ROOT))                     # a model-built widget still has to close one first
    run(w.show("b", ROOT))                         # re-showing makes it the youngest
    assert list(w.surfaces) == ["c", "weather", "b"]
