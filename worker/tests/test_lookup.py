import asyncio

import pytest
from aiohttp import web
from aiohttp.test_utils import TestServer

from luna.ha import HAError
from luna.info import Weather
from luna.search import PREFIX, WebSearch


def serve(app):
    loop = asyncio.new_event_loop()
    srv = TestServer(app)
    loop.run_until_complete(srv.start_server())
    return loop, srv, str(srv.make_url("")).rstrip("/")


# The variable names Open-Meteo accepts (the ones Luna uses; its real service answers 400 to anything else).
VALID = {
    "current": {"temperature_2m", "apparent_temperature", "weather_code", "wind_speed_10m", "wind_direction_10m", "precipitation"},
    "daily": {"weather_code", "temperature_2m_max", "temperature_2m_min", "precipitation_probability_max", "precipitation_sum",
              "wind_speed_10m_max", "wind_direction_10m_dominant"},
    "hourly": {"temperature_2m", "precipitation_probability", "precipitation", "weather_code", "wind_speed_10m", "wind_direction_10m", "is_day"},
}


def check_variables(query) -> set:
    return {v for kind, ok in VALID.items() for v in set(query.get(kind, "").split(",")) - ok if v}


def hourly_day(date: str, first_hour: int = 0, hours: int = 24) -> dict:
    """A day of hourly numbers shaped like Open-Meteo's: wind from the SE (135 degrees), clear at night."""
    n = range(first_hour, first_hour + hours)
    return {"time": [f"{date}T{h:02d}:00" for h in n], "temperature_2m": [10 - h % 5 for h in n],
            "precipitation_probability": [10] * len(n), "precipitation": [0.0] * len(n), "weather_code": [3] * len(n),
            "wind_speed_10m": [11.2] * len(n), "wind_direction_10m": [135] * len(n), "is_day": [1 if 7 <= h < 19 else 0 for h in n]}


def forecast_payload(with_hourly: bool = True) -> dict:
    """Saturday 7 November 2026, 17:45 in Calgary: 9 degrees, cloudy, wind 11 km/h from the SE, high 12, low 5, 10% rain."""
    daily = {"time": ["2026-11-07", "2026-11-08", "2026-11-09"], "weather_code": [3, 61, 0], "temperature_2m_max": [12, 8, 13],
             "temperature_2m_min": [5, 2, 7], "precipitation_probability_max": [10, 60, 0], "precipitation_sum": [0.0, 2.4, 0.0],
             "wind_speed_10m_max": [14.0, 20.0, 9.0], "wind_direction_10m_dominant": [135, 270, 0]}
    out = {"current": {"time": "2026-11-07T17:45", "temperature_2m": 9.2, "apparent_temperature": 7.0, "weather_code": 3,
                       "wind_speed_10m": 11.2, "wind_direction_10m": 135, "precipitation": 0.0}, "daily": daily}
    if with_hourly:
        day1, day2 = hourly_day("2026-11-07"), hourly_day("2026-11-08")
        out["hourly"] = {k: day1[k] + day2[k] for k in day1}
    return out


def test_weather_card_has_the_hourly_strip_like_googles():
    async def forecast(req):
        bad = check_variables(req.query)
        if bad:
            return web.json_response({"error": True, "reason": f"Invalid value: {sorted(bad)}"}, status=400)
        return web.json_response(forecast_payload())
    app = web.Application()
    app.router.add_get("/v1/forecast", forecast)
    loop, srv, base = serve(app)
    w = Weather({"base_url": base, "name": "Calgary, AB", "latitude": 51.0, "longitude": -114.0})
    loop.run_until_complete(w.report("now"))
    card = w.card
    assert card["day"] == "Saturday" and card["temp"] == "9°C" and card["alt_temp"] == "°F"
    hours = card["hourly"]
    assert len(hours) == 12 and [h["time"] for h in hours[:4]] == ["5 PM", "6 PM", "7 PM", "8 PM"]    # from the current hour on
    assert hours[0]["pop"] == "10%" and hours[0]["rain"] == "0.00 mm" and hours[0]["wind"] == "11 km/h"
    assert hours[0]["dir"] == "SE" and hours[0]["arrow"] == "↘"
    assert hours[0]["icon"] == "☁️" and hours[0]["time"] == "5 PM"
    assert card["rain_day"] == "0.00 mm" and card["alt_rain"] == "in"
    assert card["wind_now"] == "11 km/h" and card["wind_from"] == "SE" and card["alt_wind"] == "mph"
    # tomorrow: the hours start at 6 AM, and the wind is the day's
    loop.run_until_complete(w.report("tomorrow"))
    assert w.card["day"] == "Sunday" and w.card["hourly"][0]["time"] == "6 AM"
    assert w.card["rain_day"] == "2.40 mm" and w.card["wind_now"] == "20 km/h" and w.card["wind_from"] == "W"
    loop.run_until_complete(srv.close())


def test_weather_card_without_hourly_data_is_the_plain_card():
    async def forecast(req):
        return web.json_response(forecast_payload(with_hourly=False))
    app = web.Application()
    app.router.add_get("/v1/forecast", forecast)
    loop, srv, base = serve(app)
    w = Weather({"base_url": base, "name": "Calgary", "latitude": 51.0, "longitude": -114.0})
    loop.run_until_complete(w.report("now"))
    assert "hourly" not in w.card and w.card["day"] == "Saturday"       # the card says so and the layout falls back (see test_ui)
    loop.run_until_complete(srv.close())


def test_imperial_units_and_night_icons_and_odd_hours():
    from luna.info import compass_index, hour_label, hourly_slots
    assert [compass_index(d) for d in (0, 22, 23, 90, 135, 180, 225, 270, 315, 359, 360, -10)] == [0, 0, 1, 2, 3, 4, 5, 6, 7, 0, 0, 0]
    assert [hour_label(f"2026-11-07T{h:02d}:00") for h in (0, 1, 11, 12, 13, 23)] == ["12 AM", "1 AM", "11 AM", "12 PM", "1 PM", "11 PM"]
    h = hourly_day("2026-11-07", 18, 3)                       # 6 PM (day), 7 PM (night), 8 PM (night)
    slots = hourly_slots({**h, "weather_code": [0, 0, 3]}, 0, "°F", "mph", "in")
    assert [s["icon"] for s in slots] == ["☀️", "🌙", "☁️"]       # clear at night is a moon; cloud is a cloud day or night
    assert slots[0]["wind"] == "11 mph" and slots[0]["rain"] == "0.00 in" and slots[0]["temp"] == "7°"
    broken = {**h, "temperature_2m": [10, None, 10]}          # a missing value leaves that hour out instead of showing it wrong
    assert len(hourly_slots(broken, 0, "°C", "km/h", "mm")) == 2


def test_weather_other_place_and_home():
    seen = {}

    async def geo(req):
        seen["name"] = req.query["name"]
        if req.query["name"] == "Nowhereville":
            return web.json_response({})
        return web.json_response({"results": [{"name": "Vancouver", "admin1": "British Columbia", "country_code": "CA",
                                               "latitude": 49.2, "longitude": -123.1}]})

    async def forecast(req):
        seen["lat"] = req.query["latitude"]
        # the real service answers 400 to anything that is not a variable (it once rejected "time"): check what we ask for
        bad = check_variables(req.query)
        if bad:
            return web.json_response({"error": True, "reason": f"Invalid value: {sorted(bad)}"}, status=400)
        return web.json_response({"current": {"temperature_2m": 9.0, "apparent_temperature": 7.0, "weather_code": 61,
                                              "wind_speed_10m": 10, "precipitation": 1},
                                  "daily": {"time": ["2026-11-07", "2026-11-08", "2026-11-09"], "weather_code": [61, 3, 0], "temperature_2m_max": [11, 12, 13], "temperature_2m_min": [5, 6, 7],
                                            "precipitation_probability_max": [70, 20, 0]}})
    app = web.Application()
    app.router.add_get("/v1/search", geo)
    app.router.add_get("/v1/forecast", forecast)
    loop, srv, base = serve(app)
    w = Weather({"base_url": base, "geocoding_url": base, "name": "Calgary", "latitude": 51.0, "longitude": -114.0})
    out = loop.run_until_complete(w.report("now", "Vancouver"))
    assert "Vancouver, British Columbia" in out and seen["lat"] == "49.2"
    loop.run_until_complete(w.report("now", "home"))
    assert seen["lat"] == "51.0"                                   # home never geocodes
    with pytest.raises(HAError, match="could not find"):
        loop.run_until_complete(w.report("now", "Nowhereville"))
    loop.run_until_complete(srv.close())


@pytest.mark.parametrize("provider", ["brave", "tavily", "searxng"])
def test_search_providers(provider, monkeypatch):
    monkeypatch.setenv("K", "secret")

    async def brave(req):
        assert req.headers["X-Subscription-Token"] == "secret"
        return web.json_response({"web": {"results": [{"title": "A", "url": "https://a.example", "description": "alpha  beta"},
                                                      {"title": "bad", "url": "javascript:x", "description": "no"}]}})

    async def tavily(req):
        assert req.headers["Authorization"] == "Bearer secret" and (await req.json())["query"] == "q"
        return web.json_response({"results": [{"title": "A", "url": "https://a.example", "content": "alpha beta"}]})

    async def searx(req):
        assert req.query["format"] == "json"
        return web.json_response({"results": [{"title": "A", "url": "https://a.example", "content": "alpha beta"}]})
    app = web.Application()
    app.router.add_get("/res/v1/web/search", brave)
    app.router.add_post("/search", tavily)
    app.router.add_get("/search", searx)
    loop, srv, base = serve(app)
    s = WebSearch({"provider": provider, "api_key_env": "K", "base_url": base})
    out = loop.run_until_complete(s.report("q"))
    assert out.startswith(PREFIX) and "https://a.example" in out and "alpha beta" in out and "javascript" not in out
    loop.run_until_complete(srv.close())


def test_search_failure_is_reported():
    s = WebSearch({"provider": "searxng", "base_url": "http://127.0.0.1:1"})
    with pytest.raises(HAError):
        asyncio.run(s.report("q"))
    with pytest.raises(ValueError):
        WebSearch({"provider": "bing"})
